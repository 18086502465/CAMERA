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

# 验证心跳按 camera_name 匹配时实际命中哪一行
MY="mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new"

echo "=== SELECT ... WHERE camera_name='CV1_Cam1' LIMIT 1（代码实际执行的 SQL）==="
$MY -e "SELECT CONCAT('命中 id=',id,' code=',camera_code,' ws=',workstation_id) FROM camera_status WHERE camera_name='CV1_Cam1' LIMIT 1" 2>/dev/null

echo ""
echo "=== EXPLAIN 看是否走索引 ==="
$MY -e "EXPLAIN SELECT id FROM camera_status WHERE camera_name='CV1_Cam1' LIMIT 1" 2>/dev/null

echo ""
echo "=== camera_status 全量（看有多少 is_active=0 的历史行）==="
$MY -e "SELECT id, camera_code, camera_name, workstation_id, is_active, IFNULL(recognize_count,0) FROM camera_status ORDER BY id" 2>/dev/null

echo ""
echo "=== 表行数 ==="
$MY -e "SELECT COUNT(*) FROM camera_status" 2>/dev/null

echo ""
echo "=== 心跳接口最近日志（含 UPDATE 痕迹）==="
journalctl -u camera-detection --since '10 minutes ago' --no-pager 2>/dev/null | grep -iE "心跳|heartbeat|计数器重置|未在系统配置" | tail -12
