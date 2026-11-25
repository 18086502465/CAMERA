#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 缺陷图片磁盘清理 + 兼容表清理
#
# 【2026-11-05 职责重构：本脚本只管磁盘，不再删 defect_image_* 库记录】
# 图片数据的生命周期现由「两层」分别负责：
#
#   ① 数据库：应用内 DefectImageArchiveTask（每日 02:30）
#      把超过 hot-days（默认 31 天）的 defect_image_file/_box/_batch 记录
#      按月移入 defect_image_*_hist_YYYYMM 月表（移动，不是复制）。
#      主表恒定"近一个月"，页面默认查询永远毫秒级；
#      月表超过 retain-days（默认 730 天）由任务整表 DROP。
#
#   ② 磁盘：本脚本（每日 03:30）
#      图片文件保留 KEEP_DAYS（默认 730 天 = 2 年），按日期目录
#      （{上传根}/{yyyyMMdd}/{cameraId}/...）整目录删除。
#      磁盘文件与月表按同一口径（730 天）淘汰，保证"库里有记录就必须有文件"。
#      ★ H4（2026-11-24 深度评审）：执行顺序为「先库后盘」—— DB 步骤
#        （camera_images 清理 / --purge-db 的 defect_image_* 删除）成功后
#        才删磁盘日目录；DB 不可达或清理失败一律保留磁盘文件并 exit 2，
#        下一轮 cron 自动重试。绝不先删盘后删库。
#
# 【为什么不按 mtime 逐文件删】
# 旧版用 `find -mtime +7 -delete` 逐文件删除，在 10 万张/天量级下会扫全目录、
# 且删除顺序与库记录脱节。新版按日期目录整目录 `rm -rf`：一次 stat 判断即可，
# 目录名就是采集日期，天然与月表边界对齐。
#
# 【容量口径（务必按实际相机数核算）】
#   ZK23 两台相机实测 ≈ 120 MB/天（124 KB/张 × 约 950 张）
#     → 1 年 ≈ 44 GB，2 年 ≈ 88 GB（500G /data 充裕）
#   扩到 200 台同规格相机 ≈ 12 GB/天 → 2 年 ≈ 8.8 TB，必须扩盘或下调 KEEP_DAYS
#   脚本末尾会打印 /data 使用率，低于 MAX_DISK_USE 才算健康。
#
# 安装：
#   sudo install -m 0755 cleanup-defect-images.sh /usr/local/bin/
#   sudo tee /etc/cron.d/cleanup-defect-images > /dev/null <<'EOF'
#   30 3 * * * root /main/app/dist/scripts/cleanup-defect-images.sh
#   EOF
#
# 可配置环境变量（建议写进 /etc/default/camera-detection）：
#   DIR                     图片根目录
#   KEEP_DAYS               图片文件保留天数（默认 730 = 2 年）
#   KEEP_CAMERA_IMAGES_DAYS camera_images 兼容表保留天数（默认 30）
#   DB_NAME                 数据库名（默认 wms-new）
#   MYSQL_PASSWORD          数据库口令
#   MAX_DISK_USE            磁盘水位告警阈值（百分比，默认 85）
#
# 参数：
#   --dry-run          只打印将删除的目录/行数，不实际删除
#   --purge-db         手工兜底：删除超过 KEEP_DAYS 的 defect_image_*
#                      库记录（正常流程不需要，归档任务已接管）
#   --keep-days N      覆盖保留天数
#   --dir PATH         覆盖图片根目录
#   --db NAME          覆盖数据库名
# ==============================================================================
set -uo pipefail

DIR="${DIR:-/main/app/data/images/defect-images}"
KEEP_DAYS="${KEEP_DAYS:-730}"
KEEP_CAMERA_IMAGES_DAYS="${KEEP_CAMERA_IMAGES_DAYS:-30}"
DB_NAME="${DB_NAME:-wms-new}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-root}"
MAX_DISK_USE="${MAX_DISK_USE:-85}"
DRY_RUN=0
PURGE_BY_DB=0

# --- 口令来源 ----------------------------------------------------------------
# cron 行不再传 MYSQL_PASSWORD（cron 文件全局可读，写口令等于公开给所有本地用户）。
# 与 cleanup-test-cameras.sh 同一模式：优先自读 /etc/default/camera-detection
# （640 root:root），找不到时回退到已导出的环境变量（手工调试用）。
if [[ -z "${MYSQL_PASSWORD:-}" && -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi

log() { printf '[cleanup] %s\n' "$*"; }

# --- 参数解析 ----------------------------------------------------------------
# 必须用 `while [[ $# -gt 0 ]]` + `shift`，不能用 `for arg in "$@"`：
# for 会在开始时把 "$@" 展开成固定列表，shift 改不动它，
# 带值参数会取到"参数名本身"而不是值（2026-10-28 实测踩过）。
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --purge-db) PURGE_BY_DB=1; shift ;;
    --dir) DIR="${2:?--dir 需要一个值}"; shift 2 ;;
    --keep-days) KEEP_DAYS="${2:?--keep-days 需要一个值}"; shift 2 ;;
    --keep-camera-images-days) KEEP_CAMERA_IMAGES_DAYS="${2:?需要一个值}"; shift 2 ;;
    --db) DB_NAME="${2:?--db 需要一个值}"; shift 2 ;;
    -h|--help) sed -n '2,50p' "$0"; exit 0 ;;
    *) log "未知参数：$1"; exit 1 ;;
  esac
done

CUTOFF_DATE=$(date -d "-${KEEP_DAYS} days" '+%Y%m%d' 2>/dev/null) \
  || CUTOFF_DATE=$(date -d "@$(( $(date +%s) - KEEP_DAYS*86400 ))" '+%Y%m%d')
CUTOFF_TS=$(date -d "-${KEEP_DAYS} days" '+%Y-%m-%d %H:%M:%S' 2>/dev/null) \
  || CUTOFF_TS=$(date -d "@$(( $(date +%s) - KEEP_DAYS*86400 ))" '+%Y-%m-%d %H:%M:%S')
CUTOFF_CAM_IMG=$(date -d "-${KEEP_CAMERA_IMAGES_DAYS} days" '+%Y-%m-%d %H:%M:%S' 2>/dev/null) \
  || CUTOFF_CAM_IMG=$(date -d "@$(( $(date +%s) - KEEP_CAMERA_IMAGES_DAYS*86400 ))" '+%Y-%m-%d %H:%M:%S')

log "图片目录  ：${DIR}"
log "保留期    ：图片文件 ${KEEP_DAYS} 天（早于 ${CUTOFF_DATE} 的日期目录）"
log "兼容表    ：camera_images ${KEEP_CAMERA_IMAGES_DAYS} 天"
log "数据库    ：${DB_NAME}"
log "库记录清理：由应用归档任务负责（超过 retain-days 的月表整表 DROP）"

# --- 数据库先行（H4：先删库后删盘，DB 失败必须保住磁盘文件）--------------------
# 【2026-11-24 深度评审 H4 修复】原顺序是「磁盘 rm -rf 在前、DB DELETE 在后」，
# 注释宣称"二者互不影响"——对 camera_images（独立兼容表）勉强成立，但对
# --purge-db 的 defect_image_* 与磁盘日目录是**同一批数据**：DB 清理失败时
# 文件已删，留下大量「有记录无文件」的裂图，且 exit 2 只能事后告警。
# 现改为：DB 清理（或可达性确认）成功后才执行磁盘删除；DB 不可达/失败一律
# 保留磁盘文件并以非零退出（下一轮 cron 重试）。宁可这一轮不清理，
# 也不破坏「库里有记录就必须有文件」的约定。
# 普通模式（非 --purge-db）磁盘数据对应的 defect_image_* 记录由应用内
# DefectImageArchiveTask（每日 02:30，先于本脚本 03:30）处理 —— 本步骤
# 仍先执行 camera_images 清理以维持「数据库步骤在前」的结构。
HAVE_DB=0
DB_OK=0
if command -v mysql >/dev/null 2>&1; then
  if [[ -n "${MYSQL_PASSWORD:-}" ]]; then
    export MYSQL_PWD="$MYSQL_PASSWORD"
  fi
  if mysql -h"$DB_HOST" -u"$DB_USER" -N -B -e "SELECT 1" >/dev/null 2>&1; then
    HAVE_DB=1
  fi
fi
if [[ "$HAVE_DB" -eq 0 ]]; then
  log "ERROR: 无法连接数据库 —— 按先库后盘纪律，本轮不删磁盘文件（exit 2 让 cron 可见）"
  log "       下一轮调度会自动重试；连续失败请检查 MySQL / /etc/default/camera-detection"
  [[ "$DRY_RUN" == "1" ]] || exit 2
else
  # SQL 走独立临时文件：直接 -e 传多行 SQL 时反引号要多层转义，极易出错
  SQL_FILE="$(mktemp /tmp/cds_cleanup_XXXXXX.sql)"
  cat > "$SQL_FILE" <<SQLEOF
DELETE FROM camera_images WHERE capture_time < '${CUTOFF_CAM_IMG}';
SQLEOF
  if [[ "$PURGE_BY_DB" == "1" ]]; then
    log "（--purge-db：手工删除超过 ${KEEP_DAYS} 天的 defect_image_* 库记录）"
    cat >> "$SQL_FILE" <<SQLEOF
DELETE b FROM defect_image_box b
JOIN defect_image_file f ON f.image_id = b.image_id
WHERE COALESCE(f.capture_time, f.upload_time, f.create_time) < '${CUTOFF_TS}';
DELETE FROM defect_image_file WHERE COALESCE(capture_time, upload_time, create_time) < '${CUTOFF_TS}';
DELETE FROM defect_image_batch
WHERE upload_time < '${CUTOFF_TS}'
  AND NOT EXISTS (SELECT 1 FROM defect_image_file f WHERE f.batch_id = defect_image_batch.batch_id);
SQLEOF
  fi
  if [[ "$DRY_RUN" == "1" ]]; then
    log "（--dry-run 模式，不执行 SQL）"
    DB_OK=1
  elif mysql -h"$DB_HOST" -u"$DB_USER" "$DB_NAME" < "$SQL_FILE"; then
    log "✓ camera_images（> ${KEEP_CAMERA_IMAGES_DAYS} 天）已清理"
    DB_OK=1
  else
    # 不能用 2>/dev/null 吞 stderr —— 那会把「清理失败」伪装成「清理成功」
    rm -f "$SQL_FILE"
    log "ERROR: camera_images 清理失败（详见上方 MySQL 报错）—— 磁盘文件保留不删"
    exit 2
  fi
  rm -f "$SQL_FILE"
fi

# --- 磁盘：按日期目录整目录删除（仅在 DB 步骤成功后执行）------------------------
SKIP_DISK=0
if [[ ! -d "$DIR" ]]; then
  log "图片目录不存在：${DIR}（跳过磁盘步骤）"
  SKIP_DISK=1
fi

if [[ "$SKIP_DISK" -eq 0 && "$DB_OK" -eq 1 ]]; then
  # 日期目录名 = yyyyMMdd，字符串比较即可判断先后（避免逐文件 mtime 扫描）
  mapfile -t OLD_DIRS < <(find "$DIR" -mindepth 1 -maxdepth 1 -type d \
      -regextype posix-extended -regex '.*/[0-9]{8}' 2>/dev/null | sort)
  TO_DELETE=()
  for d in "${OLD_DIRS[@]:-}"; do
    [[ -z "$d" ]] && continue
    name="$(basename "$d")"
    if [[ "$name" < "$CUTOFF_DATE" ]]; then
      TO_DELETE+=("$d")
    fi
  done
  log "待删除日期目录：${#TO_DELETE[@]} 个（保留期 ${KEEP_DAYS} 天）"
  for d in "${TO_DELETE[@]:-}"; do
    [[ -z "$d" ]] && continue
    n=$(find "$d" -type f 2>/dev/null | wc -l | tr -d ' ')
    sz=$(du -sh "$d" 2>/dev/null | cut -f1)
    if [[ "$DRY_RUN" == "1" ]]; then
      log "  [dry-run] $d（${n} 个文件，${sz}）"
    else
      rm -rf "$d" && log "  ✓ 已删除 $d（${n} 个文件，${sz}）"
    fi
  done
  if [[ "$DRY_RUN" != "1" ]]; then
    find "$DIR" -mindepth 1 -maxdepth 1 -type d -empty -delete 2>/dev/null || true
  fi
fi

# --- 收尾：容量与水位 -----------------------------------------------------------
if [[ "$SKIP_DISK" -eq 0 ]]; then
  log "图片目录当前占用：$(du -sh "$DIR" 2>/dev/null | cut -f1)"
fi
DATA_MNT=$(df --output=target "$(dirname "$DIR")" 2>/dev/null | tail -1)
DATA_USE=$(df --output=pcent "$(dirname "$DIR")" 2>/dev/null | tail -1 | tr -d ' %')
if [[ -n "${DATA_USE:-}" ]]; then
  if [[ "$DATA_USE" -gt "$MAX_DISK_USE" ]]; then
    log "WARN: ${DATA_MNT} 使用率 ${DATA_USE}% 超过阈值 ${MAX_DISK_USE}% —— 考虑扩盘或下调 KEEP_DAYS"
  else
    log "分区 ${DATA_MNT} 使用率 ${DATA_USE}%（阈值 ${MAX_DISK_USE}%）"
  fi
fi
log "清理完成"
