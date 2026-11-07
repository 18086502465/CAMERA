#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 数据库恢复 / 恢复演练
#
# 为什么需要这个脚本：
#   在此之前，「备份能不能恢复」是一个**从未被验证过的假设**。备份脚本只做
#   `zstd -t`（压缩流完整性），不验证 SQL 能否导入；仓库里也没有任何 restore 入口。
#   出事之前，「备份文件存在」和「备份能恢复」看不出区别 —— 出事之后区别就是
#   「救回来」和「没救回来」。
#
# 设计原则：
#   1. **绝不覆盖生产库**：目标库默认 restore_check_<时间戳>，wms-new 等受保护库名一律拒绝
#   2. 先校验 sha256，再解压导入 —— 避免拿一个损坏的备份白跑一小时
#   3. --compare-with 支持与原库逐表比行数，用于判断「备份落后了多少」
#   4. 恢复完默认**保留**目标库，人工确认后再 --drop-after 或手工 DROP
#
# 用法：
#   # 恢复最新一份备份到临时库并比对
#   sudo bash restore-database.sh --latest --compare-with wms-new
#
#   # 恢复指定文件，验证完自动删库（CI / 月度演练用）
#   sudo bash restore-database.sh --file /path/x.sql.zst --verify --drop-after
#
# 环境变量：
#   MYSQL_PASSWORD   数据库口令（不给则自读 /etc/default/camera-detection）
#   BACKUP_DIR       备份根目录（默认 /data/camera-detection/backup/db）
#   DB_HOST / DB_PORT / DB_USER
#
# 退出码：0=成功；1=参数或环境错误；2=校验/恢复失败；3=比对不一致（仅告警，不算失败时用 0）
# ==============================================================================
set -uo pipefail

BACKUP_DIR="${BACKUP_DIR:-/data/camera-detection/backup/db}"
FULL_DIR="${BACKUP_DIR}/full"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"

SRC_FILE=""
TARGET_DB=""
COMPARE_WITH=""
VERIFY=0
DROP_AFTER=0
USE_LATEST=0

# cron / systemd 环境下没有已导出的变量，靠自己读（640 root:root）
if [[ -z "${MYSQL_PASSWORD:-}" && -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi

log() { printf '[restore] %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)        SRC_FILE="${2:?--file 需要一个值}"; shift 2 ;;
    --latest)      USE_LATEST=1; shift ;;
    --target-db)   TARGET_DB="${2:?--target-db 需要一个值}"; shift 2 ;;
    --compare-with) COMPARE_WITH="${2:?--compare-with 需要一个值}"; shift 2 ;;
    --verify)      VERIFY=1; shift ;;
    --drop-after)  DROP_AFTER=1; shift ;;
    -h|--help)     sed -n '2,40p' "$0"; exit 0 ;;
    *)             die "未知参数：$1" ;;
  esac
done

MP="${MYSQL_PASSWORD:-}"
[[ -n "$MP" ]] || die "未提供数据库口令（MYSQL_PASSWORD 或 /etc/default/camera-detection）"
command -v mysql >/dev/null 2>&1 || die "未找到 mysql 客户端"
command -v zstd  >/dev/null 2>&1 || die "未找到 zstd"

# --- 选文件 --------------------------------------------------------------------
if [[ "$USE_LATEST" == "1" ]]; then
  SRC_FILE="$(ls -1t "${FULL_DIR}"/*.sql.zst 2>/dev/null | head -1)"
  [[ -n "$SRC_FILE" ]] || die "${FULL_DIR} 下没有 .sql.zst"
fi
[[ -n "$SRC_FILE" ]] || die "必须指定 --file 或 --latest"
[[ -f "$SRC_FILE" ]] || die "文件不存在：$SRC_FILE"

# --- 目标库名与保护 ------------------------------------------------------------
if [[ -z "$TARGET_DB" ]]; then
  TARGET_DB="restore_check_$(date +%Y%m%d_%H%M%S)"
fi

# 生产库与系统库一律拒绝。这里用**白名单式前缀**而不是黑名单：
# 只允许 restore_check_ / zztest_ 开头，避免 `--target-db wms-neww` 这类近似名误伤。
case "$TARGET_DB" in
  restore_check_*|zztest_*|zztest-*|ZZTEST_*) : ;;
  *) die "拒绝恢复到这个库名：${TARGET_DB}（只允许 restore_check_* / ZZTEST_* 前缀，防止误覆盖生产库）" ;;
esac

log "源文件  ：${SRC_FILE}（$(du -h "$SRC_FILE" | cut -f1)）"
log "目标库  ：${TARGET_DB}"
log "服务器  ：${DB_USER}@${DB_HOST}:${DB_PORT}"

# --- sha256 校验 ---------------------------------------------------------------
if [[ -f "${SRC_FILE}.sha256" ]]; then
  log "校验 sha256 ..."
  # sha256 文件里是绝对路径，cd 到目录后按 basename 校验，避免路径漂移导致误报
  if ! ( cd "$(dirname "$SRC_FILE")" && sha256sum -c "$(basename "$SRC_FILE").sha256" >/dev/null 2>&1 ); then
    die "sha256 校验失败 —— 备份文件已损坏，不要用它恢复"
  fi
  log "sha256 校验通过"
else
  log "WARN: 没有 .sha256 文件，跳过完整性校验"
fi

# --- 建库 + 导入 ---------------------------------------------------------------
log "创建目标库 ..."
MYSQL_PWD="$MP" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
  -e "DROP DATABASE IF EXISTS \`${TARGET_DB}\`; CREATE DATABASE \`${TARGET_DB}\` CHARACTER SET utf8mb4;" \
  || die "创建目标库失败"

log "开始导入（zstd -dc | mysql）..."
START=$(date +%s)
# pipefail 已开启：zstd 或 mysql 任一段失败都会让整个管道非 0
if ! zstd -dc "$SRC_FILE" | MYSQL_PWD="$MP" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$TARGET_DB"; then
  die "导入失败 —— 目标库 ${TARGET_DB} 可能只导入了一半，请先 DROP 再重试"
fi
ELAPSED=$(( $(date +%s) - START ))
log "导入完成，耗时 ${ELAPSED}s"

# --- 统计 ----------------------------------------------------------------------
table_list() {
  MYSQL_PWD="$MP" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -N -B \
    -e "SELECT table_name FROM information_schema.tables
        WHERE table_schema='$1' AND table_type='BASE TABLE' ORDER BY table_name"
}
count_tables() { table_list "$1" | wc -l | tr -d ' '; }
table_rows() {
  local db="$1" t n
  table_list "$db" | while IFS= read -r t; do
    [[ -z "$t" ]] && continue
    n=$(MYSQL_PWD="$MP" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -N -B -D "$db" \
          -e "SELECT COUNT(*) FROM \`$t\`" 2>/dev/null || echo "ERR")
    printf '%s\t%s\n' "$t" "$n"
  done
}

T_TARGET=$(count_tables "$TARGET_DB")
log "恢复后表数量：${T_TARGET}"
[[ "$T_TARGET" -gt 0 ]] || die "恢复后一张表都没有 —— 备份内容异常"

if [[ "$VERIFY" == "1" ]]; then
  log "=== 逐表行数 ==="
  table_rows "$TARGET_DB" | awk -F'\t' '{printf "  %-40s %10s\n", $1, $2; s+=$2} END{printf "  %-40s %10s\n", "合计", s}'
fi

# --- 与原库比对 ----------------------------------------------------------------
if [[ -n "$COMPARE_WITH" ]]; then
  log "=== 与 ${COMPARE_WITH} 逐表比对 ==="
  A=$(mktemp /tmp/cds_restore_a.XXXXXX)
  B=$(mktemp /tmp/cds_restore_b.XXXXXX)
  table_rows "$COMPARE_WITH" > "$A"
  table_rows "$TARGET_DB"   > "$B"
  # awk 合并两份「表名\t行数」，输出差异
  DIFF=$(awk -F'\t' '
    NR==FNR { a[$1]=$2; next }
    { b[$1]=$2 }
    END {
      n=0
      for (t in a) {
        if (!(t in b))            { printf "  仅原库有: %s (%s)\n", t, a[t]; n++ }
        else if (a[t] != b[t])    { printf "  行数不同: %-38s 原库 %8s  备份 %8s\n", t, a[t], b[t]; n++ }
      }
      for (t in b) if (!(t in a)) { printf "  仅备份有: %s (%s)\n", t, b[t]; n++ }
      if (n==0) printf "  全部表行数一致\n"
    }' "$A" "$B")
  echo "$DIFF"
  rm -f "$A" "$B"
  log "注：备份是 01:04 的快照，与原库有差异属正常；差异 = 备份之后新增的数据量。"
fi

# --- 收尾 ----------------------------------------------------------------------
if [[ "$DROP_AFTER" == "1" ]]; then
  MYSQL_PWD="$MP" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    -e "DROP DATABASE IF EXISTS \`${TARGET_DB}\`" && log "已删除目标库 ${TARGET_DB}"
else
  log "目标库 ${TARGET_DB} 已保留，确认无误后手工 DROP："
  log "  mysql -e \"DROP DATABASE \\\`${TARGET_DB}\\\"\""
fi

log "恢复完成"
exit 0
