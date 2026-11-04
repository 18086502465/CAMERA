#!/bin/bash
#!/usr/bin/env bash
# ==============================================================================
# ⚠️ 凭据从环境变量读取，脚本内不含明文口令：
#      export MYSQL_PASSWORD='<数据库口令>'
#      export SSH_PASSWORD='<SSH 口令>'
#      export SSH_HOST='10.2.77.72'  SSH_USER='lims'
#    未设置时脚本会明确报错退出，而不是用默认口令。
# ==============================================================================

# ---- 凭据前置检查（缺失即退出，不用默认口令）----
: "${MYSQL_PASSWORD:?请先 export MYSQL_PASSWORD=<数据库口令>}"
: "${SSH_PASSWORD:?请先 export SSH_PASSWORD=<SSH 口令>}"

# 核实 DetectionList 可用的真实数据源
MY="mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new"

echo "=== product_detection_history 行数（前端 defect.js 指向的表）==="
$MY -e "SELECT COUNT(*) FROM product_detection_history" 2>/dev/null

echo ""
echo "=== defect_image_file 行数（现场设备真实在写的表）==="
$MY -e "SELECT COUNT(*) FROM defect_image_file" 2>/dev/null

echo ""
echo "=== product_detection_history 最新几条 ==="
$MY -e "SELECT CONCAT(id,' | ',IFNULL(camera_name,'-'),' | ',IFNULL(defect_code,'-'),' | ',IFNULL(detection_time,'-')) FROM product_detection_history ORDER BY id DESC LIMIT 5" 2>/dev/null

echo ""
echo "=== defect_image_file 最近 5 条（看字段口径）==="
$MY -e "SELECT CONCAT(image_id,' | ',IFNULL(camera_id,'-'),' | ',IFNULL(camera_name,'-'),' | ',IFNULL(create_time,'-')) FROM defect_image_file ORDER BY image_id DESC LIMIT 5" 2>/dev/null

echo ""
echo "=== defect_image_file 有哪些可映射的维度 ==="
$MY -e "SHOW COLUMNS FROM defect_image_file" 2>/dev/null | awk '{print $1}' | tr '\n' ' '
echo ""

echo ""
echo "=== /api/v1/defect-images/history 的参数（现场真实通道）==="
grep -n -A20 '@GetMapping("/history")' /dev/null 2>/dev/null || true
