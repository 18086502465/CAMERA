#!/bin/bash
# ==============================================================================
# 测试数据清理：只保留 ZK23 产线（DA8119489 / DA4733319）的相机数据
#
# 顺序（铁律：先删库记录，再删磁盘文件）：
#   1. 备份涉及的 4 张表（zstd 压缩）
#   2. 删 DB：box → file → batch → camera_images
#   3. 删磁盘：非保留相机的图片目录 + 空日期目录
#   4. OPTIMIZE TABLE 回收 InnoDB 空间
#   5. 附带清理：旧 JAR 备份（保留最近 3 个）、journal 限额
# ==============================================================================
set -uo pipefail

KEEP="'DA8119489','DA4733319'"
KEEP_DIRS="DA8119489 DA4733319"
IMG_ROOT="/data/camera-detection/images/defect-images"
BK_DIR="/data/camera-detection/backup/pre_cleanup_20261104"
MP="${MYSQL_PASSWORD:?请以环境变量提供 MYSQL_PASSWORD}"
SQL() { mysql -uroot -p"$MP" -N -B -D 'wms-new' -e "$1" 2>/dev/null; }

echo "==== 0. 前置检查 ===="
systemctl is-active camera-detection || { echo "服务未运行，中止"; exit 1; }
BEFORE_DF=$(df --output=used -BG /data | tail -1 | tr -dc '0-9')
echo "  /data 清理前已用: ${BEFORE_DF}G"

echo ""
echo "==== 1. 备份 4 张表（zstd）===="
mkdir -p "$BK_DIR" && chmod 700 "$BK_DIR"
for t in defect_image_box defect_image_file defect_image_batch camera_images; do
  mysqldump -uroot -p"$MP" --single-transaction --quick wms-new "$t" 2>/dev/null \
    | zstd -q -3 > "$BK_DIR/${t}.sql.zst"
  echo "  $t -> $(du -h "$BK_DIR/${t}.sql.zst" | cut -f1)"
done
sha256sum "$BK_DIR"/*.zst > "$BK_DIR/SHA256SUMS"
echo "  备份目录: $BK_DIR"

echo ""
echo "==== 2. 删除 DB 记录（先子后父）===="
T0=$(date +%s)
N_BOX=$(SQL "SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN ($KEEP);")
echo "  box 待删: $N_BOX"
SQL "DELETE b FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN ($KEEP);" \
  && echo "  box 已删" || { echo "  ❌ box 删除失败，中止（未动磁盘）"; exit 1; }

N_FILE=$(SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id NOT IN ($KEEP);")
SQL "DELETE FROM defect_image_file WHERE camera_id NOT IN ($KEEP);" \
  && echo "  file 已删（$N_FILE）" || { echo "  ❌ file 删除失败，中止"; exit 1; }

N_BAT=$(SQL "SELECT COUNT(*) FROM defect_image_batch WHERE camera_id NOT IN ($KEEP);")
SQL "DELETE FROM defect_image_batch WHERE camera_id NOT IN ($KEEP);" \
  && echo "  batch 已删（$N_BAT）" || { echo "  ❌ batch 删除失败"; exit 1; }

N_CI=$(SQL "SELECT COUNT(*) FROM camera_images WHERE image_path NOT LIKE '%/DA8119489/%' AND image_path NOT LIKE '%/DA4733319/%';")
SQL "DELETE FROM camera_images WHERE image_path NOT LIKE '%/DA8119489/%' AND image_path NOT LIKE '%/DA4733319/%';" \
  && echo "  camera_images 已删（$N_CI）" || { echo "  ❌ camera_images 删除失败"; exit 1; }
echo "  DB 删除耗时: $(( $(date +%s) - T0 ))s"

echo ""
echo "==== 3. 校验 DB 保留结果 ===="
SQL "SELECT 'file', COUNT(*) FROM defect_image_file UNION ALL SELECT 'box', COUNT(*) FROM defect_image_box UNION ALL SELECT 'batch', COUNT(*) FROM defect_image_batch UNION ALL SELECT 'camera_images', COUNT(*) FROM camera_images;"

echo ""
echo "==== 4. 删除磁盘文件（保留相机的目录）===="
T0=$(date +%s)
# 列出将被删除的相机目录（先展示再删）
MAPFILE -t DEL_DIRS < <(find "$IMG_ROOT" -mindepth 2 -maxdepth 2 -type d \
  ! -name 'DA8119489' ! -name 'DA4733319' 2>/dev/null)
echo "  将删除 ${#DEL_DIRS[@]} 个相机目录:"
for d in "${DEL_DIRS[@]}"; do echo "    $d"; done
for d in "${DEL_DIRS[@]}"; do rm -rf "$d"; done
# 删空的日期目录
find "$IMG_ROOT" -mindepth 1 -maxdepth 1 -type d -empty -delete 2>/dev/null
echo "  磁盘删除耗时: $(( $(date +%s) - T0 ))s"
echo "  剩余目录结构:"
find "$IMG_ROOT" -mindepth 1 -maxdepth 2 -type d 2>/dev/null | sed 's/^/    /'
du -sh "$IMG_ROOT" 2>/dev/null | sed 's/^/  图片目录现为: /'

echo ""
echo "==== 5. OPTIMIZE 回收 InnoDB 空间 ===="
for t in defect_image_box defect_image_file defect_image_batch camera_images; do
  R=$(SQL "OPTIMIZE TABLE $t;" | awk '{print $3, $4}')
  echo "  $t -> $R"
done

echo ""
echo "==== 6. 附带清理 ===="
# 6a. JAR 备份保留最近 3 个
cd /opt/camera-detection-system/app || exit 1
LS_BAK=$(ls -1t time-bucket-demo.jar.bak-* 2>/dev/null)
CNT=$(echo "$LS_BAK" | grep -c . || true)
if [ "${CNT:-0}" -gt 3 ]; then
  echo "$LS_BAK" | tail -n +4 | while read -r f; do rm -f "$f" && echo "  已删 JAR 备份: $f"; done
fi
# 6b. journal 限额 100M
journalctl --vacuum-size=100M 2>&1 | tail -1 | sed 's/^/  /'
echo "  app 目录现为: $(du -sh /opt/camera-detection-system/app | cut -f1)"

echo ""
echo "==== 7. 结果 ===="
AFTER_DF=$(df --output=used -BG /data | tail -1 | tr -dc '0-9')
echo "  /data: ${BEFORE_DF}G -> ${AFTER_DF}G（释放 $((BEFORE_DF - AFTER_DF))G）"
df -h / /var /data | grep -v Filesystem | sed 's/^/  /'
SQL "SELECT CONCAT('  保留: file=', (SELECT COUNT(*) FROM defect_image_file), ' box=', (SELECT COUNT(*) FROM defect_image_box), ' batch=', (SELECT COUNT(*) FROM defect_image_batch), ' camera_images=', (SELECT COUNT(*) FROM camera_images));"
systemctl is-active camera-detection | sed 's/^/  服务: /'
