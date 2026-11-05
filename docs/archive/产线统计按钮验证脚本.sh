#!/usr/bin/env bash
# 产线统计页按钮逐项验证（接口级）
BASE=http://127.0.0.1:8091
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data",{}).get("token") or "")')
AUTH="Authorization: Bearer $TOKEN"
PASS=0; FAIL=0
chk(){ # chk <名称> <期望HTTP> <命令...>
  local name="$1"; shift
  local expect="$1"; shift
  local code
  code=$(curl -s -o /tmp/_body.json -w "%{http_code}" "$@" 2>/dev/null)
  if [ "$code" = "$expect" ]; then
    PASS=$((PASS+1)); printf "  [PASS] %-38s HTTP %s\n" "$name" "$code"
  else
    FAIL=$((FAIL+1)); printf "  [FAIL] %-38s HTTP %s (期望 %s)\n" "$name" "$code" "$expect"
    head -c 300 /tmp/_body.json; echo
  fi
}
chkcode(){ # chkcode <名称> <jq路径> <期望值> <命令...>
  local name="$1"; shift
  local path="$1"; shift
  local expect="$1"; shift
  local got
  got=$(curl -s "$@" 2>/dev/null | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    for k in '$path'.split('.'):
        if k=='': continue
        d = d[int(k)] if isinstance(d,list) else d.get(k)
    print(d)
except Exception as e:
    print('ERR')
")
  if [ "$got" = "$expect" ]; then
    PASS=$((PASS+1)); printf "  [PASS] %-38s %s = %s\n" "$name" "$path" "$got"
  else
    FAIL=$((FAIL+1)); printf "  [FAIL] %-38s %s = %s (期望 %s)\n" "$name" "$path" "$got" "$expect"
  fi
}
echo "TOKEN_LEN=${#TOKEN}"
echo "================================================================"
echo "[A] 顶部操作区"
echo "================================================================"
chk "看板模板下拉(列表)"       200 "$BASE/api/v1/analytics/dashboards?factoryId=2" -H "$AUTH"
DASH=$(curl -s "$BASE/api/v1/analytics/dashboards?factoryId=2" -H "$AUTH" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d[0]["dashboardId"] if d else "")')
echo "  -> 默认看板ID = $DASH"
chk "默认看板(GET default)"     200 "$BASE/api/v1/analytics/dashboards/default?factoryId=2" -H "$AUTH"
chk "看板详情(GET by id)"       200 "$BASE/api/v1/analytics/dashboards/$DASH" -H "$AUTH"
chk "看板组件(GET widgets)"     200 "$BASE/api/v1/analytics/dashboards/$DASH/widgets" -H "$AUTH"
chk "刷新报表(line-summary)"    200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2" -H "$AUTH"
chk "重置筛选(默认参数)"        200 "$BASE/api/v1/analytics/line-summary?factoryId=2" -H "$AUTH"
chk "统计元数据(metadata)"      200 "$BASE/api/v1/analytics/metadata" -H "$AUTH"

echo "================================================================"
echo "[B] 筛选下拉联动（每个下拉各测一次）"
echo "================================================================"
chk "产品筛选"                 200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&productId=1" -H "$AUTH"
chk "班次筛选"                 200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&shiftId=1" -H "$AUTH"
chk "相机筛选"                 200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&cameraId=10" -H "$AUTH"
chk "缺陷类型筛选"             200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&defectTypeId=1" -H "$AUTH"
chk "严重度筛选"               200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&severity=HIGH" -H "$AUTH"
chk "产线筛选(lineId)"         200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&lineId=3" -H "$AUTH"
chk "工位筛选(workstationId)"  200 "$BASE/api/v1/analytics/line-summary?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2&workstationId=8" -H "$AUTH"
chk "POST /query"              200 -X POST "$BASE/api/v1/analytics/query" -H "$AUTH" -H 'Content-Type: application/json' -d '{"startTime":"2026-11-02 00:00:00","endTime":"2026-11-02 23:59:59","factoryId":2}'

echo "================================================================"
echo "[C] 看板模板 CRUD（新建/编辑/复制/另存/删除）"
echo "================================================================"
NEW=$(curl -s -X POST "$BASE/api/v1/analytics/dashboards" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"dashboardName":"按钮验证临时看板","factoryId":2,"scopeType":"FACTORY","isDefault":0,"layoutJson":"{\"cols\":12,\"rowHeight\":120}"}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data",{}).get("dashboardId") or "")')
if [ -n "$NEW" ]; then PASS=$((PASS+1)); echo "  [PASS] 新建模板                                  新ID=$NEW";
else FAIL=$((FAIL+1)); echo "  [FAIL] 新建模板"; fi
chk "编辑模板(PUT)"            200 -X PUT "$BASE/api/v1/analytics/dashboards/$NEW" -H "$AUTH" -H 'Content-Type: application/json' -d '{"dashboardName":"按钮验证临时看板-已改","factoryId":2,"scopeType":"FACTORY","isDefault":0,"layoutJson":"{\"cols\":12,\"rowHeight\":120}"}'
COPY=$(curl -s -X POST "$BASE/api/v1/analytics/dashboards/$NEW/copy" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"dashboardName":"按钮验证副本","scopeType":"FACTORY","isDefault":0}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data",{}).get("dashboardId") or "")')
if [ -n "$COPY" ]; then PASS=$((PASS+1)); echo "  [PASS] 复制模板                                  新ID=$COPY";
else FAIL=$((FAIL+1)); echo "  [FAIL] 复制模板"; fi
chk "另存为(POST create)"      200 -X POST "$BASE/api/v1/analytics/dashboards" -H "$AUTH" -H 'Content-Type: application/json' -d '{"dashboardName":"按钮验证另存","factoryId":2,"scopeType":"FACTORY","isDefault":0}'
chk "删除模板(DELETE copy)"     200 -X DELETE "$BASE/api/v1/analytics/dashboards/$COPY" -H "$AUTH"
chk "删除模板(DELETE new)"      200 -X DELETE "$BASE/api/v1/analytics/dashboards/$NEW" -H "$AUTH"

echo "================================================================"
echo "[D] 图表组件 CRUD + 排序 + 导出"
echo "================================================================"
W=$(curl -s -X POST "$BASE/api/v1/analytics/widgets" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"dashboardId\":$DASH,\"widgetName\":\"按钮验证图表\",\"widgetType\":\"BAR\",\"metricJson\":\"{\\\"metric\\\":\\\"defectPareto\\\"}\",\"dimensionJson\":\"[\\\"defectType\\\"]\",\"layoutJson\":\"{\\\"w\\\":6,\\\"h\\\":3}\",\"sortOrder\":99}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("data",{}).get("widgetId") or "")')
if [ -n "$W" ]; then PASS=$((PASS+1)); echo "  [PASS] 新增图表                                  widgetId=$W";
else FAIL=$((FAIL+1)); echo "  [FAIL] 新增图表"; fi
chk "编辑图表(PUT)"            200 -X PUT "$BASE/api/v1/analytics/widgets/$W" -H "$AUTH" -H 'Content-Type: application/json' -d "{\"widgetName\":\"按钮验证图表-改\",\"widgetType\":\"BAR\",\"metricJson\":\"{\\\"metric\\\":\\\"defectPareto\\\"}\",\"dimensionJson\":\"[\\\"defectType\\\"]\",\"layoutJson\":\"{\\\"w\\\":8,\\\"h\\\":3}\",\"sortOrder\":99,\"isEnabled\":1}"
chk "拖拽排序(reorder)"        200 -X POST "$BASE/api/v1/analytics/widgets/reorder" -H "$AUTH" -H 'Content-Type: application/json' -d "{\"widgets\":[{\"widgetId\":$W,\"sortOrder\":98,\"layoutJson\":\"{\\\"w\\\":8,\\\"h\\\":3}\"}]}"
chk "导出Excel"                200 "$BASE/api/v1/analytics/widgets/$W/export?startTime=2026-11-02%2000:00:00&endTime=2026-11-02%2023:59:59&factoryId=2" -H "$AUTH" -o /tmp/_export.xlsx
XLSX_SIZE=$(stat -c %s /tmp/_export.xlsx 2>/dev/null || echo 0)
if [ "$XLSX_SIZE" -gt 1000 ]; then PASS=$((PASS+1)); echo "  [PASS] 导出文件有效                             ${XLSX_SIZE} 字节";
else FAIL=$((FAIL+1)); echo "  [FAIL] 导出文件过小 ${XLSX_SIZE} 字节"; fi
chk "删除图表(DELETE)"         200 -X DELETE "$BASE/api/v1/analytics/widgets/$W" -H "$AUTH"

echo "================================================================"
echo "[E] 日聚合 / 任务 / 定时配置"
echo "================================================================"
chk "任务列表(recent)"         200 "$BASE/api/v1/analytics/aggregation/jobs/recent" -H "$AUTH"
chk "任务列表(分页)"            200 "$BASE/api/v1/analytics/aggregation/jobs?page=1&pageSize=10" -H "$AUTH"
chk "定时配置(GET)"            200 "$BASE/api/v1/analytics/aggregation/schedule" -H "$AUTH"
chk "执行日聚合(POST)"         200 -X POST "$BASE/api/v1/analytics/aggregation/daily/run" -H "$AUTH" -H 'Content-Type: application/json' -d '{"startDate":"2026-11-02","endDate":"2026-11-02","factoryId":2}'
sleep 3
LAST=$(curl -s "$BASE/api/v1/analytics/aggregation/jobs/recent" -H "$AUTH" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d[0]["jobId"] if d else "")')
LASTSTATUS=$(curl -s "$BASE/api/v1/analytics/aggregation/jobs/$LAST" -H "$AUTH" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d.get("status"),"|",(d.get("message") or "")[:40])')
echo "  -> 最新聚合任务 #$LAST : $LASTSTATUS"
if echo "$LASTSTATUS" | grep -q SUCCESS; then PASS=$((PASS+1)); echo "  [PASS] 日聚合任务成功（原 FAILED 报错已修复）";
else FAIL=$((FAIL+1)); echo "  [FAIL] 日聚合任务未成功: $LASTSTATUS"; fi
chk "任务详情(GET by id)"      200 "$BASE/api/v1/analytics/aggregation/jobs/$LAST" -H "$AUTH"
chk "任务重试(POST retry)"     200 -X POST "$BASE/api/v1/analytics/aggregation/jobs/$LAST/retry" -H "$AUTH"
chk "保存定时配置(PUT)"        200 -X PUT "$BASE/api/v1/analytics/aggregation/schedule" -H "$AUTH" -H 'Content-Type: application/json' -d '{"enabled":true,"scheduleTime":"02:00","lookbackDays":7}'

echo "================================================================"
echo "[F] 汇总"
echo "================================================================"
echo "  通过 $PASS 项，失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && echo "  全部按钮接口验证通过" || echo "  存在失败项"
exit $FAIL
