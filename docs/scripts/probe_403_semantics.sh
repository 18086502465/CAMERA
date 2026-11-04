#!/bin/bash
# 核实后端 403 的两种来源及响应体差异
BASE="http://127.0.0.1:8091/api"
PW="${1:-admin123}"

echo "=== A. 无 token 访问需认证端点 ==="
echo -n "  GET /api/cascade/all (无 token) -> "
curl -s -o /tmp/a1.txt -w "%{http_code}" "$BASE/cascade/all"
echo "  body: $(head -c 200 /tmp/a1.txt)"

echo ""
echo "=== B. 过期/非法 token 访问 ==="
echo -n "  GET /api/cascade/all (伪造 token) -> "
curl -s -o /tmp/a2.txt -w "%{http_code}" "$BASE/cascade/all" -H "Authorization: Bearer invalid.token.here"
echo "  body: $(head -c 200 /tmp/a2.txt)"

echo ""
echo "=== C. 合法 token 但权限不足（用普通用户访问 ADMIN 端点）==="
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('token') or '')" 2>/dev/null)
echo "  admin token 长度: ${#TOKEN}"

echo -n "  GET /api/cascade/all (admin) -> "
curl -s -o /tmp/a3.txt -w "%{http_code}" "$BASE/cascade/all" -H "Authorization: Bearer $TOKEN"
echo "  body: $(head -c 120 /tmp/a3.txt)"

echo ""
echo "=== D. 是否存在普通用户账号（用于验证权限不足场景）==="
curl -s -o /tmp/a4.txt "$BASE/user?page=1&size=20" -H "Authorization: Bearer $TOKEN"
python3 -c "
import json
d=json.load(open('/tmp/a4.txt'))
recs=(d.get('data') or {}).get('records') or []
print('  用户总数:', len(recs))
for r in recs:
    print('   -', r.get('username'), '| role=', r.get('role'), '| disabled=', r.get('isDisabled'))
" 2>/dev/null

echo ""
echo "=== E. 设备端点（走另一套 token 体系）==="
echo -n "  POST /api/v1/camera/status/heartbeat (无 token) -> "
curl -s -o /tmp/a5.txt -w "%{http_code}" -X POST "$BASE/v1/camera/status/heartbeat" \
  -H 'Content-Type: application/json' -d '{"cameraName":"x"}'
echo "  body: $(head -c 150 /tmp/a5.txt)"

echo ""
echo "=== F. 响应头是否有 WWW-Authenticate（区分依据）==="
echo "  无 token 场景的响应头:"
curl -s -D - -o /dev/null "$BASE/cascade/all" | grep -iE "HTTP/|WWW-Authenticate" | head -3
echo "  伪造 token 场景的响应头:"
curl -s -D - -o /dev/null "$BASE/cascade/all" -H "Authorization: Bearer invalid.token" | grep -iE "HTTP/|WWW-Authenticate" | head -3
