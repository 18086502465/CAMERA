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

# 核查相机状态与索引现状（用于心跳匹配风险评估）
MY="mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new"

echo "=== camera_status 活跃相机明细 ==="
$MY -e "SELECT id, camera_code, camera_name, workstation_id, IFNULL(line_id,0), last_heartbeat_at FROM camera_status WHERE is_active=1 ORDER BY workstation_id" 2>/dev/null

echo ""
echo "=== camera_name 重名检查 ==="
$MY -e "SELECT camera_name, COUNT(*) FROM camera_status GROUP BY camera_name HAVING COUNT(*)>1" 2>/dev/null
echo "(空=无重名)"

echo ""
echo "=== camera_status 索引 ==="
$MY -e "SHOW INDEX FROM camera_status" 2>/dev/null | awk '{print $3, $5, $2}'

echo ""
echo "=== dim_camera 索引 ==="
$MY -e "SHOW INDEX FROM dim_camera" 2>/dev/null | awk '{print $3, $5, $2}'

echo ""
echo "=== dim_camera 绑定关系（camera_code -> workstation）==="
$MY -e "SELECT camera_id, camera_code, camera_name, workstation_id FROM dim_camera WHERE IFNULL(is_deleted,0)=0 ORDER BY workstation_id" 2>/dev/null

echo ""
echo "=== 相机名到 code 的映射（心跳按 name 匹配会取谁）==="
$MY -e "SELECT camera_name, camera_code, id, workstation_id FROM camera_status WHERE is_active=1 ORDER BY camera_name" 2>/dev/null
