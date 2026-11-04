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

# 实测心跳串号：连续采样两台重名相机的 recognize_count 与 last_heartbeat_at
MY="mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new"

for i in 1 2 3; do
  echo "--- 采样 $i ($(date +%H:%M:%S)) ---"
  $MY -e "SELECT CONCAT('id=',id,' code=',camera_code,' name=',camera_name,' ws=',workstation_id,' rc=',IFNULL(recognize_count,0),' hb=',IFNULL(last_heartbeat_at,'NULL')) FROM camera_status WHERE camera_name IN ('CV1_Cam1','CV2_Cam1') ORDER BY id" 2>/dev/null
  [ $i -lt 3 ] && sleep 20
done
