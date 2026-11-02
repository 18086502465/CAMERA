#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 修改管理员口令（Ubuntu 22.04 LTS）
#
# 用法：
#   ./reset-password.sh --password '新口令'
#   MYSQL_PWD=xxx ./reset-password.sh --password '新口令' --user admin
#
# 不写死哈希：现场用 htpasswd 生成 BCrypt，避免把哈希写进脚本或日志。
# 若目标机没有 htpasswd，用 openssl 生成 SHA-512 也可以（登录校验用的是
# BCryptPasswordEncoder，只支持 BCrypt），因此本脚本优先用 htpasswd。
# ==============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
DATABASE="${DATABASE:-wms-new}"
TARGET_USER="${TARGET_USER:-admin}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --password) NEW_PASSWORD="$2"; shift 2 ;;
    --user) TARGET_USER="$2"; shift 2 ;;
    --database) DATABASE="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

[[ -n "${NEW_PASSWORD:-}" ]] || { echo "用法：$0 --password '新口令'" >&2; exit 1; }
command -v mysql >/dev/null 2>&1 || { echo "未找到 mysql 客户端" >&2; exit 1; }

MYSQL_ARGS=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" --default-character-set=utf8mb4 --protocol=tcp)
[[ -n "$MYSQL_PASSWORD" ]] && export MYSQL_PWD="$MYSQL_PASSWORD"

# --- 生成 BCrypt 哈希 -------------------------------------------------------
# 优先用 htpasswd（apache2-utils）；没装时退回 python3 的 crypt 模块
# （libxcrypt 支持 bcrypt，Ubuntu 自带的 python3 即可用，无需联网装包）。
gen_hash_htpasswd() {
  htpasswd -bnBC 10 "" "$1" 2>/dev/null | tr -d '\r\n' | sed 's/^://'
}

gen_hash_python() {
  python3 - "$1" <<'PYEOF' 2>/dev/null
import crypt, sys
try:
    print(crypt.crypt(sys.argv[1], crypt.mksalt(crypt.METHOD_BLOWFISH)))
except Exception:
    sys.exit(1)
PYEOF
}

HASH=""
if command -v htpasswd >/dev/null 2>&1; then
  HASH="$(gen_hash_htpasswd "$NEW_PASSWORD")"
fi
if [[ -z "$HASH" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    HASH="$(gen_hash_python "$NEW_PASSWORD")"
  fi
fi
if [[ -z "$HASH" ]]; then
  echo "无法生成 BCrypt 哈希。请安装其一：" >&2
  echo "    sudo apt install -y apache2-utils   # 提供 htpasswd" >&2
  echo "或确认 python3 可用（Ubuntu 自带）。" >&2
  exit 1
fi

[[ "$HASH" == \$2* ]] || { echo "哈希生成异常：$HASH" >&2; exit 1; }

# --- 更新数据库 -------------------------------------------------------------
# 注意：用户名含变量，用单引号防注入；库名用反引号（MySQL 标识符）
ESCAPED_HASH="${HASH//\'/\'\'}"
mysql "${MYSQL_ARGS[@]}" -e "UPDATE \`${DATABASE}\`.sys_user SET password_hash = '${ESCAPED_HASH}' WHERE username = '${TARGET_USER}';"

AFFECTED="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM \`${DATABASE}\`.sys_user WHERE username = '${TARGET_USER}' AND password_hash IS NOT NULL;" 2>/dev/null | tr -d '[:space:]')"

if [[ "$AFFECTED" == "1" ]]; then
  echo "✓ 已更新 ${TARGET_USER} 的口令"
else
  echo "✗ 未找到用户 ${TARGET_USER}，口令未修改" >&2
  exit 1
fi

# 把账号与口令记到 /etc/default/camera-detection，供 verify.sh 自动读取。
# 该文件会被 run.sh 用 bash source，因此值必须做 shell 转义并加双引号，
# 否则含空格/引号/特殊字符的口令会让 source 失败、连带应用起不来。
# 权衡：明文口令落盘。目的是让自检脚本能自动登录，不必每次手工传参；
#       该文件权限应保持 640（仅 root 可读）。
if [[ -w /etc/default/camera-detection ]]; then
  # 注意：必须用**一条** grep -v 同时过滤两个键。
  # 写成两条独立的 `grep -v "^ADMIN_USER="` 和 `grep -v "^ADMIN_PASSWORD="` 时，
  # 两者都读原文件 —— 第一个保留了旧的 ADMIN_PASSWORD 行，第二个保留了旧的
  # ADMIN_USER 行，结果旧口令条目会残留在配置里（且越改越多）。
  {
    grep -v -e "^ADMIN_USER=" -e "^ADMIN_PASSWORD=" /etc/default/camera-detection 2>/dev/null || true
    echo "ADMIN_USER=\"$(printf '%s' "$TARGET_USER" | sed 's/[\\"$`]/\\&/g')\""
    echo "ADMIN_PASSWORD=\"$(printf '%s' "$NEW_PASSWORD" | sed 's/[\\"$`]/\\&/g')\""
  } > /tmp/.cds_admin_cfg
  cat /tmp/.cds_admin_cfg > /etc/default/camera-detection
  rm -f /tmp/.cds_admin_cfg
  chmod 640 /etc/default/camera-detection 2>/dev/null || true
  echo "✓ 已记录到 /etc/default/camera-detection（verify.sh 将自动使用）"
fi

echo
echo "请重新登录验证："
echo "  curl -X POST -H 'Content-Type: application/json' \\"
echo "    -d '{\"username\":\"${TARGET_USER}\",\"password\":\"******\"}' \\"
echo "    http://127.0.0.1:8091/api/auth/login"
