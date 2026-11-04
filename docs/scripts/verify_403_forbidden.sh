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

# 验证「权限不足 → 403 + ACCESS_DENIED」场景
# 需要一个普通用户（role=0）。用完立即删除，避免污染现场数据。
MY="mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new"
BASE="http://127.0.0.1:8091/api"
PW="${1:-admin123}"
TMP_USER="ZZTEST_ROLE"

echo "=== 1. 创建临时普通用户（role=0）==="
$MY -e "DELETE FROM sys_user WHERE username='${TMP_USER}'" 2>/dev/null
# password_hash 用与 admin 相同的 BCrypt 哈希（即明文 123456）
HASH='$2a$10$yFm9KX1BAMStwgNDD8pQA.Qze3.R.kzzg81qqZR5WlBt5RdsGvRYq'
$MY -e "INSERT INTO sys_user (username, password_hash, real_name, role, factory_id, is_disabled, create_time, update_time) VALUES ('${TMP_USER}', '${HASH}', '临时权限测试用户', 0, 2, 0, NOW(), NOW())" 2>/dev/null
echo "  已创建 ${TMP_USER}（role=0 普通用户）"
$MY -e "SELECT CONCAT('  确认: id=',user_id,' role=',role,' disabled=',is_disabled) FROM sys_user WHERE username='${TMP_USER}'" 2>/dev/null

echo ""
echo "=== 2. 用普通用户登录 ==="
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${TMP_USER}\",\"password\":\"123456\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('token') or '')" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  echo "  登录失败：$LOGIN" | head -c 300
else
  echo "  登录成功，token 长度: ${#TOKEN}"
fi

echo ""
echo "=== 3. 普通用户访问普通端点（应 200）==="
echo -n "  GET /api/cascade/all -> "
curl -s -o /tmp/r1.txt -w "%{http_code}" "$BASE/cascade/all" -H "Authorization: Bearer $TOKEN"
echo "  $(head -c 90 /tmp/r1.txt)"

echo ""
echo "=== 4. 普通用户访问 ADMIN 端点（应 403 + ACCESS_DENIED）==="
echo -n "  GET /api/user -> "
curl -s -o /tmp/r2.txt -w "%{http_code}" "$BASE/user?page=1&size=5" -H "Authorization: Bearer $TOKEN"
echo "  $(head -c 200 /tmp/r2.txt)"

echo -n "  PUT /api/v1/system/settings -> "
curl -s -o /tmp/r3.txt -w "%{http_code}" -X PUT "$BASE/v1/system/settings" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"test":1}'
echo "  $(head -c 200 /tmp/r3.txt)"

echo ""
echo "=== 5. 关键对比：同一端点，admin vs 普通用户 ==="
ADMIN_LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}")
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('token') or '')" 2>/dev/null)
echo -n "  admin      GET /api/user -> "
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/user?page=1&size=5" -H "Authorization: Bearer $ADMIN_TOKEN"
echo -n "  普通用户    GET /api/user -> "
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/user?page=1&size=5" -H "Authorization: Bearer $TOKEN"
echo -n "  无 token    GET /api/user -> "
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/user?page=1&size=5"

echo ""
echo "=== 6. 清理临时用户 ==="
$MY -e "DELETE FROM sys_user WHERE username='${TMP_USER}'" 2>/dev/null
LEFT=$($MY -e "SELECT COUNT(*) FROM sys_user WHERE username='${TMP_USER}'" 2>/dev/null)
echo "  已删除，残留: ${LEFT} 条"
