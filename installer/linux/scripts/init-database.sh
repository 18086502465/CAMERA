#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 数据库初始化（Ubuntu 22.04 LTS）
#
# 用法：
#   sudo ./init-database.sh                      # 用本机 mysql 客户端导入
#   MYSQL_HOST=... MYSQL_USER=... MYSQL_PASSWORD=... ./init-database.sh
#
# 关键点（已实测）：
#   1. 库名带连字符，必须用反引号包起来：CREATE DATABASE `wms-new`
#      —— 在 bash 里反引号是「命令替换」，必须用单引号包住整条 SQL，
#         或写 \` 转义，否则会被当成命令执行（报 command not found）。
#   2. 必须按 01 → 02 → 03 → 04 → 05 顺序执行，不可跳步。
#      05 给两张相机表加 camera_type 列（幂等），漏跑则清理脚本查不到
#      "哪些相机不能删"，保护集恒为空 → 每小时清理直接中止并告警。
#   3. 用 `mysql < file` 直接重定向导入，中文不会乱码
#      （bash 重定向是字节流，不做编码转换；Windows PowerShell 的管道会按 GBK 重编码）。
#   4. 02 会 DROP 并重建 21 张表 —— 只适用于空库。
# ==============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
DATABASE="${DATABASE:-wms-new}"
INSTALL_DIR="${INSTALL_DIR:-/main/app/dist}"
SQL_DIR="${SQL_DIR:-${INSTALL_DIR}/sql}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --database) DATABASE="$2"; shift 2 ;;
    --user) MYSQL_USER="$2"; shift 2 ;;
    --password) MYSQL_PASSWORD="$2"; shift 2 ;;
    --sql-dir) SQL_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

log() { printf '\033[1;36m[init-db]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }

command -v mysql >/dev/null 2>&1 || die "未找到 mysql 客户端，请先安装：apt install mysql-client"

# 密码不进命令行（避免出现在 ps / shell history 里）
MYSQL_ARGS=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" --default-character-set=utf8mb4 --protocol=tcp)
if [[ -n "$MYSQL_PASSWORD" ]]; then
  export MYSQL_PWD="$MYSQL_PASSWORD"
fi

# --- 1. 建库 -----------------------------------------------------------------
# 注意库名用反引号包（MySQL 标识符），整条 SQL 用单引号包（避免 bash 命令替换）
log "创建数据库 ${DATABASE}"
mysql "${MYSQL_ARGS[@]}" -e "CREATE DATABASE IF NOT EXISTS \`${DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
ok "数据库就绪"

# --- 2. 逐段导入 -------------------------------------------------------------
SCRIPTS=(
  "01_init_schema_nodb:基础表与种子数据"
  "02_final_schema_p0:P0 维度表与事实表"
  "03_system_settings:系统设置"
  "04_align_entity_columns:实体类列对齐"
  "05_camera_type_migration:相机类型隔离（REAL/TEST）"
)

for entry in "${SCRIPTS[@]}"; do
  FILE="${entry%%:*}"; DESC="${entry#*:}"
  PATH_TO_SQL="${SQL_DIR}/${FILE}.sql"
  [[ -f "$PATH_TO_SQL" ]] || die "缺少脚本：${PATH_TO_SQL}"

  log "导入 ${FILE}.sql（${DESC}）"
  # 用 < 直接重定向：bash 不做编码转换，中文原样进库
  if ! mysql "${MYSQL_ARGS[@]}" "$DATABASE" < "$PATH_TO_SQL"; then
    die "导入 ${FILE}.sql 失败，请检查上方错误输出"
  fi
  ok "${FILE}.sql 完成"
done

# --- 3. 校验 -----------------------------------------------------------------
TABLE_COUNT="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DATABASE}';" 2>/dev/null | tr -d '[:space:]')"
ok "表数量：${TABLE_COUNT}"

HAS_PASSWORD_HASH="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${DATABASE}' AND table_name='sys_user' AND column_name='password_hash';" 2>/dev/null | tr -d '[:space:]')"
if [[ "$HAS_PASSWORD_HASH" == "1" ]]; then
  ok "sys_user.password_hash 已就位（可正常登录）"
else
  die "sys_user 缺 password_hash 列，请确认 04_align_entity_columns.sql 已执行"
fi

HAS_DIM_CAMERA="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DATABASE}' AND table_name='dim_camera';" 2>/dev/null | tr -d '[:space:]')"
if [[ "$HAS_DIM_CAMERA" == "1" ]]; then
  ok "dim_camera 已就位（设备上报接口可用）"
else
  die "缺 dim_camera 表，设备上报接口会 500，请确认 02_final_schema_p0.sql 已执行"
fi

# 库名含连字符（wms-new）必须加反引号，否则被解析成 "wms 减 new" 语法错误；
# 且该查询失败不应静默杀脚本（pipefail + set -e），显式兜底
HAS_MAPPING="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM \`${DATABASE}\`.cfg_camera_defect_mapping;" 2>/dev/null | tr -d '[:space:]' || true)"
ok "缺陷映射条数：${HAS_MAPPING:-查询失败（表可能未建，不影响本次部署）}"

# camera_type 是清理链路的安全阀：cleanup-zk54-incremental.sh 靠它算保护集。
# 缺这一列不会报错，只会让"哪些相机不能删"恒为空 —— 静默失效，必须显式校验。
CAMERA_TYPE_COLS="$(mysql "${MYSQL_ARGS[@]}" -N -B -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${DATABASE}' AND column_name='camera_type' AND table_name IN ('dim_camera','camera_status');" 2>/dev/null | tr -d '[:space:]')"
if [[ "$CAMERA_TYPE_COLS" == "2" ]]; then
  ok "camera_type 已就位（dim_camera + camera_status）"
else
  die "camera_type 列缺失（应为 2 张表，实际 ${CAMERA_TYPE_COLS}），请确认 05_camera_type_migration.sql 已执行"
fi

echo
ok "数据库初始化完成"
echo "  下一步：systemctl reset-failed camera-detection && systemctl start camera-detection"
echo "  然后： ${INSTALL_DIR}/scripts/verify.sh"
