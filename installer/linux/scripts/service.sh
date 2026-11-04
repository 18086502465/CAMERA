#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 统一运维入口
#
# 用法：
#   ./service.sh start | stop | restart | status | logs | enable | disable
#   ./service.sh version          显示当前运行的版本信息
#   ./service.sh health           健康检查（进程 + HTTP + 磁盘 + 数据库 + 心跳）
#   ./service.sh rollback [版本]   回滚到指定备份（省略版本=回滚到上一个）
#   ./service.sh cleanup [保留数]  清理过期的 JAR 备份（默认保留 5 个）
#   ./service.sh config-check     校验配置与密钥的权限、占位符是否已注入
#
# ★ 2026-11-04 扩展：把原先散落在各处的运维动作收敛到一个入口，
#   补齐「版本可追溯 / 健康可判定 / 故障可回滚」三项发布侧闭环能力。
# ==============================================================================
set -uo pipefail

SERVICE_NAME="camera-detection"
INSTALL_DIR="${INSTALL_DIR:-/opt/camera-detection-system}"
APP_DIR="${INSTALL_DIR}/app"
TARGET_JAR="${APP_DIR}/time-bucket-demo.jar"
DEFAULT_CFG="/etc/default/camera-detection"

if [[ -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi
PORT="${PORT:-8091}"

# ⚠️ /etc/default/camera-detection 权限是 640 root:root —— **普通用户读不了**。
#   需要读它的子命令（health / config-check）必须借助 sudo。
#   这里探测一次：有 sudo 权限就用 sudo 读，没有则退回普通读取（会读不到，
#   此时相关检查项显示「未知」并提示用 sudo 重跑，而不是误报「缺失」）。
SUDO=""
if [[ -w /etc/default/camera-detection ]] || [[ -r /etc/default/camera-detection ]]; then
  SUDO=""
elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  SUDO="sudo -n"
else
  SUDO="sudo -S"
fi

# 读取密钥文件某一行（不打印值，只用于后续命令使用）
read_secret() {
  local key="$1"
  if [[ -n "$SUDO" ]]; then
    $SUDO grep -E "^${key}=" "$DEFAULT_CFG" 2>/dev/null | head -1 \
      | sed -E "s/^${key}=\"?([^\"]*)\"?\$/\1/"
  else
    grep -E "^${key}=" "$DEFAULT_CFG" 2>/dev/null | head -1 \
      | sed -E "s/^${key}=\"?([^\"]*)\"?\$/\1/"
  fi
}

# 判断密钥是否存在
has_secret() {
  local key="$1"
  if [[ -n "$SUDO" ]]; then
    $SUDO grep -qE "^${key}=.+" "$DEFAULT_CFG" 2>/dev/null
  else
    grep -qE "^${key}=.+" "$DEFAULT_CFG" 2>/dev/null
  fi
}

log() { printf '[service] %s\n' "$*"; }
die() { printf '[service] 错误：%s\n' "$*" >&2; exit 1; }

# --- 读取已安装版本信息（install/update 时写入）---------------------------
read_version_info() {
  local vf="${APP_DIR}/VERSION"
  [[ -f "$vf" ]] && cat "$vf" || echo "（未安装版本信息文件，可能由旧版本手工部署）"
}

cmd_version() {
  echo "========================================="
  echo "产线缺陷在线AI检测系统 - 版本信息"
  echo "========================================="
  echo "JAR 路径：${TARGET_JAR}"
  echo "JAR 大小：$(stat -c %s "$TARGET_JAR" 2>/dev/null || echo '未知') 字节"
  echo "修改时间：$(stat -c %y "$TARGET_JAR" 2>/dev/null || echo '未知')"
  echo "SHA256   ：$(sha256sum "$TARGET_JAR" 2>/dev/null | awk '{print $1}' || echo '未知')"
  echo
  echo "--- 版本记录 ---"
  read_version_info
  echo
  echo "--- 可回滚的备份 ---"
  ls -1t "${TARGET_JAR}".bak-* 2>/dev/null | head -10 | while read -r f; do
    printf '  %s  %s  %s\n' \
      "$(stat -c %y "$f" 2>/dev/null | cut -d. -f1)" \
      "$(stat -c %s "$f" 2>/dev/null)" \
      "$(basename "$f")"
  done
  [[ -z "$(ls -1 "${TARGET_JAR}".bak-* 2>/dev/null)" ]] && echo "  （无备份）"
}

cmd_health() {
  echo "========================================="
  echo "产线缺陷在线AI检测系统 - 健康检查"
  echo "========================================="
  local overall=0

  # 1) 进程
  local st; st=$(systemctl is-active "$SERVICE_NAME" 2>/dev/null)
  printf "  %-14s %s\n" "进程状态" "${st:-未知}"
  [[ "$st" == "active" ]] || overall=1

  # 2) HTTP
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/" 2>/dev/null)
  printf "  %-14s HTTP %s\n" "HTTP 探活" "${code:-000}"
  [[ "$code" == "200" ]] || overall=1

  # 3) 磁盘
  local mp use
  for mp in / /data /var; do
    [[ -d "$mp" ]] || continue
    use=$(df --output=pcent "$mp" 2>/dev/null | tail -1 | tr -d ' %')
    printf "  %-14s %s 使用率 %s%%\n" "磁盘 $mp" "$(df -h "$mp" 2>/dev/null | tail -1 | awk '{print $2}')" "${use:-?}"
    [[ -n "$use" && "$use" -ge 90 ]] && overall=1
  done

  # 4) 数据库
  # ⚠️ 变量必须先初始化：set -u 下未赋值即引用会直接退出脚本
  local db_pw="" db_ok="未知"
  db_pw=$(read_secret MYSQL_PASSWORD)
  if [[ -n "$db_pw" ]]; then
    if MYSQL_PWD="$db_pw" mysql -h127.0.0.1 -uroot --protocol=tcp -N -B -e "SELECT 1" >/dev/null 2>&1; then
      db_ok="连通"
    else
      db_ok="不可用"
      overall=1
    fi
  else
    db_ok="未读到口令（需 sudo 运行本命令）"
    overall=1
  fi
  printf "  %-14s %s\n" "数据库" "$db_ok"

  # 5) 设备心跳（近 5 分钟内有更新的相机数）
  local cams="未知"
  if [[ -n "$db_pw" ]]; then
    cams=$(MYSQL_PWD="$db_pw" mysql -h127.0.0.1 -uroot --protocol=tcp -N -B -D "${VERIFY_DB_NAME:-wms-new}" \
      -e "SELECT CONCAT(COUNT(*),' 台') FROM camera_status WHERE is_active=1 AND last_heartbeat_at > NOW() - INTERVAL 5 MINUTE" 2>/dev/null)
    [[ -z "$cams" ]] && cams="查询失败"
  fi
  printf "  %-14s %s（近 5 分钟）\n" "设备心跳" "$cams"

  echo
  if [[ "$overall" == "0" ]]; then
    echo "结论：健康"
  else
    echo "结论：存在异常（见上方标记项）"
  fi
  return "$overall"
}

cmd_rollback() {
  local target="${1:-}"
  local backups
  backups=$(ls -1t "${TARGET_JAR}".bak-* 2>/dev/null)
  [[ -z "$backups" ]] && die "没有可用备份，无法回滚"

  if [[ -z "$target" ]]; then
    target=$(echo "$backups" | head -1)
    log "未指定版本，回滚到最近一个备份：$(basename "$target")"
  else
    # 支持按时间戳前缀或序号匹配
    target=$(echo "$backups" | grep -- "$target" | head -1)
    [[ -z "$target" ]] && die "未找到匹配的备份：$target"
    log "回滚目标：$(basename "$target")"
  fi

  [[ -f "$target" ]] || die "备份文件不存在：$target"
  echo "  备份大小：$(stat -c %s "$target") 字节"
  echo "  备份时间：$(stat -c %y "$target")"

  # 备份当前版本，便于「回退回滚」
  local cur="${TARGET_JAR}.bak-$(date '+%Y%m%d_%H%M%S')"
  cp -p "$TARGET_JAR" "$cur" 2>/dev/null && log "当前版本已另存为 $(basename "$cur")"

  systemctl stop "$SERVICE_NAME" 2>/dev/null
  cp -p "$target" "${TARGET_JAR}.new" && mv -f "${TARGET_JAR}.new" "$TARGET_JAR"
  systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
  systemctl start "$SERVICE_NAME"

  # 健康检查
  local ok=0 code
  for _ in $(seq 1 20); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/" 2>/dev/null)
    if [[ "$code" == "200" ]]; then ok=1; break; fi
    sleep 2
  done

  if [[ "$ok" == "1" ]]; then
    log "回滚成功，健康检查通过"
  else
    log "回滚后健康检查未通过，请查看 journalctl -u $SERVICE_NAME -n 100"
    return 1
  fi
}

cmd_cleanup() {
  local keep="${1:-5}"
  [[ "$keep" =~ ^[0-9]+$ ]] || die "保留数量必须是正整数"
  local total freed=0
  total=$(ls -1 "${TARGET_JAR}".bak-* 2>/dev/null | wc -l)
  if [[ "$total" -le "$keep" ]]; then
    log "现有备份 $total 个，不超过保留数 $keep，无需清理"
    return 0
  fi
  log "清理 JAR 备份：保留最近 $keep 个，删除 $((total - keep)) 个"
  ls -1t "${TARGET_JAR}".bak-* 2>/dev/null | tail -n "+$((keep + 1))" | while read -r f; do
    sz=$(stat -c %s "$f" 2>/dev/null || echo 0)
    rm -f "$f" && echo "  已删除 $(basename "$f")（$((sz / 1024 / 1024)) MB）"
  done
  log "清理完成"
}

cmd_config_check() {
  echo "========================================="
  echo "产线缺陷在线AI检测系统 - 配置与密钥检查"
  echo "========================================="
  local bad=0

  # ⚠️ /etc/default/camera-detection 是 640 root:root，普通用户 stat 不到也读不了。
  #   用 sudo 探测；没有 sudo 时显示「需 root 权限查看」而不是误报异常。
  local perm="" owner=""
  if [[ -n "$SUDO" ]]; then
    perm=$($SUDO stat -c '%a' "$DEFAULT_CFG" 2>/dev/null | tail -1)
    owner=$($SUDO stat -c '%U:%G' "$DEFAULT_CFG" 2>/dev/null | tail -1)
  else
    perm=$(stat -c '%a' "$DEFAULT_CFG" 2>/dev/null | tail -1)
    owner=$(stat -c '%U:%G' "$DEFAULT_CFG" 2>/dev/null | tail -1)
  fi
  printf "  %-34s %s\n" "/etc/default/camera-detection" \
    "${perm:-需 root 权限查看} ${owner:-}"

  local cfg_perm="" cfg_owner=""
  if [[ -n "$SUDO" ]]; then
    cfg_perm=$($SUDO stat -c '%a' "${INSTALL_DIR}/config/application.yml" 2>/dev/null | tail -1)
    cfg_owner=$($SUDO stat -c '%U:%G' "${INSTALL_DIR}/config/application.yml" 2>/dev/null | tail -1)
  else
    cfg_perm=$(stat -c '%a' "${INSTALL_DIR}/config/application.yml" 2>/dev/null | tail -1)
    cfg_owner=$(stat -c '%U:%G' "${INSTALL_DIR}/config/application.yml" 2>/dev/null | tail -1)
  fi
  printf "  %-34s %s\n" "config/application.yml" "${cfg_perm:-未知} ${cfg_owner:-}"

  # 口令文件必须仅 root 可读（640 或更严）
  if [[ -n "$perm" ]]; then
    if [[ "$perm" -gt 640 && "$perm" != 600 && "$perm" != 400 ]]; then
      echo "  ⚠ /etc/default 权限过宽（当前 $perm，应 ≤ 640），其它本地用户可能读到密钥"
      bad=1
    else
      echo "  ✓ /etc/default 权限 $perm（仅 root 可读，符合要求）"
    fi
  else
    echo "  ⚠ 无法读取 /etc/default 的权限，请用 sudo 运行本命令"
  fi

  printf "  %-34s %s\n" "config/application.yml" \
    "$(stat -c '%a %U:%G' "${INSTALL_DIR}/config/application.yml" 2>/dev/null || echo '不存在')"

  echo
  echo "  --- 密钥是否已注入（只看是否存在，不打印值）---"
  # ⚠️ 匹配式必须是 `=.+` 而不是 `=..+`：
  #    密钥值在文件里带引号（MYSQL_PASSWORD="xxx"），
  #    `..+` 里的两个点要求前两个字符非引号 → 带引号的行匹配不上 → 误报「缺失」。
  for k in MYSQL_PASSWORD JWT_SECRET; do
    printf "    %-16s " "$k"
    if has_secret "$k"; then
      echo "已设置"
    else
      echo "缺失！服务将无法启动"
      bad=1
    fi
  done

  echo
  echo "  --- 配置文件是否残留明文口令 ---"
  local plain
  if [[ -n "$SUDO" ]]; then
    plain=$($SUDO grep -cE 'password:[[:space:]]*[A-Za-z0-9]|secret:[[:space:]]*[A-Za-z0-9]' \
      "${INSTALL_DIR}/config/application.yml" 2>/dev/null || echo 0)
  else
    plain=$(grep -cE 'password:[[:space:]]*[A-Za-z0-9]|secret:[[:space:]]*[A-Za-z0-9]' \
      "${INSTALL_DIR}/config/application.yml" 2>/dev/null || echo 0)
  fi
  # ⚠️ 必须只取最后一行纯数字：
  #    `sudo -S` 会在 stdout 混入 "[sudo] password for xxx:" 提示，
  #    直接当数字用会得到 "00" 这类脏值 → 误判为「发现明文口令」。
  plain=$(printf '%s' "$plain" | grep -oE '[0-9]+' | tail -1)
  plain="${plain:-0}"
  if [[ "$plain" == "0" ]]; then
    echo "    无明文口令（正确）"
  else
    echo "    ⚠ 发现 $plain 处明文口令，请改用 \${MYSQL_PASSWORD} / \${JWT_SECRET} 占位"
    bad=1
  fi

  echo
  [[ "$bad" == "0" ]] && echo "结论：配置与密钥管理正常" || echo "结论：存在需要处理的问题（见上方 ⚠）"
  return "$bad"
}

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
  version) cmd_version ;;
  health)  cmd_health ;;
  rollback) cmd_rollback "${2:-}" ;;
  cleanup)  cmd_cleanup "${2:-5}" ;;
  config-check) cmd_config_check ;;
  *)
    echo "用法：$0 {start|stop|restart|status|logs|enable|disable|version|health|rollback|cleanup|config-check}" >&2
    exit 1
    ;;
esac
