# ==============================================================================
# 产线缺陷在线AI检测系统 —— 服务安装脚本（Ubuntu 22.04 / 24.04 LTS）
#
# 用法（在解压后的发布目录内执行）：
#   sudo ./install.sh
#   sudo ./install.sh --base /main/app        # 指定统一根目录（默认 /main/app）
#
# 统一布局（2026-11 改造：所有产品文件收敛到一个根，便于查找与跟踪）：
#   /main/app/dist     程序（jar / config / sql / scripts / docs）
#   /main/app/data     业务数据（mysql / images / uploads / backup）
#   /main/app/logs     全部日志
#   /main/app/run      状态与锁
#   /main/app/tmp      临时文件
#   ★ /main 必须是**数据盘挂载点**。脚本会校验；未挂载直接报错退出 ——
#     否则数据会静默落到系统盘的空目录里，表现为「跑得好好的，重启后数据全没了」。
#
# 做的事：
#   1. 校验 /main 为独立数据盘挂载点
#   2. 建系统账号 cdsys（应用以非 root 运行）与统一目录树
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

# ---- 统一布局根（唯一权威定义点：本文件的 heredoc 写进 /etc/default，其余脚本继承）----
BASE="${BASE:-/main/app}"
BASE_MOUNT="${BASE_MOUNT:-$(dirname "${BASE}")}"     # 数据盘挂载点（/main）
INSTALL_DIR="${INSTALL_DIR:-${BASE}/dist}"
WORK_DIR="${WORK_DIR:-${BASE}}"
DATA_BASE="${DATA_BASE:-${BASE}/data}"
LOG_BASE="${LOG_BASE:-${BASE}/logs}"
RUN_BASE="${RUN_BASE:-${BASE}/run}"
BACKUP_BASE="${BACKUP_BASE:-${DATA_BASE}/backup}"
OFFSITE_BASE="${OFFSITE_BASE:-/var/backups/camera-detection}"   # 跨盘副本：系统盘
IMAGES_SRC="${IMAGES_SRC:-${DATA_BASE}/images/defect-images}"
IMAGE_TRASH="${IMAGE_TRASH:-${DATA_BASE}/images/.cleanup-trash}"
MYSQL_DATA_DIR="${MYSQL_DATA_DIR:-${DATA_BASE}/mysql}"

DEFAULT_CONFIG="/etc/default/camera-detection"
PORT="${PORT:-8091}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SOURCE_DIR:-$(cd "${SCRIPT_DIR}/.." && pwd)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE="$(readlink -m "$2")"; INSTALL_DIR="${BASE}/dist"; WORK_DIR="$BASE"
            DATA_BASE="${BASE}/data"; LOG_BASE="${BASE}/logs"; RUN_BASE="${BASE}/run"
            BACKUP_BASE="${DATA_BASE}/backup"; IMAGES_SRC="${DATA_BASE}/images/defect-images"
            IMAGE_TRASH="${DATA_BASE}/images/.cleanup-trash"; MYSQL_DATA_DIR="${DATA_BASE}/mysql"
            shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

log() { printf '\033[1;36m[install]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 运行（sudo ./install.sh）"
[[ -f "${SOURCE_DIR}/app/time-bucket-demo.jar" ]] || die "发布目录不完整，缺少 app/time-bucket-demo.jar（当前：${SOURCE_DIR}）"

# --- 0. 数据盘挂载断言（2026-11 统一布局后新增）----------------------------
#   /main 必须是**独立设备**的挂载点。原因：
#     盘没挂上时 /main 只是系统盘上的普通目录，程序、图片、数据库会静默写进系统盘，
#     盘修好后一挂载就被"盖住"，表现为「数据全丢」——却没有任何报错。
#   因此这里宁可拒绝安装，也不做任何"自动降级"。
require_main_mount() {
  local mnt base_src root_src
  command -v findmnt >/dev/null 2>&1 || { log "未找到 findmnt，跳过挂载校验"; return 0; }

  # /main 可能尚未创建 —— 此时 findmnt --target 会失败，正好作为「未挂载」判据
  mnt="$(dirname "$BASE")"
  base_src="$(findmnt -n -o SOURCE --target "$mnt" 2>/dev/null || true)"
  root_src="$(findmnt -n -o SOURCE --target / 2>/dev/null || true)"

  if [[ -z "$base_src" || -z "$root_src" ]]; then
    die "无法确定 ${mnt} 所在设备 —— 该目录不存在或未挂载数据盘。
     请先挂载数据盘，例如：
       sudo mkdir -p ${mnt}
       echo 'UUID=<数据盘UUID> ${mnt} ext4 defaults 0 2' | sudo tee -a /etc/fstab
       sudo mount ${mnt}
     然后重跑本脚本。"
  fi
  if [[ "$base_src" == "$root_src" ]]; then
    die "${BASE} 仍在系统盘（设备 ${root_src}）—— 数据盘没有挂载到 ${mnt}。
     请先挂载数据盘，例如：
       sudo mkdir -p ${mnt}
       echo 'UUID=<数据盘UUID> ${mnt} ext4 defaults 0 2' | sudo tee -a /etc/fstab
       sudo mount ${mnt}
     然后重跑本脚本。"
  fi
  ok "统一根 ${BASE}：设备 ${base_src}，与系统盘 ${root_src} 不同 ✓"
}
log "校验数据盘挂载点"
require_main_mount

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

# --- 2. 目录（统一根下的完整树）---------------------------------------------
log "创建统一目录树 ${BASE}"
mkdir -p "${BASE}" \
         "${INSTALL_DIR}/app" "${INSTALL_DIR}/config" "${INSTALL_DIR}/sql" "${INSTALL_DIR}/scripts" "${INSTALL_DIR}/docs" \
         "${IMAGES_SRC}" "${IMAGE_TRASH}" \
         "${DATA_BASE}/uploads/images" \
         "${DATA_BASE}/backup/db/full" "${DATA_BASE}/backup/db/binlog" "${DATA_BASE}/backup/db/logs" \
         "${LOG_BASE}" "${RUN_BASE}" "${BASE}/tmp" "${BASE}/backup"
ok "布局：dist(程序) / data(数据) / logs / run / tmp / backup"

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

# ★ MySQL datadir 必须归还 mysql:mysql 700 —— 上面的 chown -R 会把它改成 cdsys，
#   MySQL 见到属主不对会直接拒绝启动（且报错只含糊地说 datadir 不可用）。
if [[ -d "$MYSQL_DATA_DIR" ]]; then
  chown -R mysql:mysql "$MYSQL_DATA_DIR" 2>/dev/null || true
  chmod 700 "$MYSQL_DATA_DIR" 2>/dev/null || true
fi
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

# ---- 统一布局（2026-11：全部产品文件收敛到 ${BASE} 一个根）----
# 改这里等于整体搬迁；改完 systemctl restart camera-detection（并同步 unit 的 ReadWritePaths）
BASE=${BASE}
INSTALL_DIR=${INSTALL_DIR}
WORK_DIR=${WORK_DIR}
DATA_BASE=${DATA_BASE}
LOG_BASE=${LOG_BASE}
RUN_BASE=${RUN_BASE}
IMAGES_SRC=${IMAGES_SRC}
IMAGE_TRASH=${IMAGE_TRASH}
MYSQL_DATA_DIR=${MYSQL_DATA_DIR}
OFFSITE_BASE=${OFFSITE_BASE}

# 应用主目录：决定日志与上传图片的落盘位置（${app.home}/logs、${app.home}/data/uploads）
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

# --- 5a. 图片目录（统一根下，落在数据盘）------------------------------------
# ★ 目录名 defect-images **不可改**：Java 读图白名单按 "/defect-images/" 过滤
#   （DefectImageServiceImpl），改名会导致所有读图请求 403。
# ★ .cleanup-trash 必须与图片同一文件系统：清理用「先 mv 到中转区再 rm」保证原子性。
mkdir -p "${IMAGES_SRC}" "${IMAGE_TRASH}"
chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${DATA_BASE}/images" 2>/dev/null || true
chmod 750 "${DATA_BASE}" "${DATA_BASE}/images" 2>/dev/null || true
ok "图片目录：${IMAGES_SRC}（删除中转区 ${IMAGE_TRASH}）"

# 先把 unit 装到位，再改它的路径 —— 顺序反了会报
# "sed: can't read /etc/systemd/system/camera-detection.service: No such file or directory"
install -m 0644 "${SOURCE_DIR}/systemd/${SERVICE_NAME}.service" "/etc/systemd/system/${SERVICE_NAME}.service"

# unit 的路径按 BASE 校正（默认值已与 /main/app 一致；--base 覆盖时才需要改）
# systemd 沙箱：ProtectSystem=full 下，未列入 ReadWritePaths 的路径不可写。
# 漏了这一步会表现为「上传返回成功但文件不落盘」，极难排查。
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
if ! grep -qx "ReadWritePaths=${BASE}" "$UNIT_FILE" 2>/dev/null; then
  sed -i "s|^WorkingDirectory=.*|WorkingDirectory=${BASE}|" "$UNIT_FILE"
  sed -i "s|^ExecStart=.*|ExecStart=${INSTALL_DIR}/scripts/run.sh|" "$UNIT_FILE"
  sed -i "s|^ReadWritePaths=.*|ReadWritePaths=${BASE}|" "$UNIT_FILE"
  sed -i "s|^RequiresMountsFor=.*|RequiresMountsFor=$(dirname "$BASE")|" "$UNIT_FILE"
  ok "unit 路径已按 BASE=${BASE} 校正"
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
    echo "    ${INSTALL_DIR}/scripts/init-database.sh   # 建库（5 段脚本，脚本会自动按序导入）" >&2
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
  log "配置日志轮转"
  cat > /etc/logrotate.d/camera-detection <<EOF
# 统一布局：全部产品日志集中在 ${LOG_BASE}
${LOG_BASE}/camera-detection-system.log
${LOG_BASE}/backup.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su ${SERVICE_USER} ${SERVICE_GROUP}
}

# 部署 / 清理 / 巡检日志（root 所有）
# cleanup_zk54.log 每小时追加、约 300KB/月，长期不轮转会缓慢吃掉数据盘。
${LOG_BASE}/deploy.log
${LOG_BASE}/cleanup-defect-images.log
${LOG_BASE}/cleanup_zk54.log
${LOG_BASE}/disk-alert.log {
    weekly
    rotate 8
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su root root
}
EOF
  ok "logrotate 规则已写入（业务/备份 + 部署/清理/巡检，均指向 ${LOG_BASE}）"
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
  log "安装缺陷图片清理任务"

  # 2026-11 修正（老 bug）：原实现先按 application.yml 解析出图片目录，
  # 随后又被「若 /data 存在则用它」无条件覆盖 —— 解析白做，改 yml 根本不生效。
  # 现在统一由统一布局变量推导，application.yml 与该目录的一致性由 verify.sh 校验。
  IMAGE_DIR="${IMAGES_SRC}"
  mkdir -p "${IMAGE_DIR}"
  chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${DATA_BASE}/images" 2>/dev/null || true
  chmod 750 "${DATA_BASE}/images" 2>/dev/null || true

  # cron 是生成物：路径随布局变化，每次都重写（重复执行结果一致）
  cat > /etc/cron.d/cleanup-defect-images <<EOF
# 每天 03:30 清理过期缺陷图片磁盘文件（错开应用 02:00 清理与 02:30 归档任务）
# ★ 保留期必须与图片归档月表口径一致（application.yml app.archive.images.retain-days，默认 730 天）：
#   两边不同步会出现「库里有记录、磁盘没文件」的裂图。改这里之前先改 application.yml。
# ★ 口令不写进 cron（cron 全局可读）：脚本自己从 /etc/default/camera-detection 读取。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
30 3 * * * root DIR="${IMAGE_DIR}" KEEP_DAYS=730 KEEP_CAMERA_IMAGES_DAYS=30 DB_NAME="wms-new" ${INSTALL_DIR}/scripts/cleanup-defect-images.sh >> ${LOG_BASE}/cleanup-defect-images.log 2>&1
EOF
  ok "已加入 cron（03:30 每日，磁盘文件保留 730 天，与归档月表口径一致）"
  ok "图片清理目录：${IMAGE_DIR}"
fi

# --- 8a-2. 测试相机增量清理（每小时 :15） ------------------------------------
# 2026-11-05 新增：把「哪些相机的数据可以清」从脚本硬编码白名单改为
# 数据库字段 camera_type（REAL=真实产线相机不清理 / TEST=测试相机参与清理）。
#
# ★ 口令来源：/etc/default/camera-detection（640 root:root），**不写进 cron 文件**。
#   cron 文件全局可读，把口令写进去等于把库口令公开给所有本地用户。
#   脚本自己 source 该文件取口令。
if [[ -f "${SOURCE_DIR}/scripts/cleanup-zk54-incremental.sh" ]]; then
  log "安装测试相机增量清理任务"

  # 脚本自读口令：/etc/default/camera-detection → MYSQL_PASSWORD
  if [[ -f /etc/default/camera-detection ]] \
     && ! grep -q 'camera-detection' /etc/default/camera-detection 2>/dev/null; then
    cat >> /etc/default/camera-detection <<'EOF'

# ---- 测试相机增量清理（2026-11-05）----
# 清理脚本自己 source 本文件取口令，不在 cron 文件里写口令。
# PROTECTED_CODES：额外保护的真实相机编号（逗号分隔），运维临时加码用。
PROTECTED_CODES=""
MAX_SINGLE_DELETE=50000
EOF
    chmod 640 /etc/default/camera-detection
    chown root:root /etc/default/camera-detection
    ok "已在 /etc/default/camera-detection 追加清理任务配置"
  fi

  # 图片根目录与脚本默认一致（脚本内 IMG_ROOT 默认值相同）
  cat > /etc/cron.d/cleanup-test-camera-images <<EOF
# 每小时 :15 清理测试相机的无效图片与对应数据库记录
# 真实相机（camera_type='REAL'）的数据一律不参与清理。
# 口令由脚本自己从 /etc/default/camera-detection 读取，此处不写口令。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
15 * * * * root IMG_ROOT="${IMAGES_SRC}" TRASH="${IMAGE_TRASH}" LOG="${LOG_BASE}/cleanup_zk54.log" ${INSTALL_DIR}/scripts/cleanup-zk54-incremental.sh
EOF
  ok "已加入 cron（每小时 :15，仅清理测试相机）"
fi

# --- 8b. 磁盘水位监控 --------------------------------------------------------
# 磁盘写满时应用能启动，但图片上传与数据库写入会陆续失败，
# 表现为「服务活着但业务全错」，极难排查。每 30 分钟巡检一次。
if [[ -f "${SOURCE_DIR}/scripts/check-disk-usage.sh" ]]; then
  cat > /etc/cron.d/check-disk-usage <<EOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
*/30 * * * * root MOUNTS="/ ${BASE_MOUNT}" CAPACITY_MOUNT="${BASE_MOUNT}" ${INSTALL_DIR}/scripts/check-disk-usage.sh >> ${LOG_BASE}/disk-alert.log 2>&1
EOF
  ok "已加入磁盘水位巡检（每 30 分钟，监控 / 与 ${BASE_MOUNT}）"
fi

# --- 8c. 数据库定时备份 ------------------------------------------------------
# 用 systemd timer 而非 cron：失败信息进 journal（本机无 MTA，cron 失败会静默），
# 且可用 systemctl list-timers 一览无余。
if [[ -f "${SOURCE_DIR}/systemd/camera-detection-backup.service" && \
      -f "${SOURCE_DIR}/systemd/camera-detection-backup.timer" ]]; then
  log "安装数据库备份定时器"
  install -m 0755 "${SOURCE_DIR}/scripts/backup-database.sh" "${INSTALL_DIR}/scripts/"

  # 主备份：与业务数据同盘（数据盘），目录已由第 2 节建好
  mkdir -p "${BACKUP_BASE}/db/full" "${BACKUP_BASE}/db/binlog" \
           "${BACKUP_BASE}/db/images" "${BACKUP_BASE}/db/logs"
  chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${BACKUP_BASE}" 2>/dev/null || true
  chmod 700 "${BACKUP_BASE}" 2>/dev/null || true

  # --- 跨盘副本目录（2026-11 统一布局后重新启用）----------------------------
  # 业务数据与数据库现在全在数据盘上 —— 盘坏即全失，副本必须落在**系统盘**。
  # backup-database.sh 会校验「副本与源不同设备」，同盘则 WARN 并跳过。
  mkdir -p "${OFFSITE_BASE}/db/full" "${OFFSITE_BASE}/db/binlog" "${OFFSITE_BASE}/images"
  chown -R "${SERVICE_USER}:${SERVICE_GROUP}" "${OFFSITE_BASE}" 2>/dev/null || true
  chmod 750 "$(dirname "${OFFSITE_BASE}")" 2>/dev/null || true
  chmod 700 "${OFFSITE_BASE}" 2>/dev/null || true
  ok "跨盘副本目录：${OFFSITE_BASE}（$(df --output=source "${OFFSITE_BASE}" 2>/dev/null | tail -1)）"

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
  sed -i '/^MYSQL_PASSWORD=/d; /^BACKUP_DIR=/d; /^KEEP_FULL_DAYS=/d; /^MAX_DISK_USE=/d; /^JWT_SECRET=/d; /^OFFSITE_DIR=/d; /^IMAGES_SRC=/d; /^IMAGES_DST=/d; /^IMAGE_BACKUP_MAX_GB=/d' \
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
OFFSITE_DIR="${OFFSITE_BASE}/db"
IMAGES_SRC="${IMAGES_SRC}"
IMAGES_DST="${OFFSITE_BASE}/images"
# 图片副本总量上限（GB）。超限时从最旧日期目录开始回收 ——
# 防 REAL 相机台数增长后 30 天窗口本身撑满系统盘。
IMAGE_BACKUP_MAX_GB=20

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

  # 自定义 BASE 时同步 unit 的执行路径（默认 /main/app 与 unit 内值一致，无需改）
  BU="/etc/systemd/system/camera-detection-backup.service"
  if [[ "$BASE" != "/main/app" ]]; then
    sed -i "s|^ExecStart=.*|ExecStart=${INSTALL_DIR}/scripts/backup-database.sh|" "$BU"
    sed -i "s|^RequiresMountsFor=.*|RequiresMountsFor=${BASE_MOUNT}|" "$BU"
    log "自定义 BASE=${BASE}：请同步修改 ${BU} 里的两行 ReadWritePaths"
  fi
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
log "排查手册：docs/部署与运维手册.md（现场故障速查 §7）与 docs/踩坑速查.md"

# 退出码：0=全部就绪；1=缺数据库（装完了但要先建库）；2=服务真起不来
case "${DB_PENDING:-0}" in
  0) ;;
  1) exit 1 ;;
  2) exit 2 ;;
esac
