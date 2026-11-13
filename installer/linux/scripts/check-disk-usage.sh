#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 系统健康巡检（磁盘水位 + 备份新鲜度）
#
# 为什么需要：
#   磁盘写满时应用**能正常启动**，但图片上传与数据库写入会陆续失败，
#   表现为「服务活着但业务全错」——这是最难排查的一类故障。
#
#   备份则相反：它的失败模式是**静默停摆**。timer 被 disable、系统时间漂移、
#   有人 `systemctl stop` 了 service —— 这三种情况不产生任何错误日志，因为
#   根本没有「失败」，只是「没有发生」。原脚本只查磁盘，管不到这一块。
#
# 检查项：
#   1. 关键分区水位（/ /main），超过 THRESHOLD 告警
#      ⚠️ 2026-11-13 前这里是「/ /var /data」——统一布局后 /data 已不是挂载点，
#      `df /data` 返回的是系统盘，500G 数据盘 /main 从未被巡检。改默认值却忘了改现场
#      已存在的 /etc/cron.d 条目，就是这个后果；现在由 verify.sh 的「cron 脚本路径」项守。
#   2. 最新数据库备份的年龄，超过 BACKUP_MAX_AGE_H 告警
#   3. 存储容量预测：按 REAL 相机近 7 天实际日增，推算剩余可写天数
#   4. 告警**同时**写入 fact_anomaly_alert（前端告警页可见）+ journal + 本地文件
#      原脚本只写 journal 和文件 —— 磁盘水位是唯一能提前数小时预警「服务活着但
#      业务全错」的信号，却恰恰是唯一不进告警页的信号。
#
# 去重：本脚本每 30 分钟跑一次。若每次都插一条告警，一天就是 48 条，会把真正
#   需要看的东西挤出页面。所以同类型告警未恢复时**只更新 update_time**。
#
# 安装：
#   sudo install -m 0755 check-disk-usage.sh /usr/local/bin/
#   sudo tee /etc/cron.d/check-disk-usage > /dev/null <<'EOF'
#   */30 * * * * root /main/app/dist/scripts/check-disk-usage.sh
#   EOF
#
# 可配置环境变量：
#   THRESHOLD         水位阈值百分比（默认 85）
#   ALERT_FILE        告警文件路径（默认 /main/app/logs/disk-alert.log）
#   MOUNTS            要检查的分区，空格分隔（默认 "/ /main"）
#   BACKUP_FULL_DIR   备份目录（默认 /main/app/data/backup/db/full）
#   BACKUP_MAX_AGE_H  备份最大年龄（小时，默认 26 = 每日一次 + 余量）
#   CAPACITY_WARN_DAYS  容量预测告警阈值（天，默认 180）
#   CAPACITY_MOUNT      容量预测针对的分区（默认 /data）
#   MYSQL_PASSWORD    数据库口令（不给则自读 /etc/default/camera-detection）
#
# 退出码：0=全部正常；1=有异常（便于 cron / 监控采集）
# ==============================================================================
set -uo pipefail

THRESHOLD="${THRESHOLD:-85}"
ALERT_FILE="${ALERT_FILE:-/main/app/logs/disk-alert.log}"
# ★ 2026-11-04：加入 "/" —— JAR 备份（app/*.bak-*）与发布包都落在 / 分区，
#   而 / 只有 40G（实测备份曾涨到 1.3GB）。原先只巡检 /var 与 /data，
#   / 被撑满时没有任何告警。
MOUNTS="${MOUNTS:-/ /main}"
BACKUP_FULL_DIR="${BACKUP_FULL_DIR:-/main/app/data/backup/db/full}"
BACKUP_MAX_AGE_H="${BACKUP_MAX_AGE_H:-26}"
DB_NAME="${DB_NAME:-wms-new}"
DB_HOST="${DB_HOST:-127.0.0.1}"

# cron 环境没有已导出的变量，靠自己读（640 root:root）
if [[ -z "${MYSQL_PASSWORD:-}" && -r /etc/default/camera-detection ]]; then
  # shellcheck disable=SC1091
  . /etc/default/camera-detection
fi

RC=0

emit() {
  local msg="[$(date '+%F %T')] $*"
  logger -t camera-detection "$msg" 2>/dev/null || true
  echo "$msg" >> "$ALERT_FILE" 2>/dev/null || true
}

# 写告警：同类型未恢复则只更新，避免 30 分钟一条刷屏
#
# ★ 2026-11-07 修复：原实现用 UPDATE ... 然后读 ROW_COUNT() 判断「有没有命中」。
#   这是错的 —— MySQL 的 ROW_COUNT() 返回「实际被修改的行数」，不是「匹配的行数」。
#   同一秒内重复执行时 update_time=NOW() 与原值相同、其余列也没变，MySQL 认为
#   没有行被修改 → ROW_COUNT()=0 → 又会插一条新的。实测连跑两次得到 2 条。
#   现改为先 SELECT 出 alert_id 再精确 UPDATE，与「值是否变化」彻底解耦。
alert_once() {
  local type="$1" level="$2" title="$3" msg="$4" esc_t esc_m existing
  [[ -n "${MYSQL_PASSWORD:-}" ]] || { emit "WARN: 无数据库口令，告警仅落文件：[$type] $title"; return; }
  esc_t=$(printf '%s' "$title" | sed "s/'/''/g")
  esc_m=$(printf '%s' "$msg"   | sed "s/'/''/g")
  existing=$(MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$DB_HOST" -u"${DB_USER:-root}" -N -B -D "$DB_NAME" -e "
      SELECT alert_id FROM fact_anomaly_alert
       WHERE alert_type='$type' AND is_resolved=0
       ORDER BY alert_id DESC LIMIT 1;" 2>/dev/null | tail -1)
  case "${existing:-}" in ''|*[!0-9]*) existing="" ;; esac
  if [[ -n "$existing" ]]; then
    MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$DB_HOST" -u"${DB_USER:-root}" -D "$DB_NAME" -e "
      UPDATE fact_anomaly_alert
         SET update_time=NOW(), alert_message='$esc_m', message='$esc_m', alert_level='$level'
       WHERE alert_id=$existing" >/dev/null 2>&1 \
      || emit "WARN: 告警更新失败：[alert_id=$existing] $title"
  else
    MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$DB_HOST" -u"${DB_USER:-root}" -D "$DB_NAME" -e "
      INSERT INTO fact_anomaly_alert(line_id, workstation_id, detection_id, alert_type, alert_level,
            alert_title, alert_message, message, detect_time, is_resolved, create_time, update_time)
      VALUES(0, 0, NULL, '$type', '$level', '$esc_t', '$esc_m', '$esc_m', NOW(), 0, NOW(), NOW())" \
      >/dev/null 2>&1 || emit "WARN: 告警写入数据库失败：[$type] $title"
  fi
}

# --- 1. 磁盘水位 ----------------------------------------------------------------
for MOUNT in $MOUNTS; do
  [ -d "$MOUNT" ] || continue
  USE=$(df --output=pcent "$MOUNT" 2>/dev/null | tail -1 | tr -d ' %')
  AVAIL=$(df --output=avail "$MOUNT" 2>/dev/null | tail -1 | tr -d ' ')
  TOTAL=$(df --output=size "$MOUNT" 2>/dev/null | tail -1 | tr -d ' ')
  case "$USE" in
    ''|*[!0-9]*) continue ;;
  esac
  if [ "$USE" -gt "$THRESHOLD" ]; then
    MSG="磁盘告警: ${MOUNT} 使用率 ${USE}% 超过阈值 ${THRESHOLD}%（剩余 ${AVAIL} KB / 共 ${TOTAL} KB）"
    emit "$MSG"
    alert_once "DISK_USAGE_HIGH" "HIGH" "磁盘水位超阈值：${MOUNT} ${USE}%" "$MSG"
    RC=1
  fi
done

# --- 2. 备份新鲜度 --------------------------------------------------------------
# 26h = 每日 01:00 + RandomizedDelaySec 300s + 余量。
# 超过这个年龄只有两种可能：备份跑挂了，或者根本没跑 —— 都要人来看。
if [ -d "$BACKUP_FULL_DIR" ]; then
  LATEST=$(ls -1t "$BACKUP_FULL_DIR"/*.sql.zst 2>/dev/null | head -1)
  if [ -z "$LATEST" ]; then
    MSG="备份告警: ${BACKUP_FULL_DIR} 下没有任何 .sql.zst"
    emit "$MSG"
    alert_once "BACKUP_FAILED" "HIGH" "数据库备份缺失：从未成功备份" "$MSG"
    RC=1
  else
    AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$LATEST") ) / 3600 ))
    if [ "$AGE_H" -gt "$BACKUP_MAX_AGE_H" ]; then
      MSG="备份告警: 最新备份 $(basename "$LATEST") 已 ${AGE_H} 小时（阈值 ${BACKUP_MAX_AGE_H}h）"
      emit "$MSG"
      alert_once "BACKUP_FAILED" "HIGH" "数据库备份已过期：${AGE_H} 小时" \
                 "$MSG。请检查 systemctl status camera-detection-backup.timer"
      RC=1
    fi
  fi
fi

# --- 3. 容量预测 ----------------------------------------------------------------
# 为什么百分比不够：THRESHOLD=85% 是「已经快满了」，而扩盘要走采购/停机流程，
#   需要半年以上的提前量。百分比只说「现在多满」，不说「还有多少天」，也不告诉你
#   日增正在变快。相机从 2 台扩到 10 台时日增约涨 5 倍，那时 85% 可能几个月就到。
#
# 为什么必须排除 TEST 相机：TEST 数据由 cleanup-test-cameras.sh 每小时整目录
#   回收，不累积。把它们算进日增会把结论放大一个数量级 —— 本项目实测过：
#   同一份 /data 数据，含 TEST 是 780MB/天（推得「615 天写满、必须扩盘」），
#   只算 REAL 是 128MB/天（推得「730 天约 93GB、余量 4 倍」）。前者是错的。
#
# 分母口径：近 7 天有完整数据的日子（不含今天，今天不满一天会低估日增）。
CAPACITY_WARN_DAYS="${CAPACITY_WARN_DAYS:-180}"
CAPACITY_MOUNT="${CAPACITY_MOUNT:-/main}"
if [[ -n "${MYSQL_PASSWORD:-}" && -d "$CAPACITY_MOUNT" ]]; then
  DAILY_BYTES=$(MYSQL_PWD="$MYSQL_PASSWORD" mysql -h"$DB_HOST" -u"${DB_USER:-root}" -N -B -D "$DB_NAME" -e "
      SELECT IFNULL(ROUND(AVG(day_bytes)), 0) FROM (
        SELECT DATE(f.capture_time) d, SUM(f.file_size) day_bytes
          FROM defect_image_file f
          JOIN dim_camera c ON c.camera_code = f.camera_id
         WHERE c.camera_type = 'REAL'
           AND IFNULL(c.is_deleted, 0) = 0
           AND f.capture_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)
           AND DATE(f.capture_time) < CURDATE()
         GROUP BY DATE(f.capture_time)
      ) t" 2>/dev/null | tail -1)
  AVAIL_KB=$(df --output=avail "$CAPACITY_MOUNT" 2>/dev/null | tail -1 | tr -d ' ')
  case "${DAILY_BYTES:-}" in ''|*[!0-9]*) DAILY_BYTES=0 ;; esac
  case "${AVAIL_KB:-}" in ''|*[!0-9]*) AVAIL_KB=0 ;; esac
  if [ "$DAILY_BYTES" -gt 0 ] && [ "$AVAIL_KB" -gt 0 ]; then
    DAYS_LEFT=$(( AVAIL_KB * 1024 / DAILY_BYTES ))
    if [ "$DAYS_LEFT" -lt "$CAPACITY_WARN_DAYS" ]; then
      MSG="容量告警: ${CAPACITY_MOUNT} 按 REAL 相机近 7 天日增 $(( DAILY_BYTES / 1024 / 1024 )) MB/天 计算，仅可再写 ${DAYS_LEFT} 天（阈值 ${CAPACITY_WARN_DAYS} 天）。请评估扩容，或下调 cleanup-defect-images.sh 的 KEEP_DAYS。"
      emit "$MSG"
      alert_once "DISK_CAPACITY_LOW" "WARNING" "存储容量将在 ${DAYS_LEFT} 天后耗尽" "$MSG"
      RC=1
    fi
  else
    # 查不到日增（新部署、REAL 相机无数据）时不告警，但留痕，便于排查「为什么没预测」
    emit "INFO: 容量预测跳过（REAL 日增=${DAILY_BYTES} 字节，可用=${AVAIL_KB} KB）"
  fi
fi

exit $RC
