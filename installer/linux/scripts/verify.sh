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
INSTALL_DIR="${INSTALL_DIR:-/main/app/dist}"
WORK_DIR="${WORK_DIR:-/main/app}"

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

# --- 代理免疫 -----------------------------------------------------------------
# 验收全部走 127.0.0.1 本机回环。桌面版 Ubuntu 常配 http_proxy 环境变量，
# curl 会把 127.0.0.1 的请求也交给代理 → 代理连不上 → 全部判 000，
# 表现为"应用明明就绪、验收 12 项全红"。强制绕过一切代理。
export NO_PROXY='*' no_proxy='*'
CURL_NOPROXY=('--noproxy' '*')

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
DEFECT_IMAGE_DIR="${DEFECT_IMAGE_DIR:-/main/app/data/images/defect-images/}"
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

# --- 3a. 统一布局与数据盘（2026-11 新增）------------------------------------
# 背景：全部产品文件收敛到 /main/app，/main 是**数据盘挂载点**。
# 最危险的故障模式是「数据盘没挂上」—— 此时 /main 只是系统盘上的普通目录，
# 程序与数据静默写进系统盘，重启或修好挂载后看起来"数据全丢"，且没有任何报错。
# 因此这里把「挂载、unit 依赖、fstab、结构、图片白名单、跨盘副本」全部变成可验证项。
# 只在 /etc/default 声明了 BASE 的机器上检查（老布局机器跳过 [3a] 这 6 项，
# 因此老布局是 35 项、统一布局是 42 项；改动本文件的检查项数时两个数都要跟着改文档）。
LAYOUT_ENABLED=0
grep -q '^BASE=' /etc/default/camera-detection 2>/dev/null && LAYOUT_ENABLED=1

if [[ "$LAYOUT_ENABLED" -eq 1 ]]; then
  BASE="${BASE:-/main/app}"
  BASE_MOUNT="$(dirname "$BASE")"
  echo
  echo "${C_HEAD}[3a] 统一布局与数据盘${C_OFF}"

  MAIN_SRC="$(findmnt -n -o SOURCE --target "$BASE_MOUNT" 2>/dev/null || true)"
  ROOT_SRC="$(findmnt -n -o SOURCE --target / 2>/dev/null || true)"
  if [[ -n "$MAIN_SRC" && "$MAIN_SRC" != "$ROOT_SRC" ]]; then
    check "数据盘挂载" true "${BASE_MOUNT} ← ${MAIN_SRC}（系统盘 ${ROOT_SRC}）"
  else
    check "数据盘挂载" false "${BASE_MOUNT} 与系统盘同设备（${ROOT_SRC:-未知}）—— 数据会静默写进系统盘"
  fi

  if grep -qx "RequiresMountsFor=${BASE_MOUNT}" /etc/systemd/system/camera-detection.service 2>/dev/null; then
    check "unit 挂载依赖" true "RequiresMountsFor=${BASE_MOUNT}"
  else
    check "unit 挂载依赖" false "camera-detection.service 缺 RequiresMountsFor=${BASE_MOUNT} —— 盘未挂载时会启动并写错盘"
  fi

  FSTAB_LINE="$(awk -v m="$BASE_MOUNT" '$2==m' /etc/fstab 2>/dev/null | head -1)"
  if [[ -z "$FSTAB_LINE" ]]; then
    check "fstab 挂载项" false "/etc/fstab 无 ${BASE_MOUNT} 条目 —— 重启后不会自动挂载"
  elif [[ "$FSTAB_LINE" == *nofail* ]]; then
    check "fstab 挂载项" false "${BASE_MOUNT} 带 nofail —— 盘故障时会静默退化为系统盘目录"
  else
    check "fstab 挂载项" true "${BASE_MOUNT} 无 nofail"
  fi

  LAYOUT_MISSING=""
  for _p in "${BASE}/dist/app/time-bucket-demo.jar" "${BASE}/dist/config/application.yml" \
            "${BASE}/data/images/defect-images" "${BASE}/logs" \
            "${BASE}/data/backup/db/full" "${BASE}/run"; do
    [[ -e "$_p" ]] || LAYOUT_MISSING="${LAYOUT_MISSING} ${_p#"${BASE}"/}"
  done
  if [[ -z "$LAYOUT_MISSING" ]]; then
    check "布局完整性" true "dist / data / logs / run 齐备"
  else
    check "布局完整性" false "缺少：${LAYOUT_MISSING# }"
  fi

  case "$DEFECT_IMAGE_DIR" in
    */defect-images*) check "图片路径白名单" true "$DEFECT_IMAGE_DIR" ;;
    *) check "图片路径白名单" false "${DEFECT_IMAGE_DIR:-未配置} 不含 /defect-images/ —— Java 读图会全部 403" ;;
  esac

  # 注意：BACKUP_FULL_DIR 在 3c 才定义，这里必须自带默认值（set -u 下未定义即崩）
  OFF_DB="${OFFSITE_DIR:-/var/backups/camera-detection/db}"
  BAK_DIR_3A="${BACKUP_FULL_DIR:-/main/app/data/backup/db/full}"
  OFF_SRC="$(df --output=source "$OFF_DB" 2>/dev/null | tail -1 || true)"
  BAK_SRC="$(df --output=source "$BAK_DIR_3A" 2>/dev/null | tail -1 || true)"
  if [[ -d "$OFF_DB" && -n "$OFF_SRC" && "$OFF_SRC" != "$BAK_SRC" ]]; then
    check "跨盘副本" true "${OFF_SRC} ≠ ${BAK_SRC}（系统盘上的副本）"
  elif [[ -d "$OFF_DB" ]]; then
    check "跨盘副本" false "${OFF_DB} 与主备份同盘（${BAK_SRC:-?}）—— 不抗单盘故障"
  else
    check "跨盘副本" false "${OFF_DB} 不存在 —— 数据盘故障将同时丢失库与备份"
  fi
fi

# 从 /etc/default 读数据库口令（640 root）。**定义必须放在第一个使用点之前** ——
# 3d 与第 7 节都要用；原先定义在第 7 节，早于它的段落只能各自重复读取逻辑。
_read_db_pw() {
  local pw="${MYSQL_PASSWORD:-}"
  if [[ -z "$pw" && -r /etc/default/camera-detection ]]; then
    pw="$(sed -n 's/^MYSQL_PASSWORD=["'\'']\{0,1\}\([^"'\'']*\)["'\'']\{0,1\}$/\1/p' \
      /etc/default/camera-detection | head -1)"
  fi
  echo "$pw"
}

# --- 3b. 保留期口径一致性（v2 P0-4）-----------------------------------------
# 背景：图片归档月表按 config/application.yml 的 app.archive.images.retain-days
# （730 天）保留，磁盘上的日期目录却是由 /etc/cron.d 里的 KEEP_DAYS 删的。
# 两边口径必须一致 —— install.sh 曾经把 cron 写成 KEEP_DAYS=7，重装一次就会把
# 7 天前的图片删掉、库里记录仍在（大面积裂图）。这个检查把「跨进程的隐式契约」
# 变成可验证项：改了一边忘了另一边，verify 直接报红。
YML_FILE="${INSTALL_DIR}/config/application.yml"
# 可用环境变量覆盖，仅为便于在非现场环境做「口径不一致必须报红」的负向测试
CRON_FILE="${CRON_FILE:-/etc/cron.d/cleanup-defect-images}"

# ★ 注意「外部配置不全」是常态：Spring 按 key 合并，外部 application.yml 只覆盖它写了
#   的键，没写的走 JAR 内 BOOT-INF/classes/application.yml。现场实测外部配置 100 行里
#   一个 app.archive.* 都没有（归档任务日志里 hotDays=31 就是 JAR 默认值）。
#   所以取不到时必须回退到 JAR 内默认值 —— 否则这个检查会变成「外部配置没写就报红」，
#   那是误报，会训练人直接忽略它。
ARCHIVE_RETAIN="$(awk '/^[[:space:]]*retain-days:/{v=$2; gsub(/[^0-9]/,"",v); print v; exit}' \
  "$YML_FILE" 2>/dev/null)"
RETAIN_SOURCE="$YML_FILE"
if [[ -z "$ARCHIVE_RETAIN" && -f "${INSTALL_DIR}/app/time-bucket-demo.jar" ]] \
     && command -v unzip >/dev/null 2>&1; then
  ARCHIVE_RETAIN="$(unzip -p "${INSTALL_DIR}/app/time-bucket-demo.jar" \
    BOOT-INF/classes/application.yml 2>/dev/null \
    | awk '/^[[:space:]]*retain-days:/{v=$2; gsub(/[^0-9]/,"",v); print v; exit}')"
  RETAIN_SOURCE="JAR 内默认（外部配置未覆盖）"
fi
CRON_KEEP="$(grep -oE 'KEEP_DAYS=[0-9]+' "$CRON_FILE" 2>/dev/null | head -1 | cut -d= -f2)"

if [[ -z "$ARCHIVE_RETAIN" || -z "$CRON_KEEP" ]]; then
  check "保留期口径" false "取不到值（retain-days='${ARCHIVE_RETAIN}' 来源=${RETAIN_SOURCE}，cron KEEP_DAYS='${CRON_KEEP}'）"
elif [[ "$ARCHIVE_RETAIN" == "$CRON_KEEP" ]]; then
  check "保留期口径" true "归档与磁盘清理均为 ${CRON_KEEP} 天（${RETAIN_SOURCE}）"
else
  check "保留期口径" false "归档 ${ARCHIVE_RETAIN} 天（${RETAIN_SOURCE}）vs 磁盘清理 ${CRON_KEEP} 天 —— 不一致会导致裂图或磁盘泄漏"
fi

# /etc/cron.d/* 是 644 全局可读的，把 MYSQL_PASSWORD 写进去等于把库口令
# 公开给所有本地用户。这是 H2 的回归防线，别再写回 cron。
#
# ★ 2026-11-07 v3：从「只查 CRON_FILE 单文件」改为「遍历整个目录」。
#   实测现场残留 /etc/cron.d/cleanup-defect-images.bak-20261105 与 .bak-20261107：
#   644 root:root、`sudo -u nobody cat` 可读、内容是改造前的旧 cron 行、
#   里面躺着**当前有效**的数据库 root 口令。单文件检查对此完全看不见——
#   改造时新文件写对了，旧文件没删，防线就漏在这一步。
#   注意 find 必须带 dotfile（cron 目录里出现 .swp/.bak 是常态）。
CRON_DIR="${CRON_DIR:-$(dirname "$CRON_FILE")}"
LEAK_FILES=""
if [[ -d "$CRON_DIR" ]]; then
  while IFS= read -r _f; do
    [[ -f "$_f" ]] || continue
    if grep -q 'MYSQL_PASSWORD' "$_f" 2>/dev/null; then
      LEAK_FILES="${LEAK_FILES} $(basename "$_f")"
    fi
  done < <(find "$CRON_DIR" -maxdepth 1 -type f 2>/dev/null)
fi

if [[ -n "$LEAK_FILES" ]]; then
  check "cron 无明文口令" false "${CRON_DIR} 下这些文件含 MYSQL_PASSWORD：${LEAK_FILES} —— 应改为脚本自读 /etc/default/camera-detection"
elif [[ -d "$CRON_DIR" ]]; then
  check "cron 无明文口令" true "${CRON_DIR}（整个目录，$(find "$CRON_DIR" -maxdepth 1 -type f 2>/dev/null | wc -l) 个文件）"
fi

# --- 3b-2. cron 指向统一布局（2026-11-13）-----------------------------------
# 现场实测：三个计划任务**全部**还指着 /usr/local/bin/*.sh（迁移前的副本）。
# 后果不是"多占几个文件"，而是：
#   旧版 check-disk-usage.sh 的 MOUNTS 默认是「/ /var /data」，而 /data 早就不是挂载点
#   → `df /data` 返回的是**系统盘**（40G），500G 数据盘 /main 从未被巡检；
#   旧版 cleanup-zk54-incremental.sh 缺「调用方显式传值优先于 /etc/default」的修复。
# 根因：迁移只改了 install.sh 的**生成模板**，没有重写**已存在**的 /etc/cron.d/*，
#       而本脚本当时只查「保留期口径」和「明文口令」，两项都通过 → 40/40 全绿却漏了。
# 顺带查被调用脚本是否存在：cron 路径写错时只会静默失败，不产生任何日志。
# ★ 只在统一布局机器上判「必须指向 ${INSTALL_DIR}/scripts/」——
#   老布局机器（没声明 BASE）本来就该调 /usr/local/bin/*，判它错是假红。
LEGACY_CRON_FILES=""
MISSING_CRON_TARGETS=""
if [[ -d "$CRON_DIR" ]]; then
  while IFS= read -r _f; do
    [[ -f "$_f" ]] || continue
    if [[ "$LAYOUT_ENABLED" -eq 1 ]]; then
      grep -qE '/usr/local/bin/(cleanup|check-disk)' "$_f" 2>/dev/null \
        && LEGACY_CRON_FILES="${LEGACY_CRON_FILES} $(basename "$_f")"
    fi
    while IFS= read -r _p; do
      [[ -n "$_p" ]] || continue
      [[ -x "$_p" ]] || MISSING_CRON_TARGETS="${MISSING_CRON_TARGETS} ${_p}"
    done < <(grep -oE '/(main/app|opt|camera)[^ ]*\.sh' "$_f" 2>/dev/null)
  done < <(find "$CRON_DIR" -maxdepth 1 -type f 2>/dev/null)
fi

if [[ -n "$LEGACY_CRON_FILES" ]]; then
  check "cron 脚本路径" false "${CRON_DIR} 下仍引用 /usr/local/bin 旧副本：${LEGACY_CRON_FILES} —— 应改指 ${INSTALL_DIR}/scripts/"
elif [[ -n "$MISSING_CRON_TARGETS" ]]; then
  check "cron 脚本路径" false "cron 引用的脚本不存在：${MISSING_CRON_TARGETS} —— cron 会静默失败"
elif [[ -d "$CRON_DIR" ]]; then
  if [[ "$LAYOUT_ENABLED" -eq 1 ]]; then
    check "cron 脚本路径" true "全部指向 ${INSTALL_DIR}/scripts/ 且脚本存在"
  else
    check "cron 脚本路径" true "老布局：脚本路径存在且可执行（不校验统一布局）"
  fi
fi

# --- 3c. 备份新鲜度（v3 P0-4）------------------------------------------------
# 「备份文件存在」和「备份能恢复」是两件事；「备份一直在更新」是第三件事。
# timer 被 disable、机器时间漂移、有人 `systemctl stop` 了 service —— 这三种情况
# **不产生任何错误日志**，因为没有失败，只是没有发生。备份唯一的失败模式正是
# 「静默停摆」，而这个模式此前没有任何检查项能发现。
# 阈值 26h：每日 01:00 跑 + 最多 5 分钟随机延迟，留 1 小时余量。
BACKUP_FULL_DIR="${BACKUP_FULL_DIR:-/main/app/data/backup/db/full}"
BACKUP_MAX_AGE_H="${BACKUP_MAX_AGE_H:-26}"
if [[ -d "$BACKUP_FULL_DIR" ]]; then
  LATEST_BACKUP="$(ls -1t "$BACKUP_FULL_DIR"/*.sql.zst 2>/dev/null | head -1)"
  if [[ -z "$LATEST_BACKUP" ]]; then
    check "备份新鲜度" false "${BACKUP_FULL_DIR} 下没有任何 .sql.zst —— 从未成功备份过"
  else
    BACKUP_AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$LATEST_BACKUP") ) / 3600 ))
    # sha256 存在则顺手校验：要证明的是「文件没坏」，不只是「文件在」
    BACKUP_SHA_STATE="未提供 .sha256"
    if [[ -f "${LATEST_BACKUP}.sha256" ]]; then
      if ( cd "$(dirname "$LATEST_BACKUP")" && sha256sum -c "$(basename "$LATEST_BACKUP").sha256" ) >/dev/null 2>&1; then
        BACKUP_SHA_STATE="sha256 通过"
      else
        BACKUP_SHA_STATE="sha256 不匹配（备份已损坏）"
      fi
    fi
    if [[ "$BACKUP_AGE_H" -le "$BACKUP_MAX_AGE_H" && "$BACKUP_SHA_STATE" != *"不匹配"* ]]; then
      check "备份新鲜度" true "$(basename "$LATEST_BACKUP")，${BACKUP_AGE_H} 小时前，${BACKUP_SHA_STATE}"
    elif [[ "$BACKUP_SHA_STATE" == *"不匹配"* ]]; then
      check "备份新鲜度" false "$(basename "$LATEST_BACKUP") ${BACKUP_SHA_STATE} —— 这份备份不可用于恢复"
    else
      check "备份新鲜度" false "最新备份已 ${BACKUP_AGE_H} 小时（阈值 ${BACKUP_MAX_AGE_H}h）—— 备份定时器可能已停止：systemctl status camera-detection-backup.timer"
    fi
  fi
fi

# --- 3c-2. binlog 副本新鲜度（2026-11-13）------------------------------------
# backup-database.sh 自 v2 P0-3（2026-11-07）就有 binlog 增量备份（mysqlbinlog
# --read-from-remote-server --raw，只取 dump 起点之后的增量，副本侧保留 14 天），
# 但**没有任何检查项验证它真的在跑**。该脚本自己的注释就记着历史状态：
#   「${BACKUP_DIR}/binlog/ 从来是空目录 —— 有能力做分钟级恢复却从没接上」
# 代码有、实际没跑 = RPO 静默退回 24 小时，且日志里什么都不报。
#
# 判据：目录非空 + 最新文件 mtime ≤ 26h（与全备同口径：01:00 + 5min 抖动 + 余量）。
# ⚠️ 假红的唯一情形：产线长时间零写入 → binlog 不轮转、已封口文件不重拷 → mtime 不更新。
#    此时看详情里的「共 N 个」即可判断机制本身是好的（N>1 说明历史增量都在）。
#
# ⚠️ 路径必须是 `/var/backups/camera-detection/db/binlog`，**不能漏掉 `db` 段**。
#    backup-database.sh 的 OFFSITE_DIR 默认是 `/var/backups/camera-detection/db`，
#    binlog 跨盘副本因此落在 `${OFFSITE_DIR}/binlog/`。本检查项最初写成
#    `/var/backups/camera-detection/binlog`（2026-11-13 上线首跑即恒红），
#    而同脚本的 IMAGES_DST 恰恰不带 `db` 段（图片副本独立在 .../images）——
#    两者不对称，抄路径时极易抄错。写死前先对 backup-database.sh 核实。
BINLOG_DST="${BINLOG_DST:-/var/backups/camera-detection/db/binlog}"
BINLOG_MAX_AGE_H="${BINLOG_MAX_AGE_H:-26}"
if [[ "$LAYOUT_ENABLED" -eq 1 ]]; then
  if [[ ! -d "$BINLOG_DST" ]]; then
    check "binlog 副本" false "${BINLOG_DST} 不存在 —— binlog 增量备份从未跑过（RPO 退回 24h）：journalctl -u camera-detection-backup -n 50"
  else
    _BINLOG_N="$(ls -1 "$BINLOG_DST" 2>/dev/null | wc -l)"
    if [[ "${_BINLOG_N:-0}" -eq 0 ]]; then
      check "binlog 副本" false "${BINLOG_DST} 是空目录 —— 备份机制存在但从未成功复制（RPO 退回 24h）"
    else
      _BINLOG_LATEST="$(ls -1t "$BINLOG_DST"/* 2>/dev/null | head -1)"
      _BINLOG_AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$_BINLOG_LATEST") ) / 3600 ))
      _BINLOG_KB="$(du -sk "$BINLOG_DST" 2>/dev/null | cut -f1 || true)"
      case "${_BINLOG_KB:-}" in ''|*[!0-9]*) _BINLOG_KB=0 ;; esac
      if [[ "$_BINLOG_AGE_H" -le "$BINLOG_MAX_AGE_H" ]]; then
        check "binlog 副本" true "$(basename "$_BINLOG_LATEST")，${_BINLOG_AGE_H} 小时前，共 ${_BINLOG_N} 个 / $(( _BINLOG_KB / 1024 ))MB"
      else
        check "binlog 副本" false "最新 $(basename "$_BINLOG_LATEST") 已 ${_BINLOG_AGE_H} 小时（阈值 ${BINLOG_MAX_AGE_H}h），共 ${_BINLOG_N} 个 —— 产线长期零写入时属正常；否则查 backup-database.sh 的 backup_binlogs"
      fi
    fi
  fi
fi

# --- 3d. 图片副本不含 TEST 相机（2026-11-07）----------------------------------
# 背景：TEST 相机（压测）单日写 907MB，源侧每小时清掉；副本侧按「30 天窗口 +
# 不用 --delete」把它们全留着 → 副本 3.6G vs 源 911M，长期挤占 /var 系统盘。
# backup-database.sh 已改为排除 TEST 目录并加总量上限；这里把「副本真的干净」
# 变成可验证项 —— 改了排除逻辑而忘记验证，问题会静默重现。
# 数据库不可用时不判红（无法确定哪些是 TEST），只提示。
IMAGES_DST="${IMAGES_DST:-/var/backups/camera-detection/images}"
IMAGES_MAX_GB="${IMAGE_BACKUP_MAX_GB:-20}"
if [[ -d "$IMAGES_DST" ]]; then
  _VERIFY_DBPW="$(_read_db_pw)"
  _TEST_CODES=""
  if [[ -n "$_VERIFY_DBPW" ]]; then
    _TEST_CODES="$(MYSQL_PWD="$_VERIFY_DBPW" mysql -h127.0.0.1 -uroot -D "${VERIFY_DB_NAME:-wms-new}" -N -B -e "
      SELECT DISTINCT camera_code FROM dim_camera    WHERE camera_type='TEST' AND IFNULL(is_deleted,0)=0
      UNION
      SELECT DISTINCT camera_code FROM camera_status WHERE camera_type='TEST'" 2>/dev/null | sed '/^[[:space:]]*$/d')"
  fi
  _DIRTY=""
  for _c in $_TEST_CODES; do
    for _d in "$IMAGES_DST"/*/"$_c"; do
      [[ -d "$_d" ]] && _DIRTY="${_DIRTY} ${_d#"$IMAGES_DST"/}"
    done
  done
  _DST_KB="$(du -sk "$IMAGES_DST" 2>/dev/null | cut -f1 || true)"
  case "${_DST_KB:-}" in ''|*[!0-9]*) _DST_KB=0 ;; esac
  if [[ -n "$_DIRTY" ]]; then
    check "图片副本无 TEST 相机" false "副本残留 TEST 目录：${_DIRTY# } —— backup-database.sh 的 --exclude 未生效"
  elif [[ -z "$_TEST_CODES" ]]; then
    check "图片副本无 TEST 相机" true "无 TEST 相机（或数据库不可用），副本 $(du -sh "$IMAGES_DST" 2>/dev/null | cut -f1)"
  elif [[ "$_DST_KB" -gt $(( IMAGES_MAX_GB * 1024 * 1024 )) ]]; then
    check "图片副本无 TEST 相机" false "副本 $(( _DST_KB / 1024 / 1024 ))GB 超过上限 ${IMAGES_MAX_GB}GB —— 总量回收未生效"
  else
    check "图片副本无 TEST 相机" true "已排除 ${_TEST_CODES//$'\n'/, }；副本 $(du -sh "$IMAGES_DST" 2>/dev/null | cut -f1) / 上限 ${IMAGES_MAX_GB}GB"
  fi
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

  # 服务器授权（2026-11-12 新增）
  # 只断言「接口通 + 状态可解析」和「授权不是 INVALID」。
  # MISSING 视为通过：现场可能还没装授权，而默认策略 warn 本来就不阻断 ——
  # 这里若判红，会让每次自检都失败，把真正的问题淹掉。
  # INVALID / MACHINE_MISMATCH 才判红：那两种是「装了但不对」，必须当场处理。
  LIC_JSON=$(curl -s -m 10 -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/license" 2>/dev/null)
  LIC_STATUS=$(printf '%s' "$LIC_JSON" | grep -oE '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
  LIC_MODE=$(printf '%s' "$LIC_JSON" | grep -oE '"expiredMode":"[^"]*"' | head -1 | cut -d'"' -f4)
  LIC_MC=$(printf '%s' "$LIC_JSON" | grep -oE '"machineCode":"[^"]*"' | head -1 | cut -d'"' -f4)
  check "授权接口" "$([[ -n "$LIC_STATUS" ]] && echo true || echo false)" \
        "status=${LIC_STATUS:-无响应}，模式=${LIC_MODE:-?}，机器码=${LIC_MC:-未采集}"

  case "$LIC_STATUS" in
    VALID|EXPIRING|DISABLED)
      check "授权可用性" true "status=${LIC_STATUS}" ;;
    MISSING)
      check "授权可用性" true "未安装授权（${LIC_MODE:-warn} 模式不阻断业务）" ;;
    *)
      check "授权可用性" false "status=${LIC_STATUS} —— 到「服务器授权」页看 message，或查 ${WORK_DIR}/data/license/" ;;
  esac
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

# ★ 2026-11-02 修正：相机编号与产品名原先**硬编码** CAM-001 / 产品A。
#   现场把「杭州工厂」配置删除后（只保留临平二期），
#   CAM-001 已不存在 → 批量上报恒返回 422 → 自检永远失败 1 项。
#   现在改为**从库里动态取一台真实存在的相机与产品**，换机器/换配置都不会失效。
# 口令读取见 3b 之前的 _read_db_pw()（3d 与本节共用，定义必须靠前）
db_query() {
  local pw; pw="$(_read_db_pw)"
  [[ -z "$pw" ]] && return 1
  MYSQL_PWD="$pw" mysql -h127.0.0.1 -uroot -D "${VERIFY_DB_NAME:-wms-new}" -N -e "$1" 2>/dev/null
}
DEV_CAMERA_CODE="$(db_query "SELECT camera_code FROM dim_camera WHERE IFNULL(is_deleted,0)=0 ORDER BY camera_id LIMIT 1;")"
DEV_PRODUCT_NAME="$(db_query "SELECT product_name FROM dim_product WHERE IFNULL(is_enabled,1)=1 ORDER BY product_id LIMIT 1;")"
[[ -z "$DEV_CAMERA_CODE" ]] && DEV_CAMERA_CODE="CAM-001"
[[ -z "$DEV_PRODUCT_NAME" ]] && DEV_PRODUCT_NAME="产品A"
echo "  自检使用相机: ${DEV_CAMERA_CODE}  产品: ${DEV_PRODUCT_NAME}"

HB_CODE=$(http_code -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: ${DEV_CAMERA_CODE}" -H "Authorization: Bearer dev-token" \
  -d "{\"cameraName\":\"自检相机\",\"cameraCode\":\"${DEV_CAMERA_CODE}\",\"cameraStatus\":1}" \
  "${BASE_URL}/api/v1/camera/status/heartbeat")
check "心跳上报" "$([[ "$HB_CODE" == "200" ]] && echo true || echo false)" "HTTP ${HB_CODE}"

BU_CODE=$(http_code -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: ${DEV_CAMERA_CODE}" -H "X-Batch-Id: VERIFY-$(date +%s)" -H "Authorization: Bearer dev-token" \
  -d "{\"schemaVersion\":\"2.0\",\"batchId\":\"VERIFY\",\"cameraCode\":\"${DEV_CAMERA_CODE}\",\"productName\":\"${DEV_PRODUCT_NAME}\",\"reportTime\":\"2026-01-01 00:00:00\",\"counterMode\":\"INCREMENTAL\",\"timeRange\":{\"startTime\":\"2026-01-01 00:00:00\",\"endTime\":\"2026-01-01 00:30:00\"},\"summary\":{\"totalCount\":1,\"okCount\":1,\"ngCount\":0},\"defects\":[]}" \
  "${BASE_URL}/api/v1/camera/inspection/batch-upload")
check "批量上报" "$([[ "$BU_CODE" == "200" ]] && echo true || echo false)" "HTTP ${BU_CODE}"

# 未映射编码应返回 422 UNMAPPED
UNMAPPED_CODE=$(http_code -X POST -H "Content-Type: application/json" \
  -H "X-Device-Id: ${DEV_CAMERA_CODE}" -H "X-Batch-Id: VERIFY-UNMAPPED-$(date +%s)" -H "Authorization: Bearer dev-token" \
  -d "{\"schemaVersion\":\"2.0\",\"batchId\":\"VERIFY-UNMAPPED\",\"cameraCode\":\"${DEV_CAMERA_CODE}\",\"productName\":\"${DEV_PRODUCT_NAME}\",\"reportTime\":\"2026-01-01 00:00:00\",\"counterMode\":\"INCREMENTAL\",\"timeRange\":{\"startTime\":\"2026-01-01 00:00:00\",\"endTime\":\"2026-01-01 00:30:00\"},\"summary\":{\"totalCount\":1,\"okCount\":0,\"ngCount\":1},\"defects\":[{\"sourceCode\":\"__NOT_MAPPED__\",\"count\":1}]}" \
  "${BASE_URL}/api/v1/camera/inspection/batch-upload")
check "未映射编码拒绝" "$([[ "$UNMAPPED_CODE" == "422" ]] && echo true || echo false)" "HTTP ${UNMAPPED_CODE}（期望 422）"

# --- 8. 图片超量快速拒绝 -----------------------------------------------------
OVER_CODE=$(http_code -X POST -H "X-Image-Count: 101" \
  -H "X-Device-Id: ${DEV_CAMERA_CODE}" -H "Authorization: Bearer dev-token" \
  "${BASE_URL}/api/v1/defect-images/upload")
check "图片超量拒绝" "$([[ "$OVER_CODE" == "413" ]] && echo true || echo false)" "HTTP ${OVER_CODE}"

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
