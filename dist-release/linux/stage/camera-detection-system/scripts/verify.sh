#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 安装自检（Ubuntu 22.04 LTS）
#
# 用法：sudo ./verify.sh [base_url]
#   默认 base_url = http://127.0.0.1:${PORT}（PORT 取自 /etc/default/camera-detection）
#
# 全部通过退出码 0；有失败项退出码 1。可直接接监控/流水线。
# ==============================================================================
set -uo pipefail

SERVICE_NAME="camera-detection"
INSTALL_DIR="${INSTALL_DIR:-/opt/camera-detection-system}"
WORK_DIR="${WORK_DIR:-/var/lib/camera-detection-system}"

if [[ -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi

PORT="${PORT:-8091}"
BASE_URL="${1:-http://127.0.0.1:${PORT}}"

# 管理员口令：默认取建库种子口令 123456。
# 现场改过口令后，用环境变量传实际口令，例如：
#   VERIFY_PASSWORD='实际口令' sudo -E ./verify.sh
# 也可以放在 /etc/default/camera-detection 的 ADMIN_PASSWORD 里（该文件默认 640，root 可读）。
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${VERIFY_PASSWORD:-${ADMIN_PASSWORD:-123456}}"

PASS=0
FAIL=0
FAILED_ITEMS=()

# --- 输出helpers -------------------------------------------------------------
if [[ -t 1 ]]; then
  C_OK=$'\033[1;32m'; C_BAD=$'\033[1;31m'; C_DIM=$'\033[2m'; C_HEAD=$'\033[1;36m'; C_OFF=$'\033[0m'
else
  C_OK=""; C_BAD=""; C_DIM=""; C_HEAD=""; C_OFF=""
fi

check() {
  local name="$1" passed="$2" detail="${3:-}"
  if [[ "$passed" == "true" ]]; then
    PASS=$((PASS+1)); printf "  ${C_OK}通过${C_OFF}  %-24s ${C_DIM}%s${C_OFF}\n" "$name" "$detail"
  else
    FAIL=$((FAIL+1)); FAILED_ITEMS+=("$name")
    printf "  ${C_BAD}失败${C_OFF}  %-24s %s\n" "$name" "$detail"
  fi
}

http_code() {
  curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$@" 2>/dev/null || echo "000"
}

echo
echo "${C_HEAD}=========================================${C_OFF}"
echo "${C_HEAD}产线缺陷在线AI检测系统 - 安装自检报告${C_OFF}"
echo "${C_HEAD}=========================================${C_OFF}"
echo "  目标地址：${BASE_URL}"
echo

# --- 1. 运行环境 -------------------------------------------------------------
echo "${C_HEAD}[1] 运行环境${C_OFF}"

JAVA_BIN=""
if [[ -x "${INSTALL_DIR}/jre/bin/java" ]]; then
  JAVA_BIN="${INSTALL_DIR}/jre/bin/java"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
if [[ -n "$JAVA_BIN" ]]; then
  JV="$("$JAVA_BIN" -version 2>&1 | head -1 || true)"
  check "Java 运行时" true "${JV:-已安装}"
else
  check "Java 运行时" false "未找到 java"
fi

MYSQL_BIN="$(command -v mysql 2>/dev/null || true)"
if [[ -n "$MYSQL_BIN" ]]; then
  MV="$("$MYSQL_BIN" --version 2>/dev/null | head -1 || true)"
  check "MySQL 客户端" true "${MV:-已安装}"
else
  check "MySQL 客户端" false "未找到 mysql 命令"
fi

# --- 2. 文件完整性 -----------------------------------------------------------
echo
echo "${C_HEAD}[2] 程序文件${C_OFF}"

[[ -f "${INSTALL_DIR}/app/time-bucket-demo.jar" ]] \
  && check "后端 JAR" true "${INSTALL_DIR}/app/time-bucket-demo.jar" \
  || check "后端 JAR" false "缺失"

[[ -f "${INSTALL_DIR}/config/application.yml" ]] \
  && check "外部配置" true "${INSTALL_DIR}/config/application.yml" \
  || check "外部配置" false "缺失"

[[ -f "/etc/default/camera-detection" ]] \
  && check "参数配置" true "/etc/default/camera-detection" \
  || check "参数配置" false "缺失"

sql_count=$(find "${INSTALL_DIR}/sql" -name "*.sql" 2>/dev/null | wc -l | tr -d ' ')
[[ "$sql_count" -ge 4 ]] \
  && check "建库脚本" true "${sql_count} 个" \
  || check "建库脚本" false "只找到 ${sql_count} 个（应为 4）"

# --- 3. 工作目录 -------------------------------------------------------------
echo
echo "${C_HEAD}[3] 工作目录${C_OFF}"

for d in "logs" "data/uploads/images"; do
  if [[ -d "${WORK_DIR}/${d}" ]]; then
    if [[ -w "${WORK_DIR}/${d}" ]]; then
      check "目录 ${d}" true "存在且可写"
    else
      check "目录 ${d}" false "存在但不可写（属主应为 cdsys）"
    fi
  else
    check "目录 ${d}" false "不存在"
  fi
done

# 缺陷图片目录：**实际落盘位置**，取自 config/application.yml 的 defect-image.upload.dir。
# 2026-10-31 修正：原来检查的是 ${WORK_DIR}/data/uploads/defect-images，
# 那是迁移前的遗留路径（应用根本不用），检查它等于检查一个永远为空的目录。
# 真正要检查的是配置里指的那个 —— 目录不存在会导致「上传返回成功但图片不落盘」。
DEFECT_IMAGE_DIR="$(sed -n 's/^[[:space:]]*dir:[[:space:]]*\(.*\)$/\1/p' \
  "${INSTALL_DIR}/config/application.yml" 2>/dev/null | grep -E '^/' | head -1)"
DEFECT_IMAGE_DIR="${DEFECT_IMAGE_DIR:-/data/camera-detection/images/defect-images/}"
DEFECT_IMAGE_DIR="${DEFECT_IMAGE_DIR%/}"
if [[ -d "$DEFECT_IMAGE_DIR" ]]; then
  if [[ -w "$DEFECT_IMAGE_DIR" ]]; then
    check "缺陷图片目录" true "${DEFECT_IMAGE_DIR}"
  else
    check "缺陷图片目录" false "${DEFECT_IMAGE_DIR} 存在但不可写（属主应为 cdsys）"
  fi
else
  check "缺陷图片目录" false "${DEFECT_IMAGE_DIR} 不存在 —— 图片上传会「成功但不落盘」"
fi

# --- 4. 服务状态 -------------------------------------------------------------
echo
echo "${C_HEAD}[4] 服务状态${C_OFF}"

# 注意：不要用 `systemctl list-unit-files | grep -q ...` ——
# grep -q 命中后立即退出，systemctl 收到 SIGPIPE 而返回非 0，
# 配合 set -o pipefail 会把整条管道判为失败，导致明明装了服务却报"未安装"。
UNIT_COUNT="$(systemctl list-unit-files 2>/dev/null | grep -c "^${SERVICE_NAME}.service" || true)"
if [[ "${UNIT_COUNT:-0}" -gt 0 ]]; then
  check "systemd 服务" true "${SERVICE_NAME}.service"

  ACTIVE="$(systemctl is-active "$SERVICE_NAME" 2>/dev/null || echo inactive)"
  check "服务运行中" "$([[ "$ACTIVE" == "active" ]] && echo true || echo false)" "$ACTIVE"

  ENABLED="$(systemctl is-enabled "$SERVICE_NAME" 2>/dev/null || echo disabled)"
  check "开机自启" "$([[ "$ENABLED" == "enabled" ]] && echo true || echo false)" "$ENABLED"
else
  check "systemd 服务" false "未安装 ${SERVICE_NAME}.service"
fi

# --- 5. 端口监听 -------------------------------------------------------------
echo
echo "${C_HEAD}[5] 端口监听${C_OFF}"

if command -v ss >/dev/null 2>&1; then
  LISTEN="$(ss -tlnp 2>/dev/null | grep -c ":${PORT} ")"
elif command -v netstat >/dev/null 2>&1; then
  LISTEN="$(netstat -tlnp 2>/dev/null | grep -c ":${PORT} ")"
else
  LISTEN=0
fi
check "TCP ${PORT} 监听" "$([[ "$LISTEN" -gt 0 ]] && echo true || echo false)" "匹配 ${LISTEN} 条"

# --- 6. 功能自检 -------------------------------------------------------------
echo
echo "${C_HEAD}[6] 功能自检${C_OFF}"

code=$(http_code "${BASE_URL}/")
check "页面访问" "$([[ "$code" == "200" ]] && echo true || echo false)" "HTTP ${code}"

code=$(http_code "${BASE_URL}/system/config")
check "SPA 深链刷新" "$([[ "$code" == "200" ]] && echo true || echo false)" "HTTP ${code}"

LOGIN_RESP="$(curl -s --max-time 10 -X POST -H "Content-Type: application/json" \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}" \
  "${BASE_URL}/api/auth/login" 2>/dev/null)"
TOKEN=""
if [[ "$LOGIN_RESP" == *'"code":200'* ]]; then
  check "登录接口" true "code:200（账号 ${ADMIN_USER}）"
  # 提取 token —— 后续的鉴权检查需要它
  TOKEN="$(printf '%s' "$LOGIN_RESP" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  [[ -z "$TOKEN" ]] && TOKEN="$(printf '%s' "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4)"
  [[ -n "$TOKEN" ]] && check "提取访问令牌" true "长度 ${#TOKEN}" \
                    || check "提取访问令牌" false "登录响应里没有 token 字段"
else
  check "登录接口" false "未返回 code:200 —— 若现场已改口令，请用 VERIFY_PASSWORD='实际口令' 重跑本脚本"
fi

# 鉴权有效性检查（2026-10-28 新增）
# 背景：JwtRequestFilter 曾解析 token、查用户、validateToken 全做，却**从不写入
# SecurityContext**，加上 SecurityConfig 的宽白名单，等于完全无鉴权。
# 这里用「不带 token 应 403 / 带 token 应 200」把鉴权是否真的生效验出来。
ANON_CODE=$(http_code "${BASE_URL}/api/stats/dashboard-overview")
if [[ -n "$TOKEN" ]]; then
  AUTH_CODE=$(http_code -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/stats/dashboard-overview")
  check "看板统计接口（带 token）" "$([[ "$AUTH_CODE" == "200" ]] && echo true || echo false)" "HTTP ${AUTH_CODE}"
  check "鉴权生效（匿名应被拒）" "$([[ "$ANON_CODE" == "403" || "$ANON_CODE" == "401" ]] && echo true || echo false)" \
        "匿名 HTTP ${ANON_CODE}（期望 401/403）"

  # 服务器监控接口（2026-11-02 新增）
  # 这个接口依赖 OSHI + JNA，是本项目唯一依赖 native 库的采集能力。
  # 若 java.io.tmpdir 不可写（JNA 无法解压 native 库），接口会返回
  # errorCode=OSHI_INIT_FAILED 而不是崩溃 —— 属于「可降级但需知晓」的状态，
  # 因此单独列一项检查，让现场一眼看出监控是否真的在跑。
  METRICS_CODE=$(http_code -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/system/server/metrics")
  check "服务器监控接口" "$([[ "$METRICS_CODE" == "200" ]] && echo true || echo false)" \
        "HTTP ${METRICS_CODE}"
  if [[ "$METRICS_CODE" == "200" ]]; then
    METRICS_ERR=$(curl -s -m 10 -H "Authorization: Bearer ${TOKEN}" \
      "${BASE_URL}/api/v1/system/server/metrics" 2>/dev/null \
      | grep -oE '"errorCode":"[^"]*"' | head -1 | cut -d'"' -f4)
    check "服务器指标采集可用" "$([[ -z "$METRICS_ERR" || "$METRICS_ERR" == "null" ]] && echo true || echo false)" \
          "$([[ -z "$METRICS_ERR" || "$METRICS_ERR" == "null" ]] && echo "OSHI 采集正常" || echo "采集异常：${METRICS_ERR}")"
  fi
else
  check "看板统计接口（带 token）" false "未取到 token，无法验证"
fi

# 匿名不得访问管理端点（2026-10-31 新增）
# 背景：SecurityConfig 曾把整个 "/api/v1/**" 放进白名单，
# 而该前缀下不只有设备端点，还有系统设置、产品配置、缺陷类型、
# 映射、看板等管理端点 → 实测匿名 PUT 系统设置、匿名 POST 产品配置
# 都能改生产库。这里逐条确认它们已被挡下。
echo
echo "  匿名访问管理端点（期望全部 401/403）："
ANON_ADMIN_ALL_OK=true
for p in /api/v1/system/settings /api/v1/config/products /api/v1/config/defect-types \
         /api/v1/config/camera-defect-mappings /api/v1/analytics/metadata \
         /api/user /api/logs/operation /api/config-import/template; do
  c=$(http_code "${BASE_URL}${p}")
  if [[ "$c" == "403" || "$c" == "401" ]]; then
    printf "    \033[0;32m✓\033[0m %-42s HTTP %s\n" "${p}" "${c}"
  else
    printf "    \033[0;31m✗\033[0m %-42s HTTP %s（期望 401/403）\n" "${p}" "${c}"
    ANON_ADMIN_ALL_OK=false
  fi
done
check "匿名不得访问管理端点" "$ANON_ADMIN_ALL_OK" "8 个管理端点"

# 匿名不得执行写操作（2026-10-31 新增）
ANON_WRITE_CODE=$(http_code -X PUT -H "Content-Type: application/json" \
  -d '{"settings":[{"settingKey":"alert.cpuWarning","settingValue":"1"}]}' \
  "${BASE_URL}/api/v1/system/settings")
check "匿名不得写系统设置" "$([[ "$ANON_WRITE_CODE" == "403" || "$ANON_WRITE_CODE" == "401" ]] && echo true || echo false)" \
      "匿名 PUT HTTP ${ANON_WRITE_CODE}（期望 401/403）"

# --- 7. 冻结设备接口 ---------------------------------------------------------
echo
echo "${C_HEAD}[7] 设备上报接口${C_OFF}"

HB_CODE=$(http_code -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" \
  -d '{"cameraName":"自检相机","cameraCode":"CAM-001","cameraStatus":1}' \
  "${BASE_URL}/api/v1/camera/status/heartbeat")
check "心跳上报" "$([[ "$HB_CODE" == "200" ]] && echo true || echo false)" "HTTP ${HB_CODE}"

BU_CODE=$(http_code -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: CAM-001" -H "X-Batch-Id: VERIFY-$(date +%s)" -H "Authorization: Bearer dev-token" \
  -d '{"schemaVersion":"2.0","batchId":"VERIFY","cameraCode":"CAM-001","productName":"产品A","reportTime":"2026-01-01 00:00:00","counterMode":"INCREMENTAL","timeRange":{"startTime":"2026-01-01 00:00:00","endTime":"2026-01-01 00:30:00"},"summary":{"totalCount":1,"okCount":1,"ngCount":0},"defects":[]}' \
  "${BASE_URL}/api/v1/camera/inspection/batch-upload")
check "批量上报" "$([[ "$BU_CODE" == "200" ]] && echo true || echo false)" "HTTP ${BU_CODE}"

# 未映射编码应返回 422 UNMAPPED
UNMAPPED_CODE=$(http_code -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: CAM-001" -H "X-Batch-Id: VERIFY-UNMAPPED-$(date +%s)" -H "Authorization: Bearer dev-token" \
  -d '{"schemaVersion":"2.0","batchId":"VERIFY-UNMAPPED","cameraCode":"CAM-001","productName":"产品A","reportTime":"2026-01-01 00:00:00","counterMode":"INCREMENTAL","timeRange":{"startTime":"2026-01-01 00:00:00","endTime":"2026-01-01 00:30:00"},"summary":{"totalCount":1,"okCount":0,"ngCount":1},"defects":[{"sourceCode":"__NOT_MAPPED__","count":1}]}' \
  "${BASE_URL}/api/v1/camera/inspection/batch-upload")
check "未映射编码拒绝" "$([[ "$UNMAPPED_CODE" == "422" ]] && echo true || echo false)" "HTTP ${UNMAPPED_CODE}（期望 422）"

# --- 8. 图片超量快速拒绝 -----------------------------------------------------
OVER_CODE=$(http_code -X POST -H "X-Image-Count: 101" \
  -H "X-Device-Id: CAM-001" -H "Authorization: Bearer dev-token" \
  "${BASE_URL}/api/v1/defect-images/upload")
check "图片超量拒绝" "$([[ "$OVER_CODE" == "413" ]] && echo true || echo false)" "HTTP ${OVER_CODE}（期望 413）"

# --- 9. 清理自检产生的测试数据 -------------------------------------------------
# ⚠️ 2026-10-31 服务器实测发现的问题：
#   上面第 7 节的两条设备端上报（VERIFY / VERIFY-UNMAPPED）**会真的写库**：
#     - fact_inspection_batch            写入一条批次记录
#     - fact_inspection_mapping_exception 写入一条 UNMAPPED 异常记录
#   这两张表**没有任何定时清理任务**（`sys_data_retention_policy` 表只是元数据，
#   代码从不读它），而自检是要人工/自动反复跑的 ——
#   实测跑一次自检，异常表就 +1 条，跑得越多污染越多。
#   这些假数据还会出现在「异常统计」里，让现场误以为设备真有映射问题。
#
#   现在自检结束后**立即清掉自己造的数据**（只按 batch_id 精确匹配，不误伤真实数据）。
echo
echo "${C_HEAD}[8] 清理自检数据${C_OFF}"

# 需要的口令来自 /etc/default/camera-detection（640 root）
MYSQL_PW="${MYSQL_PASSWORD:-}"
if [[ -z "$MYSQL_PW" && -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  MYSQL_PW="$(sed -n 's/^MYSQL_PASSWORD=["'\'']\{0,1\}\([^"'\'']*\)["'\'']\{0,1\}$/\1/p' \
    /etc/default/camera-detection | head -1)"
fi
DB_NAME="${VERIFY_DB_NAME:-wms-new}"

if [[ -z "$MYSQL_PW" ]] || ! command -v mysql >/dev/null 2>&1; then
  check "清理自检数据" false "缺少数据库口令或 mysql 客户端，无法清理"
elif ! MYSQL_PWD="$MYSQL_PW" mysql -h127.0.0.1 -uroot -D "$DB_NAME" -e "SELECT 1" >/dev/null 2>&1; then
  check "清理自检数据" false "数据库连接失败，无法清理"
else
  # 只删 batch_id 以 VERIFY 开头的记录 —— 真实设备的 batch_id 不会是这个前缀
  BEFORE_E=$(MYSQL_PWD="$MYSQL_PW" mysql -h127.0.0.1 -uroot -D "$DB_NAME" -N -e \
    "SELECT COUNT(*) FROM fact_inspection_mapping_exception WHERE batch_id LIKE 'VERIFY%'" 2>/dev/null || echo 0)
  BEFORE_B=$(MYSQL_PWD="$MYSQL_PW" mysql -h127.0.0.1 -uroot -D "$DB_NAME" -N -e \
    "SELECT COUNT(*) FROM fact_inspection_batch WHERE batch_id LIKE 'VERIFY%'" 2>/dev/null || echo 0)

  MYSQL_PWD="$MYSQL_PW" mysql -h127.0.0.1 -uroot -D "$DB_NAME" >/dev/null 2>&1 <<'SQL'
DELETE FROM fact_inspection_mapping_exception WHERE batch_id LIKE 'VERIFY%';
DELETE FROM fact_inspection_batch WHERE batch_id LIKE 'VERIFY%';
DELETE FROM fact_inspection_defect_count WHERE batch_id LIKE 'VERIFY%';
SQL

  AFTER_E=$(MYSQL_PWD="$MYSQL_PW" mysql -h127.0.0.1 -uroot -D "$DB_NAME" -N -e \
    "SELECT COUNT(*) FROM fact_inspection_mapping_exception WHERE batch_id LIKE 'VERIFY%'" 2>/dev/null || echo 0)
  AFTER_B=$(MYSQL_PWD="$MYSQL_PW" mysql -h127.0.0.1 -uroot -D "$DB_NAME" -N -e \
    "SELECT COUNT(*) FROM fact_inspection_batch WHERE batch_id LIKE 'VERIFY%'" 2>/dev/null || echo 0)

  if [[ "$AFTER_E" == "0" && "$AFTER_B" == "0" ]]; then
    check "清理自检数据" true "已清理 异常 ${BEFORE_E} 条 / 批次 ${BEFORE_B} 条"
  else
    check "清理自检数据" false "清理不彻底：剩余 异常 ${AFTER_E} 条 / 批次 ${AFTER_B} 条"
  fi
fi

# --- 汇总 -------------------------------------------------------------------
echo
echo "${C_HEAD}=========================================${C_OFF}"
echo "  通过 ${PASS} 项，失败 ${FAIL} 项"
if [[ "$FAIL" -gt 0 ]]; then
  echo
  echo "  ${C_BAD}未通过项：${C_OFF}"
  for item in "${FAILED_ITEMS[@]}"; do echo "    - ${item}"; done
  echo
  echo "  ${C_DIM}排查：journalctl -u ${SERVICE_NAME} -n 100 --no-pager${C_OFF}"
  echo "  ${C_DIM}业务日志：${WORK_DIR}/logs/camera-detection-system.log${C_OFF}"
  echo "${C_HEAD}=========================================${C_OFF}"
  exit 1
fi
echo "  ${C_OK}全部自检通过${C_OFF}"
echo "${C_HEAD}=========================================${C_OFF}"
exit 0
