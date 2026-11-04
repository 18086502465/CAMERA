#!/bin/bash
#
# ★ 口令从环境变量读取（勿把真实口令写进仓库）：
#   export SUDO_PASSWORD='<服务器 sudo 口令>'
#   bash deploy_jar.sh
# ============================================================
# 部署新 JAR 到 camera-detection 服务（带自动回滚）
# ============================================================
set -u
JAR=/tmp/time-bucket-demo.jar
APP_DIR=/opt/camera-detection-system/app
TARGET=$APP_DIR/time-bucket-demo.jar
STAMP=$(date '+%Y%m%d_%H%M%S')

echo "########## 1. 备份当前 JAR ##########"
echo "${SUDO_PASSWORD:-}" | sudo -S cp -p "$TARGET" "$TARGET.bak-$STAMP" 2>/dev/null
ls -la "$TARGET".bak-$STAMP
echo "$STAMP" > /tmp/deploy_stamp.txt

echo ""
echo "########## 2. 停服务 ##########"
echo "${SUDO_PASSWORD:-}" | sudo -S systemctl stop camera-detection 2>/dev/null
sleep 2
systemctl is-active camera-detection || echo "已停止"

echo ""
echo "########## 3. 替换 JAR ##########"
echo "${SUDO_PASSWORD:-}" | sudo -S cp "$JAR" "$TARGET" 2>/dev/null
echo "${SUDO_PASSWORD:-}" | sudo -S chown cdsys:cdsys "$TARGET" 2>/dev/null
ls -la "$TARGET"

echo ""
echo "########## 4. 启动服务 ##########"
echo "${SUDO_PASSWORD:-}" | sudo -S systemctl start camera-detection 2>/dev/null

# 轮询等待真正 active（restart 返回 0 只代表排队成功）
for i in $(seq 1 30); do
  st=$(systemctl is-active camera-detection 2>/dev/null)
  if [ "$st" = "active" ]; then
    echo "服务已 active（等待 ${i} 秒）"
    break
  fi
  sleep 1
done

echo ""
echo "########## 5. 启动后检查 ##########"
systemctl is-active camera-detection
systemctl show camera-detection -p NRestarts --value
echo "--- 健康检查 ---"
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' 'http://127.0.0.1:8091/' 2>/dev/null)
  if [ "$code" = "200" ]; then
    echo "HTTP 200（等待 ${i} 秒）"
    break
  fi
  echo "  HTTP $code，重试..."
  sleep 2
done

echo ""
echo "########## 6. 最近日志 ##########"
journalctl -u camera-detection --since "2 minutes ago" --no-pager 2>/dev/null | tail -25
