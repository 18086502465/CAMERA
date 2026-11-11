#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 数据库全量备份
#
# 特点：
#   1. --single-transaction —— 不锁表，业务无感知（InnoDB 一致性快照）
#   2. 备份前检查磁盘水位 —— 与数据同盘，写满时不再写入
#   3. 每个文件生成 .sha256 —— 备份可校验
#   4. 保留 N 天，过期自动删除
#   5. 可选异机同步（rsync）
#
# 安装：
#   sudo install -m 0755 backup-database.sh /main/app/dist/scripts/
#
# 手动执行：
#   MYSQL_PASSWORD='xxx' bash /main/app/dist/scripts/backup-database.sh
#
# 作为 systemd timer 运行（推荐，见 camera-detection-backup.service/.timer）：
#   由 /etc/default/camera-detection 提供 MYSQL_PASSWORD 环境变量
# ==============================================================================
set -euo pipefail

# 统一布局（2026-11）：业务数据与备份主目录都在数据盘 /main/app/data 下，
# 由 install.sh 以 root 建好并 chown 给 cdsys —— 本脚本以 cdsys 身份运行也能写。
BACKUP_DIR="${BACKUP_DIR:-/main/app/data/backup/db}"
DB_NAME="${DB_NAME:-wms-new}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${MYSQL_PASSWORD:-}"
KEEP_FULL_DAYS="${KEEP_FULL_DAYS:-14}"
# 磁盘水位上限（百分比），超过则跳过本次备份，避免把磁盘写满
MAX_DISK_USE="${MAX_DISK_USE:-85}"
# 是否同步到跨盘副本（目录存在才同步）
# ★ 统一布局后业务数据全在数据盘：主备份 ${BACKUP_DIR} 在 /main 上，
#   副本必须落在**系统盘**（/var/backups/...）才真正抗得住单盘故障。
#   backup-database.sh 会校验两者是否同一设备，同盘则告警并**跳过同步**
#   （同盘副本不仅无意义，还会因 rsync 把源侧误删一起复制过去）。
OFFSITE_DIR="${OFFSITE_DIR:-/var/backups/camera-detection/db}"

# --- 图片热窗口备份（v3 P0-3）--------------------------------------------------
# 之前 ${BACKUP_DIR}/images 只是个空目录，一行业务代码都没写过 —— 盘坏时数据库
# 能恢复到 14 天内任意时刻，图片一张都回不来。而图片是本系统的核心产出。
#
# 不备份 730 天：REAL 相机实测 67MB/天（2026-11-07），730 天约 48~93GB，
#   跨盘副本按全量镜像会长期占用 /var 这块系统盘（43G），不划算。
# 只备份近 30 天（≈2GB，随业务量浮动），覆盖「最近一个月业务追溯」这个真实需求。
# ⚠️ 估算容量前必须先排除 TEST 相机：含压测流量是 780MB/天，会高估一个数量级。
IMAGES_SRC="${IMAGES_SRC:-/main/app/data/images/defect-images}"
IMAGES_DST="${IMAGES_DST:-/var/backups/camera-detection/images}"
IMAGE_BACKUP_KEEP_DAYS="${IMAGE_BACKUP_KEEP_DAYS:-30}"
# 副本总量上限（GB）。★ 2026-11-07 实测副本 3.6G vs 源 911M，全部是 TEST 相机压测
#   垃圾：源侧每小时清理、副本侧只按 30 天窗口留 → 越攒越多，最终吃掉 /var 这块
#   43G 的系统盘。两处修正：
#     ① 备份时排除 TEST 相机目录（数据源头就不进副本）；
#     ② 总量超 IMAGE_BACKUP_MAX_GB 时从最旧日期目录开始回收（兜底，防 REAL 相机
#        台数增长后 30 天窗口本身超限）。
IMAGE_BACKUP_MAX_GB="${IMAGE_BACKUP_MAX_GB:-20}"

FULL_DIR="${BACKUP_DIR}/full"
BINLOG_DIR="${BACKUP_DIR}/binlog"
LOG_DIR="${LOG_DIR:-/main/app/logs}"
LOG_FILE="${LOG_DIR}/backup.log"
# binlog 副本保留天数。与 KEEP_FULL_DAYS 对齐（默认都是 14 天）：
# 比最早的本地全量还旧的 binlog 没有「可以往上回放」的基线快照，留着也没用。
# 注意 MySQL 侧 binlog_expire_logs_seconds 现场是 30 天（不是无限），所以副本
# 通常能覆盖到 14 天；服务器先清掉的文件，副本按 mtime 到期后一并删除。
BINLOG_KEEP_DAYS="${BINLOG_KEEP_DAYS:-14}"

log() {
  local msg="[$(date '+%F %T')] $*"
  printf '%s\n' "$msg"
  printf '%s\n' "$msg" >> "${LOG_FILE}" 2>/dev/null || true
}

die() { log "ERROR: $*"; exit 1; }

# --- 告警落库 ----------------------------------------------------------------
# 备份失败/跳过时写 fact_anomaly_alert（alert_type=BACKUP_FAILED），前端告警页可见。
# 之前只写 journal：清理失败会写告警而备份不写，备份静默停摆数月都不会有人发现。
# 注意：告警写不进去不能阻塞主流程 —— 很可能正是数据库有问题才导致备份失败。
alert() {
  local level="$1" title="$2" message="$3" esc_title esc
  esc_title=$(printf "%s" "$title" | sed "s/'/''/g")
  esc=$(printf "%s" "$message" | sed "s/'/''/g")
  if ! command -v mysql >/dev/null 2>&1; then
    log "WARN: 无法写告警（mysql 客户端不可用）：[$level] $title"
    return
  fi
  # MYSQL_PWD 环境变量传口令，避免口令出现在进程列表（-p"$DB_PASS" 会被 ps 看到）
  if MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -u"$DB_USER" -N -B -D "$DB_NAME" \
      -e "INSERT INTO fact_anomaly_alert(line_id, workstation_id, detection_id, alert_type, alert_level,
            alert_title, alert_message, message, detect_time, is_resolved, create_time, update_time)
          VALUES(0, 0, NULL, 'BACKUP_FAILED', '$level',
            '$esc_title', '$esc', '$esc', NOW(), 0, NOW(), NOW())" >/dev/null 2>&1; then
    log "已写入告警：[$level] $title"
  else
    log "WARN: 告警写入失败（不影响备份结果）：[$level] $title"
  fi
}

# --- binlog 增量备份 ------------------------------------------------------------
# v2 P0-3（2026-11-07）：binlog 一直是开启的（binlog_format=ROW），但
# ${BACKUP_DIR}/binlog/ 从来是空目录 —— 「有能力做分钟级恢复却从没接上」，
# 实际 RPO 仍是 24 小时；而且 binlog 与数据同盘，盘坏了一起没。
#
# 只备份**增量**（dump 起点文件及更新），不复制历史：现场历史 binlog 约 2.2GB
# 且持续增长，全量复制会瞬间吃满 /data。
#
# 取文件用 mysqlbinlog --read-from-remote-server --raw：走协议，不需要读
# /var/lib/mysql（该目录 750 mysql:mysql，本脚本以 cdsys 运行，读不了；
# 加组权限会影响 MySQL 的安全基线，不值得）。
backup_binlogs() {
  local files f size target available copied=0
  files=$(MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -N -B \
          -e "SHOW BINARY LOGS" 2>/dev/null)
  if [[ -z "$files" ]]; then
    log "WARN: 取不到 binlog 列表（SHOW BINARY LOGS 为空），跳过 binlog 备份"
    return 0
  fi

  while read -r f size; do
    [[ -n "$f" ]] || continue
    # 文件名零填充，字典序即时间序；早于 dump 起点的文件已经体现在全量里，不用复制
    if [[ "$f" < "$BINLOG_START_FILE" ]]; then
      continue
    fi
    target="${BINLOG_DIR}/${f}"
    # 已备份且大小一致（该 binlog 已封口、不再增长）→ 跳过，避免每晚重复拉
    if [[ -f "$target" ]] && [[ "$(stat -c %s "$target" 2>/dev/null)" == "$size" ]]; then
      continue
    fi
    # 正在写的那个 binlog 每晚大小都变，会重复复制；副本是「当晚那个时刻的前缀」，
    # 回放到该时刻为止仍然有效（截断的尾部只会影响继续往后回放）。
    rm -f "${BINLOG_DIR}/${f}"
    # ★ --result-file 必须给**目录且结尾带斜杠**。给完整文件名时 mysqlbinlog 会把
    #   原始名再接一遍：--result-file=/x/binlog.000001 → 产物叫
    #   binlog.000001binlog.000001（实测踩过）。给目录则按原名落盘。
    if MYSQL_PWD="$DB_PASS" mysqlbinlog --read-from-remote-server \
         -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" --raw \
         --result-file="${BINLOG_DIR}/" "$f" >/dev/null 2>&1 \
       && [[ -s "${BINLOG_DIR}/${f}" ]]; then
      copied=$((copied + 1))
    else
      log "WARN: binlog 复制失败：$f"
    fi
  done <<< "$files"

  # 保留期：服务器已经清掉的 binlog，本副本超过 BINLOG_KEEP_DAYS 天后一并删除。
  # 用 mtime 判断 —— 已封口的文件不会重复复制，mtime 停在首次复制时间。
  available=$(printf '%s\n' "$files" | awk '{print $1}')
  for target in "${BINLOG_DIR}"/*; do
    [[ -f "$target" ]] || continue
    f=$(basename "$target")
    if printf '%s\n' "$available" | grep -qxF "$f"; then
      continue
    fi
    if [[ -n "$(find "$target" -mtime +"$BINLOG_KEEP_DAYS" -print 2>/dev/null)" ]]; then
      rm -f "$target"
    fi
  done

  log "binlog 备份完成：本次复制 ${copied} 个文件，目录共 $(ls -1 "${BINLOG_DIR}" 2>/dev/null | wc -l) 个"
}

# --- 工具：两个路径是否在同一文件系统 -------------------------------------------
# 同盘副本不抗物理故障。不中止（仍然多一份拷贝总比没有好），但必须让日志和告警
# 说真话 —— 之前的日志写「已同步到异机目录」，而 df 显示源和副本都是 vg0-data。
same_device() {
  local a b
  a=$(df --output=source "$1" 2>/dev/null | tail -1)
  b=$(df --output=source "$2" 2>/dev/null | tail -1)
  [[ -n "$a" && "$a" == "$b" ]]
}

# 当前标记为 TEST 的相机编号（dim_camera 与 camera_status 两表并集）。
# TEST = 压测相机，数据由 cleanup-zk54-incremental.sh 每小时清掉，没有备份价值。
# 查询失败或返回空 → 不排除任何目录：**宁可多备份，不可漏备份**。
test_camera_codes() {
  MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -N -B -D "$DB_NAME" -e "
    SELECT DISTINCT camera_code FROM dim_camera     WHERE camera_type='TEST' AND IFNULL(is_deleted,0)=0
    UNION
    SELECT DISTINCT camera_code FROM camera_status  WHERE camera_type='TEST'" 2>/dev/null \
    | tr -d '\r' | sed 's/[[:space:]]*$//' | sed '/^$/d' || true
}

# --- 图片热窗口备份（v3 P0-3）--------------------------------------------------
# 只同步近 IMAGE_BACKUP_KEEP_DAYS 天的日期目录（与清理脚本同一判据：目录名 yyyyMMdd）。
#
# ★ 为什么必须排除 TEST 相机目录（2026-11-07 实测）：
#   源 /data 911M，副本 /var 3.6G —— 副本是源的 4 倍。原因是 TEST 相机（DA9467294）
#   单日写 907M，源侧每小时清掉，而副本按「30 天窗口 + 不用 --delete」把它们全留着。
#   备份的意义是「删了能找回」，压测垃圾本来就不该在找回范围里。
#   排除后副本规模与源同量级（≈2G），/var 这块 43G 系统盘不再被挤占。
backup_images() {
  local cutoff d dd name copied=0 skipped=0 failed=0 reclaimed=0
  local -a TEST_CODES=()

  if [[ ! -d "$IMAGES_SRC" ]]; then
    log "WARN: 图片源目录不存在：${IMAGES_SRC}，跳过图片备份"
    return 0
  fi
  if ! mkdir -p "$IMAGES_DST" 2>/dev/null; then
    log "WARN: 无法创建图片备份目录：${IMAGES_DST}"
    return 1
  fi

  if same_device "$IMAGES_SRC" "$IMAGES_DST"; then
    log "WARN: 图片备份目录与源在同一文件系统（$(df --output=source "$IMAGES_DST" 2>/dev/null | tail -1)）—— 该副本不抗单盘故障"
  fi

  while IFS= read -r d; do
    [[ -n "$d" ]] && TEST_CODES+=("$d")
  done < <(test_camera_codes)
  if [[ ${#TEST_CODES[@]} -eq 0 ]]; then
    log "WARN: 未取到 TEST 相机清单（数据库不可用或全为 REAL）—— 本轮不排除任何目录"
  else
    log "图片备份排除 TEST 相机 ${#TEST_CODES[@]} 台：${TEST_CODES[*]}"
  fi

  # 副本里历史遗留的 TEST 目录（排除生效前已经拷进去的）一次性回收
  if [[ ${#TEST_CODES[@]} -gt 0 ]]; then
    for dd in "${IMAGES_DST}"/*/; do
      [[ -d "$dd" ]] || continue
      for d in "${dd}"*/; do
        [[ -d "$d" ]] || continue
        name=$(basename "$d")
        for c in "${TEST_CODES[@]}"; do
          if [[ "$name" == "$c" ]]; then
            rm -rf -- "$d" && reclaimed=$((reclaimed + 1))
            break
          fi
        done
      done
    done
    [[ "$reclaimed" -gt 0 ]] && log "已回收副本中的 TEST 相机目录 ${reclaimed} 个"
  fi

  cutoff=$(date -d "-${IMAGE_BACKUP_KEEP_DAYS} days" '+%Y%m%d')

  # 组装 rsync 排除项：--exclude=/CODE/ 是「传输根下的一级目录」
  local -a EXCLUDES=()
  for c in "${TEST_CODES[@]}"; do
    EXCLUDES+=("--exclude=/${c}/")
  done

  for d in "${IMAGES_SRC}"/*/; do
    [[ -d "$d" ]] || continue
    name=$(basename "$d")
    [[ "$name" =~ ^[0-9]{8}$ ]] || continue
    if [[ "$name" < "$cutoff" ]]; then
      skipped=$((skipped + 1))
      continue
    fi
    mkdir -p "${IMAGES_DST}/${name}" 2>/dev/null || { failed=$((failed + 1)); continue; }
    # 源带尾斜杠 = 同步目录**内容**。
    # ★ 不用 --delete：备份应当比源留得更多。源侧删掉的文件（30 天兼容表清理）
    #   在副本里保留，正是备份该有的行为；用 --delete 的话一次误删就会同步删掉
    #   唯一副本。TEST 相机目录是**显式排除**，与 --delete 是两回事。
    if rsync -a ${EXCLUDES[@]+"${EXCLUDES[@]}"} "${d%/}/" "${IMAGES_DST}/${name}/" >/dev/null 2>&1; then
      copied=$((copied + 1))
    else
      failed=$((failed + 1))
      log "WARN: 图片目录同步失败：${name}"
    fi
  done

  # 目标侧清理超期副本（源侧已过保留期，副本也没有保留价值）
  for d in "${IMAGES_DST}"/*/; do
    [[ -d "$d" ]] || continue
    name=$(basename "$d")
    [[ "$name" =~ ^[0-9]{8}$ ]] || continue
    [[ "$name" < "$cutoff" ]] && rm -rf -- "$d"
  done

  # 总量上限兜底：超限时从最旧日期目录开始回收，降到上限的 90% 为止
  local max_kb cur_kb size_kb
  max_kb=$(( IMAGE_BACKUP_MAX_GB * 1024 * 1024 ))
  cur_kb=$(du -sk "$IMAGES_DST" 2>/dev/null | cut -f1 || true)
  case "${cur_kb:-}" in ''|*[!0-9]*) cur_kb=0 ;; esac
  if [[ "$cur_kb" -gt "$max_kb" ]]; then
    log "WARN: 图片副本 ${cur_kb}KB 超过上限 ${IMAGE_BACKUP_MAX_GB}GB，开始回收最旧日期目录"
    while IFS= read -r d; do
      [[ -d "$d" ]] || continue
      [[ "$cur_kb" -le $(( max_kb * 9 / 10 )) ]] && break
      name=$(basename "$d")
      size_kb=$(du -sk "$d" 2>/dev/null | cut -f1 || true)
      case "${size_kb:-}" in ''|*[!0-9]*) size_kb=0 ;; esac
      if rm -rf -- "$d"; then
        cur_kb=$(( cur_kb - size_kb ))
        log "WARN: 副本超限回收 ${name}（释放 $(( size_kb / 1024 ))MB）"
      fi
    done < <(find "$IMAGES_DST" -mindepth 1 -maxdepth 1 -type d -name '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]' 2>/dev/null | sort)
  fi

  log "图片备份完成：同步 ${copied} 个日期目录（超期跳过 ${skipped}、失败 ${failed}、回收 TEST ${reclaimed} 个），副本共 $(ls -1 "$IMAGES_DST" 2>/dev/null | wc -l) 个目录 / $(du -sh "$IMAGES_DST" 2>/dev/null | cut -f1)"
  [[ "$failed" -eq 0 ]]
}

# --- 前置检查 ------------------------------------------------------------------
if [[ -z "$DB_PASS" ]]; then
  alert "HIGH" "数据库备份未执行：缺少数据库口令" \
        "环境变量 MYSQL_PASSWORD 为空，本次备份未执行。请检查 /etc/default/camera-detection。"
  die "未提供数据库口令（设置环境变量 MYSQL_PASSWORD）"
fi
mkdir -p "$FULL_DIR" "$LOG_DIR" "${BACKUP_DIR}/binlog" "${BACKUP_DIR}/images"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

# --- 磁盘水位检查 --------------------------------------------------------------
# 备份与数据可能同盘；水位过高时再写备份会把磁盘彻底写满，反而丢数据
DISK_USE=$(df --output=pcent "$(dirname "$BACKUP_DIR")" | tail -1 | tr -d ' %')
if [[ "$DISK_USE" -gt "$MAX_DISK_USE" ]]; then
  alert "HIGH" "数据库备份已跳过：备份分区水位超阈值" \
        "备份分区使用率 ${DISK_USE}% 超过阈值 ${MAX_DISK_USE}%，本次备份跳过。磁盘越满越不备份 —— 请尽快扩盘或清理空间。"
  log "WARN: 备份分区使用率 ${DISK_USE}% 超过阈值 ${MAX_DISK_USE}%，跳过本次备份"
  exit 1
fi

# --- 全量转储 ------------------------------------------------------------------
STAMP=$(date +%Y%m%d_%H%M%S)
OUT="${FULL_DIR}/${DB_NAME}_${STAMP}.sql.zst"

# 记下 dump 开始时刻的 binlog 文件名（v2 P0-3）： backups 只复制「这个文件及更新的」，
# 才能从全量快照那一刻起向前回放到任意时间点。取不到就只做全量，不影响备份本身。
BINLOG_START_FILE=""
if [[ "${BINLOG_BACKUP_ENABLED:-1}" == "1" ]] && command -v mysqlbinlog >/dev/null 2>&1; then
  BINLOG_START_FILE=$(MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -N -B \
      -e "SHOW MASTER STATUS" 2>/dev/null | awk 'NR==1{print $1}')
  [[ -n "$BINLOG_START_FILE" ]] || log "WARN: 取不到 binlog 起点（SHOW MASTER STATUS 为空），本次跳过 binlog 备份"
else
  log "WARN: mysqlbinlog 不可用或已禁用，本次跳过 binlog 备份"
fi

log "开始全量备份：${DB_NAME} -> ${OUT}"
START=$(date +%s)

# --single-transaction：对 InnoDB 的一致性快照，不阻塞业务写入
# --quick：逐行读取，避免大表一次性载入内存
# --set-gtid-purged=OFF：本库 gtid_mode=OFF，不加此项在部分版本会报错
# 2>/dev/null：屏蔽口令告警（stderr 会把口令带出来，不安全）
#
# ★ 2026-11-07（v2 P0-2）：口令从 -p"$DB_PASS" 改为 MYSQL_PWD 环境变量。
#   -p 写法会把口令明文放进进程命令行，`ps aux` / /proc/<pid>/cmdline 全都看得到，
#   任何本地用户都能读到数据库 root 口令。本脚本的 alert() 早就是 MYSQL_PWD，
#   同一条链路两种口径属于历史遗留，这次统一。
if ! MYSQL_PWD="$DB_PASS" mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
      --single-transaction --quick \
      --routines --triggers --events \
      --set-gtid-purged=OFF \
      --default-character-set=utf8mb4 \
      "$DB_NAME" 2>/dev/null | zstd -3 -T4 -o "$OUT.tmp"; then
  rm -f "$OUT.tmp"
  alert "HIGH" "数据库备份失败：mysqldump 执行出错" \
        "全量备份 ${DB_NAME} 失败（mysqldump/zstd 管道错误），今日无备份。详见 ${LOG_FILE}。"
  die "mysqldump 执行失败"
fi

# 管道里 mysqldump 失败时 zstd 仍可能返回 0，必须单独校验文件
if [[ ! -s "$OUT.tmp" ]]; then
  rm -f "$OUT.tmp"
  alert "HIGH" "数据库备份失败：备份文件为空" \
        "全量备份 ${DB_NAME} 产物为空（mysqldump 可能已失败），今日无备份。详见 ${LOG_FILE}。"
  die "备份文件为空（mysqldump 可能已失败）"
fi
mv "$OUT.tmp" "$OUT"

# --- 校验 ----------------------------------------------------------------------
# zstd -t 检查压缩流完整性；sha256 供事后校验
if ! zstd -t "$OUT" >/dev/null 2>&1; then
  rm -f "$OUT"
  alert "HIGH" "数据库备份失败：压缩文件校验不通过" \
        "全量备份 ${DB_NAME} 的压缩产物校验失败，已删除该文件，今日无备份。详见 ${LOG_FILE}。"
  die "压缩文件校验失败，已删除"
fi
sha256sum "$OUT" > "${OUT}.sha256"

ELAPSED=$(( $(date +%s) - START ))
SIZE=$(du -h "$OUT" | cut -f1)
log "全量备份完成：${OUT} (${SIZE}, 耗时 ${ELAPSED}s)"

# --- binlog 增量 ----------------------------------------------------------------
# 放在全量成功之后：全量都没成功时，单独一份 binlog 副本没法用来恢复。
if [[ -n "$BINLOG_START_FILE" ]]; then
  backup_binlogs || log "WARN: binlog 备份异常（不影响全量备份结果）"
fi

# --- 图片热窗口 ----------------------------------------------------------------
# 放在 binlog 之后：库侧的恢复能力先保住，图片是第二优先级。
if [[ "${IMAGE_BACKUP_ENABLED:-1}" == "1" ]]; then
  if ! backup_images; then
    alert "MEDIUM" "图片备份部分失败" \
          "有日期目录同步失败，详见 ${LOG_FILE}。数据库备份本身正常。"
  fi
fi

# --- 清理过期备份 --------------------------------------------------------------
# 先删 .sql.zst 再删 .sha256，避免出现「有校验文件无备份文件」的中间态
DELETED=$(find "$FULL_DIR" -name "${DB_NAME}_*.sql.zst" -mtime "+${KEEP_FULL_DAYS}" -print -delete | wc -l)
if [[ "$DELETED" -gt 0 ]]; then
  find "$FULL_DIR" -name "${DB_NAME}_*.sql.zst.sha256" -mtime "+${KEEP_FULL_DAYS}" -delete
  log "已清理 ${DELETED} 份超过 ${KEEP_FULL_DAYS} 天的旧备份"
fi

# --- 跨盘副本 -------------------------------------------------------------------
# 2026-11 统一布局后：主备份在数据盘（/main），副本必须落在系统盘才抗单盘故障。
#   1. 默认目录 /var/backups/camera-detection/db（系统盘）；
#   2. 不用 --delete —— 它是「镜像」语义不是「备份」语义。源目录被误清空
#      （脚本 bug / 误 rm / 磁盘错误）时，--delete 会把副本一起清空；
#   3. 副本侧自己做保留期清理，避免只增不减；
#   4. 与源同设备时直接跳过同步并告警（同盘副本无意义）。
#   仍然只是「跨盘」，不是「异机」：抗单盘故障，不抗整机/机房故障。
if [[ -d "$OFFSITE_DIR" ]]; then
  if same_device "$FULL_DIR" "$OFFSITE_DIR"; then
    log "WARN: 副本目录与备份源在同一文件系统（$(df --output=source "$OFFSITE_DIR" 2>/dev/null | tail -1)）—— 跳过本次同步，不抗单盘故障"
    alert "MEDIUM" "数据库备份副本与源同盘" \
          "OFFSITE_DIR=${OFFSITE_DIR} 与 ${FULL_DIR} 在同一文件系统，该副本不能抵御磁盘故障。请指向独立挂载点（如系统盘 /var/backups）。"
  else
    if rsync -a "$FULL_DIR/" "${OFFSITE_DIR}/full/" 2>/dev/null; then
      # 副本侧按保留期清理（+2 天余量，避免与源侧删除竞争导致副本先删）
      find "${OFFSITE_DIR}/full" -name "${DB_NAME}_*.sql.zst" -mtime "+$((KEEP_FULL_DAYS + 2))" -delete 2>/dev/null || true
      find "${OFFSITE_DIR}/full" -name "${DB_NAME}_*.sql.zst.sha256" -mtime "+$((KEEP_FULL_DAYS + 2))" -delete 2>/dev/null || true
      log "已同步到跨盘副本：${OFFSITE_DIR}/full/（$(ls -1 "${OFFSITE_DIR}/full"/*.sql.zst 2>/dev/null | wc -l) 份）"
    else
      log "WARN: 副本同步失败（不影响本地备份）"
    fi

    if [[ -d "$BINLOG_DIR" ]] && rsync -a "$BINLOG_DIR/" "${OFFSITE_DIR}/binlog/" 2>/dev/null; then
      log "已同步到跨盘副本：${OFFSITE_DIR}/binlog/"
    fi
  fi
fi

log "本次备份结束"
