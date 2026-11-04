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

# 确认心跳接口 vs 图片上传接口各自的调用频次（判断心跳是否真在用）
echo "=== 近 15 分钟 心跳接口 调用次数 ==="
journalctl -u camera-detection --since '15 minutes ago' --no-pager 2>/dev/null | grep -c "camera/status/heartbeat"

echo ""
echo "=== 近 15 分钟 图片上传接口 调用次数 ==="
journalctl -u camera-detection --since '15 minutes ago' --no-pager 2>/dev/null | grep -cE "defect-images/upload|/upload"

echo ""
echo "=== 访问日志里的心跳路径（若有 nginx）==="
ls -la /var/log/nginx/access.log 2>/dev/null && grep -c "camera/status/heartbeat" /var/log/nginx/access.log 2>/dev/null

echo ""
echo "=== 相机 9 与 23 的 recognize_count 与 last_upload_at ==="
mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new -e "SELECT CONCAT('id=',id,' code=',camera_code,' name=',camera_name,' ws=',workstation_id,' rc=',IFNULL(recognize_count,0),' last_upload=',IFNULL(last_upload_at,'NULL'),' hb=',IFNULL(last_heartbeat_at,'NULL')) FROM camera_status WHERE id IN (9,10,23,24) ORDER BY id" 2>/dev/null

echo ""
echo "=== 心跳接口是否在日志中留下痕迹（含 422/401 之类）==="
journalctl -u camera-detection --since '15 minutes ago' --no-pager 2>/dev/null | grep -iE "heartbeat|心跳" | tail -8
