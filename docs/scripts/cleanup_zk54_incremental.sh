#!/bin/bash
# ==============================================================================
# ZK54 测试相机数据增量清理（设备暂不关闭期间的自动治理）
#
# 策略：白名单外（非 ZK23 的 DA8119489/DA4733319）的相机图片记录与文件，
#       全部删除 —— 与 2026-11-04 全量清理同一口径，可重复执行（幂等）。
# 频率：cron 每小时 :15（root）
# 日志：/data/camera-detection/backup/cleanup_zk54.log（追加，含每次删除量）
# 顺序：铁律 —— 先删库记录，再删磁盘文件
# ==============================================================================
set -uo pipefail
KEEP="'DA8119489','DA4733319'"
IMG_ROOT="/data/camera-detection/images/defect-images"
LOG="/data/camera-detection/backup/cleanup_zk54.log"
MP="${MYSQL_PASSWORD:?请以环境变量提供 MYSQL_PASSWORD}"
SQL() { mysql -uroot -p"$MP" -N -B -D 'wms-new' -e "$1" 2>/dev/null; }
log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

# 1) DB：box（JOIN file）→ file → batch → camera_images
N1=$(SQL "SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN ($KEEP);")
SQL "DELETE b FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN ($KEEP);" || true
N2=$(SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id NOT IN ($KEEP);")
SQL "DELETE FROM defect_image_file WHERE camera_id NOT IN ($KEEP);" || true
N3=$(SQL "SELECT COUNT(*) FROM defect_image_batch WHERE camera_id NOT IN ($KEEP);")
SQL "DELETE FROM defect_image_batch WHERE camera_id NOT IN ($KEEP);" || true
N4=$(SQL "SELECT COUNT(*) FROM camera_images WHERE image_path NOT LIKE '%/DA8119489/%' AND image_path NOT LIKE '%/DA4733319/%';")
SQL "DELETE FROM camera_images WHERE image_path NOT LIKE '%/DA8119489/%' AND image_path NOT LIKE '%/DA4733319/%';" || true

# 2) 磁盘：非白名单相机目录 + 空日期目录
DISK=0
while IFS= read -r d; do
  rm -rf "$d" && DISK=$((DISK+1))
done < <(find "$IMG_ROOT" -mindepth 2 -maxdepth 2 -type d ! -name 'DA8119489' ! -name 'DA4733319' 2>/dev/null)
find "$IMG_ROOT" -mindepth 1 -maxdepth 1 -type d -empty -delete 2>/dev/null

log "box=$N1 file=$N2 batch=$N3 camera_images=$N4 diskDirs=$DISK remain_file=$(SQL "SELECT COUNT(*) FROM defect_image_file;")"

# 3) 阈值守护：若单次删除过大（说明清理间隔被拉开），额外告警到日志
if [ "${N2:-0}" -gt 100000 ]; then
  log "WARN: 单次删除 file=$N2 超过 10 万条 —— 检查 cron 是否失效"
fi
