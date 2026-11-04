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

# 最终确认：心跳串号是否已发生
# 假设：ZK54 的 CV1_Cam1=id9(DA8119487,ws7)，ZK23 的 CV1_Cam1=id23(DA8119489,ws23)
# 若串号，则 ZK23 设备心跳会写进 id9，导致 id9 的 recognize_count 被 ZK23 设备值覆盖
MY="mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new"

echo "=== 两台重名相机的 current 值 ==="
$MY -e "SELECT CONCAT('id=',id,' code=',camera_code,' ws=',workstation_id,' rc=',IFNULL(recognize_count,0),' cc=',IFNULL(correct_count,0),' ac=',IFNULL(abnormal_count,0),' hb=',IFNULL(last_heartbeat_at,'NULL'),' prod=',IFNULL(product_name,'NULL')) FROM camera_status WHERE id IN (9,23) ORDER BY id" 2>/dev/null

echo ""
echo "=== 应用日志：心跳处理涉及的 cameraName ==="
journalctl -u camera-detection --since '20 minutes ago' --no-pager 2>/dev/null | grep -oE "CV[0-9]+_Cam[0-9]+" | sort | uniq -c | sort -rn | head -10

echo ""
echo "=== 相机 9 / 23 的 defect_image_file 实际图片量（谁在真正上报）==="
$MY -e "SELECT CONCAT('camera_id=',camera_id,' name=',IFNULL(camera_name,'-'),' images=',COUNT(*)) FROM defect_image_file WHERE camera_id IN ('DA8119487','DA8119489','DA9200622','DA4733319') GROUP BY camera_id, camera_name" 2>/dev/null

echo ""
echo "=== fact_camera_status_current（真实状态表，按 code）==="
$MY -e "SELECT CONCAT('camera_code=',IFNULL(camera_code,'-'),' rc=',IFNULL(recognize_count,0),' last=',IFNULL(last_upload_time,'NULL')) FROM fact_camera_status_current WHERE camera_code IN ('DA8119487','DA8119489','DA9200622','DA4733319')" 2>/dev/null
