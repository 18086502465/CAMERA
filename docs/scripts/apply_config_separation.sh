#!/bin/bash
# ============================================================
# 配置去密钥改造的服务器落地（一次性脚本，改造完成后可删）
#
# 目标：
#   1. application.yml 里的 DB 口令改为 ${MYSQL_PASSWORD} 占位（不再含明文）
#   2. application.yml 里的 JWT 密钥改为 ${JWT_SECRET} 占位
#   3. /etc/default/camera-detection 注入 MYSQL_PASSWORD 与 JWT_SECRET（640 root）
#   4. application.yml 权限收紧为 640
#
# 幂等：可重复执行；已有 JWT_SECRET 保留（不使现场已签发的令牌失效）。
# ============================================================
set -u
APP=/opt/camera-detection-system
DEFAULT_CFG=/etc/default/camera-detection
APP_YML=${APP}/config/application.yml

run_sql() {
  MYSQL_PWD="$1" mysql -uroot -h127.0.0.1 --protocol=tcp -N -B -D 'wms-new' -e "$2" 2>/dev/null
}

echo "########## 1. 读取现有口令（从 application.yml 取，随后写进 /etc/default）##########"
DB_PWD=$(grep -E '^\s*password:\s*\S' "$APP_YML" 2>/dev/null | head -1 | sed -E 's/^\s*password:\s*//')
OLD_JWT=$(grep -E '^\s*secret:\s*\S' "$APP_YML" 2>/dev/null | head -1 | sed -E 's/^\s*secret:\s*//')
if [[ -z "$DB_PWD" ]]; then
  echo "错误：无法从 $APP_YML 读取数据库口令，请手工确认"
  exit 1
fi
echo "  已读取数据库口令（长度 ${#DB_PWD}）"
echo "  已读取 JWT 密钥（长度 ${#OLD_JWT}）"

echo ""
echo "########## 2. 改写 application.yml：口令与密钥换成占位符 ##########"
cp -p "$APP_YML" "${APP_YML}.bak-$(date '+%Y%m%d_%H%M%S')"

# 用 sed 精确替换 password: 与 secret: 两行（只在 spring.datasource / jwt 段落内）
python3 - "$APP_YML" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
# datasource 段的 password
s = re.sub(r'(\n\s*password:)\s*\S+', r'\1 ${MYSQL_PASSWORD}', s, count=1)
# jwt 段的 secret
s = re.sub(r'(\n\s*secret:)\s*\S+', r'\1 ${JWT_SECRET}', s, count=1)
open(p, 'w', encoding='utf-8').write(s)
print("  已替换 password 与 secret")
PY

echo ""
echo "########## 3. 写入 /etc/default/camera-detection（注入密钥）##########"
if grep -qE '^JWT_SECRET=' "$DEFAULT_CFG" 2>/dev/null; then
  echo "  保留已存在的 JWT_SECRET（不使现场已签发的令牌失效）"
  JWT_SECRET=$(grep -E '^JWT_SECRET=' "$DEFAULT_CFG" | head -1 | sed -E 's/^JWT_SECRET="?([^"]*)"?$/\1/')
else
  # 256 bit 随机十六进制
  JWT_SECRET=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
  echo "  已生成新的 JWT 密钥（256 bit）"
fi

# 幂等：先剥离同名行再追加
sed -i '/^MYSQL_PASSWORD=/d; /^JWT_SECRET=/d' "$DEFAULT_CFG" 2>/dev/null || true
awk 'BEGIN{blank=0} {if ($0=="") {blank++; if (blank>1) next} else {blank=0} print}' \
  "$DEFAULT_CFG" > /tmp/.cds_cfg_new
{
  echo ""
  echo "# ---- 密钥（唯一来源，权限 640 root:root，勿提交到仓库）----"
  echo "# MYSQL_PASSWORD 供 application.yml 的 \${MYSQL_PASSWORD} 引用"
  echo "MYSQL_PASSWORD=\"${DB_PWD}\""
  echo "# JWT_SECRET 供 application.yml 的 \${JWT_SECRET} 引用；轮换后需重启服务"
  echo "JWT_SECRET=\"${JWT_SECRET}\""
} >> /tmp/.cds_cfg_new
mv -f /tmp/.cds_cfg_new "$DEFAULT_CFG"
chmod 640 "$DEFAULT_CFG"
chown root:root "$DEFAULT_CFG"
echo "  已写入并设置权限 640 root:root"

echo ""
echo "########## 4. 收紧 application.yml 权限 ##########"
chmod 640 "$APP_YML"
chown cdsys:cdsys "$APP_YML"
ls -la "$APP_YML"

echo ""
echo "########## 5. 确认占位符替换正确（不打印口令值）##########"
grep -nE 'password:|secret:' "$APP_YML" | sed -E 's/(password:|secret:).*/\1 <已脱敏>/'

echo ""
echo "########## 6. 校验 /etc/default 中两项存在（只打印是否设置）##########"
for k in MYSQL_PASSWORD JWT_SECRET; do
  printf "  %-16s " "$k"
  if grep -qE "^${k}=..+" "$DEFAULT_CFG"; then echo "已设置"; else echo "缺失！"; fi
done

echo ""
echo "完成。下一步：替换 JAR 并重启服务。"
