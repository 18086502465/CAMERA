#!/bin/bash
# 核实前端 API 路径与后端端点的对应关系（只读，不改数据）
BASE="http://127.0.0.1:8091/api"
PW="${1:-}"

echo "=== 登录取令牌 ==="
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('token') or '')" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  echo "登录失败：$LOGIN" | head -c 300
  exit 1
fi
echo "令牌获取成功"

AUTH="Authorization: Bearer $TOKEN"

echo ""
echo "=== 前端 api/user.js 使用的路径 /users ==="
echo -n "  GET /api/users?page=1&size=10 -> "
curl -s -o /tmp/r1.txt -w "%{http_code}" "$BASE/users?page=1&size=10" -H "$AUTH"
echo "  $(head -c 120 /tmp/r1.txt)"

echo ""
echo "=== 后端真实路径 /api/user ==="
echo -n "  GET /api/user?page=1&size=10 -> "
curl -s -o /tmp/r2.txt -w "%{http_code}" "$BASE/user?page=1&size=10" -H "$AUTH"
echo "  $(head -c 200 /tmp/r2.txt)"

echo ""
echo "=== auth.js 的 /profile/password ==="
echo -n "  PUT /api/profile/password -> "
curl -s -o /tmp/r3.txt -w "%{http_code}" -X PUT "$BASE/profile/password" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"oldPassword":"x","newPassword":"y"}'
echo "  $(head -c 120 /tmp/r3.txt)"

echo ""
echo "=== 对比：其它确认可用的路径（抽样验证路径风格一致）==="
for p in "factories?page=1&size=5" "lines?page=1&size=5" "cameras?page=1&size=5" "workstations?page=1&size=5"; do
  printf "  GET /api/%-28s -> " "$p"
  curl -s -o /dev/null -w "%{http_code}\n" "$BASE/$p" -H "$AUTH"
done

echo ""
echo "=== 告警接口（P0-2 相关）==="
echo -n "  GET /api/alerts/active -> "
curl -s -o /tmp/r4.txt -w "%{http_code}" "$BASE/alerts/active" -H "$AUTH"
echo "  $(head -c 300 /tmp/r4.txt)"
