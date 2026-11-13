#!/bin/bash
# ==============================================================================
# 相机类型隔离 —— 防误删防线实机验证脚本（2026-11-05）
#
# 验证内容：
#   正向：造测试相机数据 → 清理后测试数据被删、真实相机数据分毫不动
#   防线一：单表标记 → 另一表漏更新也不误删（保护集合取两表并集）
#   防线二：全部误标 → 保护集合为空 → 中止本轮（退出码 3）+ 写告警
#   防线三：单轮待删量超阈值 → 中止本轮（退出码 4）
#
# ★ 本脚本会临时修改 camera_type 并插入测试图片记录，**结束时必须还原/清理**
#   （已内置 trap 兜底）。测试数据统一用 ZZTEST 前缀，验证后可安全识别。
#
# 用法：sudo bash verify_camera_type_guards.sh
# ==============================================================================
set -uo pipefail

DB_NAME="${DB_NAME:-wms-new}"
SCRIPT="/main/app/dist/scripts/cleanup-test-cameras.sh"
LOG="/main/app/logs/cleanup_zk54.log"
TEST_CODE="ZZTEST-GUARD-CAM"
FAILED=0

if [[ -z "${MYSQL_PASSWORD:-}" && -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi
MP="${MYSQL_PASSWORD:?请以环境变量提供 MYSQL_PASSWORD}"
SQL() { mysql -uroot -p"$MP" -N -B -D "$DB_NAME" -e "$1" 2>/dev/null; }
SQLX() { mysql -uroot -p"$MP" -D "$DB_NAME" -e "$1" 2>&1; }

ok()   { echo "  ✓ $*"; }
bad()  { echo "  ✗ $*"; FAILED=$((FAILED+1)); }

echo "=== 相机类型隔离防线验证 ==="
echo "清理脚本：$SCRIPT"
echo

# ---------- 基线 ----------
BASE_ALL=$(SQL "SELECT COUNT(*) FROM defect_image_file")
BASE_REAL=$(SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id IN ('DA8119489','DA4733319')")
echo "基线：defect_image_file 全部=${BASE_ALL}，真实相机=${BASE_REAL}"

# ---------- 清理与还原 ----------
cleanup_test_data() {
  SQL "DELETE FROM defect_image_file WHERE camera_id='${TEST_CODE}';"
  SQL "DELETE FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id='${TEST_CODE}';"
}
restore() {
  SQL "UPDATE dim_camera SET camera_type='REAL' WHERE camera_code IN ('DA8119489','DA4733319');"
  SQL "UPDATE camera_status SET camera_type='REAL' WHERE camera_code IN ('DA8119489','DA4733319');"
  cleanup_test_data
  echo "[还原] 真实相机类型已恢复，ZZTEST 数据已清理"
}
trap 'restore' EXIT

# 造 N 条测试相机图片记录（camera_id 不在 dim_camera 中 → 属孤儿，会被清理）
seed_test_data() {
  local n="$1"
  cleanup_test_data
  local i=0
  while [[ $i -lt $n ]]; do
    SQLX "INSERT INTO defect_image_file(batch_id, camera_id, camera_name, file_name, image_path, image_url,
             capture_time, upload_time, defect_count)
           VALUES(999999, '${TEST_CODE}', 'ZZTEST相机', 'zztest_${i}.jpg',
             '/main/app/data/images/defect-images/20261105/${TEST_CODE}/zztest_${i}.jpg',
             '/api/v1/defect-images/999999', NOW(), NOW(), 1);" >/dev/null
    i=$((i+1))
  done
}

echo
echo "--- 正向验证：测试相机数据被清理，真实相机数据不动 ---"
seed_test_data 3
SEEDED=$(SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id='${TEST_CODE}'")
echo "  已插入测试数据 ${SEEDED} 条"
"$SCRIPT"
RC=$?
AFTER_TEST=$(SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id='${TEST_CODE}'")
AFTER_REAL=$(SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id IN ('DA8119489','DA4733319')")
if [[ "$RC" == "0" ]]; then ok "退出码 0（正常执行）"; else bad "退出码 $RC —— 期望 0"; fi
if [[ "$AFTER_TEST" == "0" ]]; then ok "测试相机数据已清理（剩 ${AFTER_TEST} 条）"; else bad "测试相机数据未清理（剩 ${AFTER_TEST} 条）"; fi
if [[ "$AFTER_REAL" -ge "$BASE_REAL" ]]; then
  ok "真实相机数据未受影响（${BASE_REAL} → ${AFTER_REAL}，只增不减）"
else
  bad "真实相机数据减少：${BASE_REAL} → ${AFTER_REAL}"
fi
echo

echo "--- 防线一：仅 camera_status 标 REAL（dim_camera 漏更新）---"
SQL "UPDATE dim_camera SET camera_type='TEST' WHERE camera_code IN ('DA8119489','DA4733319');"
SQL "UPDATE camera_status SET camera_type='REAL' WHERE camera_code IN ('DA8119489','DA4733319');"
P=$(SQL "SELECT GROUP_CONCAT(camera_code) FROM (SELECT camera_code FROM dim_camera WHERE camera_type='REAL' AND IFNULL(is_deleted,0)=0 UNION SELECT camera_code FROM camera_status WHERE camera_type='REAL' AND IFNULL(is_active,0)=1) t")
if [[ "$P" == *"DA8119489"* && "$P" == *"DA4733319"* ]]; then
  ok "保护集合 = ${P}（单表漏更新不误删）"
else
  bad "保护集合 = ${P} —— 防线一失效"
fi
restore
echo

echo "--- 防线二：两台真实相机全标 TEST（保护集合为空 → 中止）---"
SQL "UPDATE dim_camera SET camera_type='TEST' WHERE camera_code IN ('DA8119489','DA4733319');"
SQL "UPDATE camera_status SET camera_type='TEST' WHERE camera_code IN ('DA8119489','DA4733319');"
BEFORE=$(SQL "SELECT COUNT(*) FROM defect_image_file")
"$SCRIPT"
RC=$?
AFTER=$(SQL "SELECT COUNT(*) FROM defect_image_file")
if [[ "$RC" == "3" ]]; then ok "退出码 3（保护集合为空而中止）"; else bad "退出码 $RC —— 期望 3"; fi
if [[ "$AFTER" == "$BEFORE" ]]; then ok "数据未被删除（${BEFORE}）"; else bad "数据发生变化：${BEFORE} → ${AFTER}"; fi
ALERT=$(SQL "SELECT COUNT(*) FROM fact_anomaly_alert WHERE alert_type='CLEANUP_FAILED' AND create_time > DATE_SUB(NOW(), INTERVAL 10 MINUTE)")
if [[ "${ALERT:-0}" -gt 0 ]]; then ok "已写入 fact_anomaly_alert 告警（${ALERT} 条）"; else bad "未找到告警记录"; fi
restore
echo

echo "--- 防线三：待删量超阈值 → 中止 ---"
seed_test_data 3
BEFORE=$(SQL "SELECT COUNT(*) FROM defect_image_file")
MAX_SINGLE_DELETE=2 "$SCRIPT"
RC=$?
AFTER=$(SQL "SELECT COUNT(*) FROM defect_image_file")
if [[ "$RC" == "4" ]]; then ok "退出码 4（超阈值而中止）"; else bad "退出码 $RC —— 期望 4"; fi
if [[ "$AFTER" == "$BEFORE" ]]; then ok "数据未被删除（${BEFORE}）"; else bad "数据发生变化：${BEFORE} → ${AFTER}"; fi
cleanup_test_data
echo

echo "--- 收尾核对 ---"
echo "真实相机类型（应均为 REAL）："
SQL "SELECT camera_code, camera_type FROM dim_camera WHERE camera_code IN ('DA8119489','DA4733319');" | sed 's/^/  /'
echo "真实相机图片数（应与基线一致或更多）："
SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id IN ('DA8119489','DA4733319');" | sed 's/^/  /'
echo "ZZTEST 残留（应为 0）："
SQL "SELECT COUNT(*) FROM defect_image_file WHERE camera_id='${TEST_CODE}';" | sed 's/^/  /'
echo "最近日志："
tail -10 "$LOG" 2>/dev/null | sed 's/^/  /'

echo
if [[ "$FAILED" -gt 0 ]]; then
  echo "✗ $FAILED 项验证失败"
  exit 1
fi
echo "✓ 全部验证通过"
