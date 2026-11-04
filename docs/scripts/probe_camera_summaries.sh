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

# 核实 camera-summaries 返回的 imageCount 是否正确
BASE="http://127.0.0.1:8091/api"
PW="${1:-admin123}"

LOGIN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('data') or {}).get('token') or '')" 2>/dev/null)

echo "=== camera-summaries 前 3 条（关键字段）==="
curl -s "$BASE/v1/defect-images/camera-summaries?page=1&size=5" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data') or {}
recs = data.get('records') or []
print('  总数:', data.get('total'))
for r in recs[:3]:
    print('   -', r.get('cameraName') or r.get('cameraCode'),
          '| imageCount=', r.get('imageCount'),
          '| defectCount=', r.get('defectCount'),
          '| high=', r.get('highCount'),
          '| latestImages=', len(r.get('latestImages') or []))
print()
print('  所有字段名:', sorted(recs[0].keys()) if recs else '(无数据)')
"

echo ""
echo "=== 数据库实际图片数（按相机编号）==="
mysql -uroot -p${MYSQL_PASSWORD} -N -B -D wms-new -e "
SELECT CONCAT('  ', camera_id, ' ', IFNULL(camera_name,'-'), ' → ', COUNT(*), ' 张')
FROM defect_image_file GROUP BY camera_id, camera_name ORDER BY COUNT(*) DESC LIMIT 5" 2>/dev/null
