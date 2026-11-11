#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 新机一键部署（Ubuntu 22.04 LTS）
#
# 定位：把 install.sh 不管的「宿主机前置动作」补齐，并按正确顺序串起全流程。
#       install.sh 本身只做应用层（账号/目录/配置/systemd/cron），
#       装 MySQL、挂数据盘、改 root 认证方式、调内存参数都在本脚本里。
#
# 用法：
#   sudo bash scripts/deploy.sh --disk /dev/sdb
#   sudo bash scripts/deploy.sh --disk /dev/sdb --db-password 'xxx' --app-password 'xxx'
#   sudo bash scripts/deploy.sh --check                    # 只做只读预检，不改系统
#   sudo bash scripts/deploy.sh --from p6                  # 中途失败后从某阶段续跑
#
# 阶段（可用 --from 指定起点，均幂等）：
#   p1 依赖安装      p2 数据盘挂载    p3 时区同步    p4 MySQL 初始化与调优
#   p5 应用安装      p6 建库          p7 JVM/MySQL 调优并重启    p8 验收
#
# 日志与状态：
#   全过程输出同时写入 /var/log/camera-detection-deploy.log（排障只看这一个文件）；
#   最后成功阶段记在 /var/lib/camera-detection/.deploy-state（系统盘，不依赖数据盘挂载），
#   失败时脚本会直接打印「用哪条命令续跑」，不用自己推算。
#
# 注意：
#   - 数据盘必须用 --disk 显式指定。不自动猜盘 —— 猜错就是格式化别人的数据盘。
#   - 02_final_schema_p0.sql 会 DROP 重建 21 张表，只允许在**空库**上跑。
#     本脚本建库前会检查库是否已存在，已存在则跳过，绝不覆盖。
# ==============================================================================
# set -E（errtrace）必须开：不开的话 ERR trap 不会在函数内部生效，
# 而本脚本每个阶段都是函数 —— 等于 trap 形同虚设。
set -Eeuo pipefail

DISK=""
DB_PASSWORD="${MYSQL_PASSWORD:-}"
APP_PASSWORD="${VERIFY_PASSWORD:-}"
FROM_STAGE="p1"
WITH_NGINX=0
ASSUME_YES=0
CHECK_ONLY=0

APP_DIR="/main/app/dist"
DEFAULT_CFG="/etc/default/camera-detection"
MYSQL_TUNE_CNF="/etc/mysql/mysql.conf.d/zz-camera-detection.cnf"
BASE="/main/app"
BASE_MOUNT="/main"
DATA_BASE="/main/app/data"
IMAGES_SRC="${DATA_BASE}/images/defect-images"
MYSQL_DATA_DIR="${DATA_BASE}/mysql"
LOG_BASE="/main/app/logs"
DB_NAME="wms-new"
PORT=8091
# 引导期产物（部署日志、阶段状态）必须落在**系统盘**：p2 之前数据盘还没挂载，
# 写 /main 会直接失败，而这两样东西正是排障时最需要的。
DEPLOY_LOG="/var/log/camera-detection-deploy.log"
STATE_FILE="/var/lib/camera-detection/.deploy-state"
CURRENT_STAGE="p0"
SELF_PATH="$(readlink -f "$0")"

log()  { printf '\033[1;36m[deploy]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m  !\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
产线缺陷在线AI检测系统 —— 新机一键部署（Ubuntu 22.04 / 24.04）

用法：
  sudo bash scripts/deploy.sh [选项]

选项：
  --disk <设备>          指定数据盘（如 /dev/sdb）；未格式化会先确认再格式化
  --db-password <口令>   MySQL root 口令；省略则交互输入（不能含单引号/反斜杠）
  --app-password <口令>  管理员口令，供 p8 验收登录使用（默认 123456）
  --from <p1..p8>        从指定阶段续跑（各阶段幂等）
  --check                只做只读预检（不装包、不挂盘、不改配置）
  --with-nginx           额外安装 Nginx
  -y, --yes              全部确认类提示自动通过
  -h, --help             显示本帮助

阶段：
  p1 依赖安装    p2 数据盘    p3 时区    p4 MySQL 初始化与调优
  p5 应用安装    p6 建库      p7 JVM 调优并重启    p8 验收

日志：/var/log/camera-detection-deploy.log
状态：/var/lib/camera-detection/.deploy-state（记录最后成功阶段）
USAGE
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --disk)         DISK="$2"; shift 2 ;;
    --db-password)  DB_PASSWORD="$2"; shift 2 ;;
    --app-password) APP_PASSWORD="$2"; shift 2 ;;
    --from)         FROM_STAGE="$2"; shift 2 ;;
    --with-nginx)   WITH_NGINX=1; shift ;;
    --check)        CHECK_ONLY=1; shift ;;
    -y|--yes)       ASSUME_YES=1; shift ;;
    -h|--help)      usage ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done

# ==============================================================================
# 运行环境初始化：日志留痕 + 状态记录 + 失败定位
#
# 2026-11-10 上机教训：全程只在 Web 终端里看输出，终端一滚屏就再也查不到
# 任何线索，排障全靠截图。部署过程必须落盘。
# ==============================================================================
if [[ "$CHECK_ONLY" -eq 0 ]]; then
  mkdir -p "$(dirname "$DEPLOY_LOG")" 2>/dev/null || true
  touch "$DEPLOY_LOG" 2>/dev/null || true
  if [[ -w "$DEPLOY_LOG" ]]; then
    exec > >(tee -a "$DEPLOY_LOG") 2>&1
    printf '\n========== 部署开始 %s（--from %s）==========\n' "$(date '+%F %T')" "$FROM_STAGE"
  fi
fi

mark_ok() {
  mkdir -p "$(dirname "$STATE_FILE")" 2>/dev/null || true
  printf '%s %s\n' "$(date '+%F %T')" "$1" > "$STATE_FILE" 2>/dev/null || true
}
last_ok() {
  [[ -f "$STATE_FILE" ]] && awk '{print $NF}' "$STATE_FILE" 2>/dev/null || true
}

# ERR trap：把「静默死」变成明确报错。
# 背景：set -euo pipefail 下，`x=$(cmd | tr ...)` 这类赋值只要有一环非零就会
# 直接杀脚本、什么也不打印（本次上机 p6 就是这样无声消失的）。
# 主循环故意**不用** `stage_x || die` —— bash 在 || / if 上下文里会抑制函数内的
# set -e，导致阶段内部失败却继续往下跑，比静默死更危险。
on_err() {
  local rc=$?
  local disk_hint=""
  if [[ -n "$DISK" ]]; then disk_hint=" --disk ${DISK}"; fi
  printf '\n\033[1;31m错误：\033[0m 阶段 %s 失败（退出码 %s）\n' "$CURRENT_STAGE" "$rc" >&2
  printf '  失败命令：%s\n' "$BASH_COMMAND" >&2
  printf '  续跑命令：sudo bash %s --from %s%s\n' "$SELF_PATH" "$CURRENT_STAGE" "$disk_hint" >&2
  printf '  完整日志：%s\n' "$DEPLOY_LOG" >&2
  exit "$rc"
}
trap on_err ERR

# ==============================================================================
# 定位安装包根目录（deploy.sh 位于 <包>/scripts/ 下，install.sh 与它同级）
# ==============================================================================
resolve_src() {
  local d="${SELF_PATH%/*}"
  if [[ -f "${d}/install.sh" ]]; then
    (cd "${d}/.." && pwd)
  elif [[ -f "./scripts/install.sh" ]]; then
    pwd
  else
    return 1
  fi
}

# ==============================================================================
# p0 前置检查（只读，不改系统；--check 时到此为止）
#   2026-11-10 上机教训：包不完整、端口被占、盘没挂，这些在 p5 之后才炸，
#   排查成本极高。全部提前到 p0 一次说清。
# ==============================================================================
stage_p0() {
  log "p0 前置检查"
  [[ "$(id -u)" -eq 0 ]] || die "必须以 root 执行：sudo bash $0"
  [[ -r /etc/os-release ]] || die "无法识别操作系统"
  # shellcheck disable=SC1091
  . /etc/os-release
  [[ "${ID:-}" == "ubuntu" ]] || warn "非 Ubuntu（${PRETTY_NAME:-unknown}），脚本按 Ubuntu 22.04/24.04 编写，请自行核对包名"
  ok "系统：${PRETTY_NAME:-unknown}"

  local mem_kb mem_gb
  mem_kb="$(awk '/MemTotal/{print $2}' /proc/meminfo)"
  mem_gb=$(( mem_kb / 1024 / 1024 ))
  ok "内存：${mem_gb} GB"
  [[ "$mem_gb" -lt 4 ]] && warn "内存小于 4 GB，MySQL buffer pool 与 JVM 堆都会被下调"

  ok "CPU：$(nproc) 核"
  ok "时间：$(date '+%F %T %Z')"

  # 安装包完整性：上传中断/解压失败时缺文件，直到 p5 才暴露，代价太大
  local src
  if src="$(resolve_src)"; then
    local missing=() f
    for f in app/time-bucket-demo.jar config/application.yml sql/01_init_schema_nodb.sql \
             scripts/install.sh scripts/init-database.sh scripts/verify.sh scripts/run.sh \
             systemd/camera-detection.service; do
      [[ -e "${src}/${f}" ]] || missing+=("$f")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
      die "安装包不完整，缺少 ${#missing[@]} 个文件：${missing[*]}（上传/解压可能中断，请重新解包）"
    fi
    ok "安装包完整：${src}"
  else
    die "找不到 install.sh —— 请在解压后的 camera-detection-system 目录内执行：sudo bash scripts/deploy.sh"
  fi

  # 端口占用：被别的进程占着的话，应用启动后会绑不上，表现为"服务 active 但打不开"
  local port_owner
  port_owner="$(ss -lntp 2>/dev/null | awk -v p=":${PORT}" '$4 ~ p"$" {print $6}' | head -1)"
  if [[ -n "$port_owner" ]]; then
    if [[ "$port_owner" == *java* ]]; then
      ok "端口 ${PORT} 由现有 java 进程占用（p7 重启时接管）"
    else
      warn "端口 ${PORT} 被其他进程占用：${port_owner}"
      warn "应用将无法监听 ${PORT}，请先释放端口（sudo ss -lntp | grep ${PORT}）"
    fi
  else
    ok "端口 ${PORT} 空闲"
  fi

  # 磁盘：装 JDK/MySQL/解包需要空间
  local root_avail
  root_avail="$(df -BG --output=avail / 2>/dev/null | tail -1 | tr -dc '0-9')"
  if [[ -n "$root_avail" && "$root_avail" -lt 5 ]]; then
    warn "根分区可用 ${root_avail}G（小于 5G），依赖安装可能失败"
  else
    ok "根分区可用 ${root_avail:-?}G"
  fi

  if [[ -d "$BASE_MOUNT" ]] && findmnt -no TARGET "$BASE_MOUNT" >/dev/null 2>&1; then
    ok "${BASE_MOUNT} 已挂载：$(df -h "$BASE_MOUNT" | awk 'NR==2{print $2" 总 / "$4" 可用"}')（p2 将跳过挂盘）"
  else
    warn "${BASE_MOUNT} 未挂载 —— p2 需 --disk 指定数据盘；未挂载时 install.sh 会拒绝安装（绝不降级到系统盘）"
  fi

  [[ -f "${APP_DIR}/app/time-bucket-demo.jar" ]] \
    && warn "检测到已有安装：${APP_DIR}（p5 会覆盖程序文件，配置与数据保留）"

  if [[ -n "$DB_PASSWORD" && ( "$DB_PASSWORD" == *"'"* || "$DB_PASSWORD" == *"\\"* ) ]]; then
    die "数据库口令不能含单引号或反斜杠（会破坏 SQL 与配置解析）"
  fi

  if [[ "$CHECK_ONLY" -eq 1 ]]; then
    echo
    ok "预检完成 —— --check 模式未做任何改动"
    exit 0
  fi
}

# ==============================================================================
# p1 依赖安装
#   JDK 8 有两个来源：
#     Ubuntu 22.04 → openjdk-8-jdk（官方源）
#     Ubuntu 24.04+ → 官方源已移除 openjdk-8，走 Adoptium Temurin 8
#   服务器无法出网时两者都装不上 —— 必须离线装好后用 --skip-apt 续跑。
# ==============================================================================
find_java8_home() {
  # 必须是 Java 8（应用按 8 编译；17+ 下 MyBatis-Plus 反射会炸）。
  # 优先从 PATH 上的 java 反推真实 HOME —— 对 apt/Temurin/手工解压都成立；
  # PATH 上的不是 8 时，退回扫描 /usr/lib/jvm/*。
  local jbin jreal d cand
  if jbin="$(command -v java 2>/dev/null)"; then
    jreal="$(readlink -f "$jbin")"
    d="$(dirname "$(dirname "$jreal")")"
    if [[ -x "$d/bin/java" ]] && "$d/bin/java" -version 2>&1 | grep -q '"1\.8\.'; then
      printf '%s' "$d"; return 0
    fi
  fi
  for cand in /usr/lib/jvm/*; do
    [[ -x "$cand/bin/java" ]] || continue
    if "$cand/bin/java" -version 2>&1 | grep -q '"1\.8\.'; then
      printf '%s' "$cand"; return 0
    fi
  done
  return 1
}

install_jdk8() {
  if apt-cache show openjdk-8-jdk >/dev/null 2>&1; then
    apt-get install -y -qq openjdk-8-jdk && return 0
  fi
  log "官方源无 openjdk-8（Ubuntu 24.04+），改用 Adoptium Temurin 8"
  apt-get install -y -qq wget gpg ca-certificates
  wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
  local codename
  codename="$(. /etc/os-release && printf '%s' "${VERSION_CODENAME:-noble}")"
  printf 'deb https://packages.adoptium.net/artifactory/deb %s main\n' "$codename" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  apt-get install -y -qq temurin-8-jdk
}

stage_p1() {
  log "p1 安装系统依赖"
  local pkgs=(mysql-server chrony curl)
  [[ "$WITH_NGINX" -eq 1 ]] && pkgs+=(nginx)

  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq || die "apt update 失败：服务器可能无法访问外网软件源，请配置内网源或离线安装上述包"
  apt-get install -y -qq "${pkgs[@]}" || die "依赖安装失败：${pkgs[*]}"

  install_jdk8 || die "JDK 8 安装失败（需外网；离线环境请手工装好 JDK8 后重跑）"

  local java_home
  java_home="$(find_java8_home)" || die "未找到 JDK 8 安装目录"
  ok "Java：$("$java_home/bin/java" -version 2>&1 | head -1)"
  ok "MySQL：$(mysql --version | head -1)"
  systemctl enable --now mysql >/dev/null 2>&1 || true
  ok "依赖就绪"
}

# ==============================================================================
# p2 数据盘挂载 /data
#   不自动猜盘：只列出候选，必须 --disk 显式指定。
# ==============================================================================
list_candidate_disks() {
  lsblk -dno NAME,SIZE,TYPE 2>/dev/null | awk '$3=="disk"{print $1, $2}' | while read -r name size; do
    local dev="/dev/${name}" fstype
    fstype="$(blkid -o value -s TYPE "$dev" 2>/dev/null || true)"
    # 有文件系统、或已被挂载的，不列为候选
    if [[ -z "$fstype" ]] && ! lsblk -no MOUNTPOINT "$dev" 2>/dev/null | grep -q '/'; then
      printf '  %s  %s  未格式化、未挂载\n' "$dev" "$size"
    fi
  done
}

stage_p2() {
  log "p2 数据盘 → ${BASE_MOUNT}"
  if [[ -n "$DISK" ]]; then
    [[ -b "$DISK" ]] || die "${DISK} 不是块设备"

    local sysdisk cur_target
    sysdisk="$(lsblk -no PKNAME "$(findmnt -no SOURCE /)" 2>/dev/null | head -1)"
    [[ -n "$sysdisk" && "/dev/${sysdisk}" == "$DISK" ]] && die "${DISK} 是系统盘，拒绝操作"

    cur_target="$(findmnt -no TARGET "$DISK" 2>/dev/null | head -1 || true)"

    if [[ "$cur_target" == "$BASE_MOUNT" ]]; then
      ok "${DISK} 已挂载于 ${BASE_MOUNT}，跳过挂盘"
    else
      if [[ -n "$cur_target" ]]; then
        # 已挂在别处（典型：老机器的 /data）。有数据就绝不自动搬 —— 交给迁移脚本。
        local has_data=""
        has_data="$(ls -A "$cur_target" 2>/dev/null | grep -v '^lost+found$' | head -1 || true)"
        if [[ -n "$has_data" ]]; then
          die "${DISK} 当前挂载于 ${cur_target} 且非空。本脚本不搬数据：
       ① 老机器原地迁移：sudo bash ${SELF_PATH%/*}/migrate-to-main-app.sh --apply
       ② 确认 ${cur_target} 下无需保留的数据：umount ${cur_target}，再重跑本脚本"
        fi
        umount "$cur_target"
        ok "已卸载空挂载点 ${cur_target}"
      fi

      local fstype
      fstype="$(blkid -o value -s TYPE "$DISK" 2>/dev/null || true)"
      if [[ -z "$fstype" ]]; then
        warn "即将格式化 ${DISK}（ext4），该盘现有数据将全部丢失"
        lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT "$DISK" || true
        if [[ "$ASSUME_YES" -ne 1 ]]; then
          local ans
          read -r -p "确认格式化为 ext4 并挂载到 ${BASE_MOUNT} ？输入 yes 继续: " ans
          [[ "$ans" == "yes" ]] || die "已取消"
        fi
        mkfs.ext4 -F -L camera-data "$DISK" >/dev/null
        fstype="ext4"
        ok "已格式化 ${DISK} 为 ext4"
      else
        ok "${DISK} 已有文件系统（${fstype}），仅挂载不格式化"
      fi

      mkdir -p "$BASE_MOUNT"
      local uuid
      uuid="$(blkid -o value -s UUID "$DISK" 2>/dev/null || true)"
      [[ -n "$uuid" ]] || die "取不到 ${DISK} 的 UUID，拒绝写 fstab"
      # ★ 用 UUID 而非设备名：sda/sdb 顺序会变，写错就是开机挂不上。
      # ★ 绝不加 nofail：盘挂不上必须让服务启动失败，而不是静默写系统盘。
      if grep -qE "[[:space:]]${BASE_MOUNT}[[:space:]]" /etc/fstab; then
        sed -i "\|${BASE_MOUNT}[[:space:]]|s|^.*$|UUID=${uuid} ${BASE_MOUNT} ${fstype} defaults 0 2|" /etc/fstab
      else
        printf 'UUID=%s %s %s defaults 0 2\n' "$uuid" "$BASE_MOUNT" "$fstype" >> /etc/fstab
      fi
      systemctl daemon-reload 2>/dev/null || true
      mount "$BASE_MOUNT"

      local mnt_src root_src
      mnt_src="$(findmnt -n -o SOURCE --target "$BASE_MOUNT" 2>/dev/null || true)"
      root_src="$(findmnt -n -o SOURCE --target / 2>/dev/null || true)"
      [[ -n "$mnt_src" && "$mnt_src" != "$root_src" ]] \
        || die "${BASE_MOUNT} 挂载后仍不是独立设备（${mnt_src:-未知}），已中止"
      ok "已挂载 ${DISK} → ${BASE_MOUNT}（UUID=${uuid}）"
    fi
  fi

  if [[ -d "$BASE_MOUNT" ]] && findmnt -no TARGET "$BASE_MOUNT" >/dev/null 2>&1; then
    # 统一目录树：root 先建好再 chown —— 数据盘根是 755 root，cdsys 建不了目录
    mkdir -p "${DATA_BASE}/images/defect-images" "${DATA_BASE}/images/.cleanup-trash" \
             "${DATA_BASE}/uploads/images" \
             "${DATA_BASE}/backup/db/full" "${DATA_BASE}/backup/db/binlog" "${DATA_BASE}/backup/db/logs" \
             "${LOG_BASE}" "${BASE}/run" "${BASE}/tmp" "${BASE}/backup" \
             "${MYSQL_DATA_DIR}" "/var/backups/camera-detection/db/full" \
             "/var/backups/camera-detection/db/binlog" "/var/backups/camera-detection/images"
    ok "${BASE_MOUNT} 可用：$(df -h "$BASE_MOUNT" | awk 'NR==2{print $2" 总 / "$4" 可用"}')"
  else
    # 不提供"降级到系统盘"选项：统一布局下数据盘是硬要求
    echo "  可用的空白磁盘候选（未格式化、未挂载）："
    list_candidate_disks || true
    die "${BASE_MOUNT} 未挂载 —— 统一布局要求数据盘先挂载到 ${BASE_MOUNT}：
       sudo bash $0 --disk /dev/sdX --from p2"
  fi
}

# ==============================================================================
# p3 时区与时间同步
#   老现场踩过：本机与服务器时钟差 4 分钟，导致「清理任务未执行」误判。
# ==============================================================================
stage_p3() {
  log "p3 时区与时间同步"
  timedatectl set-timezone Asia/Shanghai
  systemctl enable --now chrony >/dev/null 2>&1 || systemctl enable --now chrony.service >/dev/null 2>&1 || true
  ok "时区：$(timedatectl show -p Timezone --value)，时间：$(date '+%F %T')"
}

# ==============================================================================
# p4 MySQL 初始化
#   Ubuntu 的 mysql-server 默认 root@localhost 用 auth_socket —— JDBC 用密码
#   连不上。应用配置里写死 username=root，所以必须把 root 改成密码认证。
# ==============================================================================
stage_p4() {
  log "p4 MySQL 初始化与调优"

  # 口令来源优先级：命令行/环境变量 > 已有 /etc/default 配置 > 交互输入
  if [[ -z "$DB_PASSWORD" && -f "$DEFAULT_CFG" ]]; then
    DB_PASSWORD="$(sed -n 's/^MYSQL_PASSWORD="\(.*\)"$/\1/p' "$DEFAULT_CFG" | head -1)"
  fi
  if [[ -z "$DB_PASSWORD" ]]; then
    if [[ -t 0 ]]; then
      read -r -s -p "请设置 MySQL root 口令（应用将以 root 连接本机库）: " DB_PASSWORD
      echo
      read -r -s -p "再输入一次: " _pwd2
      echo
      [[ "$DB_PASSWORD" == "$_pwd2" ]] || die "两次输入不一致"
    else
      die "非交互执行，请用 --db-password 'xxx' 指定口令"
    fi
  fi
  [[ ${#DB_PASSWORD} -ge 8 ]] || warn "口令长度小于 8 位，建议改强口令"

  # 判断能否用该口令登录（三种状态都兼容：TCP+密码 / socket+密码 / 全新 auth_socket 免密）
  local can_pwd_login=0
  if MYSQL_PWD="$DB_PASSWORD" mysql -h 127.0.0.1 -P 3306 -uroot --protocol=tcp -N -B -e "SELECT 1;" >/dev/null 2>&1; then
    can_pwd_login=1
  elif MYSQL_PWD="$DB_PASSWORD" mysql --protocol=socket -uroot -N -B -e "SELECT 1;" >/dev/null 2>&1; then
    can_pwd_login=1
  fi

  if [[ "$can_pwd_login" -eq 1 ]]; then
    ok "MySQL root 已可用口令登录"
  else
    log "尝试用 auth_socket 免密（Ubuntu 全新初始态）设置 root 口令"
    mysql --protocol=socket -uroot <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '${DB_PASSWORD}';
FLUSH PRIVILEGES;
SQL
    MYSQL_PWD="$DB_PASSWORD" mysql -h 127.0.0.1 -P 3306 -uroot --protocol=tcp -N -B -e "SELECT 1;" >/dev/null 2>&1 \
      || die "root 口令登录验证失败。当前 root 已设过其他口令时，请用 --db-password 传那个现有口令，先登录再谈改密"
    ok "root 口令已设置并可登录"
  fi
  export MYSQL_PWD="$DB_PASSWORD"

  # buffer pool：默认 128M 是玩具口径。按物理内存 50% 且不超过 4G，给小内存机兜底。
  local mem_kb pool_mb cur_datadir
  mem_kb="$(awk '/MemTotal/{print $2}' /proc/meminfo)"
  pool_mb=$(( mem_kb / 1024 / 2 ))
  [[ "$pool_mb" -gt 4096 ]] && pool_mb=4096
  [[ "$pool_mb" -lt 256 ]] && pool_mb=256

  # --- datadir 迁到数据盘（2026-11 统一布局）---------------------------------
  # 统一布局要求「所有产品文件都在 /main/app 下」，数据库也不例外。
  # 顺序有硬约束：先停库 → 复制 → chown → **写 AppArmor 放行规则** → 写 datadir → 启动。
  # AppArmor 规则晚于 start 才写，mysqld 会在启动阶段被拒绝访问新目录
  # （报 "Can't create/write to file ... (Errcode: 13)"）——这是 Ubuntu 上迁移 datadir 的头号失败原因。
  cur_datadir="$(MYSQL_PWD="$DB_PASSWORD" mysql -N -B -e 'SELECT @@datadir;' 2>/dev/null | tr -d '[:space:]' || true)"

  if [[ "${cur_datadir%/}" == "${MYSQL_DATA_DIR}" ]]; then
    ok "MySQL datadir 已在 ${MYSQL_DATA_DIR}"
  else
    log "迁移 MySQL datadir：${cur_datadir:-未知} → ${MYSQL_DATA_DIR}"
    systemctl stop mysql
    mkdir -p "$MYSQL_DATA_DIR"
    rsync -aHAX "${cur_datadir%/}/" "${MYSQL_DATA_DIR}/" \
      || die "datadir 复制失败（${cur_datadir} → ${MYSQL_DATA_DIR}）"
    chown -R mysql:mysql "$MYSQL_DATA_DIR"
    chmod 700 "$MYSQL_DATA_DIR"
    ok "已复制 $(du -sh "$MYSQL_DATA_DIR" 2>/dev/null | cut -f1) 到数据盘（原目录保留作回滚）"

    if [[ -d /etc/apparmor.d ]]; then
      local ap=/etc/apparmor.d/local/usr.sbin.mysqld
      mkdir -p "$(dirname "$ap")"
      touch "$ap"
      if ! grep -qF "${MYSQL_DATA_DIR}/" "$ap"; then
        {
          printf '\n# 产线缺陷在线AI检测系统：MySQL datadir 位于数据盘（deploy.sh 追加）\n'
          printf '  %s/ r,\n'  "$MYSQL_DATA_DIR"
          printf '  %s/** rwk,\n' "$MYSQL_DATA_DIR"
        } >> "$ap"
        apparmor_parser -r /etc/apparmor.d/usr.sbin.mysqld 2>/dev/null \
          || warn "AppArmor 规则重载失败（若系统未强制 AppArmor 可忽略）"
        ok "AppArmor 已放行 ${MYSQL_DATA_DIR}"
      fi
    fi
  fi

  cat > "$MYSQL_TUNE_CNF" <<EOF
# 产线缺陷在线AI检测系统 —— 由 deploy.sh 生成，独立文件便于回滚
[mysqld]
# 数据库文件统一落在数据盘（/main），不再使用系统盘 /var/lib/mysql
datadir=${MYSQL_DATA_DIR}
innodb_buffer_pool_size=${pool_mb}M
innodb_flush_log_at_trx_commit=2
max_connections=200
# 单机部署：只监听本机，不对外暴露 3306
bind-address=127.0.0.1
EOF
  chmod 0644 "$MYSQL_TUNE_CNF"
  systemctl reset-failed mysql 2>/dev/null || true
  systemctl restart mysql

  # 断言：datadir 必须真的生效（写进 cnf 不等于 MySQL 接受了它）
  local i final_datadir=""
  for i in $(seq 1 15); do
    final_datadir="$(MYSQL_PWD="$DB_PASSWORD" mysql -N -B -e 'SELECT @@datadir;' 2>/dev/null | tr -d '[:space:]' || true)"
    [[ -n "$final_datadir" ]] && break
    sleep 2
  done
  [[ "${final_datadir%/}" == "${MYSQL_DATA_DIR}" ]] \
    || die "MySQL 未使用新 datadir（实际 ${final_datadir:-无响应}，期望 ${MYSQL_DATA_DIR}）。查看：journalctl -u mysql -n 50"
  ok "datadir=${final_datadir}，buffer pool=${pool_mb}M，bind-address=127.0.0.1"
}

# ==============================================================================
# p5 应用安装（install.sh 负责账号/目录/配置/systemd/cron/防火墙/logrotate）
# ==============================================================================
stage_p5() {
  log "p5 安装应用"
  local src
  src="$(resolve_src)" || die "找不到 install.sh —— 请在解压后的 camera-detection-system 目录内执行"

  # install.sh 的退出码有语义：0=就绪；1=装完了但缺数据库（正常，由 p6 补）；
  # 2=服务真起不来。不能一律当失败，否则「先装应用后建库」这条正常路径会中断。
  local rc=0
  set +e
  MYSQL_PASSWORD="$DB_PASSWORD" bash "${src}/scripts/install.sh"
  rc=$?
  set -e
  case "$rc" in
    0) ok "应用已安装到 ${APP_DIR}，服务已启动" ;;
    1) warn "应用已安装；数据库尚未创建（DB_PENDING），由 p6 建库后重启" ;;
    *) die "install.sh 失败（退出码 ${rc}），见上方日志" ;;
  esac
  return 0
}

# ==============================================================================
# p6 建库（脚本内部按 01→05 顺序导入，含 camera_type 校验）
# ==============================================================================
stage_p6() {
  log "p6 建库"

  # --from p6 续跑时 p4 被跳过：若口令与 MySQL 实际值不一致，先尝试用 socket 免密通道重置
  if ! MYSQL_PWD="$DB_PASSWORD" mysql -h 127.0.0.1 -P 3306 -uroot --protocol=tcp -N -B \
      -e "SELECT 1;" >/dev/null 2>&1; then
    warn "TCP 口令登录失败，尝试经 auth_socket 免密通道重置 root 口令为本次传入值"
    mysql --protocol=socket -uroot \
      -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '${DB_PASSWORD}'; FLUSH PRIVILEGES;" \
      >/dev/null 2>&1 \
      || die "无法登录 MySQL：TCP 口令被拒，且 socket 免密通道不可用（root 已是密码认证）。请用 p4 当时实际输入的口令重跑：--db-password '<那个口令>'"
    MYSQL_PWD="$DB_PASSWORD" mysql -h 127.0.0.1 -P 3306 -uroot --protocol=tcp -N -B \
      -e "SELECT 1;" >/dev/null 2>&1 || die "口令重置后仍无法登录，请手工排查：sudo mysql -uroot -p"
    ok "root 口令已重置并可登录"
  fi
  export MYSQL_PWD="$DB_PASSWORD"

  # 命令替换 + pipefail 的组合下，mysql 缺失/失败会让赋值语句返回非零，
  # set -e 会静默杀死脚本 —— 必须显式 || true 再判空。
  local exists
  exists="$(mysql -h 127.0.0.1 -P 3306 -uroot --protocol=tcp -N -B \
      -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${DB_NAME}';" 2>/dev/null \
      | tr -d '[:space:]' || true)"

  if [[ "${exists:-0}" == "1" ]]; then
    local tables
    tables="$(mysql -h 127.0.0.1 -P 3306 -uroot --protocol=tcp -N -B \
        -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}';" 2>/dev/null \
        | tr -d '[:space:]' || true)"
    if [[ "${tables:-0}" -gt 0 ]]; then
      # 02 会 DROP 重建 21 张表 —— 已有数据时绝不能跑
      warn "库 ${DB_NAME} 已存在且含 ${tables} 张表，跳过建库（避免 DROP 清数据）"
      ok "如需重建请先自行备份并手工删库"
      return 0
    fi
  fi

  # SQL 目录：优先用装好的 ${APP_DIR}/sql，回落当前包内 sql/
  local sql_dir="${APP_DIR}/sql"
  [[ -f "${sql_dir}/01_init_schema_nodb.sql" ]] || sql_dir="$(cd "$(dirname "$0")/.." && pwd)/sql"
  [[ -f "${sql_dir}/01_init_schema_nodb.sql" ]] || die "找不到建库脚本（已查 ${APP_DIR}/sql 与包内 sql/）"

  SQL_DIR="$sql_dir" MYSQL_PASSWORD="$DB_PASSWORD" bash "${APP_DIR}/scripts/init-database.sh" \
    || die "建库失败"
  ok "建库完成"
}

# ==============================================================================
# p7 内存调优（8 GB 机器默认 -Xmx1024m 偏小）
# ==============================================================================
stage_p7() {
  log "p7 JVM 调优并重启"

  # --from p7 续跑时 p5 可能没跑过：缺件就明确指出来，别让 systemd 报一句
  # "status=203/EXEC" 让人猜
  [[ -f "${APP_DIR}/app/time-bucket-demo.jar" ]] || die "应用未安装（缺 JAR），请先跑 --from p5"
  [[ -f "${APP_DIR}/config/application.yml" ]]   || die "缺少外部配置 ${APP_DIR}/config/application.yml，请先跑 --from p5"
  [[ -f "${DEFAULT_CFG}" ]]                      || die "缺少 ${DEFAULT_CFG}，请先跑 --from p5"
  [[ -f /etc/systemd/system/camera-detection.service ]] || die "缺少 systemd unit，请先跑 --from p5"

  local mem_kb heap_mb
  mem_kb="$(awk '/MemTotal/{print $2}' /proc/meminfo)"
  heap_mb=$(( mem_kb / 1024 / 4 ))
  [[ "$heap_mb" -gt 2048 ]] && heap_mb=2048
  [[ "$heap_mb" -lt 512 ]] && heap_mb=512

  # install.sh 在 /etc/default 里写死了 java-8-openjdk-amd64；
  # 若实际装的是 Temurin（Ubuntu 24.04+），必须改成真实路径，否则服务起不来。
  local real_home
  if real_home="$(find_java8_home)"; then
    if grep -q '^JAVA_HOME=' "$DEFAULT_CFG" 2>/dev/null; then
      grep -q "^JAVA_HOME=${real_home}$" "$DEFAULT_CFG" \
        || sed -i "s|^JAVA_HOME=.*|JAVA_HOME=${real_home}|" "$DEFAULT_CFG"
    else
      printf 'JAVA_HOME=%s\n' "$real_home" >> "$DEFAULT_CFG"
    fi
    ok "JAVA_HOME=${real_home}"
  fi

  # run.sh 从环境读 JAVA_OPTS，systemd 通过 EnvironmentFile 注入本文件。
  # 值含空格必须加引号：不加引号时 systemd 能读，但 bash source 会把第二段当命令执行
  # （run.sh 注释里记录过的坑），verify.sh 也会报 "未找到命令"。
  if grep -q '^JAVA_OPTS=' "$DEFAULT_CFG" 2>/dev/null; then
    sed -i "s|^JAVA_OPTS=.*|JAVA_OPTS=\"-Xms512m -Xmx${heap_mb}m\"|" "$DEFAULT_CFG"
  else
    printf 'JAVA_OPTS="-Xms512m -Xmx%sm"\n' "$heap_mb" >> "$DEFAULT_CFG"
  fi
  grep -q '^JAVA_OPTS=' "$DEFAULT_CFG" || die "写入 JAVA_OPTS 失败"
  ok "堆内存 -Xmx${heap_mb}m"

  systemctl reset-failed camera-detection 2>/dev/null || true
  systemctl restart camera-detection

  local i
  for i in $(seq 1 30); do
    if systemctl is-active --quiet camera-detection; then ok "服务已 active"; break; fi
    [[ "$i" -eq 30 ]] && { journalctl -u camera-detection -n 40 --no-pager; die "服务未能启动"; }
    sleep 2
  done

  # Type=simple 的 active ≠ 应用就绪（JVM 还在启动），轮询健康检查最多 90 秒。
  # 注意：探 / （SPA 首页，200 即就绪）—— 项目没有 /api/health 端点，
  # 探它会得到 Spring Security 的 401（无 token），不是就绪信号。
  local code="" j
  for j in $(seq 1 45); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 "http://127.0.0.1:${PORT}/" || true)"
    [[ "$code" == "200" ]] && break
    sleep 2
  done
  if [[ "$code" == "200" ]]; then
    ok "健康检查 HTTP 200（${PORT} 已就绪，启动耗时见日志）"
  else
    warn "健康检查 HTTP ${code:-000}（${PORT} 未监听）—— 抓一段 journal 附在下面，便于离线排障"
    echo "----- journalctl -u camera-detection -n 40 -----"
    journalctl -u camera-detection -n 40 --no-pager 2>/dev/null || true
    echo "----------------------------------------------"
    warn "若为启动失败：修好后可以 --from p7 重试；业务日志在 /main/app/logs/"
  fi
}

# ==============================================================================
# p8 验收
# ==============================================================================
stage_p8() {
  log "p8 验收"
  # verify.sh 要用真实管理员口令登录；优先命令行，其次 /etc/default 里记录的口令
  if [[ -z "$APP_PASSWORD" && -f "$DEFAULT_CFG" ]]; then
    APP_PASSWORD="$(sed -n 's/^ADMIN_PASSWORD="\(.*\)"$/\1/p' "$DEFAULT_CFG" | head -1)"
  fi
  # 有初始口令推断：全新库是 123456；现场老库是 admin123
  if [[ -z "$APP_PASSWORD" ]]; then
    APP_PASSWORD="123456"
    warn "未指定 --app-password，按全新库初始口令 ${APP_PASSWORD} 验收（改过口令请补 --app-password）"
  fi

  local rc=0
  set +e
  VERIFY_PASSWORD="$APP_PASSWORD" MYSQL_PASSWORD="$DB_PASSWORD" \
    bash "${APP_DIR}/scripts/verify.sh"
  rc=$?
  set -e

  if [[ "$rc" -eq 0 ]]; then
    ok "验收全通过"
  else
    warn "验收有未通过项（verify.sh 退出码 ${rc}）"
    echo "  复查：sudo VERIFY_PASSWORD='<应用口令>' MYSQL_PASSWORD='<库口令>' bash ${APP_DIR}/scripts/verify.sh"
    echo "  服务日志：journalctl -u camera-detection -n 100 --no-pager"
    echo "  业务日志：/main/app/logs/camera-detection-system.log"
    echo "  备份未到时间属正常：定时器每日 01:00 首跑（systemctl list-timers camera-detection-backup）"
  fi
  return 0
}

# ==============================================================================
# 主流程
# ==============================================================================
case "$FROM_STAGE" in
  p1|p2|p3|p4|p5|p6|p7|p8) ;;
  *) die "--from 只接受 p1..p8" ;;
esac

# 续跑提醒：本次上机栽过一次 —— --from p6 跳过了 p4，root 口令与传入值不一致，
# 建库直接 Access denied。把「最后成功阶段」摆到台面上，避免同类误判。
if [[ -n "$(last_ok)" ]]; then
  log "上次最后成功阶段：$(last_ok)；本次从 ${FROM_STAGE} 开始"
fi

STARTED=0
CURRENT_STAGE="p0"
stage_p0
mark_ok p0
for s in p1 p2 p3 p4 p5 p6 p7 p8; do
  [[ "$s" == "$FROM_STAGE" ]] && STARTED=1
  [[ "$STARTED" -eq 1 ]] || continue
  CURRENT_STAGE="$s"
  log "───── 阶段 ${s} ─────"
  # 直接调用，绝不写成 "stage_${s}" || die：
  # bash 在 || / if 上下文会抑制函数体内的 set -e，阶段中途失败却继续往下跑。
  # 失败由 ERR trap（on_err）统一报错并给出续跑命令。
  "stage_${s}"
  mark_ok "$s"
done

echo
ok "部署流程结束（${FROM_STAGE} → p8）"
echo "  应用地址： http://<本机IP>:${PORT}   （本机访问 http://127.0.0.1:${PORT}）"
echo "  登录账号： admin / 初始口令 123456（已改则用新口令）"
echo "  部署日志： ${DEPLOY_LOG}"
echo "  服务管理： systemctl status|restart camera-detection"
echo "  验收复查： sudo VERIFY_PASSWORD='<应用口令>' MYSQL_PASSWORD='<库口令>' bash ${APP_DIR}/scripts/verify.sh"
echo
echo "  还需人工完成（脚本不代做）："
echo "    1) 浏览器登录后立即修改 admin 口令（123456 不可留）"
echo "    2) history -c && rm -f ~/.bash_history   —— 清掉命令行里的明文口令"
echo "    3) 录入相机档案时 dim_camera.camera_type 必须为 'REAL'，"
echo "       否则每小时 :15 的清理任务会把它当 TEST 相机、连图片一起删掉"
echo "    4) 备份定时器每日 01:00 首跑；次日复查 systemctl list-timers camera-detection-backup"
