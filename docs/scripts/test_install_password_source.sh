#!/bin/bash
# ============================================================
# P0-1 回归测试：install.sh 的数据库口令来源
# ============================================================
# 场景：模拟一台"新机"（/etc/default 里没有 MYSQL_PASSWORD）
#       与一台"重装机"（/etc/default 里已有真口令）
#
# 验证：
#   1. 新机：绝不把占位符 ${MYSQL_PASSWORD} 当成口令
#   2. 重装：现场已有口令被保留
#   3. 占位符形态（${XXX}）一律视为未配置
#   4. 第 8c 节的 sed 删除 JWT_SECRET 后必须被写回
#
# 用法：bash test_install_password_source.sh
# ============================================================

PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [通过] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [失败] $1"; }

echo "=========================================="
echo "P0-1  install.sh 口令来源回归测试"
echo "=========================================="

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ---- 复刻 install.sh 的 read_cfg_value 与占位符判定逻辑 ----
read_cfg_value() {
  [[ -f "$1" ]] || return 0
  sed -n "s/^$2=[\"']\{0,1\}\([^\"']*\)[\"']\{0,1\}\$/\1/p" \
    "$1" 2>/dev/null | head -1 | tr -d "\r"
}
is_placeholder() { [[ "$1" =~ ^\$\{.*\}$ ]]; }

echo ""
echo "--- 用例 1：新机（无 /etc/default）提取口令 ---"
CFG="$WORK/default_new"
cat > "$CFG" <<'EOF'
JAVA_OPTS="-Xms512m -Xmx1024m"
INSTALL_DIR=/opt/camera-detection-system
CONFIG_FILE=/opt/camera-detection-system/config/application.yml
EOF
V=$(read_cfg_value "$CFG" MYSQL_PASSWORD)
if [[ -z "$V" ]]; then
  ok "新机提取结果为空（正确：没有就不该有值）"
else
  bad "新机提取出意外值: '$V'"
fi

echo ""
echo "--- 用例 2：重装机保留现场真口令 ---"
CFG="$WORK/default_existing"
cat > "$CFG" <<'EOF'
MYSQL_PASSWORD="Re@lPwd2026"
ADMIN_USER="admin"
ADMIN_PASSWORD="admin123"
EOF
V=$(read_cfg_value "$CFG" MYSQL_PASSWORD)
if [[ "$V" == "Re@lPwd2026" ]]; then
  ok "重装保留了现场口令: $V"
else
  bad "重装未保留口令，得到: '$V'"
fi
V=$(read_cfg_value "$CFG" ADMIN_PASSWORD)
if [[ "$V" == "admin123" ]]; then
  ok "重装保留了管理员口令"
else
  bad "重装未保留管理员口令，得到: '$V'"
fi

echo ""
echo "--- 用例 3：占位符形态必须被识别为「未配置」 ---"
CFG="$WORK/default_placeholder"
cat > "$CFG" <<'EOF'
MYSQL_PASSWORD="${MYSQL_PASSWORD}"
JWT_SECRET="${JWT_SECRET}"
EOF
V=$(read_cfg_value "$CFG" MYSQL_PASSWORD)
if [[ "$V" == '${MYSQL_PASSWORD}' ]]; then
  ok "sed 确实提取到了占位符原文（这正是原缺陷的根因）"
else
  bad "提取行为与预期不符: '$V'"
fi
if is_placeholder "$V"; then
  ok "占位符判定生效 → 判定为未配置（不会当成口令写入）"
else
  bad "占位符判定未生效"
fi

echo ""
echo "--- 用例 4：第 8c 节 sed 删除后 JWT_SECRET 必须写回 ---"
CFG="$WORK/default_8c"
cat > "$CFG" <<'EOF'
JAVA_OPTS="-Xms512m -Xmx1024m"
MYSQL_PASSWORD="Re@lPwd2026"
JWT_SECRET="abc123def456"
ADMIN_USER="admin"
ADMIN_PASSWORD="admin123"
EOF
# 复刻 8c 的 sed（删 MYSQL_PASSWORD / JWT_SECRET / 备份三键）
sed -i '/^MYSQL_PASSWORD=/d; /^BACKUP_DIR=/d; /^KEEP_FULL_DAYS=/d; /^MAX_DISK_USE=/d; /^JWT_SECRET=/d' "$CFG"
if grep -qE '^JWT_SECRET=' "$CFG"; then
  bad "sed 后 JWT_SECRET 仍在（用例前提不成立，需重新设计测试）"
else
  ok "sed 后 JWT_SECRET 已被删除（复刻成功）"
fi
if grep -qE '^ADMIN_PASSWORD=' "$CFG"; then
  ok "sed 未误删 ADMIN_PASSWORD"
else
  bad "sed 误删了 ADMIN_PASSWORD"
fi
# 复刻修复后的 heredoc 写回
JWT_SECRET="abc123def456"
MYSQL_PASSWORD="Re@lPwd2026"
cat >> "$CFG" <<EOF

# ---- 备份相关（供 camera-detection-backup.service 使用）----
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
BACKUP_DIR="/data/camera-detection/backup/db"
KEEP_FULL_DAYS=14
MAX_DISK_USE=85

# ---- JWT 签名密钥（application.yml 用 \${JWT_SECRET} 引用）----
JWT_SECRET="${JWT_SECRET}"
EOF
if grep -qE '^JWT_SECRET=.+' "$CFG"; then
  ok "JWT_SECRET 已写回（修复生效）"
else
  bad "JWT_SECRET 未写回 → 重装后服务会启动失败"
fi
if grep -qE '^MYSQL_PASSWORD=.+' "$CFG"; then
  ok "MYSQL_PASSWORD 已写回"
else
  bad "MYSQL_PASSWORD 未写回"
fi

echo ""
echo "--- 用例 5：修复后 install.sh 不再从 application.yml 提取口令 ---"
INSTALL_SH="installer/linux/scripts/install.sh"
if grep -qE "sed -n 's/\.\*password:.*application\.yml" "$INSTALL_SH" 2>/dev/null; then
  bad "install.sh 仍在从 application.yml 提取口令（原缺陷未消除）"
else
  ok "install.sh 已不再从 application.yml 提取口令"
fi
if grep -q 'EXISTING_MYSQL_PASSWORD' "$INSTALL_SH"; then
  ok "install.sh 已改为从 /etc/default 捕获现场口令"
else
  bad "install.sh 缺少现场口令捕获逻辑"
fi

echo ""
echo "=========================================="
echo "结果：通过 $PASS 项，失败 $FAIL 项"
echo "=========================================="
[[ $FAIL -eq 0 ]]
