#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 应用启动器
#
# 作用：集中解析配置并 exec 启动 JVM。这是 systemd unit 唯一调用的入口，
#       所有可变项都来自 /etc/default/camera-detection，改配置不用改 unit。
#
# 启动参数与 Windows 版服务包装器（CameraDetectionService.cs）保持一致：
#   -Dfile.encoding=UTF-8
#   -Dapp.home=<工作目录>          ← 决定日志、上传图片的落盘位置
#   -jar <jar>
#   --spring.config.additional-location=file:<外部配置>
#
# Java 查找顺序（与 Windows 版一致）：内置 jre → JAVA_HOME → PATH
# ==============================================================================
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/main/app/dist}"
WORK_DIR="${WORK_DIR:-/main/app}"
CONFIG_FILE="${CONFIG_FILE:-${INSTALL_DIR}/config/application.yml}"
JAR_FILE="${JAR_FILE:-${INSTALL_DIR}/app/time-bucket-demo.jar}"
JAVA_HOME="${JAVA_HOME:-}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"
APP_HOME="${APP_HOME:-${WORK_DIR}}"

# 允许用 /etc/default/camera-detection 覆盖上面任何一项
#
# 关键：这里刻意不用「直接 source + set -e」。配置文件里若有一行格式不当
# （例如 JAVA_OPTS 未加引号而含空格），直接 source 会让本脚本因 set -e 立刻退出，
# systemd 只看到 exit 127 而没有任何线索（这正是首次上机踩到的坑）。
# 做法：先在子 shell 里试跑一次，把错误信息打出来；正式 source 时用 || true 兜住；
# 最后再用 ${VAR:-默认} 补齐所有变量，确保坏配置降级为默认值而不是启动失败。
if [[ -r /etc/default/camera-detection ]]; then
  if ! ( set -euo pipefail; . /etc/default/camera-detection ) 2>/tmp/.cds_cfg_err; then
    printf '[run.sh] 警告：/etc/default/camera-detection 格式有误：%s\n' \
      "$(tr '\n' ' ' < /tmp/.cds_cfg_err 2>/dev/null | head -c 300)" >&2
    printf '[run.sh] 已改用内置默认值继续启动。\n' >&2
  fi
  rm -f /tmp/.cds_cfg_err 2>/dev/null || true
  # shellcheck disable=SC1091
  . /etc/default/camera-detection || true
fi

# 二次兜底：配置里没写或写坏了的项，用默认值补上
INSTALL_DIR="${INSTALL_DIR:-/main/app/dist}"
WORK_DIR="${WORK_DIR:-/main/app}"
CONFIG_FILE="${CONFIG_FILE:-${INSTALL_DIR}/config/application.yml}"
JAR_FILE="${JAR_FILE:-${INSTALL_DIR}/app/time-bucket-demo.jar}"
JAVA_HOME="${JAVA_HOME:-}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"
APP_HOME="${APP_HOME:-${WORK_DIR}}"

log() { printf '[run.sh] %s\n' "$*" >&2; }
die() { log "错误：$*"; exit 1; }

[[ -f "$JAR_FILE" ]] || die "未找到 JAR：$JAR_FILE"
[[ -f "$CONFIG_FILE" ]] || die "未找到外部配置：$CONFIG_FILE"

# --- 定位 java --------------------------------------------------------------
resolve_java() {
  local candidate
  if [[ -x "${INSTALL_DIR}/jre/bin/java" ]]; then
    echo "${INSTALL_DIR}/jre/bin/java"; return 0
  fi
  if [[ -n "$JAVA_HOME" ]]; then
    candidate="${JAVA_HOME}/bin/java"
    [[ -x "$candidate" ]] && { echo "$candidate"; return 0; }
  fi
  if command -v java >/dev/null 2>&1; then
    command -v java; return 0
  fi
  return 1
}

JAVA_BIN="$(resolve_java)" || die "未找到 java，请安装 openjdk-8-jre-headless 或设置 JAVA_HOME"

# --- 运行时目录（权限不足会导致启动失败，这里先自愈一次）-------------------
# 统一布局（2026-11）：APP_HOME=/main/app，因此
#   日志   → ${APP_HOME}/logs
#   旧图   → ${APP_HOME}/data/uploads/images（app.upload.image-dir）
#   缺陷图 → ${APP_HOME}/data/images/defect-images（application.yml 的 defect-image.upload.dir）
#   状态   → ${APP_HOME}/run
# 目录由 install.sh 统一建好并 chown 给 cdsys；这里只做兜底。
mkdir -p "${WORK_DIR}/logs" \
         "${WORK_DIR}/data/uploads/images" \
         "${WORK_DIR}/run" \
         "${WORK_DIR}/data/images/defect-images" 2>/dev/null || true

# --- 磁盘水位预检 ------------------------------------------------------------
# 磁盘写满时应用能启动，但图片上传与数据库写入会陆续失败，
# 表现为"服务活着但业务全错"，极难排查。这里在启动前先看一眼。
# 统一布局后数据全在数据盘（/main），系统盘（/）只放程序与依赖 —— 两块都要看。
for MOUNT in / /main; do
  [[ -d "$MOUNT" ]] || continue
  USE=$(df --output=pcent "$MOUNT" 2>/dev/null | tail -1 | tr -d ' %')
  if [[ -n "$USE" && "$USE" -gt 90 ]]; then
    printf '[run.sh] 警告：分区 %s 使用率 %s%%，接近写满；请立即清理或扩容\n' "$MOUNT" "$USE" >&2
  fi
done

# --- 数据库就绪探测 ----------------------------------------------------------
# systemd 的 After=mysql.service 只保证「启动顺序」，不保证「已经就绪」。
# 开机时 MySQL 可能还在初始化，应用抢跑会因连不上库而退出 → 触发重启循环。
# 这里在 exec 之前主动等待，最多 120 秒。
DB_WAIT="${DB_WAIT_SECONDS:-120}"
if [[ "$DB_WAIT" -gt 0 ]] && command -v mysqladmin >/dev/null 2>&1; then
  DB_HOST_="${DB_HOST:-127.0.0.1}"
  DB_USER_="${DB_USER:-root}"
  DB_PASS_="${MYSQL_PASSWORD:-}"
  MYSQLADMIN_ARGS=(-h "$DB_HOST_" -u "$DB_USER_" --protocol=tcp)
  [[ -n "$DB_PASS_" ]] && MYSQLADMIN_ARGS+=("-p${DB_PASS_}")
  log "等待数据库就绪（最多 ${DB_WAIT} 秒）..."
  DB_READY=0
  for _ in $(seq 1 "$DB_WAIT"); do
    if mysqladmin "${MYSQLADMIN_ARGS[@]}" ping >/dev/null 2>&1; then
      DB_READY=1
      break
    fi
    sleep 1
  done
  if [[ "$DB_READY" -eq 1 ]]; then
    log "数据库已就绪"
  else
    # 不直接退出：数据库可能由别的机器提供，或应用本身会重连。
    # 但必须留下明确线索，否则 systemd 只看到 exit 1 而无从判断。
    printf '[run.sh] 警告：等待 %s 秒后数据库仍未就绪，继续启动（应用会自行重试连接）\n' "$DB_WAIT" >&2
  fi
fi

log "安装目录：${INSTALL_DIR}"
log "工作目录：${APP_HOME}"
log "Java：${JAVA_BIN}"
log "JAR：${JAR_FILE}"

cd "$WORK_DIR"

# exec 让 JVM 成为 systemd 追踪的主进程，停止信号才能正确送达
exec "$JAVA_BIN" \
  -Dfile.encoding=UTF-8 \
  -Dapp.home="$APP_HOME" \
  -Djava.security.egd=file:/dev/./urandom \
  $JAVA_OPTS \
  -jar "$JAR_FILE" \
  --spring.config.additional-location="file:${CONFIG_FILE}" \
  "$@"
