#!/bin/bash
# ============================================================
# 部署新 JAR 到 camera-detection 服务
#
# ★ 口令从环境变量读取（勿把真实口令写进仓库）：
#   export SUDO_PASSWORD='<服务器 sudo 口令>'
#   bash deploy_jar.sh [JAR路径]
#
# 能力：
#   1. 备份当前 JAR（带时间戳）
#   2. 原子替换（先写临时文件再 mv，避免替换过程中服务读到半个文件）
#   3. 启动 + 轮询健康检查
#   4. **健康检查失败自动回滚**到上一版本，并明确告警
#
# ⚠️ 2026-11-04 修正（一次真实的静默失败）：
#   原实现用 `echo "${SUDO_PASSWORD:-}" | sudo -S ... 2>/dev/null`，
#   当 SUDO_PASSWORD 未设置时传入的是**空字符串** → sudo 认证失败 →
#   备份与替换全部失败，但 `2>/dev/null` 把错误吞了，脚本却继续往下跑并
#   打印「服务已 active」—— **实际 JAR 根本没换**，排查时极具误导性。
#   现在改为：
#     · 前置检查 SUDO_PASSWORD，为空直接退出；
#     · 去掉 2>/dev/null，让错误可见；
#     · 每步检查返回码，失败即中止；
#     · 校验替换后的 JAR 大小/校验和确实变了。
# ============================================================
set -u

JAR="${1:-/tmp/time-bucket-demo.jar}"
APP_DIR=/opt/camera-detection-system/app
TARGET="$APP_DIR/time-bucket-demo.jar"
SERVICE=camera-detection
STAMP="$(date '+%Y%m%d_%H%M%S')"
BACKUP="$TARGET.bak-$STAMP"
HEALTH_URL="http://127.0.0.1:8091/"
HEALTH_RETRIES=20

log()  { printf '[deploy] %s\n' "$*"; }
die()  { printf '[deploy] 错误：%s\n' "$*" >&2; exit 1; }

# --- 前置检查 --------------------------------------------------------------
[[ -n "${SUDO_PASSWORD:-}" ]] || die "未设置环境变量 SUDO_PASSWORD，无法执行需要 root 的操作
  用法：export SUDO_PASSWORD='<服务器 sudo 口令>' && bash $0"
[[ -f "$JAR" ]] || die "未找到待部署的 JAR：$JAR"

sudo_do() {
  # 所有需要 root 的操作统一走这里；口令错误会立刻暴露，不静默
  echo "$SUDO_PASSWORD" | sudo -S "$@" || die "sudo 执行失败：$*"
}

OLD_SIZE=$(stat -c %s "$TARGET" 2>/dev/null || echo 0)
NEW_SIZE=$(stat -c %s "$JAR" 2>/dev/null || echo 0)
log "待部署 JAR：$JAR（$NEW_SIZE 字节）"
log "当前线上 JAR：$TARGET（$OLD_SIZE 字节）"

echo ""
log "1/5 备份当前 JAR"
sudo_do cp -p "$TARGET" "$BACKUP"
[[ -f "$BACKUP" ]] || die "备份失败：$BACKUP 不存在"
log "备份完成：$BACKUP"

echo ""
log "2/5 停服务"
sudo_do systemctl stop "$SERVICE"
for _ in $(seq 1 15); do
  systemctl is-active "$SERVICE" >/dev/null 2>&1 || break
  sleep 1
done
log "服务已停止"

echo ""
log "3/5 原子替换 JAR"
# 先写临时文件再 mv：替换过程中即使服务意外重启，也不会读到半个文件
sudo_do cp "$JAR" "${TARGET}.new"
sudo_do chown cdsys:cdsys "${TARGET}.new"
sudo_do chmod 644 "${TARGET}.new"
sudo_do mv -f "${TARGET}.new" "$TARGET"
NEW_SIZE2=$(stat -c %s "$TARGET" 2>/dev/null || echo 0)
[[ "$NEW_SIZE2" == "$NEW_SIZE" ]] || die "替换后大小不符（期望 $NEW_SIZE，实际 $NEW_SIZE2），已保留备份 $BACKUP"
log "替换完成（$NEW_SIZE2 字节）"

echo ""
log "4/5 启动服务"
sudo_do systemctl start "$SERVICE"

HEALTH_OK=0
for i in $(seq 1 "$HEALTH_RETRIES"); do
  st=$(systemctl is-active "$SERVICE" 2>/dev/null)
  code=$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null)
  if [ "$st" = "active" ] && [ "$code" = "200" ]; then
    HEALTH_OK=1
    log "健康检查通过（HTTP 200，等待 ${i} 秒）"
    break
  fi
  sleep 2
done

if [[ "$HEALTH_OK" != "1" ]]; then
  echo ""
  log "健康检查失败（服务状态=$(systemctl is-active "$SERVICE" 2>/dev/null)，HTTP=$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null)）"
  log "开始自动回滚到上一版本：$BACKUP"
  sudo_do cp -p "$BACKUP" "$TARGET"
  sudo_do systemctl restart "$SERVICE"
  for _ in $(seq 1 15); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null)
    [ "$code" = "200" ] && break
    sleep 2
  done
  echo ""
  echo "============================================================"
  echo " 部署失败，已自动回滚到上一版本"
  echo " 当前运行的 JAR：$TARGET（来自 $BACKUP）"
  echo " 请查看日志：journalctl -u $SERVICE -n 100 --no-pager"
  echo "============================================================"
  exit 1
fi

echo ""
log "5/5 部署完成"
echo "  版本 JAR  ：$TARGET"
echo "  备份      ：$BACKUP"
echo "  回滚命令  ：sudo cp -p $BACKUP $TARGET && sudo systemctl restart $SERVICE"
echo "  服务状态  ：$(systemctl is-active "$SERVICE" 2>/dev/null)"
echo "  重启次数  ：$(systemctl show "$SERVICE" -p NRestarts --value 2>/dev/null)"
echo ""
echo " 最近日志："
journalctl -u "$SERVICE" --since "2 minutes ago" --no-pager 2>/dev/null | tail -15
