#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 磁盘水位巡检
#
# 为什么需要：
#   磁盘写满时应用**能正常启动**，但图片上传与数据库写入会陆续失败，
#   表现为「服务活着但业务全错」——这是最难排查的一类故障。
#   实测：图片按 350KB/张、144000 张/天 计算，日增 48 GB，
#         而 /var 原本只剩 45 GB —— 约 1 天写满，且**没有任何告警**。
#
# 动作：
#   - 检查关键分区（/var 数据库与日志、/data 图片与备份）
#   - 超过阈值时写 systemd journal（logger）+ 本地告警文件
#   - 退出码 1 表示有分区超阈值（便于被 cron 或监控采集）
#
# 安装：
#   sudo install -m 0755 check-disk-usage.sh /usr/local/bin/
#   sudo tee /etc/cron.d/check-disk-usage > /dev/null <<'EOF'
#   */30 * * * * root /usr/local/bin/check-disk-usage.sh
#   EOF
#
# 可配置环境变量：
#   THRESHOLD   水位阈值百分比（默认 85）
#   ALERT_FILE  告警文件路径（默认 /var/log/camera-detection-disk-alert.log）
#   MOUNTS      要检查的分区，空格分隔（默认 "/ /var /data"）
# ==============================================================================
set -uo pipefail

THRESHOLD="${THRESHOLD:-85}"
ALERT_FILE="${ALERT_FILE:-/var/log/camera-detection-disk-alert.log}"
# ★ 2026-11-04：加入 "/" —— JAR 备份（app/*.bak-*）与发布包都落在 / 分区，
#   而 / 只有 40G（实测备份曾涨到 1.3GB）。原先只巡检 /var 与 /data，
#   / 被撑满时没有任何告警。
MOUNTS="${MOUNTS:-/ /var /data}"

RC=0
for MOUNT in $MOUNTS; do
  [ -d "$MOUNT" ] || continue
  USE=$(df --output=pcent "$MOUNT" 2>/dev/null | tail -1 | tr -d ' %')
  AVAIL=$(df --output=avail "$MOUNT" 2>/dev/null | tail -1 | tr -d ' ')
  TOTAL=$(df --output=size "$MOUNT" 2>/dev/null | tail -1 | tr -d ' ')
  case "$USE" in
    ''|*[!0-9]*) continue ;;
  esac
  if [ "$USE" -gt "$THRESHOLD" ]; then
    MSG="[$(date '+%F %T')] 磁盘告警: ${MOUNT} 使用率 ${USE}% 超过阈值 ${THRESHOLD}%（剩余 ${AVAIL} KB / 共 ${TOTAL} KB）"
    logger -t camera-detection "$MSG" 2>/dev/null || true
    echo "$MSG" >> "$ALERT_FILE" 2>/dev/null || true
    RC=1
  fi
done

exit $RC
