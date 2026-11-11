#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 老机迁移到统一布局 /main/app
#
# 背景：2026-11 起所有产品文件收敛到一个根，便于查找与跟踪：
#   旧布局                          新布局
#   /opt/camera-detection-system →  /main/app/dist
#   /data/camera-detection       →  /main/app/data        （图片 / 备份）
#   /var/lib/camera-detection-system → /main/app          （日志 / 上传 / 状态）
#   /var/lib/mysql               →  /main/app/data/mysql  （datadir）
#   /main 由数据盘挂载（原来是 /data）
#
# 用法：
#   sudo bash scripts/migrate-to-main-app.sh                # dry-run：只打印计划，零写操作
#   sudo bash scripts/migrate-to-main-app.sh --apply        # 真正执行
#   sudo bash scripts/migrate-to-main-app.sh --apply --skip-mysql   # 跳过 datadir 迁移
#   sudo bash scripts/migrate-to-main-app.sh --rollback     # 回滚到旧布局
#
# 安全设计：
#   - 源目录**只读不删**（rsync 复制，不 --delete）；MySQL 原 datadir 原样保留
#   - 每步失败即停，打印已完成步骤与回滚命令
#   - 迁移窗口内停 cron / timer / 应用，避免清理任务在搬目录时触发
#   - 老路径保留 symlink 兼容层：数据库里持久化的旧绝对路径（image_path）仍可解析
# ==============================================================================
set -Eeuo pipefail

APPLY=0
SKIP_MYSQL=0
ROLLBACK=0

# ---- 旧布局 ----
OLD_APP_DIR="/opt/camera-detection-system"
OLD_WORK_DIR="/var/lib/camera-detection-system"
OLD_DATA_MOUNT="/data"
OLD_DATA_BASE="/data/camera-detection"
OLD_CROSS_BASE="/var/backups/camera-detection"

# ---- 新布局 ----
BASE="/main/app"
INSTALL_DIR="${BASE}/dist"
DATA_BASE="${BASE}/data"
LOG_BASE="${BASE}/logs"
RUN_BASE="${BASE}/run"
BACKUP_BASE="${DATA_BASE}/backup"
IMAGES_SRC="${DATA_BASE}/images/defect-images"
IMAGE_TRASH="${DATA_BASE}/images/.cleanup-trash"
MYSQL_DATA_DIR="${DATA_BASE}/mysql"
OFFSITE_BASE="${OLD_CROSS_BASE}"        # 跨盘副本继续放系统盘

DEFAULT_CFG="/etc/default/camera-detection"
MYSQL_TUNE_CNF="/etc/mysql/mysql.conf.d/zz-camera-detection.cnf"
APPRMOR_LOCAL="/etc/apparmor.d/local/usr.sbin.mysqld"
DB_NAME="wms-new"
SERVICE="camera-detection"
SELF_PATH="$(readlink -f "$0")"
SCRIPT_DIR="$(dirname "$SELF_PATH")"
SRC_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
STAMP="$(date '+%Y%m%d%H%M%S')"

log()  { printf '\033[1;36m[migrate]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m  !\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }
run()  {
  # 统一出口：dry-run 只打印，--apply 才真执行
  if [[ "$APPLY" -eq 1 ]]; then
    "$@"
  else
    printf '  [dry-run] %s\n' "$*"
  fi
}

usage() { sed -n '2,26p' "$0"; exit 0; }

# 失败必须报「哪一行、哪条命令、返回码」。
# 没有这个 trap 时 set -e 的退出是**完全静默**的：现场踩过一次，
# `--apply` 停在「M2 挂载点切换」只吐了一个 EXIT=1，看不出是 fuser 流水线被判失败。
# set -E（errtrace）是前提，否则 trap 不进函数体，而本脚本每个阶段都是函数。
trap 'rc=$?; printf "\033[1;31m[失败]\033[0m 第 %s 行（rc=%s）：%s\n" "${LINENO}" "${rc}" "${BASH_COMMAND}" >&2' ERR

# 统一 SQL 查询出口：口令优先从 /etc/default 读（旧布局该文件里就有 MYSQL_PASSWORD），
# 读不到则退回 root socket 认证（Ubuntu 的 root@localhost 用 auth_socket）。
# 直接把 mysql 写在业务行里，会在「依赖 socket 认证」的机器上因 -u 缺省而行为不一致。
mysql_q() {
  local pw=""
  if [[ -r "$DEFAULT_CFG" ]]; then
    pw="$(sed -n 's/^MYSQL_PASSWORD=//p' "$DEFAULT_CFG" | awk 'NR==1' | tr -d '"'"'"'')"
  fi
  if [[ -n "$pw" ]]; then
    MYSQL_PWD="$pw" mysql -N -B -e "$1" 2>/dev/null
  else
    mysql -N -B -e "$1" 2>/dev/null
  fi
}

CUR_DATADIR=""
MOUNT_DONE=0      # 1 = /main 已是数据盘挂载点（断点续跑）
DATA_MOUNT=""     # 当前数据盘挂载点（首次为 /data，续跑为 /main）

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply)       APPLY=1; shift ;;
    --skip-mysql)  SKIP_MYSQL=1; shift ;;
    --rollback)    ROLLBACK=1; shift ;;
    -h|--help)     usage ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done

[[ "$(id -u)" -eq 0 ]] || die "必须以 root 执行：sudo bash $0"

# ==============================================================================
# 只读探测
# ==============================================================================
probe() {
  log "M0 预检"

  [[ -f /etc/os-release ]] || die "无法识别操作系统（缺少 /etc/os-release）"
  ok "系统：$(. /etc/os-release && printf '%s' "${PRETTY_NAME:-unknown}")"

  # 数据盘判定。分两种入口：
  #   ① 首次执行：数据盘挂在 /data
  #   ② 断点续跑：M2 已把同一块盘挂到 /main（上一次执行在 M4 失败时就是这种状态）
  # 不区分这两种，脚本第二次就跑不起来 —— 而「断了能接着跑」是生产机上的硬需求。
  local data_src root_src mnt
  root_src="$(findmnt -n -o SOURCE --target / 2>/dev/null || true)"
  mnt="$OLD_DATA_MOUNT"
  data_src="$(findmnt -n -o SOURCE --target /main 2>/dev/null || true)"
  if [[ -n "$data_src" && "$data_src" != "$root_src" ]]; then
    MOUNT_DONE=1
    mnt="/main"
    ok "断点续跑：/main 已挂载（${data_src}），M2 视为已完成"
  else
    data_src="$(findmnt -n -o SOURCE --target "$OLD_DATA_MOUNT" 2>/dev/null || true)"
    [[ -n "$data_src" ]] || die "${OLD_DATA_MOUNT} 未挂载 —— 本脚本只适用于「数据盘独立挂载」的老布局"
    [[ "$data_src" != "$root_src" ]] || die "${OLD_DATA_MOUNT} 与系统盘同设备（${root_src}），没有独立数据盘可迁"
    ok "数据盘：${OLD_DATA_MOUNT} ← ${data_src}（系统盘 ${root_src}）"
  fi
  DATA_MOUNT="$mnt"

  DATA_UUID="$(blkid -o value -s UUID "$data_src" 2>/dev/null || true)"
  DATA_FSTYPE="$(findmnt -n -o FSTYPE --target "$DATA_MOUNT" 2>/dev/null | awk 'NR==1')"
  [[ -n "$DATA_UUID" ]] || warn "取不到 ${data_src} 的 UUID；--apply 时改用设备名写 fstab（不推荐）"
  ok "UUID=${DATA_UUID:-未取到}  fstype=${DATA_FSTYPE:-未知}  $(df -h "$DATA_MOUNT" | awk 'NR==2{print $2" 总 / "$4" 可用"}')"

  # 目标挂载点必须为空目录或不存在（仅首次执行时检查）
  if [[ "$MOUNT_DONE" -eq 0 && -d /main ]]; then
    if [[ -n "$(ls -A /main 2>/dev/null || true)" ]]; then
      die "/main 已存在且非空：$(ls -A /main | awk 'NR<=3' | tr '\n' ' ')
  这些是旧挂载点残留（多半是装系统时建的空骨架）。请先移到别处**留档**再重跑：
    mv /main/<目录> /root/pre-migrate-main-$(date +%Y%m%d)
  注意：/main 成为挂载点后，盘内原有内容会被遮住，不要往里放任何数据"
    fi
    ok "/main 可用作新挂载点"
  fi

  # 空间账：库 + binlog + uploads 需要复制到数据盘（图片就地 mv 不占额外空间）
  # 注意：du 失败时 `| cut` 仍会吃掉返回码，导致变量为空 ——
  # 空串参与算术会报 "syntax error: operand expected" 并因 set -e 直接退出，
  # 所以必须逐个兜默认值，不能指望 `|| echo 0`。
  local db_mb up_mb avail_mb
  # binlog 在 datadir 内，无需单独计
  db_mb="$(du -sm "${CUR_DATADIR:-/var/lib/mysql}" 2>/dev/null | cut -f1 || true)"
  up_mb="$(du -sm "${OLD_WORK_DIR}/data/uploads" 2>/dev/null | cut -f1 || true)"
  db_mb="${db_mb:-0}"; up_mb="${up_mb:-0}"
  avail_mb="$(df -m --output=avail "$DATA_MOUNT" | tail -1 | tr -dc '0-9')"
  avail_mb="${avail_mb:-0}"
  ok "待复制：MySQL ${db_mb}MB（含 binlog）+ uploads ${up_mb}MB；数据盘可用 ${avail_mb}MB"
  local need_mb=$(( db_mb + up_mb ))
  if [[ "$avail_mb" -lt $(( need_mb * 12 / 10 )) ]]; then
    die "数据盘空间不足：需要约 $(( need_mb * 12 / 10 ))MB，可用 ${avail_mb}MB"
  fi

  # MySQL datadir 现状（口令优先取 /etc/default，取不到则依赖 root socket 认证）
  CUR_DATADIR="$(mysql_q 'SELECT @@datadir;' | tr -d '[:space:]')"
  ok "MySQL datadir：${CUR_DATADIR:-未取到（可能需要口令）}"

  # 关键数据基线（人工核对用）
  # 断点续跑时图片已经在 /main/app/data 下，再去数旧路径只会 find 报错
  # （pipefail 下整条流水线判失败 → set -e 静默退出），所以路径随挂载状态切换。
  local imgs rows imgs_src
  imgs_src="${OLD_DATA_BASE}/images/defect-images"
  [[ "$MOUNT_DONE" -eq 1 ]] && imgs_src="$IMAGES_SRC"
  imgs="$(find "$imgs_src" -type f 2>/dev/null | wc -l | tr -d ' ' || true)"
  # 库名是 `wms-new`（带连字符）→ SQL 里必须反引号，否则 mysql 语法错误；
  # 而 set -e 下 `rows="$(mysql_q ...)"` 一旦非零会直接终止整个预检，连提示都没有。
  rows="$(mysql_q "SELECT COUNT(*) FROM \`${DB_NAME}\`.defect_image_file;" | tr -d '[:space:]' || true)"
  ok "基线：磁盘图片 ${imgs:-0} 个，defect_image_file ${rows:-?} 行"
}

# ==============================================================================
# M1 停流
# ==============================================================================
stop_flow() {
  log "M1 停流（应用 / 备份定时器 / cron）"
  run systemctl stop "$SERVICE" 2>/dev/null || true
  run systemctl stop camera-detection-backup.timer 2>/dev/null || true
  # cron 临时改名：搬目录时清理任务若触发，会把正在搬的图片当垃圾删掉
  local f
  for f in /etc/cron.d/cleanup-defect-images /etc/cron.d/cleanup-test-camera-images /etc/cron.d/check-disk-usage; do
    [[ -f "$f" ]] && run mv "$f" "${f}.migrating"
  done
  ok "已停应用与定时任务（cron 文件加 .migrating 后缀）"
}

# ==============================================================================
# M2 挂载点切换：/data → /main
# ==============================================================================
switch_mount() {
  if [[ "$MOUNT_DONE" -eq 1 ]]; then
    ok "M2 已完成（/main 已是数据盘挂载点），跳过"
    return 0
  fi
  log "M2 挂载点切换 ${OLD_DATA_MOUNT} → /main"
  run systemctl stop mysql 2>/dev/null || true

  # umount 前先看谁占着这块盘：占用者多半是「有人 cd 在里面」或遗留的 tail/find，
  # 直接 umount 会失败，而失败后的 `umount -l` 更糟 —— 写会落到已摘链的挂载上，静默丢数据。
  # ★ 这里不能用 `| head`：head 提前关闭管道 → 上游 SIGPIPE → pipefail 判定整条流水线失败
  #   → set -e 直接静默退出（现场已踩过一次，停在「M2 挂载点切换」没有任何报错）。
  #   用 awk 'NR<=8' 读完全部输入，不产生 SIGPIPE。
  local holders=""
  holders="$(fuser -m "$OLD_DATA_MOUNT" 2>/dev/null | tr -s ' ' '\n' | grep -E '^[0-9]+$' | awk 'NR<=8' | tr '\n' ' ' || true)"
  if [[ -n "$holders" ]]; then
    warn "${OLD_DATA_MOUNT} 仍被进程占用：PID ${holders}"
    if [[ "$APPLY" -eq 1 ]]; then
      die "拒绝继续。请先确认并结束这些进程（ps -fp ${holders}）后重跑"
    fi
  fi

  run cp -a /etc/fstab "/etc/fstab.pre-migrate-${STAMP}"
  ok "fstab 已备份：/etc/fstab.pre-migrate-${STAMP}"

  run umount "$OLD_DATA_MOUNT"
  run mkdir -p /main

  # 原 fstab 条目的挂载选项与 fsck 位必须原样带走：
  # 现场数据盘是 `defaults,noatime`，重建时若只写 `defaults`，图片盘会恢复 atime 更新
  # （写入量成倍增加且毫无收益）—— 这种"迁移后变慢"极难归因。
  local old_line old_opts old_pass entry fspass
  old_line="$(grep -E "[[:space:]]${OLD_DATA_MOUNT}[[:space:]]" /etc/fstab 2>/dev/null | awk 'NR==1')"
  old_opts="$(awk '{print $4}' <<< "${old_line:-}")"
  old_pass="$(awk '{print $6}' <<< "${old_line:-}")"
  old_opts="${old_opts:-defaults}"
  fspass="${old_pass:-2}"
  # XFS 不走 fstab 的 fsck 通道（fsck.xfs 是空壳），留 0 更准确
  [[ "${DATA_FSTYPE:-ext4}" == "xfs" && -z "$old_pass" ]] && fspass=0
  if [[ -n "${DATA_UUID:-}" ]]; then
    entry="UUID=${DATA_UUID} /main ${DATA_FSTYPE:-ext4} ${old_opts} 0 ${fspass}"
  else
    die "缺少 UUID，拒绝写 fstab（设备名顺序变化会导致开机挂不上）"
  fi
  ok "fstab 新条目：${entry}（保留原挂载选项 ${old_opts}）"

  if grep -qE "[[:space:]]${OLD_DATA_MOUNT}[[:space:]]" /etc/fstab; then
    run sed -i "\|${OLD_DATA_MOUNT}[[:space:]]|s|^.*$|${entry}|" /etc/fstab
  else
    run bash -c "printf '%s\n' '$entry' >> /etc/fstab"
  fi
  run systemctl daemon-reload 2>/dev/null || true
  run mount /main

  if [[ "$APPLY" -eq 1 ]]; then
    local src root_src
    src="$(findmnt -n -o SOURCE --target /main 2>/dev/null || true)"
    root_src="$(findmnt -n -o SOURCE --target / 2>/dev/null || true)"
    [[ -n "$src" && "$src" != "$root_src" ]] || die "/main 挂载后仍不是独立设备（${src:-未知}）"
    ok "/main ← ${src}（UUID=${DATA_UUID}，fstype=${DATA_FSTYPE:-ext4}）"
  fi
}

# ==============================================================================
# M3 文件搬迁（图片与备份在同一文件系统内 mv，瞬时完成）
# ==============================================================================
move_files() {
  log "M3 文件搬迁"
  run mkdir -p "$BASE"

  # 原来 /data 下的内容现在出现在 /main 下（同一块盘换了挂载点）
  if [[ -d "/main/camera-detection" ]]; then
    run mkdir -p "${DATA_BASE%/*}"
    run mv "/main/camera-detection" "$DATA_BASE"
    ok "/main/camera-detection → ${DATA_BASE}（同盘 mv，瞬时）"
  elif [[ "$APPLY" -eq 0 && -d "${OLD_DATA_BASE}" ]]; then
    # dry-run 时挂载点还没切，按「切换后它就在 /main 下」预告真实动作
    ok "（dry-run）M2 切换后 ${OLD_DATA_BASE} 将出现在 /main/camera-detection，届时同盘 mv → ${DATA_BASE}"
  else
    warn "/main/camera-detection 不存在 —— 若已迁移过可忽略"
    run mkdir -p "$DATA_BASE"
  fi

  run mkdir -p "$IMAGES_SRC" "$IMAGE_TRASH" \
            "${DATA_BASE}/uploads/images" \
            "${BACKUP_BASE}/db/full" "${BACKUP_BASE}/db/binlog" "${BACKUP_BASE}/db/logs" \
            "$LOG_BASE" "$RUN_BASE" "${BASE}/tmp" "${BASE}/backup"

  # 程序：优先用**发布包**（其脚本默认路径已指向 /main/app），
  # 否则兜底拷 /opt —— 但那样 dist 里是旧路径脚本，后续必然出「cron 调新路径、
  # 脚本写旧路径」的分裂，只能临时兜底并告警。
  run mkdir -p "$INSTALL_DIR"
  local payload=""
  if [[ -x "${SRC_DIR}/scripts/run.sh" && -f "${SRC_DIR}/config/application.yml" ]]; then
    payload="$SRC_DIR"
  fi
  if [[ -n "$payload" ]]; then
    run rsync -a --exclude='*.bak-*' "${payload}/" "${INSTALL_DIR}/"
    ok "发布包 ${payload} → ${INSTALL_DIR}（新脚本 + 新配置模板）"
  elif [[ -d "$OLD_APP_DIR" ]]; then
    run rsync -aHAX --exclude='*.bak-*' "${OLD_APP_DIR}/" "${INSTALL_DIR}/"
    warn "${OLD_APP_DIR} → ${INSTALL_DIR}（兜底：脚本仍是旧路径版，务必随后换新发布包）"
  else
    warn "${OLD_APP_DIR} 不存在，跳过程序搬迁"
  fi

  # 现场外部配置必须覆盖包内模板：按 key 合并的机制下，
  # 包里模板没有的 site 级调优（log-impl、mapper 日志级别、归档保留期）会静默丢失。
  if [[ -f "${OLD_APP_DIR}/config/application.yml" ]]; then
    run cp -p "${OLD_APP_DIR}/config/application.yml" "${INSTALL_DIR}/config/application.yml"
    ok "已保留现场配置 /opt/.../config/application.yml"
    if [[ "$APPLY" -eq 1 ]]; then
      # 其中的图片目录是旧绝对路径，必须跟着布局改，否则新进程仍往旧路径写
      sed -i "s|${OLD_DATA_BASE}/images/defect-images|${IMAGES_SRC}|g" "${INSTALL_DIR}/config/application.yml"
      ok "配置内图片目录已改为 ${IMAGES_SRC}"
    fi
  fi

  # JAR 以「线上正在跑的那份」为准：阶段 A 刚更新过，
  # 用包内旧 JAR 会把版本静默回退（健康检查照样 200，最难发现的那种回退）。
  local jar_src="${OLD_APP_DIR}/app/time-bucket-demo.jar"
  if [[ -f "$jar_src" ]]; then
    run mkdir -p "${INSTALL_DIR}/app"
    run cp -p "$jar_src" "${INSTALL_DIR}/app/time-bucket-demo.jar"
    ok "沿用线上 JAR（$(stat -c %s "$jar_src") 字节）"
  fi

  # 日志与上传：跨盘 rsync（体积小）
  if [[ -d "${OLD_WORK_DIR}/logs" ]]; then
    run rsync -a "${OLD_WORK_DIR}/logs/" "${LOG_BASE}/"
    ok "业务日志 → ${LOG_BASE}"
  fi
  if [[ -d "${OLD_WORK_DIR}/data/uploads" ]]; then
    run rsync -a "${OLD_WORK_DIR}/data/uploads/" "${DATA_BASE}/uploads/"
    ok "上传目录 → ${DATA_BASE}/uploads"
  fi

  if [[ "$APPLY" -eq 1 ]]; then
    # ★ 不能 `chown -R cdsys:cdsys "$BASE"`：那会把 data/mysql 也改成 cdsys，
    #   mysqld 当时还能跑（已打开的句柄），**下次重启才失败** —— 最难查的一类延迟故障。
    #   所以按用途逐个 chown，结构性跳过 datadir。
    local p
    for p in "$INSTALL_DIR" "$LOG_BASE" "$RUN_BASE" "${BASE}/tmp" "${BASE}/backup" \
             "${DATA_BASE}/images" "${DATA_BASE}/uploads" "${DATA_BASE}/backup"; do
      [[ -e "$p" ]] && chown -R cdsys:cdsys "$p"
    done
    chown cdsys:cdsys "$BASE" "$DATA_BASE" 2>/dev/null || true
    # 数据库目录属主必须是 mysql，且权限 700（MySQL 拒绝过宽的 datadir）
    [[ -d "$MYSQL_DATA_DIR" ]] && { chown -R mysql:mysql "$MYSQL_DATA_DIR"; chmod 700 "$MYSQL_DATA_DIR"; }
    ok "属主已归位（cdsys 拥有 ${BASE}（不含 datadir），mysql 拥有 ${MYSQL_DATA_DIR}）"
  fi
}

# ==============================================================================
# M4 MySQL datadir
# ==============================================================================
# MySQL 需要对 datadir 的**每一级祖先目录**有 x（搜索）权限。
# 现场踩过：图片/备份目录是从旧 /data/camera-detection（750 cdsys）继承下来的，
# 搬成 /main/app/data 后仍是 750 cdsys；mysql 用户既非属主也不在 cdsys 组，
# 于是 chdir 失败，mysqld 只报 "Failed to set datadir ... OS errno 13" ——
# 看起来像 AppArmor 拦的，实际是普通 DAC 权限。两层都要处理，缺一不可。
mysql_traversable() {
  if [[ "$APPLY" -eq 1 ]]; then
    chmod 755 "$BASE" "$DATA_BASE" 2>/dev/null || true
  else
    printf '  [dry-run] chmod 755 %s %s（mysql 需要能搜索到 datadir）\n' "$BASE" "$DATA_BASE"
  fi
}
migrate_datadir() {
  if [[ "$SKIP_MYSQL" -eq 1 ]]; then
    warn "M4 已跳过（--skip-mysql）"
    return 0
  fi
  log "M4 MySQL datadir → ${MYSQL_DATA_DIR}"
  local src_datadir="${CUR_DATADIR:-/var/lib/mysql}"
  src_datadir="${src_datadir%/}"
  if [[ "$src_datadir" == "$MYSQL_DATA_DIR" ]]; then
    ok "M4 已完成（datadir 已是 ${MYSQL_DATA_DIR}），跳过复制"
    mysql_traversable
    return 0
  fi
  # mysql 在 M2 已 stop；这里是冷拷贝，源目录只读不动，失败可直接丢弃目标重来
  run mkdir -p "$MYSQL_DATA_DIR"
  run rsync -aHAX "${src_datadir}/" "${MYSQL_DATA_DIR}/"
  if [[ "$APPLY" -eq 1 ]]; then
    chown -R mysql:mysql "$MYSQL_DATA_DIR"
    chmod 700 "$MYSQL_DATA_DIR"
    mysql_traversable
    ok "已复制 $(du -sh "$MYSQL_DATA_DIR" | cut -f1)（原 ${src_datadir} 保留作回滚）"
  fi

  # AppArmor 放行必须在 start 之前 —— 否则 mysqld 启动即被拒（Errcode 13）
  if [[ -d /etc/apparmor.d ]]; then
    if [[ "$APPLY" -eq 1 ]]; then
      mkdir -p "$(dirname "$APPRMOR_LOCAL")"
      touch "$APPRMOR_LOCAL"
      if ! grep -qF "${MYSQL_DATA_DIR}/" "$APPRMOR_LOCAL"; then
        {
          printf '\n# 产线缺陷在线AI检测系统：MySQL datadir 迁移（migrate-to-main-app.sh 追加）\n'
          printf '  %s/ r,\n'  "$MYSQL_DATA_DIR"
          printf '  %s/** rwk,\n' "$MYSQL_DATA_DIR"
        } >> "$APPRMOR_LOCAL"
        apparmor_parser -r /etc/apparmor.d/usr.sbin.mysqld 2>/dev/null \
          || warn "AppArmor 规则重载失败（未强制启用可忽略）"
        ok "AppArmor 已放行 ${MYSQL_DATA_DIR}"
      fi
    else
      printf '  [dry-run] 追加 AppArmor 规则 %s 并重载\n' "$APPRMOR_LOCAL"
    fi
  fi

  # datadir 写进独立 cnf（便于回滚：删这个文件即可）
  if [[ "$APPLY" -eq 1 ]]; then
    [[ -f "$MYSQL_TUNE_CNF" ]] && cp -a "$MYSQL_TUNE_CNF" "${MYSQL_TUNE_CNF}.pre-migrate-${STAMP}"
    local pool_mb
    pool_mb=$(( $(awk '/MemTotal/{print $2}' /proc/meminfo) / 1024 / 2 ))
    [[ "$pool_mb" -gt 4096 ]] && pool_mb=4096
    [[ "$pool_mb" -lt 256 ]] && pool_mb=256
    cat > "$MYSQL_TUNE_CNF" <<EOF
# 产线缺陷在线AI检测系统 —— 由 migrate-to-main-app.sh 生成（原文件已备份 *.pre-migrate-${STAMP}）
[mysqld]
datadir=${MYSQL_DATA_DIR}
innodb_buffer_pool_size=${pool_mb}M
innodb_flush_log_at_trx_commit=2
max_connections=200
bind-address=127.0.0.1
EOF
    chmod 0644 "$MYSQL_TUNE_CNF"
    systemctl reset-failed mysql 2>/dev/null || true
    systemctl start mysql
    local i cur=""
    for i in $(seq 1 20); do
      cur="$(mysql_q 'SELECT @@datadir;' | tr -d '[:space:]')"
      [[ -n "$cur" ]] && break
      sleep 2
    done
    [[ "${cur%/}" == "$MYSQL_DATA_DIR" ]] \
      || die "MySQL 未使用新 datadir（实际 ${cur:-无响应}）。回滚：rm ${MYSQL_TUNE_CNF} 后 systemctl start mysql"
    ok "datadir=${cur}"
  else
    printf '  [dry-run] 写 %s 的 datadir=%s，重启 mysql 并断言\n' "$MYSQL_TUNE_CNF" "$MYSQL_DATA_DIR"
  fi
}

# ==============================================================================
# M5 配置切换：/etc/default + unit + cron + logrotate + symlink 兼容层
# ==============================================================================
write_config() {
  log "M5 配置切换"

  # 保留现场口令与密钥（整份文件重写，不保留就丢）
  local old_pw old_admin_user old_admin_pw old_jwt old_java old_opts old_protect
  old_pw="$(sed -n 's/^MYSQL_PASSWORD="\(.*\)"$/\1/p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"
  old_admin_user="$(sed -n 's/^ADMIN_USER="\(.*\)"$/\1/p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"
  old_admin_pw="$(sed -n 's/^ADMIN_PASSWORD="\(.*\)"$/\1/p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"
  old_jwt="$(sed -n 's/^JWT_SECRET="\(.*\)"$/\1/p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"
  old_java="$(sed -n 's/^JAVA_HOME=//p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"
  old_opts="$(sed -n 's/^JAVA_OPTS="\(.*\)"$/\1/p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"
  # 现场若已手工加过保护相机编号，重写会丢 —— 丢了等于清理任务突然放开，必须带回
  old_protect="$(sed -n 's/^PROTECTED_CODES="\(.*\)"$/\1/p' "$DEFAULT_CFG" 2>/dev/null | awk 'NR==1')"

  [[ -n "$old_jwt" ]] || die "读不到 JWT_SECRET —— 拒绝重写配置（会导致所有登录令牌失效且服务起不来）"

  if [[ "$APPLY" -eq 1 ]]; then
    [[ -f "$DEFAULT_CFG" ]] && cp -a "$DEFAULT_CFG" "${DEFAULT_CFG}.pre-migrate-${STAMP}"
    cat > "$DEFAULT_CFG" <<EOF
# 产线缺陷在线AI检测系统 —— 运行参数（2026-11 统一布局，迁移于 ${STAMP}）
# 改这个文件即可，无需改 systemd unit。改完执行：systemctl restart ${SERVICE}

JAVA_OPTS="${old_opts:--Xms512m -Xmx1024m}"
JAVA_HOME=${old_java:-/usr/lib/jvm/java-8-openjdk-amd64}

# ---- 统一布局根 ----
BASE=${BASE}
INSTALL_DIR=${INSTALL_DIR}
WORK_DIR=${BASE}
APP_HOME=${BASE}
DATA_BASE=${DATA_BASE}
LOG_BASE=${LOG_BASE}
RUN_BASE=${RUN_BASE}
IMAGES_SRC=${IMAGES_SRC}
IMAGE_TRASH=${IMAGE_TRASH}
MYSQL_DATA_DIR=${MYSQL_DATA_DIR}
OFFSITE_BASE=${OFFSITE_BASE}

CONFIG_FILE=${INSTALL_DIR}/config/application.yml

MYSQL_PASSWORD="${old_pw}"
ADMIN_USER="${old_admin_user:-admin}"
ADMIN_PASSWORD="${old_admin_pw}"
JWT_SECRET="${old_jwt}"

# ---- 备份相关 ----
BACKUP_DIR=${BACKUP_BASE}/db
KEEP_FULL_DAYS=14
MAX_DISK_USE=85
OFFSITE_DIR=${OFFSITE_BASE}/db
IMAGES_DST=${OFFSITE_BASE}/images
IMAGE_BACKUP_MAX_GB=20

# ---- 测试相机清理 ----
PROTECTED_CODES="${old_protect}"
MAX_SINGLE_DELETE=50000
EOF
    chmod 640 "$DEFAULT_CFG"
    chown root:root "$DEFAULT_CFG"
    ok "${DEFAULT_CFG} 已重写（旧文件备份为 .pre-migrate-${STAMP}）"

    # unit：优先用发布包里的新版；没有就地对旧 unit 改路径 + 补 RequiresMountsFor
    local u
    for u in camera-detection.service camera-detection-backup.service camera-detection-backup.timer; do
      [[ -f "/etc/systemd/system/${u}" ]] && cp -a "/etc/systemd/system/${u}" "/etc/systemd/system/${u}.pre-migrate-${STAMP}"
      if [[ -f "${SRC_DIR}/systemd/${u}" ]]; then
        install -m 0644 "${SRC_DIR}/systemd/${u}" "/etc/systemd/system/${u}"
      elif [[ -f "/etc/systemd/system/${u}" ]]; then
        sed -i "s|/opt/camera-detection-system|${INSTALL_DIR}|g; s|/var/lib/camera-detection-system|${BASE}|g; s|/data/camera-detection|${DATA_BASE}|g" "/etc/systemd/system/${u}"
        # 旧 unit 只写 ReadWritePaths=/opt/...，替换后会变成 /main/app/dist ——
        # 服务能启动但写不了 ${BASE}/logs 与 ${DATA_BASE}（日志空、上传报错）。
        # 归一成布局根，宁可多给一层也不留半残权限。
        if grep -q '^ReadWritePaths=' "/etc/systemd/system/${u}"; then
          sed -i "s|^ReadWritePaths=.*|ReadWritePaths=${BASE}|" "/etc/systemd/system/${u}"
        fi
        grep -q '^RequiresMountsFor=' "/etc/systemd/system/${u}" \
          || sed -i "0,/^\[Service\]/s//[Service]\nRequiresMountsFor=\/main/" "/etc/systemd/system/${u}" 2>/dev/null || true
      fi
    done
    systemctl daemon-reload
    systemctl enable "$SERVICE" >/dev/null 2>&1 || true
    systemctl enable camera-detection-backup.timer >/dev/null 2>&1 || true
    ok "unit 已更新并 daemon-reload"
  else
    printf '  [dry-run] 重写 %s（保留口令/JWT/JAVA_HOME）\n' "$DEFAULT_CFG"
    printf '  [dry-run] 重装 3 个 unit + daemon-reload\n'
  fi
}

write_cron_logrotate() {
  log "M5b cron 与 logrotate"

  if [[ "$APPLY" -eq 1 ]]; then
    # ★ 三个 cron 的内容必须与 install.sh 第 8 节的生成结果**逐字一致**：
    #   cron 是生成物，两条路径（全新安装 / 现场迁移）产出不同文本，
    #   后续「对比现场与新装差异」就再也对不上了。改这里要同步改 install.sh。
    cat > /etc/cron.d/cleanup-defect-images <<EOF
# 每天 03:30 清理过期缺陷图片磁盘文件（错开应用 02:00 清理与 02:30 归档任务）
# ★ 保留期必须与图片归档月表口径一致（application.yml app.archive.images.retain-days，默认 730 天）：
#   两边不同步会出现「库里有记录、磁盘没文件」的裂图。改这里之前先改 application.yml。
# ★ 口令不写进 cron（cron 全局可读）：脚本自己从 /etc/default/camera-detection 读取。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
30 3 * * * root DIR="${IMAGES_SRC}" KEEP_DAYS=730 KEEP_CAMERA_IMAGES_DAYS=30 DB_NAME="wms-new" ${INSTALL_DIR}/scripts/cleanup-defect-images.sh >> ${LOG_BASE}/cleanup-defect-images.log 2>&1
EOF
    cat > /etc/cron.d/cleanup-test-camera-images <<EOF
# 每小时 :15 清理测试相机的无效图片与对应数据库记录
# 真实相机（camera_type='REAL'）的数据一律不参与清理。
# 口令由脚本自己从 /etc/default/camera-detection 读取，此处不写口令。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
15 * * * * root IMG_ROOT="${IMAGES_SRC}" TRASH="${IMAGE_TRASH}" LOG="${LOG_BASE}/cleanup_zk54.log" ${INSTALL_DIR}/scripts/cleanup-zk54-incremental.sh
EOF
    cat > /etc/cron.d/check-disk-usage <<EOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
*/30 * * * * root MOUNTS="/ /main" CAPACITY_MOUNT="/main" ${INSTALL_DIR}/scripts/check-disk-usage.sh >> ${LOG_BASE}/disk-alert.log 2>&1
EOF
    chmod 0644 /etc/cron.d/cleanup-defect-images /etc/cron.d/cleanup-test-camera-images /etc/cron.d/check-disk-usage
    ok "cron 已按新路径重写（3 个任务）"

    cat > /etc/logrotate.d/camera-detection <<EOF
${LOG_BASE}/camera-detection-system.log
${LOG_BASE}/backup.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    su cdsys cdsys
}

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
    ok "logrotate 已指向 ${LOG_BASE}"
  else
    printf '  [dry-run] 重写 3 个 cron 与 logrotate（全部指向 %s）\n' "$INSTALL_DIR"
  fi
}

make_symlinks() {
  log "M5c 旧路径兼容层（数据库里存的是旧绝对路径）"
  if [[ "$APPLY" -eq 1 ]]; then
    # /opt/... → dist
    if [[ -d "$OLD_APP_DIR" && ! -L "$OLD_APP_DIR" ]]; then
      mv "$OLD_APP_DIR" "${OLD_APP_DIR}.migrated-${STAMP}"
      ln -sfn "$INSTALL_DIR" "$OLD_APP_DIR"
      ok "${OLD_APP_DIR} → ${INSTALL_DIR}"
    fi
    # /var/lib/... → BASE（旧日志路径仍可解析）
    if [[ -d "$OLD_WORK_DIR" && ! -L "$OLD_WORK_DIR" ]]; then
      mv "$OLD_WORK_DIR" "${OLD_WORK_DIR}.migrated-${STAMP}"
      ln -sfn "$BASE" "$OLD_WORK_DIR"
      ok "${OLD_WORK_DIR} → ${BASE}"
    fi
    # /data/camera-detection → DATA_BASE（defect_image_file.image_path 兜底解析）
    # 注意：M3 已把 /main/camera-detection 搬成 ${DATA_BASE}，所以这里通常**已不存在**，
    # 必须允许"不存在时直接建 symlink"，否则旧绝对路径兜底就断了。
    if [[ ! -L "$OLD_DATA_BASE" ]]; then
      [[ -d "$OLD_DATA_BASE" ]] && mv "$OLD_DATA_BASE" "${OLD_DATA_BASE}.migrated-${STAMP}"
      mkdir -p "$(dirname "$OLD_DATA_BASE")"
      ln -sfn "$DATA_BASE" "$OLD_DATA_BASE"
      ok "${OLD_DATA_BASE} → ${DATA_BASE}"
    fi
  else
    printf '  [dry-run] 建立 3 个 symlink：%s→dist、%s→%s、%s→%s\n' \
      "$OLD_APP_DIR" "$OLD_WORK_DIR" "$BASE" "$OLD_DATA_BASE" "$DATA_BASE"
  fi
}

# ==============================================================================
# M6 验收
# ==============================================================================
final_verify() {
  log "M6 验收"

  # 恢复 cron（把 .migrating 改回来）
  local f
  for f in /etc/cron.d/cleanup-defect-images /etc/cron.d/cleanup-test-camera-images /etc/cron.d/check-disk-usage; do
    [[ -f "${f}.migrating" ]] && run mv "${f}.migrating" "$f"
  done

  if [[ "$APPLY" -eq 0 ]]; then
    echo
    ok "dry-run 结束 —— 未做任何改动。确认无误后执行： sudo bash $0 --apply"
    return 0
  fi

  systemctl reset-failed "$SERVICE" 2>/dev/null || true
  systemctl start "$SERVICE"

  local i state=""
  for i in $(seq 1 90); do
    state="$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 4 "http://127.0.0.1:8091/" 2>/dev/null || true)"
    [[ "$state" == "200" ]] && break
    sleep 2
  done
  [[ "$state" == "200" ]] || die "应用未就绪（HTTP ${state:-000}）。查看：journalctl -u ${SERVICE} -n 80"
  ok "应用已就绪：HTTP 200"

  local dd
  dd="$(mysql_q 'SELECT @@datadir;' | tr -d '[:space:]')"
  [[ "${dd%/}" == "$MYSQL_DATA_DIR" ]] || warn "datadir 仍为 ${dd:-未知}（--skip-mysql 时正常）"
  [[ "${dd%/}" == "$MYSQL_DATA_DIR" ]] && ok "datadir=${dd}"

  # 读图冒烟：取一条 DB 里的旧绝对路径，验证「新配置根 + 旧路径兜底」都能解析
  local img nimg
  img="$(mysql_q "SELECT image_path FROM \`${DB_NAME}\`.defect_image_file WHERE image_path IS NOT NULL AND image_path<>'' ORDER BY id DESC LIMIT 1;" | awk 'NR==1' || true)"
  if [[ -n "$img" ]]; then
    nimg="${img/\/data\/camera-detection/${DATA_BASE}}"
    if [[ -f "$nimg" ]]; then ok "新版图片路径存在：${nimg}"; else warn "新版路径不存在：${nimg}"; fi
    if [[ -f "$img" && "$img" != "$nimg" ]]; then ok "旧绝对路径仍可解析（symlink 生效）：${img}"; fi
  else
    warn "defect_image_file 无图片记录，跳过读图冒烟"
  fi

  systemctl start camera-detection-backup.timer 2>/dev/null || true
  ok "备份定时器已恢复"

  if [[ -x "${INSTALL_DIR}/scripts/verify.sh" ]]; then
    VERIFY_PASSWORD="$(sed -n 's/^ADMIN_PASSWORD="\(.*\)"$/\1/p' "$DEFAULT_CFG" | awk 'NR==1')" \
      bash "${INSTALL_DIR}/scripts/verify.sh" || warn "verify.sh 有未通过项，见上方输出"
  fi
}

# ==============================================================================
# 回滚
# ==============================================================================
rollback() {
  log "回滚到旧布局"
  [[ "$APPLY" -eq 1 ]] || die "回滚必须显式加 --apply：sudo bash $0 --rollback --apply"

  systemctl stop "$SERVICE" 2>/dev/null || true
  systemctl stop camera-detection-backup.timer 2>/dev/null || true

  # 1) MySQL：删 datadir 行 → 起旧库
  if [[ -f "$MYSQL_TUNE_CNF" ]]; then
    if grep -q "^datadir=" "$MYSQL_TUNE_CNF"; then
      sed -i '/^datadir=/d' "$MYSQL_TUNE_CNF"
      ok "已从 ${MYSQL_TUNE_CNF} 移除 datadir"
    fi
  fi
  if [[ -f "$APPRMOR_LOCAL" ]]; then
    sed -i "\|${MYSQL_DATA_DIR}|d" "$APPRMOR_LOCAL" 2>/dev/null || true
    apparmor_parser -r /etc/apparmor.d/usr.sbin.mysqld 2>/dev/null || true
  fi
  systemctl start mysql || warn "MySQL 启动失败，请手工检查 /var/lib/mysql"
  ok "MySQL 已回到 /var/lib/mysql"

  # 2) 应用配置
  local bak
  bak="$(ls -1t "${DEFAULT_CFG}".pre-migrate-* 2>/dev/null | awk 'NR==1' || true)"
  [[ -n "$bak" ]] && { cp -a "$bak" "$DEFAULT_CFG"; ok "已还原 ${bak}"; }

  # 3) symlink 还原
  local p
  for p in "$OLD_APP_DIR" "$OLD_WORK_DIR" "$OLD_DATA_BASE"; do
    if [[ -L "$p" ]]; then
      rm -f "$p"
      local mbak; mbak="$(ls -1d "${p}.migrated-"* 2>/dev/null | awk 'NR==1' || true)"
      [[ -n "$mbak" ]] && mv "$mbak" "$p" && ok "已还原目录 ${p}"
    fi
  done

  # 4) 数据归位：/main/app/data → /main/camera-detection（M3 的逆操作，同盘 mv 瞬时）
  if [[ -d "$DATA_BASE" && ! -e "/main/camera-detection" ]]; then
    mv "$DATA_BASE" "/main/camera-detection" 2>/dev/null \
      && ok "数据已回到 /main/camera-detection（挂载点还原后即为 /data/camera-detection）" \
      || warn "数据回搬失败，请手工检查 /main"
  fi

  # 5) 挂载点还原 /main → /data
  if findmnt -n -o TARGET /main >/dev/null 2>&1; then
    local uuid fstab_bak
    uuid="$(blkid -o value -s UUID "$(findmnt -n -o SOURCE /main)" 2>/dev/null || true)"
    umount /main
    # 优先整份还原 fstab 备份（M2 前留的），比 sed 删行更可控：
    # sed 按 "/main " 匹配，会误删注释里提到 /main 的行。
    fstab_bak="$(ls -1t /etc/fstab.pre-migrate-* 2>/dev/null | awk 'NR==1' || true)"
    if [[ -n "$fstab_bak" ]]; then
      cp -a "$fstab_bak" /etc/fstab && ok "fstab 已从 ${fstab_bak} 还原"
    else
      sed -i "\|/main[[:space:]]|d" /etc/fstab
      [[ -n "$uuid" ]] && printf 'UUID=%s /data %s defaults 0 2\n' "$uuid" "${DATA_FSTYPE:-ext4}" >> /etc/fstab
      warn "未找到 fstab 备份，已按 UUID 重建 /data 条目"
    fi
    mkdir -p /data
    mount /data
    findmnt -n -o TARGET /data >/dev/null 2>&1 || warn "/data 未挂载成功，请手工检查 fstab"
    systemctl daemon-reload 2>/dev/null || true
    ok "挂载点已还原为 /data"
  fi

  systemctl daemon-reload
  systemctl start "$SERVICE" 2>/dev/null || warn "应用启动失败，查看 journalctl -u ${SERVICE} -n 80"
  ok "回滚完成 —— 请复核 verify.sh"
}

# ==============================================================================
# 主流程
# ==============================================================================
if [[ "$ROLLBACK" -eq 1 ]]; then
  rollback
  exit 0
fi

echo
log "模式：$([[ "$APPLY" -eq 1 ]] && echo 'APPLY（真正执行）' || echo 'DRY-RUN（零写操作）')"
probe
stop_flow
switch_mount
move_files
migrate_datadir
write_config
write_cron_logrotate
make_symlinks
final_verify
