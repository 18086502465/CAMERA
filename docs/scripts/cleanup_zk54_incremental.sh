#!/bin/bash
# ==============================================================================
# 测试相机数据增量清理（每小时 :15 执行，root）
#
# 【2026-11-05 职责重构：从"硬编码白名单"改为"按相机类型判定"】
#
# 旧版：KEEP="'DA8119489','DA4733319'" 写死在脚本里。
#   → 加一台真实相机必须改脚本并重跑，改漏即误删真实数据；
#   → 相机属性无法在系统内表达。
#
# 新版：真实相机由数据库字段 camera_type='REAL' 标记（dim_camera 为权威源，
#       camera_status 为冗余副本），脚本**只读不判** —— 标记即生效，脚本永不需要改。
#
# 保护集合（Protected Set）= 以下三者取并集：
#   ① dim_camera 中 camera_type='REAL' 且 is_deleted=0 的相机（权威源）
#   ② camera_status 中 camera_type='REAL' 且 is_active=1 的相机（冗余副本）
#      → 任一表标记 REAL 即受保护：单表漏更新不会导致误删
#   ③ 环境变量 PROTECTED_CODES（运维临时加码，不必改代码）
#
# 清理范围 = 保护集合之外的一切（TEST 相机 + 不在任何相机表中的孤儿数据）。
#
# 三道防误删防线（详见 docs/方案-相机类型隔离与测试相机数据清理-20261105.md）：
#   防线一：双表 OR 保护（任一表标 REAL 即跳过）
#   防线二：保护集合为空 → 判定"标记被整体误改" → 中止本轮 + 告警
#   防线三：单轮待删量超阈值 → 中止本轮 + 告警
#   附加：清理后自检 REAL 相机数据是否减少（事后检测）
#
# 幂等性：判据是"相机类型"而非"处理状态标记"，重复执行第二次影响 0 行。
#   flock 独占锁防并发重入；中途失败下一轮自动补齐。
#
# 顺序铁律：先删库 → 再删磁盘。
#   若顺序反过来（先删文件），删库失败会留下"有记录无文件"的坏数据（页面裂图）；
#   先删库后删文件失败，最坏只是留下孤儿文件，下一轮自动清。
#
# 频率：cron 每小时 :15（root）
# 日志：/data/camera-detection/backup/cleanup_zk54.log（追加，分级 INFO/WARN/ERROR）
#
# 可配置环境变量（建议写进 /etc/default/camera-detection）：
#   MYSQL_PASSWORD          数据库口令（必需）
#   IMG_ROOT                图片根目录
#   LOG                     日志文件路径
#   LOCK                    锁文件路径
#   DB_NAME / DB_HOST / DB_USER
#   PROTECTED_CODES         额外保护的真实相机编号（逗号分隔，运维临时加码用）
#   MAX_SINGLE_DELETE       单轮允许删除的 defect_image_file 最大行数（默认 50000）
#
# 参数：
#   --dry-run          只统计与打印将要删除的范围，不实际删除
#   --keep-codes LIST  临时覆盖保护集合（逗号分隔），仅本轮生效
#
# 退出码：0=成功；2=有步骤失败；3=保护集合为空而中止；4=超阈值中止；5=未获锁跳过
# ==============================================================================
set -uo pipefail

# --- 口令来源 ----------------------------------------------------------------
# 优先从 /etc/default/camera-detection 读（640 root:root）——
# cron 文件是全局可读的，把口令写进 cron 等于把库口令公开给所有本地用户。
# 找不到该文件时回退到已导出的 MYSQL_PASSWORD 环境变量（手工调试用）。
if [[ -z "${MYSQL_PASSWORD:-}" && -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi

IMG_ROOT="${IMG_ROOT:-/data/camera-detection/images/defect-images}"
LOG="${LOG:-/data/camera-detection/backup/cleanup_zk54.log}"
LOCK="${LOCK:-/var/run/camera-detection-cleanup.lock}"
DB_NAME="${DB_NAME:-wms-new}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-root}"
MAX_SINGLE_DELETE="${MAX_SINGLE_DELETE:-50000}"
PROTECTED_CODES="${PROTECTED_CODES:-}"
DRY_RUN=0
KEEP_OVERRIDE=""

MP="${MYSQL_PASSWORD:?请以环境变量提供 MYSQL_PASSWORD}"

# --- 日志（分级） ------------------------------------------------------------
log()  { echo "[$(date '+%F %T')] INFO  $*" >> "$LOG"; }
warn() { echo "[$(date '+%F %T')] WARN  $*" >> "$LOG"; }
err()  { echo "[$(date '+%F %T')] ERROR $*" >> "$LOG"; }

# --- 参数解析 ----------------------------------------------------------------
# 必须用 `while [[ $# -gt 0 ]]` + `shift`：for 会在开始时把 "$@" 展开成固定列表，
# shift 改不动它，带值参数会取到"参数名本身"而不是值。
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --keep-codes) KEEP_OVERRIDE="${2:?--keep-codes 需要一个值}"; shift 2 ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *) log "未知参数：$1"; exit 1 ;;
  esac
done

# --- 并发锁 ------------------------------------------------------------------
# 上轮未跑完时下一轮已到点（每小时触发），无锁会并发删同一批数据。
# flock 非阻塞：拿不到锁立即退出（退出码 5），下一轮自然补齐。
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK" 2>/dev/null || true
  if ! flock -n 9; then
    log "上一轮清理尚未结束，本次跳过（退出码 5）"
    exit 5
  fi
fi

# --- 数据库访问 --------------------------------------------------------------
# SQL() 用于写操作：stderr **不吞** —— 吞掉会把"清理失败"伪装成"清理成功"。
SQL()  { mysql -h"$DB_HOST" -u"$DB_USER" -p"$MP" -N -B -D "$DB_NAME" -e "$1"; }
# SQLQ 用于只读查询：失败返回空串，调用方按"空 = 异常"处理
SQLQ() { mysql -h"$DB_HOST" -u"$DB_USER" -p"$MP" -N -B -D "$DB_NAME" -e "$1" 2>/dev/null; }

FAILED=0
fail_step() {
  FAILED=$((FAILED+1))
  err "$1"
}

# --- 告警落库 ----------------------------------------------------------------
# 清理失败/中止时写 fact_anomaly_alert，前端告警页可见（不只是文本日志）。
# 该表 message 列为 NOT NULL，必须一并赋值。
alert() {
  local level="$1" title="$2" message="$3" esc
  esc=$(printf "%s" "$message" | sed "s/'/''/g")
  if SQL "INSERT INTO fact_anomaly_alert(line_id, workstation_id, detection_id, alert_type, alert_level,
        alert_title, alert_message, message, detect_time, is_resolved, create_time, update_time)
       VALUES(0, 0, NULL, 'CLEANUP_FAILED', '$level',
        '$title', '$esc', '$esc', NOW(), 0, NOW(), NOW())" >/dev/null 2>&1; then
    log "已写入告警：[$level] $title"
  else
    warn "告警写入失败（不影响清理结果）：[$level] $title"
  fi
}

# ==============================================================================
# 第 1 步：确定保护集合
# ==============================================================================
REAL_DIM=$(SQLQ "SELECT camera_code FROM dim_camera WHERE camera_type='REAL' AND IFNULL(is_deleted,0)=0")
REAL_STATUS=$(SQLQ "SELECT camera_code FROM camera_status WHERE camera_type='REAL' AND IFNULL(is_active,0)=1")

if [[ -n "$KEEP_OVERRIDE" ]]; then
  PROTECTED="$KEEP_OVERRIDE"
  log "本轮使用 --keep-codes 覆盖保护集合：$PROTECTED"
else
  PROTECTED=$(printf '%s\n%s\n%s' "$REAL_DIM" "$REAL_STATUS" "$PROTECTED_CODES" \
             | tr ',' '\n' | sed '/^[[:space:]]*$/d' | sort -u | tr '\n' ',')
  PROTECTED="${PROTECTED%,}"
fi

# 拆成数组，供 SQL 片段与磁盘判定共用
#
# ★ 必须用 here-string（`<<<`）而不是 `printf '%s' | tr`：
#   `printf '%s' "$PROTECTED"` 不带末尾换行 → tr 转换后最后一行也没有换行符 →
#   `while read` 读到最后一行时返回非零 → **循环体不执行 → 最后一个相机被静默丢弃**。
#   而被丢弃的恰好是真实相机 → 保护集合少一台 → 该相机的数据会被误删。
#   here-string 等价于 `printf '%s\n'`，末尾恒有换行，最后一行必被读到。
CODES=()
while IFS= read -r c; do
  [[ -z "$c" ]] && continue
  CODES+=("$c")
done < <(tr ',' '\n' <<< "$PROTECTED" | sed '/^[[:space:]]*$/d')

PROTECTED_COUNT=${#CODES[@]}
log "清理开始 protected=[${PROTECTED}] count=${PROTECTED_COUNT} dry_run=${DRY_RUN}"

# 防线二：保护集合为空 → 可能是"所有相机被误标为 TEST"，中止本轮
if [[ "$PROTECTED_COUNT" -eq 0 ]]; then
  err "保护集合为空（无 REAL 相机）—— 疑似相机类型被整体误改，中止本轮清理"
  alert "HIGH" "测试相机清理已中止：保护集合为空" \
        "查询 dim_camera/camera_status 未发现任何 camera_type='REAL' 的相机，疑似类型标记被整体误改。本轮清理已中止，请人工核对相机类型。"
  exit 3
fi

# 构造 SQL 片段
PROTECTED_SQL=""     # 'A','B'  → 用于 IN (...)
PATH_NOT_LIKE=""     # image_path NOT LIKE '%/A/%' AND image_path NOT LIKE '%/B/%' AND
PATH_LIKE_OR=""      # image_path LIKE '%/A/%' OR image_path LIKE '%/B/%' OR
for c in "${CODES[@]}"; do
  esc=$(printf '%s' "$c" | sed "s/'/''/g")
  PROTECTED_SQL="${PROTECTED_SQL}'${esc}',"
  PATH_NOT_LIKE="${PATH_NOT_LIKE}image_path NOT LIKE '%/${esc}/%' AND "
  PATH_LIKE_OR="${PATH_LIKE_OR}image_path LIKE '%/${esc}/%' OR "
done
PROTECTED_SQL="${PROTECTED_SQL%,}"
PATH_NOT_LIKE="${PATH_NOT_LIKE}1=1"
PATH_LIKE_OR="${PATH_LIKE_OR}1=0"

# ==============================================================================
# 第 2 步：统计待删量（防线三：超阈值中止）+ 清理前 REAL 基线
# ==============================================================================
N_FILE=$(SQLQ "SELECT COUNT(*) FROM defect_image_file WHERE camera_id NOT IN (${PROTECTED_SQL})")
N_FILE="${N_FILE:-0}"
log "待删统计 defect_image_file=${N_FILE} 行（阈值 ${MAX_SINGLE_DELETE}）"

if [[ "$N_FILE" -gt "$MAX_SINGLE_DELETE" ]]; then
  err "单轮待删 ${N_FILE} 行超过阈值 ${MAX_SINGLE_DELETE} —— 中止本轮清理（疑似类型标记异常）"
  alert "HIGH" "测试相机清理已中止：单轮删除量超阈值" \
        "本轮待删 defect_image_file ${N_FILE} 行，超过阈值 ${MAX_SINGLE_DELETE} 行，疑似相机类型被批量误改。本轮清理已中止，请人工核对。"
  exit 4
fi

# 清理前 REAL 相机的数据基线（用于事后自检）
BEFORE_FILE=$(SQLQ "SELECT COUNT(*) FROM defect_image_file WHERE camera_id IN (${PROTECTED_SQL})")
BEFORE_IMG=$(SQLQ "SELECT COUNT(*) FROM camera_images WHERE ${PATH_LIKE_OR}")
BEFORE_FILE="${BEFORE_FILE:-0}"
BEFORE_IMG="${BEFORE_IMG:-0}"

# ==============================================================================
# 第 3 步：删库（先 box → file → batch → camera_images）
# ==============================================================================
N1=0; N2=0; N3=0; N4=0

if [[ "$DRY_RUN" == "1" ]]; then
  N1=$(SQLQ "SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN (${PROTECTED_SQL})")
  N2="$N_FILE"
  N3=$(SQLQ "SELECT COUNT(*) FROM defect_image_batch WHERE camera_id NOT IN (${PROTECTED_SQL})")
  N4=$(SQLQ "SELECT COUNT(*) FROM camera_images WHERE ${PATH_NOT_LIKE}")
  log "[dry-run] 将删除 box=${N1:-0} file=${N2:-0} batch=${N3:-0} camera_images=${N4:-0}"
else
  # 每步单独执行并检查返回码，失败记 ERROR 并计数（不再 || true 吞错）
  if OUT=$(SQL "DELETE b FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN (${PROTECTED_SQL});" 2>&1); then
    N1=$(SQLQ "SELECT COUNT(*) FROM defect_image_box b JOIN defect_image_file f ON f.image_id=b.image_id WHERE f.camera_id NOT IN (${PROTECTED_SQL})" || echo 0)
  else
    fail_step "删除 defect_image_box 失败：${OUT}"
  fi

  if OUT=$(SQL "DELETE FROM defect_image_file WHERE camera_id NOT IN (${PROTECTED_SQL});" 2>&1); then
    N2="$N_FILE"
  else
    fail_step "删除 defect_image_file 失败：${OUT}"
  fi

  if OUT=$(SQL "DELETE FROM defect_image_batch WHERE camera_id NOT IN (${PROTECTED_SQL});" 2>&1); then
    N3=$(SQLQ "SELECT COUNT(*) FROM defect_image_batch WHERE camera_id NOT IN (${PROTECTED_SQL})" || echo 0)
  else
    fail_step "删除 defect_image_batch 失败：${OUT}"
  fi

  # camera_images 是旧兼容表，只有 image_path，按路径中的相机目录名判定
  if OUT=$(SQL "DELETE FROM camera_images WHERE ${PATH_NOT_LIKE};" 2>&1); then
    N4=$(SQLQ "SELECT COUNT(*) FROM camera_images WHERE ${PATH_NOT_LIKE}" || echo 0)
  else
    fail_step "删除 camera_images 失败：${OUT}"
  fi
fi

# ==============================================================================
# 第 4 步：删磁盘（仅删不在保护集合内的相机目录）
# ==============================================================================
DISK=0
DISK_FAILED=0
if [[ -d "$IMG_ROOT" ]]; then
  while IFS= read -r d; do
    [[ -z "$d" ]] && continue
    name="$(basename "$d")"
    # 保护集合判定：目录名命中任一 code 即跳过
    skip=0
    for c in "${CODES[@]}"; do
      if [[ "$name" == "$c" ]]; then skip=1; break; fi
    done
    [[ "$skip" == "1" ]] && continue
    if [[ "$DRY_RUN" == "1" ]]; then
      DISK=$((DISK+1))
    elif rm -rf "$d"; then
      DISK=$((DISK+1))
    else
      DISK_FAILED=$((DISK_FAILED+1))
      fail_step "删除磁盘目录失败：$d"
    fi
  done < <(find "$IMG_ROOT" -mindepth 2 -maxdepth 2 -type d 2>/dev/null)

  # 清空的日期目录
  if [[ "$DRY_RUN" != "1" ]]; then
    find "$IMG_ROOT" -mindepth 1 -maxdepth 1 -type d -empty -delete 2>/dev/null || true
  fi
else
  warn "图片目录不存在：${IMG_ROOT}（跳过磁盘步骤）"
fi

# ==============================================================================
# 第 5 步：事后自检 —— REAL 相机的数据是否被误删
# ==============================================================================
VIOLATION=0
if [[ "$DRY_RUN" != "1" ]]; then
  AFTER_FILE=$(SQLQ "SELECT COUNT(*) FROM defect_image_file WHERE camera_id IN (${PROTECTED_SQL})")
  AFTER_IMG=$(SQLQ "SELECT COUNT(*) FROM camera_images WHERE ${PATH_LIKE_OR}")
  AFTER_FILE="${AFTER_FILE:-0}"
  AFTER_IMG="${AFTER_IMG:-0}"
  if [[ "$AFTER_FILE" -lt "$BEFORE_FILE" || "$AFTER_IMG" -lt "$BEFORE_IMG" ]]; then
    VIOLATION=1
    err "★ 自检告警：REAL 相机数据减少 file:${BEFORE_FILE}->${AFTER_FILE} img:${BEFORE_IMG}->${AFTER_IMG} —— 可能有真实相机数据被误删"
    alert "HIGH" "测试相机清理自检告警：真实相机数据减少" \
          "清理前后 REAL 相机数据量对比：defect_image_file ${BEFORE_FILE}->${AFTER_FILE}，camera_images ${BEFORE_IMG}->${AFTER_IMG}。疑似真实相机被误标为 TEST，请立即核对 dim_camera.camera_type。"
  fi
fi

# ==============================================================================
# 汇总
# ==============================================================================
REMAIN=$(SQLQ "SELECT COUNT(*) FROM defect_image_file")
REMAIN="${REMAIN:-0}"
log "box=${N1:-0} file=${N2:-0} batch=${N3:-0} camera_images=${N4:-0} diskDirs=${DISK} diskFailed=${DISK_FAILED} remain_file=${REMAIN} protected=${PROTECTED_COUNT}"

if [[ "$FAILED" -gt 0 ]]; then
  err "本轮存在 ${FAILED} 个步骤失败，退出码 2"
  alert "HIGH" "测试相机清理部分失败" \
        "本轮有 ${FAILED} 个步骤失败，详见 /data/camera-detection/backup/cleanup_zk54.log。清理为幂等操作，下一轮会自动重试未完成部分。"
  exit 2
fi

if [[ "$VIOLATION" == "1" ]]; then
  exit 2
fi

log "清理完成"
exit 0
