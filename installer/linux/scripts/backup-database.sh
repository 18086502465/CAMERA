#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 数据库全量备份
#
# 特点：
#   1. --single-transaction —— 不锁表，业务无感知（InnoDB 一致性快照）
#   2. 备份前检查磁盘水位 —— 与数据同盘，写满时不再写入
#   3. 每个文件生成 .sha256 —— 备份可校验
#   4. 保留 N 天，过期自动删除
#   5. 可选异机同步（rsync）
#
# 安装：
#   sudo install -m 0755 backup-database.sh /opt/camera-detection-system/scripts/
#
# 手动执行：
#   MYSQL_PASSWORD='xxx' bash /opt/camera-detection-system/scripts/backup-database.sh
#
# 作为 systemd timer 运行（推荐，见 camera-detection-backup.service/.timer）：
#   由 /etc/default/camera-detection 提供 MYSQL_PASSWORD 环境变量
# ==============================================================================
set -euo pipefail

# 注意：备份目录的父路径（/data）默认是 755 root:root，
# 而备份脚本以 cdsys 身份运行 —— 无法在 /data 下自行创建 /data/backup。
# 因此默认路径必须落在已由 root 建好并 chown 给 cdsys 的 /data/camera-detection/ 内。
BACKUP_DIR="${BACKUP_DIR:-/data/camera-detection/backup/db}"
DB_NAME="${DB_NAME:-wms-new}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-}"
KEEP_FULL_DAYS="${KEEP_FULL_DAYS:-14}"
# 磁盘水位上限（百分比），超过则跳过本次备份，避免把磁盘写满
MAX_DISK_USE="${MAX_DISK_USE:-85}"
# 是否同步到异机（目录存在才同步）
OFFSITE_DIR="${OFFSITE_DIR:-/data/camera-detection/backup/offsite}"

FULL_DIR="${BACKUP_DIR}/full"
LOG_DIR="${BACKUP_DIR}/logs"
LOG_FILE="${LOG_DIR}/backup.log"

log() {
  local msg="[$(date '+%F %T')] $*"
  printf '%s\n' "$msg"
  printf '%s\n' "$msg" >> "${LOG_FILE}" 2>/dev/null || true
}

die() { log "ERROR: $*"; exit 1; }

# --- 前置检查 ------------------------------------------------------------------
[[ -n "$DB_PASS" ]] || die "未提供数据库口令（设置环境变量 MYSQL_PASSWORD）"
mkdir -p "$FULL_DIR" "$LOG_DIR" "${BACKUP_DIR}/binlog" "${BACKUP_DIR}/images"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

# --- 磁盘水位检查 --------------------------------------------------------------
# 备份与数据可能同盘；水位过高时再写备份会把磁盘彻底写满，反而丢数据
DISK_USE=$(df --output=pcent "$(dirname "$BACKUP_DIR")" | tail -1 | tr -d ' %')
if [[ "$DISK_USE" -gt "$MAX_DISK_USE" ]]; then
  log "WARN: 备份分区使用率 ${DISK_USE}% 超过阈值 ${MAX_DISK_USE}%，跳过本次备份"
  exit 1
fi

# --- 全量转储 ------------------------------------------------------------------
STAMP=$(date +%Y%m%d_%H%M%S)
OUT="${FULL_DIR}/${DB_NAME}_${STAMP}.sql.zst"

log "开始全量备份：${DB_NAME} -> ${OUT}"
START=$(date +%s)

# --single-transaction：对 InnoDB 的一致性快照，不阻塞业务写入
# --quick：逐行读取，避免大表一次性载入内存
# --set-gtid-purged=OFF：本库 gtid_mode=OFF，不加此项在部分版本会报错
# 2>/dev/null：屏蔽口令告警（stderr 会把口令带出来，不安全）
if ! mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" \
      --single-transaction --quick \
      --routines --triggers --events \
      --set-gtid-purged=OFF \
      --default-character-set=utf8mb4 \
      "$DB_NAME" 2>/dev/null | zstd -3 -T4 -o "$OUT.tmp"; then
  rm -f "$OUT.tmp"
  die "mysqldump 执行失败"
fi

# 管道里 mysqldump 失败时 zstd 仍可能返回 0，必须单独校验文件
if [[ ! -s "$OUT.tmp" ]]; then
  rm -f "$OUT.tmp"
  die "备份文件为空（mysqldump 可能已失败）"
fi
mv "$OUT.tmp" "$OUT"

# --- 校验 ----------------------------------------------------------------------
# zstd -t 检查压缩流完整性；sha256 供事后校验
if ! zstd -t "$OUT" >/dev/null 2>&1; then
  rm -f "$OUT"
  die "压缩文件校验失败，已删除"
fi
sha256sum "$OUT" > "${OUT}.sha256"

ELAPSED=$(( $(date +%s) - START ))
SIZE=$(du -h "$OUT" | cut -f1)
log "全量备份完成：${OUT} (${SIZE}, 耗时 ${ELAPSED}s)"

# --- 清理过期备份 --------------------------------------------------------------
# 先删 .sql.zst 再删 .sha256，避免出现「有校验文件无备份文件」的中间态
DELETED=$(find "$FULL_DIR" -name "${DB_NAME}_*.sql.zst" -mtime "+${KEEP_FULL_DAYS}" -print -delete | wc -l)
if [[ "$DELETED" -gt 0 ]]; then
  find "$FULL_DIR" -name "${DB_NAME}_*.sql.zst.sha256" -mtime "+${KEEP_FULL_DAYS}" -delete
  log "已清理 ${DELETED} 份超过 ${KEEP_FULL_DAYS} 天的旧备份"
fi

# --- 异机同步（可选）----------------------------------------------------------
# 同盘备份不抗物理故障，只有异机留存才是真正的最后一道防线
if [[ -d "$OFFSITE_DIR" ]]; then
  if rsync -a --delete "$FULL_DIR/" "${OFFSITE_DIR}/full/" 2>/dev/null; then
    log "已同步到异机目录：${OFFSITE_DIR}/full/"
  else
    log "WARN: 异机同步失败（不影响本地备份）"
  fi
fi

log "本次备份结束"
