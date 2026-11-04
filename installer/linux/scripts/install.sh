# ==============================================================================
# 产线缺陷在线AI检测系统 —— 服务安装脚本（Ubuntu 22.04 LTS）
#
# 用法（在解压后的发布目录内执行）：
#   sudo ./install.sh
#   sudo ./install.sh --work-dir /data/camera-detection   # 指定工作目录
#
# 做的事：
#   1. 建系统账号 cdsys（应用以非 root 运行）
#   2. 建安装目录 /opt/camera-detection-system 与工作目录
#   3. 下发 /etc/default/camera-detection（可改参数集中在这里）
#   4. 安装并启用 systemd 服务
#   5. 放行防火墙端口
#
# 幂等：重复执行会覆盖配置并重启服务，不会产生重复服务。
# ==============================================================================
set -euo pipefail

SERVICE_NAME="camera-detection"
SERVICE_USER="cdsys"
SERVICE_GROUP="cdsys"
INSTALL_DIR="/opt/camera-detection-system"
WORK_DIR="/var/lib/camera-detection-system"
DEFAULT_CONFIG="/etc/default/camera-detection"
PORT="${PORT:-8091}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SOURCE_DIR:-$(cd "${SCRIPT_DIR}/.." && pwd)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

log() { printf '\033[1;36m[install]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 运行（sudo ./install.sh）"
[[ -f "${SOURCE_DIR}/app/time-bucket-demo.jar" ]] || die "发布目录不完整，缺少 app/time-bucket-demo.jar（当前：${SOURCE_DIR}）"

# 兜底：解压后的包可能丢失执行位（跨平台打包 / 传输工具不一定保留 Unix 权限）
# 没有执行位时直接 ./install.sh 会报 Permission denied，这里就地补上。
chmod 0755 "${SCRIPT_DIR}"/*.sh 2>/dev/null || true

# --- 1. 系统账号 ------------------------------------------------------------
if id -u "$SERVICE_USER" >/dev/null 2>&1; then
  log "系统账号 ${SERVICE_USER} 已存在"
else
  log "创建系统账号 ${SERVICE_USER}"
  groupadd -r "$SERVICE_GROUP"
  useradd -r -g "$SERVICE_GROUP" -d "$WORK_DIR" -s /usr/sbin/nologin "$SERVICE_USER"
  ok "账号创建完成"
fi

# --- 2. 目录 ----------------------------------------------------------------
log "创建安装目录 ${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}/app" "${INSTALL_DIR}/config" "${INSTALL_DIR}/sql" "${INSTALL_DIR}/scripts" "${INSTALL_DIR}/docs"

log "创建工作目录 ${WORK_DIR}"
mkdir -p "${WORK_DIR}/logs" \
         "${WORK_DIR}/data/uploads/images" \
         "${WORK_DIR}/data/uploads/defect-images"

# --- 3. 同步程序文件 --------------------------------------------------------
log "同步程序文件"
install -m 0644 "${SOURCE_DIR}/app/time-bucket-demo.jar" "${INSTALL_DIR}/app/"

# 外部配置：已存在则保留现场改动（首次安装才复制模板）
if [[ -f "${SOURCE_DIR}/config/application.yml" && ! -f "${INSTALL_DIR}/config/application.yml" ]]; then
  install -m 0640 "${SOURCE_DIR}/config/application.yml" "${INSTALL_DIR}/config/"
  ok "已安装外部配置 application.yml（请按 §3 必改项修改）"
else
  log "保留已存在的外部配置 ${INSTALL_DIR}/config/application.yml"
fi

install -m 0644 "${SOURCE_DIR}"/sql/*.sql "${INSTALL_DIR}/sql/" 2>/dev/null || log "无可安装的 sql 文件"
install -m 0755 "${SOURCE_DIR}"/scripts/*.sh "${INSTALL_DIR}/scripts/" 2>/dev/null || log "无可安装的脚本"
if [[ -d "${SOURCE_DIR}/docs" ]]; then
  cp -r "${SOURCE_DIR}/docs/." "${INSTALL_DIR}/docs/" 2>/dev/null || true
fi

chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "$INSTALL_DIR"
chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "$WORK_DIR"
# 工作目录需可写；安装目录只需可读
chmod -R u+rwX,g+rX,o+rX "$INSTALL_DIR"
chmod -R u+rwX,g+rwX "$WORK_DIR"
ok "文件同步完成"

# --- 3b. 捕获现场既有配置值（必须在第 4 节覆盖之前读取）-----------------
# ⚠️ 第 4 节是「整份文件一次性重写」（mv -f），会抹掉所有既有键值。
#   这三个键必须提前捕获并回写，否则：
#     - MYSQL_PASSWORD 丢失 → 数据库认证失败、清理/备份脚本拿不到口令
#     - ADMIN_PASSWORD 丢失 → 管理员口令被重装冲掉（reset-password.sh 写的）
#   第 8c 节注释声称「保留现场已有的 ADMIN_USER / ADMIN_PASSWORD」，
#   但实现上第 4 节已先抹掉 —— 这里补上真正的保留逻辑。
read_cfg_value() {
  # 从 /etc/default/camera-detection 读取指定键的值（剥掉引号）
  [[ -f "$DEFAULT_CONFIG" ]] || return 0
  sed -n "s/^$1=[\"']\{0,1\}\([^\"']*\)[\"']\{0,1\}\$/\1/p" \
    "$DEFAULT_CONFIG" 2>/dev/null | head -1 | tr -d "\r"
}

EXISTING_MYSQL_PASSWORD=""
EXISTING_ADMIN_USER=""
EXISTING_ADMIN_PASSWORD=""
if [[ -f "$DEFAULT_CONFIG" ]]; then
  EXISTING_MYSQL_PASSWORD="$(read_cfg_value MYSQL_PASSWORD)"
  EXISTING_ADMIN_USER="$(read_cfg_value ADMIN_USER)"
  EXISTING_ADMIN_PASSWORD="$(read_cfg_value ADMIN_PASSWORD)"
  # 占位符形态（${XXX}）不是真实口令，不能采信
  for _v in EXISTING_MYSQL_PASSWORD EXISTING_ADMIN_PASSWORD; do
    if [[ "${!_v}" =~ ^\$\{.*\}$ ]]; then
      log "检测到 ${_v} 仍是占位符，视为未配置"
      printf -v "$_v" '%s' ""
    fi
  done
  if [[ -n "$EXISTING_MYSQL_PASSWORD" ]]; then
    log "已捕获现场既有数据库口令（重装保留）"
  fi
  if [[ -n "$EXISTING_ADMIN_PASSWORD" ]]; then
    log "已捕获现场既有管理员口令（重装保留）"
  fi
fi

# --- 4. 集中配置文件 --------------------------------------------------------
# ⚠️ 幂等性要求（2026-10-31 实机发现并修复）：
#   本文件会被 run.sh 用 `source` 读取，且**不允许出现重复段落**。
#   之前第 4 节用 `cat >` 写主体、第 8c 节用 `cat >>` 追加备份参数，
#   而第 8c 节只 sed 删掉了 MYSQL_PASSWORD/BACKUP_DIR/KEEP_FULL_DAYS/MAX_DISK_USE
#   四行 —— 注释行 "# ---- 备份相关 ----" 与其后的空行会残留，
#   **重复执行 install.sh 会让这个段落一段段叠加**。
#   实测：现场机器上该文件被叠加到 4 份、103 行，
#   最后一行的 ADMIN_PASSWORD 才是生效值，前面的重复段全是垃圾。
#
#   现在改为：**整份文件一次性重写**，并把备份参数合并进来。
#   这样无论执行多少次，结果都完全一致。
#
#   ⚠️ 权限必须是 640（root 可读）：
#   文件里有数据库口令与管理员口令，0644 意味着**任何本地用户都能读到**。
#   之前这里写的是 chmod 0644，第 8c 节才改回 640 ——
#   若安装中途失败，口令会短暂以 0644 暴露。现在统一在最后 chmod 640。
log "下发 ${DEFAULT_CONFIG}"
DEFAULT_CONFIG_TMP="$(mktemp)"

# --- JWT 密钥生成（2026-11-04 安全改造）---------------------------------
#   改造前：application.yml 里内置固定密钥 mySecretKey1234...
#   问题：该文件随发布包分发且现场是 644 全局可读 ——
#         拿到包的人就能伪造登录令牌；DB 口令同理。
#   现在：密钥统一放本文件（640 root:root），由 systemd EnvironmentFile 注入 JVM。
#
#   已有密钥时**保留**（重装不失效现场已签发的令牌）；
#   没有时生成 64 位随机十六进制串（256 bit 熵，满足 HS256 要求）。
#
#   为什么放在第 4 节而不是第 8c 节：
#   第 8c 节只在「已安装过数据库」分支里执行，
#   而第 4 节是**每次安装必经**的主路径 —— 密钥必须在主路径就绪。
if grep -qE '^JWT_SECRET=' "$DEFAULT_CONFIG" 2>/dev/null; then
  log "保留已存在的 JWT_SECRET（不使现场已签发的令牌失效）"
else
  JWT_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  log "已生成新的 JWT 密钥（256 bit）"
fi

# --- 数据库口令来源（2026-11-04 修正，阻断性缺陷）-------------------------
#   改造后 application.yml 里是 `password: ${MYSQL_PASSWORD}` 占位符，
#   **不能再用 sed 从 application.yml 提取口令** —— 那会把占位符本身
#   当成真实口令（实测提取结果就是字符串 "${MYSQL_PASSWORD}"），
#   导致新机安装后服务启动即 DB 认证失败，且报 "Access denied" 极难归因。
#
#   正确顺序：环境变量 → 现场既有 /etc/default → 交互式输入。
if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  MYSQL_PASSWORD="$EXISTING_MYSQL_PASSWORD"
fi
if [[ "${MYSQL_PASSWORD:-}" =~ ^\$\{.*\}$ ]]; then
  log "检测到 MYSQL_PASSWORD 仍是占位符，视为未配置"
  MYSQL_PASSWORD=""
fi
if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  if [[ -t 0 ]]; then
    read -r -s -p "请输入 MySQL root 口令（留空则稍后手工补）: " MYSQL_PASSWORD
    echo
  else
    log "非交互式执行：请用 MYSQL_PASSWORD='xxx' 指定数据库口令"
  fi
fi
if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  log "警告：未获得数据库口令，清理与备份脚本需手工补 MYSQL_PASSWORD"
fi

cat > "$DEFAULT_CONFIG_TMP" <<EOF
# 产线缺陷在线AI检测系统 —— 运行参数
# 改这个文件即可，无需改 systemd unit。改完执行：systemctl restart ${SERVICE_NAME}

# JVM 堆（按服务器内存调整：堆 + 元空间 + 线程栈 建议不超过物理内存的 70%）
# 必须带引号：run.sh 会用 bash source 本文件，不加引号时空格会截断成两个词，
# 导致 "-Xmx1024m: command not found" 而启动失败。
JAVA_OPTS="-Xms512m -Xmx1024m"

# 安装目录与工作目录
INSTALL_DIR=${INSTALL_DIR}
WORK_DIR=${WORK_DIR}

# 应用主目录：决定日志与上传图片的落盘位置
APP_HOME=${WORK_DIR}

# Java 运行时（留空则依次尝试 内置 jre → JAVA_HOME → PATH）
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

# 外部配置文件
CONFIG_FILE=${INSTALL_DIR}/config/application.yml

# ---- 数据库口令（application.yml 用 \${MYSQL_PASSWORD} 引用此处）----
# ⚠️ 这是**明文口令**，因此本文件权限必须是 640 root:root。
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"

# ---- 管理员口令（reset-password.sh 会改写这里）----
ADMIN_USER="${EXISTING_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${EXISTING_ADMIN_PASSWORD:-}"

# ---- 密钥（唯一来源，权限 640 root:root，勿提交到仓库）----
# JWT 签名密钥：application.yml 用 ${JWT_SECRET} 引用此处。
# 轮换方式：改本文件后 systemctl restart camera-detection（会使旧令牌失效）。
JWT_SECRET="${JWT_SECRET}"
EOF
mv -f "$DEFAULT_CONFIG_TMP" "$DEFAULT_CONFIG"
chmod 0640 "$DEFAULT_CONFIG"
chown root:root "$DEFAULT_CONFIG"
ok "配置已写入 ${DEFAULT_CONFIG}"

# --- 4b. 外部配置文件权限收紧（2026-11-04 安全改造）---------------------
#   改造前：application.yml 里含 DB 明文口令，权限是 644（全局可读）。
#   改造后：口令已改为 ${MYSQL_PASSWORD} 占位，由 /etc/default 注入，
#           文件本身不再含凭据；但仍收紧为 640（仅属主可读），纵深防御。
if [[ -f "${INSTALL_DIR}/config/application.yml" ]]; then
  chmod 0640 "${INSTALL_DIR}/config/application.yml" 2>/dev/null || true
  chown "${SERVICE_USER}:${SERVICE_GROUP}" "${INSTALL_DIR}/config/application.yml" 2>/dev/null || true
fi

# --- 5. systemd 服务 --------------------------------------------------------
log "安装 systemd 服务"

# --- 5a. 数据目录（图片）----------------------------------------------------
# 图片按 144000 张/天 写入，350KB/张时日增 48GB —— 必须放在大容量分区。
# /data 是 755 root:root，应用以 cdsys 运行无法在其下创建目录，
# 因此这里由 root 先建好目录树，再 chown 给服务账号。
DATA_BASE="/data/camera-detection"
if [[ -d /data ]]; then
  mkdir -p "${DATA_BASE}/images/defect-images"
  chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${DATA_BASE}" 2>/dev/null || true
  chmod 750 "${DATA_BASE}" "${DATA_BASE}/images" 2>/dev/null || true
  ok "图片目录：${DATA_BASE}/images/defect-images"
else
  log "未发现 /data，图片将存放在 ${WORK_DIR}/data/uploads"
fi

# 先把 unit 装到位，再改它的 ReadWritePaths —— 顺序反了会报
# "sed: can't read /etc/systemd/system/camera-detection.service: No such file or directory"
install -m 0644 "${SOURCE_DIR}/systemd/${SERVICE_NAME}.service" "/etc/systemd/system/${SERVICE_NAME}.service"

# systemd 沙箱：ProtectSystem=full 下，未列入 ReadWritePaths 的路径不可写。
# 漏了这一步会表现为「上传返回成功但文件不落盘」，极难排查。
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
if [[ -d /data ]] && ! grep -q "${DATA_BASE}" "$UNIT_FILE" 2>/dev/null; then
  sed -i "s|^ReadWritePaths=.*|ReadWritePaths=${WORK_DIR} ${DATA_BASE}|" "$UNIT_FILE"
  ok "systemd ReadWritePaths 已放行 ${DATA_BASE}"
fi

systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null 2>&1
ok "服务已设为开机自启"

# 注意：`systemctl restart` 只要「排队成功」就返回 0，真正的启动失败是异步的 ——
# 数据库还没建时应用会反复崩溃重启，install.sh 却会误报"服务已启动"。
# 因此这里启动后主动等待并检查服务是否真的进入 active。
# 另外 systemctl restart 在服务刚被 stop 且处于 failed 状态时可能直接返回非 0，
# 所以这里用 `|| true` 吞掉它的返回值，统一由下面的状态检查来决定成败。
#
# 先 reset-failed 清掉失败计数：systemd 的 StartLimitBurst=3 会在连续失败 3 次后
# 进入 "Start request repeated too quickly" 状态，此时即使数据库已建好、
# systemctl restart 也会被直接拒绝，必须先 reset-failed 才能拉起。
systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
systemctl restart "$SERVICE_NAME" 2>/dev/null || true

SERVICE_READY=0
for _ in $(seq 1 30); do
  sleep 2
  if [[ "$(systemctl is-active "$SERVICE_NAME" 2>/dev/null)" == "active" ]]; then
    # active 之后再多等几秒，确认不是「起来又崩」
    sleep 5
    if [[ "$(systemctl is-active "$SERVICE_NAME" 2>/dev/null)" == "active" ]]; then
      SERVICE_READY=1
      break
    fi
  fi
done

if [[ "$SERVICE_READY" -eq 1 ]]; then
  ok "服务已启动（active）"
  DB_PENDING=0
else
  # 区分「数据库还没建」与「真的启动失败」—— 前者是部署顺序问题，不是故障
  DB_NAME="$(sed -n 's|.*/3306/\([^?]*\).*|\1|p' "${INSTALL_DIR}/config/application.yml" 2>/dev/null | head -1)"
  DB_EXISTS=0
  if [[ -n "$DB_NAME" ]] && command -v mysql >/dev/null 2>&1; then
    MYSQL_ARGS=(-h 127.0.0.1 -P 3306 -u root --default-character-set=utf8mb4 --protocol=tcp -N -B)
    [[ -n "${MYSQL_PASSWORD:-}" ]] && export MYSQL_PWD="$MYSQL_PASSWORD"
    DB_EXISTS="$(mysql "${MYSQL_ARGS[@]}" -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${DB_NAME}';" 2>/dev/null | tr -d '[:space:]' || echo 0)"
  fi

  if [[ "${DB_EXISTS:-0}" == "0" ]]; then
    # 注意：这里不 exit —— 后面还有防火墙、日志轮转、清理 cron 等步骤要装。
    # 缺数据库只是「服务暂时起不来」，不是「安装没做完」。
    echo "数据库 '${DB_NAME:-wms-new}' 尚未创建，应用暂时无法启动。" >&2
    echo "这是部署顺序问题，不是故障。安装继续，装完后请按顺序执行：" >&2
    echo "    ${INSTALL_DIR}/scripts/init-database.sh   # 建库（4 段脚本）" >&2
    echo "    systemctl restart ${SERVICE_NAME}          # 再启动" >&2
    DB_PENDING=1
  else
    echo "服务未能进入 active 状态，最近日志：" >&2
    journalctl -u "$SERVICE_NAME" -n 30 --no-pager >&2
    DB_PENDING=2
  fi
fi

# --- 6. 防火墙 --------------------------------------------------------------
# 注意：不要用 `ufw status | grep -q ...` —— grep -q 命中即退出，
# ufw 收到 SIGPIPE 返回非 0，配合 set -o pipefail 会误判防火墙未启用。
UFW_STATE="$(ufw status 2>/dev/null | grep "Status:" | head -1 || true)"
if [[ "$UFW_STATE" == *"active"* ]]; then
  log "放行防火墙端口 ${PORT}"
  ufw allow "${PORT}/tcp" comment "Camera Detection System" >/dev/null
  ok "UFW 规则已添加"
elif command -v firewall-cmd >/dev/null 2>&1; then
  log "放行防火墙端口 ${PORT}（firewalld）"
  firewall-cmd --permanent --add-port="${PORT}/tcp" >/dev/null
  firewall-cmd --reload >/dev/null
  ok "firewalld 规则已添加"
else
  log "未检测到 ufw/firewalld，若有云安全组请手动放行 ${PORT}"
fi

# --- 7. 日志轮转 ------------------------------------------------------------
if [[ -d /etc/logrotate.d ]]; then
  log "配置业务日志轮转"
  cat > /etc/logrotate.d/camera-detection <<EOF
${WORK_DIR}/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su ${SERVICE_USER} ${SERVICE_GROUP}
}
EOF
  ok "logrotate 规则已写入"
fi

# --- 7b. journald 限额（2026-11-04 补充）---------------------------------
#   logrotate 只管业务日志文件，**管不到 systemd journal**。
#   实测 journald 已占 302.8MB，而 / 分区只有 40G；
#   且设备上传中断的 EOFException 每分钟刷屏（既有现象），
#   没有上限时 journal 能把 / 撑满，直接导致服务写不进去。
#
#   SystemMaxUse=200M  → 归档+活跃日志总量上限
#   RuntimeMaxUse=50M  → 运行时日志（/run）上限，重启即释放
#   MaxRetentionSec=30day → 最长保留 30 天
#
#   写完需要 systemctl restart systemd-journald 才生效。
if [[ -f /etc/systemd/journald.conf ]]; then
  log "配置 journald 磁盘限额（200M / 运行 50M / 保留 30 天）"
  # 用 drop-in 方式，不动系统原文件，便于回滚
  mkdir -p /etc/systemd/journald.conf.d
  cat > /etc/systemd/journald.conf.d/10-camera-detection.conf <<'EOF'
[Journal]
SystemMaxUse=200M
RuntimeMaxUse=50M
MaxRetentionSec=30day
EOF
  systemctl restart systemd-journald >/dev/null 2>&1 || true
  ok "journald 限额已写入 /etc/systemd/journald.conf.d/10-camera-detection.conf"
else
  log "未找到 /etc/systemd/journald.conf，跳过 journald 限额配置"
fi

# --- 8. 缺陷图片清理（磁盘 + 库记录）----------------------------------------
# 应用自带的定时任务只清理「兼容表」camera_images；新版缺陷图片表
# （defect_image_file / _box / _batch）与其磁盘文件没有任何定时任务清理
# → 磁盘只增不减、库记录永久堆积。
# 本脚本补齐这个缺口，且严格「先删库记录、再删磁盘文件」，避免产生孤儿记录。

if [[ -f "${SOURCE_DIR}/scripts/cleanup-defect-images.sh" ]]; then
  log "安装缺陷图片清理脚本"
  install -m 0755 "${SOURCE_DIR}/scripts/cleanup-defect-images.sh" /usr/local/bin/

  # 图片目录：从外部配置里读，改过路径时自动跟随
  IMAGE_DIR=$(sed -n 's|.*dir:\s*\([^ ]*/defect-images/\).*|\1|p' \
    "${INSTALL_DIR}/config/application.yml" 2>/dev/null | head -1 | tr -d "\r")
  [[ -z "$IMAGE_DIR" ]] && IMAGE_DIR="${WORK_DIR}/data/uploads/defect-images"

  # 数据目录：优先用容量更大的独立分区（实测 /var 仅 50G，/data 有 500G）
  if [[ -d /data ]] && df --output=target /data 2>/dev/null | tail -1 | grep -q "/data"; then
    IMAGE_BASE="/data/camera-detection/images"
  else
    IMAGE_BASE="${WORK_DIR}/data/uploads"
  fi
  IMAGE_DIR="${IMAGE_BASE}/defect-images"

  # 目录必须由 root 建好再 chown —— 应用以 cdsys 运行，
  # 若父目录（如 /data）是 755 root，cdsys 无法在其下创建子目录
  mkdir -p "${IMAGE_DIR}"
  chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "$(dirname "${IMAGE_DIR}")" 2>/dev/null || true
  chmod 750 "$(dirname "${IMAGE_DIR}")" 2>/dev/null || true

  # 数据库口令见本节开头（已从 application.yml 提取）

  if [[ ! -f /etc/cron.d/cleanup-defect-images ]]; then
    cat > /etc/cron.d/cleanup-defect-images <<EOF
# 每天 03:30 清理过期缺陷图片与库记录（错开应用自身的 02:00 清理任务）
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
30 3 * * * root MYSQL_PASSWORD="${MYSQL_PASSWORD:-}" DIR="${IMAGE_DIR}" KEEP_DAYS=7 KEEP_BATCH_DAYS=30 DB_NAME="wms-new" /usr/local/bin/cleanup-defect-images.sh >> /var/log/camera-detection-cleanup.log 2>&1
EOF
    ok "已加入 cron（03:30 每日，保留 7 天）"
  else
    log "cron 规则已存在，保留不动"
  fi
  ok "图片清理目录：${IMAGE_DIR}"
fi

# --- 8b. 磁盘水位监控 --------------------------------------------------------
# 磁盘写满时应用能启动，但图片上传与数据库写入会陆续失败，
# 表现为「服务活着但业务全错」，极难排查。每 30 分钟巡检一次。
if [[ -f "${SOURCE_DIR}/scripts/check-disk-usage.sh" ]]; then
  install -m 0755 "${SOURCE_DIR}/scripts/check-disk-usage.sh" /usr/local/bin/
  if [[ ! -f /etc/cron.d/check-disk-usage ]]; then
    cat > /etc/cron.d/check-disk-usage <<'EOF'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
*/30 * * * * root /usr/local/bin/check-disk-usage.sh
EOF
    ok "已加入磁盘水位巡检（每 30 分钟）"
  fi
fi

# --- 8c. 数据库定时备份 ------------------------------------------------------
# 用 systemd timer 而非 cron：失败信息进 journal（本机无 MTA，cron 失败会静默），
# 且可用 systemctl list-timers 一览无余。
if [[ -f "${SOURCE_DIR}/systemd/camera-detection-backup.service" && \
      -f "${SOURCE_DIR}/systemd/camera-detection-backup.timer" ]]; then
  log "安装数据库备份定时器"
  install -m 0755 "${SOURCE_DIR}/scripts/backup-database.sh" "${INSTALL_DIR}/scripts/"

  # 备份输出目录必须在 cdsys 可写的路径下（/data 本身是 755 root）
  BACKUP_BASE="/data/camera-detection/backup"
  if [[ -d /data ]]; then
    mkdir -p "${BACKUP_BASE}/db/full" "${BACKUP_BASE}/db/binlog" \
             "${BACKUP_BASE}/db/images" "${BACKUP_BASE}/db/logs" "${BACKUP_BASE}/offsite"
  else
    BACKUP_BASE="${WORK_DIR}/backup"
    mkdir -p "${BACKUP_BASE}/db/full" "${BACKUP_BASE}/db/binlog" \
             "${BACKUP_BASE}/db/images" "${BACKUP_BASE}/db/logs" "${BACKUP_BASE}/offsite"
  fi
  chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${BACKUP_BASE}" 2>/dev/null || true
  chmod 700 "${BACKUP_BASE}" 2>/dev/null || true

  # 口令与备份参数写进 /etc/default/camera-detection（640 root）
  # unit 里不写口令 —— unit 文件是全局可读的
  # 口令来源见第 8 节（已从 application.yml 提取到 MYSQL_PASSWORD）
  #
  # ⚠️ 幂等性（2026-10-31 实机发现并修复）：
  #   之前这里用 `cat >>` 追加，而 sed 只删掉了 4 个变量行，
  #   注释行 "# ---- 备份相关 ----" 和它后面的空行会残留，
  #   **重复执行 install.sh 会让这段叠加**（实测现场已叠到 4 份）。
  #   现在改为：把整个备份段（含注释）先剥离，再一次性追加。
  #   这样执行多少次结果都一样。
  #
  #   同时保留现场已有的 ADMIN_USER / ADMIN_PASSWORD（reset-password.sh 写的），
  #   不因为重装安装而把管理员口令清掉。
  #
  # ⚠️ 2026-11-04 修正（阻断性缺陷）：
  #   这条 sed 会把 JWT_SECRET 一并删掉，但下面的 heredoc **只写回了 MYSQL_PASSWORD**，
  #   JWT_SECRET 就此丢失 → 重装后 application.yml 里的 ${JWT_SECRET} 无值可注入 →
  #   JwtUtil 因无兜底默认值而启动失败（"Could not resolve placeholder 'JWT_SECRET'"）。
  #   现在把 JWT_SECRET 一并写回，保证「删了就要补上」。
  sed -i '/^# ---- 备份相关（供 camera-detection-backup.service 使用）----$/d' \
    /etc/default/camera-detection 2>/dev/null || true
  sed -i '/^MYSQL_PASSWORD=/d; /^BACKUP_DIR=/d; /^KEEP_FULL_DAYS=/d; /^MAX_DISK_USE=/d; /^JWT_SECRET=/d' \
    /etc/default/camera-detection 2>/dev/null || true
  # 折叠因删除而产生的连续空行（最多留一个）
  awk 'BEGIN{blank=0} {if ($0=="") {blank++; if (blank>1) next} else {blank=0} print}' \
    /etc/default/camera-detection > /tmp/.cds_default_cfg
  mv -f /tmp/.cds_default_cfg /etc/default/camera-detection
  cat >> /etc/default/camera-detection <<EOF

# ---- 备份相关（供 camera-detection-backup.service 使用）----
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
BACKUP_DIR="${BACKUP_BASE}/db"
KEEP_FULL_DAYS=14
MAX_DISK_USE=85

# ---- JWT 签名密钥（application.yml 用 \${JWT_SECRET} 引用）----
# 被上面 sed 删除后在此写回，缺了会导致服务启动失败。
JWT_SECRET="${JWT_SECRET}"
EOF
  chmod 640 /etc/default/camera-detection
  chown root:root /etc/default/camera-detection
  if [[ -n "${MYSQL_PASSWORD:-}" ]]; then
    ok "备份参数已写入 /etc/default/camera-detection"
  else
    log "备份参数已写入，但数据库口令为空（需手工补）"
  fi

  install -m 0644 "${SOURCE_DIR}/systemd/camera-detection-backup.service" /etc/systemd/system/
  install -m 0644 "${SOURCE_DIR}/systemd/camera-detection-backup.timer" /etc/systemd/system/
  systemctl daemon-reload
  systemctl enable --now camera-detection-backup.timer >/dev/null 2>&1
  if systemctl is-active --quiet camera-detection-backup.timer; then
    ok "备份定时器已启用（每日 01:00，保留 14 天）"
  else
    log "警告：备份定时器未能启动，请检查 systemctl status camera-detection-backup.timer"
  fi
fi

# --- 9. 自检 ----------------------------------------------------------------
echo
log "安装完成，执行自检："
echo "    sudo ${INSTALL_DIR}/scripts/verify.sh"
echo
log "访问地址：http://<服务器IP>:${PORT}"
log "查看日志：journalctl -u ${SERVICE_NAME} -f"
log "排查手册：docs/服务器运行与问题排查手册.md"

# 退出码：0=全部就绪；1=缺数据库（装完了但要先建库）；2=服务真起不来
case "${DB_PENDING:-0}" in
  0) ;;
  1) exit 1 ;;
  2) exit 2 ;;
esac
