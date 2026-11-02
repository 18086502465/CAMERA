#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 缺陷图片与元数据清理
#
# 【为什么需要这个脚本】
# 应用自带的 DataCleanupTask 只清理「兼容表」camera_images，
# 新版缺陷图片表（defect_image_file / _box / _batch）及其磁盘文件没有任何定时任务清理。
# 结果是：
#   - 磁盘只增不减（实测按 350KB/张、144000 张/天，日增 48 GB）
#   - 库中记录永久堆积（每天 +14.4 万行）
#
# 【关键设计：必须「先删库记录，再删文件」】
# 若反过来（先删文件），中间窗口内库中记录仍指向已删除的文件：
#   - 前端能查到图片记录，却取不到图 → 显示破图
#   - 应用侧 resolveImagePath() 对不存在的文件返回 null
# 而先删记录再删文件，即使文件删除失败，下次清理也会跳过（幂等），
# 绝不会产生"永久孤儿记录"。
#
# 安装：
#   sudo install -m 0755 cleanup-defect-images.sh /usr/local/bin/
#   sudo tee /etc/cron.d/cleanup-defect-images > /dev/null <<'EOF'
#   30 3 * * * root /usr/local/bin/cleanup-defect-images.sh
#   EOF
#
# 建议：首次先跑 --dry-run 看会删什么，确认无误后再启用定时任务。
#
# 可配置环境变量（建议写进 /etc/default/camera-detection）：
#   DIR             图片根目录
#   KEEP_DAYS       图片与元数据保留天数（默认 7）
#   KEEP_BATCH_DAYS 批次元数据保留天数（默认 30）
#   DB_NAME         数据库名（默认 wms-new）
#   MYSQL_PASSWORD  数据库口令
#   MAX_DISK_USE    磁盘水位上限（百分比）（默认 90）
# ==============================================================================
set -uo pipefail

DIR="${DIR:-/data/camera-detection/images/defect-images}"
KEEP_DAYS="${KEEP_DAYS:-7}"
KEEP_BATCH_DAYS="${KEEP_BATCH_DAYS:-30}"
DB_NAME="${DB_NAME:-wms-new}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-root}"
MAX_DISK_USE="${MAX_DISK_USE:-90}"
DRY_RUN=0
PURGE_BY_DB=0

log() { printf '[cleanup] %s\n' "$*"; }

# --- 参数解析 ----------------------------------------------------------------
# 必须用 `while [[ $# -gt 0 ]]` + `shift`，**不能**用 `for arg in "$@"`：
# for 循环会在开始时把 "$@" 展开成固定列表，此时 shift 改不动这个列表，
# 于是带值参数的 "$2" 取到的是**参数名本身**而不是它的值。
# 实测（2026-10-28）：`--keep-days 0` 会让 KEEP_DAYS 变成字符串 "--keep-days"，
# 后续 `date -d "--keep-days days"` 失败、回退分支的算术展开又报
# "keep: unbound variable" —— 保留期完全失效，--dir/--db 同样有这个问题。
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --purge-db) PURGE_BY_DB=1; shift ;;
    --dir) DIR="${2:?--dir 需要一个值}"; shift 2 ;;
    --keep-days) KEEP_DAYS="${2:?--keep-days 需要一个值}"; shift 2 ;;
    --keep-batch-days) KEEP_BATCH_DAYS="${2:?--keep-batch-days 需要一个值}"; shift 2 ;;
    --db) DB_NAME="${2:?--db 需要一个值}"; shift 2 ;;
    -h|--help) sed -n '2,42p' "$0"; exit 0 ;;
    *) log "未知参数：$1"; exit 1 ;;
  esac
done

# --- 图片目录是否存在 ----------------------------------------------------------
# 目录不存在时**只跳过「删磁盘文件」这一步，不能跳过库记录清理**。
#
# 2026-10-31 实测发现的问题：本处原本是 `exit 0`，
# 而库记录统计在第 126 行、库记录删除在第 207 行 ——
# 也就是说只要图片目录不存在（首次部署未上传过图片、
# 或目录被压测/清理脚本删掉），**库里的过期 defect_image_file/_box/_batch
# 与 camera_images 就永远不会被清理**，会无限堆积。
# 现在改成置标志位，让库记录清理照常执行。
SKIP_DISK=0
if [[ ! -d "$DIR" ]]; then
  log "图片目录不存在：${DIR}（本次只清理库记录，跳过磁盘文件）"
  SKIP_DISK=1
fi

# --- 数据库可达性 --------------------------------------------------------------
# 连不上库时不能删文件 —— 否则会立刻制造孤儿记录
HAVE_DB=0
if command -v mysql >/dev/null 2>&1; then
  if [[ -n "${MYSQL_PASSWORD:-}" ]]; then
    export MYSQL_PWD="$MYSQL_PASSWORD"
  fi
  if mysql -h"$DB_HOST" -u"$DB_USER" -N -B -e "SELECT 1" >/dev/null 2>&1; then
    HAVE_DB=1
  fi
fi
if [[ "$HAVE_DB" -eq 0 ]]; then
  log "ERROR: 无法连接数据库，为避免产生孤儿记录，本次不删除任何文件"
  exit 1
fi

# --- 磁盘水位检查 --------------------------------------------------------------
# 磁盘已接近写满时，删除旧文件正是我们想要的；但水位过低（如误配）时应谨慎
DISK_ROOT=$(df --output=target "$(dirname "$DIR")" 2>/dev/null | tail -1)
DISK_USE=$(df --output=pcent "$(dirname "$DIR")" 2>/dev/null | tail -1 | tr -d ' %')
if [[ -n "$DISK_USE" && "$DISK_USE" -gt "$MAX_DISK_USE" ]]; then
  log "WARN: 分区 ${DISK_ROOT} 使用率 ${DISK_USE}% 超过 ${MAX_DISK_USE}%，仍继续清理（正是当前需要）"
fi

log "图片目录：${DIR}"
log "保留天数：图片 ${KEEP_DAYS} 天 / 批次 ${KEEP_BATCH_DAYS} 天"
log "数据库：${DB_NAME}"

# --- 第 1 步：统计将清理的库记录 ----------------------------------------------
CUTOFF_IMG=$(date -d "-${KEEP_DAYS} days" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
CUTOFF_BATCH=$(date -d "-${KEEP_BATCH_DAYS} days" '+%Y-%m-%d %H:%M:%S' 2>/dev/null)
if [[ -z "$CUTOFF_IMG" ]]; then
  CUTOFF_IMG=$(date -d "@$(( $(date +%s) - KEEP_DAYS*86400 ))" '+%Y-%m-%d %H:%M:%S')
fi
if [[ -z "$CUTOFF_BATCH" ]]; then
  CUTOFF_BATCH=$(date -d "@$(( $(date +%s) - KEEP_BATCH_DAYS*86400 ))" '+%Y-%m-%d %H:%M:%S')
fi

FILE_COUNT=$(find "$DIR" -type f -mtime "+${KEEP_DAYS}" 2>/dev/null | wc -l | tr -d ' ')
FILE_SIZE=$(find "$DIR" -type f -mtime "+${KEEP_DAYS}" -printf '%s\n' 2>/dev/null | awk '{s+=$1} END{printf "%.1f", s/1048576}')

# 统计也走独立 SQL 文件（同样是为了避开反引号转义地狱）；
# 三个计数用一条 SQL 输出、逗号分隔，一次连接即可。
CNT_FILE="$(mktemp /tmp/cds_cnt_XXXXXX.sql)"
cat > "$CNT_FILE" <<SQLEOF
SELECT
  (SELECT COUNT(*) FROM defect_image_file WHERE capture_time < '${CUTOFF_IMG}'),
  (SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.capture_time < '${CUTOFF_IMG}'),
  (SELECT COUNT(*) FROM defect_image_batch WHERE upload_time < '${CUTOFF_BATCH}');
SQLEOF
CNT=$(mysql -h"$DB_HOST" -u"$DB_USER" -N -B "$DB_NAME" < "$CNT_FILE" 2>/dev/null | head -1)
rm -f "$CNT_FILE"
IMG_ROWS=$(echo "$CNT" | cut -f1)
BOX_ROWS=$(echo "$CNT" | cut -f2)
BATCH_ROWS=$(echo "$CNT" | cut -f3)

log "将清理：图片记录 ${IMG_ROWS:-0} 条 / 缺陷框 ${BOX_ROWS:-0} 条 / 批次 ${BATCH_ROWS:-0} 条"
log "将清理磁盘：${FILE_COUNT} 个文件，约 ${FILE_SIZE} MB"

# --- 清理用的 SQL（常规路径与 --purge-db 路径共用）-----------------------------
# 实现要点：
#  1. SQL 写到独立临时文件再交给 mysql 执行 —— 直接用 -e 传多行 SQL 时，
#     反引号需要多层转义，极易出错（曾出现 "unexpected EOF while looking for matching `"）。
#     独立文件里反引号无需转义，MySQL 直接读取。
#  2. 必须指定默认库 —— `DELETE b FROM t1 b JOIN t2 f` 这类多表语句
#     在未指定库时报 "ERROR 1046 (3D000): No database selected"。
#  3. 不能用 2>/dev/null 吞掉 stderr —— 那会把「清理失败」伪装成「清理成功」。
#     失败时脚本以非 0 退出，便于 cron 留痕与告警。
SQL_FILE="$(mktemp /tmp/cds_cleanup_XXXXXX.sql)"
cat > "$SQL_FILE" <<SQLEOF
DELETE b FROM defect_image_box b
JOIN defect_image_file f ON f.image_id = b.image_id
WHERE f.capture_time < '${CUTOFF_IMG}';

DELETE FROM defect_image_file WHERE capture_time < '${CUTOFF_IMG}';

DELETE FROM defect_image_batch
WHERE upload_time < '${CUTOFF_BATCH}'
  AND NOT EXISTS (SELECT 1 FROM defect_image_file f WHERE f.batch_id = defect_image_batch.batch_id);

DELETE FROM camera_images WHERE capture_time < '${CUTOFF_IMG}';
SQLEOF

if [[ "$DRY_RUN" == "1" ]]; then
  rm -f "$SQL_FILE"
  log "（--dry-run 模式，不实际删除）"
  find "$DIR" -type f -mtime "+${KEEP_DAYS}" -printf '  %TY-%Tm-%Td  %s\t%p\n' 2>/dev/null | head -30
  exit 0
fi

# --- 特殊模式：以库记录为准，精确删除对应文件 --------------------------------
# 场景：压测/测试产生的数据是"刚刚写入"的，mtime 还没到保留期，
#       按 mtime 删不掉，但库里的记录已是垃圾。
# 做法：先查出这些记录的 image_path，删库记录 → 再按路径删文件。
#       **以库为准而不是以文件时间为准**，才能保证「库与文件始终一致」。
if [[ "$PURGE_BY_DB" == "1" ]]; then
  log "（--purge-db 模式：以库记录为准，删除全部已过保留期的记录及其文件）"
  PURGE_LIST="$(mktemp /tmp/cds_purge_XXXXXX.txt)"
  mysql -h"$DB_HOST" -u"$DB_USER" -N -B "$DB_NAME" -e \
    "SELECT image_path FROM defect_image_file WHERE capture_time < '${CUTOFF_IMG}';" > "$PURGE_LIST" 2>/dev/null
  PURGE_N=$(wc -l < "$PURGE_LIST" | tr -d ' ')

  # 删库记录（与常规路径相同的 SQL）
  if [[ "${IMG_ROWS:-0}" -gt 0 ]]; then
    if ! mysql -h"$DB_HOST" -u"$DB_USER" "$DB_NAME" < "$SQL_FILE"; then
      log "ERROR: 库记录清理失败（详见上方 MySQL 报错）"
      rm -f "$SQL_FILE" "$PURGE_LIST"
      exit 2
    fi
    log "✓ 库记录已清理"
  fi

  # 再按路径删文件（image_path 可能是相对路径 —— 相对于 DIR）
  DEL_N=0
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    if [[ "$p" == /* ]]; then
      TARGET="$p"
    else
      TARGET="${DIR%/}/$p"
    fi
    if [[ -f "$TARGET" ]]; then
      rm -f "$TARGET" && DEL_N=$((DEL_N+1))
    fi
  done < "$PURGE_LIST"
  rm -f "$PURGE_LIST" "$SQL_FILE"
  log "✓ 按库记录删除了 ${DEL_N} 个文件（涉及 ${PURGE_N} 条记录）"

  find "$DIR" -mindepth 2 -type d -empty -delete 2>/dev/null || true
  log "清理完成，当前占用：$(du -sh "$DIR" 2>/dev/null | cut -f1)"
  exit 0
fi

# --- 第 2 步：先删库记录 --------------------------------------------------------
# 顺序至关重要，详见文件头说明
if [[ "${IMG_ROWS:-0}" -gt 0 ]]; then
  if ! mysql -h"$DB_HOST" -u"$DB_USER" "$DB_NAME" < "$SQL_FILE"; then
    rm -f "$SQL_FILE"
    log "ERROR: 库记录清理失败（详见上方 MySQL 报错）"
    exit 2
  fi
  log "✓ 库记录已清理"
fi

# --- 第 3 步：再删磁盘文件 ------------------------------------------------------
if [[ "$SKIP_DISK" -eq 1 ]]; then
  log "跳过磁盘文件删除（图片目录不存在）"
elif [[ "${FILE_COUNT:-0}" -gt 0 ]]; then
  # 注意：不能用 `xargs -I{} log "..."` —— log 是 shell 函数不是可执行命令，
  # xargs 会去 PATH 里找 "log" 并报 "No such file or directory"（2026-10-28 实测），
  # 计数因此完全丢失。正确做法是先数再删、再用 shell 自己打印。
  DELETED=$(find "$DIR" -type f -mtime "+${KEEP_DAYS}" -print -delete 2>/dev/null | wc -l | tr -d ' ')
  log "✓ 已删除 ${DELETED} 个文件"
else
  log "无需删除磁盘文件"
fi

# 清理空目录（保留最外层日期目录）
if [[ "$SKIP_DISK" -eq 0 ]]; then
  find "$DIR" -mindepth 2 -type d -empty -delete 2>/dev/null || true
fi

# --- 第 4 步：回收数据库空间 ---------------------------------------------------
# InnoDB DELETE 后空间还给表空间但不还给操作系统，需要重建表
# 仅在删除量较大时才做（重建 100 万行约 10-30 秒）
if [[ "${IMG_ROWS:-0}" -gt 1000 ]]; then
  log "回收表空间（OPTIMIZE TABLE）..."
  # 同样走独立 SQL 文件，避免反引号转义问题
  OPT_FILE="$(mktemp /tmp/cds_opt_XXXXXX.sql)"
  printf 'OPTIMIZE TABLE defect_image_file, defect_image_box;\n' > "$OPT_FILE"
  mysql -h"$DB_HOST" -u"$DB_USER" "$DB_NAME" < "$OPT_FILE" 2>/dev/null
  rm -f "$OPT_FILE"
  log "✓ 表空间已回收"
fi

log "清理完成，当前占用：$(du -sh "$DIR" 2>/dev/null | cut -f1)"
rm -f "$SQL_FILE"
