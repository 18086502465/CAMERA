#!/usr/bin/env bash
# ==============================================================================
# 发版前只读预检（S5 入口）
#
# 用法：bash docs/scripts/preflight.sh
#
# 只读：不构建、不上传、不部署、不改库、不改配置。只回答四个问题：
#   ① 当前代码处于什么状态（分支/提交/未提交变更）
#   ② 构建输入齐不齐（SQL 7 份清单、两份 application.yml key 对账）
#   ③ 本地门禁上一次跑没跑、过没过（后端 surefire / 前端 dist）
#   ④ 现有产物是什么、部署会替换哪个文件
#
# 输出直接打屏，可 `> preflight-$(date +%Y%m%d).txt` 存档随发布记录归档。
# ==============================================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="${ROOT_DIR}/fact-product"
FRONTEND_DIR="${ROOT_DIR}/frontend"
LINUX_DIR="${ROOT_DIR}/installer/linux"
OUT_DIR="${ROOT_DIR}/dist-release/linux"
STAGE_DIR="${OUT_DIR}/stage/camera-detection-system"
TARGET_JAR="/main/app/dist/app/time-bucket-demo.jar"

OK='[ OK ]'; BAD='[FAIL]'; INFO='[INFO]'
fails=0
line() { printf '%s\n' "$*"; }
head_line() { printf '\n=== %s ===\n' "$*"; }
ok()   { printf '%s %s\n' "$OK" "$*"; }
bad()  { printf '%s %s\n' "$BAD" "$*"; fails=$((fails+1)); }
info() { printf '%s %s\n' "$INFO" "$*"; }

cd "$ROOT_DIR" || exit 1
line "交付预检（只读）  $(date '+%Y-%m-%d %H:%M:%S %z')"
line "项目根目录：$ROOT_DIR"

# --- ① 代码状态 -------------------------------------------------------------
head_line "① 代码状态"
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  BRANCH="$(git branch --show-current)"
  HEAD_SHA="$(git rev-parse --short HEAD)"
  DIRTY="$(git status --porcelain | wc -l | tr -d ' ')"
  info "分支=$BRANCH  HEAD=$HEAD_SHA  未提交变更=$DIRTY 项"
  if [[ "$DIRTY" != "0" ]]; then
    info "未提交文件（前 20 条）："
    git status --short | head -20 | sed 's/^/       /'
    bad "工作树不干净——构建产物无法对应唯一提交，归档时需在发布记录写清 HEAD"
  else
    ok "工作树干净，产物可对应唯一提交"
  fi
else
  bad "不在 git 仓库内"
fi

# 凭据泄漏扫描（只报命中文件名，不打印值）
# ⚠️ 必须排除「定义扫描模式本身」的工具文件，否则 preflight.sh / sanitize_secrets.py / ci.yml
#    因含有模式字面量而命中自己 —— 2026-11-23 首跑实测踩到。
head_line "①b 凭据扫描"
CRED_HITS="$(git grep -l -E 'CCshenda889|Pass@1234' -- \
  ':(exclude)docs/scripts/preflight.sh' \
  ':(exclude)docs/scripts/sanitize_secrets.py' \
  ':(exclude).github/workflows/ci.yml' 2>/dev/null || true)"
if [[ -n "$CRED_HITS" ]]; then
  printf '%s\n' "$CRED_HITS" | sed 's/^/       /'
  bad "发现疑似真实凭据，推送前必须清除"
else
  ok "未发现已知凭据字面量"
fi

# --- ② 构建输入 -------------------------------------------------------------
head_line "② 构建输入"
EXPECTED_SQL=(01_init_schema_nodb.sql 02_final_schema_p0.sql 03_system_settings.sql
              04_align_entity_columns.sql 05_camera_type_migration.sql
              06_index_tuning.sql 07_daily_report.sql)
SQL_DIR="${BACKEND_DIR}/src/main/resources/sql/deploy"
MISSING_SQL=()
for f in "${EXPECTED_SQL[@]}"; do
  [[ -f "${SQL_DIR}/${f}" ]] || MISSING_SQL+=("$f")
done
if [[ ${#MISSING_SQL[@]} -eq 0 ]]; then
  ok "建库脚本 7 份齐全（01→05 建库，06/07 幂等补丁）"
else
  bad "建库脚本缺失：${MISSING_SQL[*]}"
fi

if [[ -f "${LINUX_DIR}/scripts/check_config_parity.py" ]]; then
  if python3 "${LINUX_DIR}/scripts/check_config_parity.py" >/dev/null 2>&1; then
    ok "两份 application.yml 的 key 结构一致"
  else
    bad "两份 application.yml 的 key 结构不一致——现场改配置会静默失效"
  fi
else
  bad "缺少 check_config_parity.py"
fi

for d in "${BACKEND_DIR}/src" "${FRONTEND_DIR}/src" "${LINUX_DIR}/scripts"; do
  [[ -d "$d" ]] && ok "源码目录存在：${d#${ROOT_DIR}/}" || bad "源码目录缺失：$d"
done

# --- ③ 上一次门禁结果 -------------------------------------------------------
head_line "③ 本地门禁（读取上次运行结果，不重新跑）"
SUREFIRE_DIR="${BACKEND_DIR}/target/surefire-reports"
if [[ -d "$SUREFIRE_DIR" ]]; then
  TOTALS="$(python3 - "$SUREFIRE_DIR" <<'PY'
import glob,re,sys
n=f=e=s=0
for p in glob.glob(sys.argv[1]+'/*.txt'):
    t=open(p,encoding='utf-8',errors='replace').read()
    m=re.search(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',t)
    if m:
        a,b,c,d=map(int,m.groups()); n+=a; f+=b; e+=c; s+=d
print(n,f,e,s)
PY
)"
  read -r T_N T_F T_E T_S <<< "$TOTALS"
  if [[ "${T_F:-1}" -eq 0 && "${T_E:-1}" -eq 0 && "${T_N:-0}" -gt 0 ]]; then
    ok "后端测试（上次）：${T_N} 例，失败 ${T_F}，错误 ${T_E}"
  else
    bad "后端测试（上次）：${T_N:-0} 例，失败 ${T_F:-?}，错误 ${T_E:-?}——需重跑 mvn -B test"
  fi
else
  info "后端尚未跑过测试（无 surefire 报告）——发版前必须跑 mvn -B test"
fi

if [[ -d "${FRONTEND_DIR}/dist" && -n "$(ls -A "${FRONTEND_DIR}/dist" 2>/dev/null)" ]]; then
  DIST_MTIME="$(stat -c '%y' "${FRONTEND_DIR}/dist" 2>/dev/null || echo '未知')"
  ok "前端 dist 存在（生成于 ${DIST_MTIME%%.*}）"
  SRC_NEWER="$(find "${FRONTEND_DIR}/src" -type f -newer "${FRONTEND_DIR}/dist" 2>/dev/null | head -1)"
  [[ -n "$SRC_NEWER" ]] \
    && bad "前端源码比 dist 新（${SRC_NEWER#${ROOT_DIR}/}）——必须先 npm run build 再打包" \
    || ok "前端 dist 不旧于源码"
else
  info "前端 dist 不存在——发版前必须跑 npm run build"
fi

# --- ④ 产物与部署目标 -------------------------------------------------------
head_line "④ 产物与部署目标"
if [[ -d "$OUT_DIR" ]]; then
  TARBALLS="$(find "$OUT_DIR" -maxdepth 1 -name 'camera-detection-system-*.tar.gz' -printf '%f %kKB\n' 2>/dev/null | sort)"
  UPDATES="$(find "$OUT_DIR/update" -maxdepth 1 -name 'update-*.zip' -printf '%f %kKB\n' 2>/dev/null | sort | tail -5)"
  [[ -n "$TARBALLS" ]] && { info "全量包："; printf '%s\n' "$TARBALLS" | sed 's/^/       /'; } || info "全量包：无"
  [[ -n "$UPDATES"  ]] && { info "增量包（最近 5）："; printf '%s\n' "$UPDATES" | sed 's/^/       /'; } || info "增量包：无"
else
  info "尚无产物目录 ${OUT_DIR}——需先跑 build-release.sh"
fi
info "部署目标 JAR（现场）：${TARGET_JAR}"
info "上传脚本：docs/scripts/upload_jar_b64.py → /tmp/update.zip"
info "部署脚本：/tmp/scripts/deploy_jar.sh（失败自动回滚，备份点见现场日志）"

# --- ⑤ 本次将执行的门禁序列 -------------------------------------------------
head_line "⑤ 发版门禁序列（按此顺序，勿跳步）"
line "  1. cd fact-product && mvn -B test"
line "  2. cd frontend && npm ci && npm test && npm run lint && npm run build"
line "  3. python docs/scripts/check_doc_links.py && check_legacy_paths.py"
line "  4. bash installer/linux/build-release.sh [--update-only]"
line "  5. upload_jar_b64.py → deploy_jar.sh（口令走环境变量/600 临时文件）"
line "  6. sudo verify.sh [--delivery] → 浏览器回归 → 清 ZZTEST* 数据"
line "  7. 客户签字验收 → 一次性文档入 archive/ → 获授权后推送"

head_line "预检结果"
if [[ "$fails" -eq 0 ]]; then
  ok "无阻断项，可进入 S5 构建（工作树状态见 ① 的提示）"
  exit 0
else
  bad "$fails 项阻断，修复后再构建"
  exit 1
fi
