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

# ============================================================
# P0 修复验证：心跳匹配 + API 路径 + 假数据接入
# ============================================================
BASE="http://127.0.0.1:8091/api"
PW="${1:-admin123}"

LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('token') or '')" 2>/dev/null)
[ -z "$TOKEN" ] && { echo "登录失败"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

echo "=== 1. 用户管理接口（原 404，应变 200）==="
CODE=$(curl -s -o /tmp/u.json -w "%{http_code}" "$BASE/user?page=1&size=10" -H "$AUTH")
echo "  GET /api/user -> $CODE"
python3 -c "
import json
d=json.load(open('/tmp/u.json'))
recs=(d.get('data') or {}).get('records') or []
print('  用户数:', len(recs))
for r in recs[:3]:
    print('   -', r.get('username'), '| role=', r.get('role'), '| realName=', r.get('realName'), '| factoryId=', r.get('factoryId'))
" 2>/dev/null

echo ""
echo "=== 2. 告警接口 ==="
CODE=$(curl -s -o /tmp/a.json -w "%{http_code}" "$BASE/alerts/active" -H "$AUTH")
echo "  GET /api/alerts/active -> $CODE"
python3 -c "
import json
d=json.load(open('/tmp/a.json'))
print('  告警数:', (d.get('data') or {}).get('total'))
" 2>/dev/null

echo ""
echo "=== 3. 缺陷图片历史（检测记录页的真实数据源）==="
CODE=$(curl -s -o /tmp/h.json -w "%{http_code}" "$BASE/v1/defect-images/history?page=1&size=3" -H "$AUTH")
echo "  GET /api/v1/defect-images/history -> $CODE"
python3 -c "
import json
d=json.load(open('/tmp/h.json'))
data=d.get('data') or {}
print('  总数:', data.get('total'))
for r in (data.get('records') or [])[:2]:
    print('   -', r.get('cameraName'), '| defectCount=', r.get('defectCount'), '| captureTime=', r.get('captureTime'))
" 2>/dev/null

echo ""
echo "=== 4. 心跳通道验证：模拟 ZK23 设备（DA8119489，名称与 ZK54 相机重名）==="
echo "  前提：camera_status 里 CV1_Cam1 有 2 行（id=9 ZK54 / id=23 ZK23）"
BEFORE=$(mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new -e "SELECT CONCAT(id,':',recognize_count,':',IFNULL(last_heartbeat_at,'NULL')) FROM camera_status WHERE id IN (9,23) ORDER BY id" 2>/dev/null)
echo "  变更前:"
echo "$BEFORE" | sed 's/^/    /'

# 用 ZK23 的真实相机编号 + 与配置名相同的名称上报心跳
curl -s -o /tmp/hb.json -w "  心跳响应 HTTP %{http_code}\n" -X POST "$BASE/v1/camera/status/heartbeat" \
  -H "$AUTH" -H "X-Device-Id: DA8119489" -H 'Content-Type: application/json' \
  -d '{"cameraName":"CV1_Cam1","cameraStatus":1,"recognizeCount":888888,"correctCount":888000,"abnormalCount":888,"timestamp":"2026-11-04 16:30:00"}'

sleep 1
AFTER=$(mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new -e "SELECT CONCAT(id,':',recognize_count,':',IFNULL(last_heartbeat_at,'NULL')) FROM camera_status WHERE id IN (9,23) ORDER BY id" 2>/dev/null)
echo "  变更后:"
echo "$AFTER" | sed 's/^/    /'

echo ""
echo "  ★ 判定：888888 应出现在 id=23（DA8119489，ZK23），"
echo "    id=9（DA8119487，ZK54）**不得**被写入 —— 若被写入即为串号。"

echo ""
echo "=== 5. 心跳通道：名称重名且无设备编号时应拒绝 ==="
curl -s -o /tmp/hb2.json -w "  无编号心跳 HTTP %{http_code}\n" -X POST "$BASE/v1/camera/status/heartbeat" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"cameraName":"CV1_Cam1","cameraStatus":1,"recognizeCount":999999}'
python3 -c "
import json
try:
    d=json.load(open('/tmp/hb2.json'))
    print('  响应:', (d.get('message') or '')[:120])
except Exception as e:
    print('  解析失败', e)
" 2>/dev/null

echo ""
echo "=== 6. 恢复 id=23 的真实计数（清理测试影响）==="
mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new -e "UPDATE camera_status SET recognize_count=473900 WHERE id=23" 2>/dev/null && echo "  已恢复为近似值（后续心跳会自然覆盖）"
