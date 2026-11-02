#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 服务管理快捷脚本（Ubuntu 22.04 LTS）
#
# 用法：
#   ./service.sh start | stop | restart | status | logs | enable | disable
# ==============================================================================
set -uo pipefail

SERVICE_NAME="camera-detection"
INSTALL_DIR="${INSTALL_DIR:-/opt/camera-detection-system}"

if [[ -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi
PORT="${PORT:-8091}"

case "${1:-status}" in
  start)   systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
           systemctl start "$SERVICE_NAME" && echo "已启动" ;;
  stop)    systemctl stop "$SERVICE_NAME" && echo "已停止" ;;
  restart) # 先清失败计数：连续失败 3 次后 systemd 会进入
           # "Start request repeated too quickly"，此时 restart 会被直接拒绝。
           systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
           systemctl restart "$SERVICE_NAME" && echo "已重启" ;;
  enable)  systemctl enable "$SERVICE_NAME" && echo "已设为开机自启" ;;
  disable) systemctl disable "$SERVICE_NAME" && echo "已取消开机自启" ;;
  logs)    journalctl -u "$SERVICE_NAME" -f ;;
  status)
    echo "========================================="
    echo "产线缺陷在线AI检测系统 - 服务状态"
    echo "========================================="
    echo "服务名称：${SERVICE_NAME}"
    echo "运行状态：$(systemctl is-active "$SERVICE_NAME" 2>/dev/null)"
    echo "开机自启：$(systemctl is-enabled "$SERVICE_NAME" 2>/dev/null)"
    echo "访问地址：http://$(hostname -I 2>/dev/null | awk '{print $1}'):${PORT}"
    echo
    systemctl status "$SERVICE_NAME" --no-pager -l 2>/dev/null | head -20
    ;;
  *)
    echo "用法：$0 {start|stop|restart|status|logs|enable|disable}" >&2
    exit 1
    ;;
esac
