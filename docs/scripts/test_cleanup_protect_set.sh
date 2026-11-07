#!/usr/bin/env bash
# ==============================================================================
# cleanup_zk54_incremental.sh 的保护集合逻辑回归测试
#
# 背景：2026-11-05 改造时发现一个**会静默丢相机**的 bug ——
#   `printf '%s' "$PROTECTED" | tr ',' '\n'` 输出的最后一行**没有换行符**，
#   `while read` 读到最后一行时返回非零 → 循环体不执行 → 最后一个相机被丢弃。
#   被丢弃的恰好是真实相机 → 保护集合少一台 → **该相机的数据会被误删**。
#   修复：`printf '%s' | tr` 改为 here-string `tr ',' '\n' <<< "$PROTECTED"`。
#
# 本测试用 Python 从真实脚本中抽取「保护集合构造 → 拆分」代码段，
# 替换掉两次数据库查询后执行 —— 保证测的就是线上跑的那段逻辑（而不是复制一份）。
#
# 用法：bash test_cleanup_protect_set.sh
# ==============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 直接测装机使用的那一份，**不再保留副本**：
# 之前 docs/scripts/ 下有一份下划线命名的拷贝，与 installer 版逐渐漂移
# （314 行 vs 429 行），测试测的是旧版 —— 「测试全绿、线上仍有 bug」的经典成因。
TARGET="${SCRIPT_DIR}/../../installer/linux/scripts/cleanup-zk54-incremental.sh"

if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "需要 python3 才能抽取被测代码段"; exit 1
fi

[[ -f "$TARGET" ]] || { echo "找不到被测脚本：$TARGET"; exit 1; }

# --- 抽取 + 生成测试脚手架 ---------------------------------------------------
# 用引号 heredoc（<<'PYEOF'）防止 bash 展开 $，Python 输出的路径直接接收
HARNESS=$("$PY" - "$TARGET" <<'PYEOF'
import re, sys, os, tempfile
target = sys.argv[1]
src = open(target, encoding='utf-8').read()

start = src.index('REAL_DIM=$(SQLQ')
end_marker = 'PROTECTED_COUNT=${#CODES[@]}'
end = src.index(end_marker, start) + len(end_marker)
block = src[start:end]

# 把两次数据库查询替换成注入的固定值
block = re.sub(r'^REAL_DIM=\$\(SQLQ.*$', 'REAL_DIM="$D_REAL_DIM"', block, flags=re.M)
block = re.sub(r'^REAL_STATUS=\$\(SQLQ.*$', 'REAL_STATUS="$D_REAL_STATUS"', block, flags=re.M)
if 'D_REAL_DIM' not in block or 'D_REAL_STATUS' not in block:
    print('!! 替换数据库查询失败，脚本结构可能已变', file=sys.stderr)
    sys.exit(1)

harness = ('#!/bin/bash\nset -uo pipefail\nKEEP_OVERRIDE=""\n' + block + '\n'
           'echo "count=${PROTECTED_COUNT}"\n'
           'echo "SQL_IN=$PROTECTED_SQL"\n'
           'for c in "${CODES[@]}"; do echo "code=[$c]"; done\n')

out = os.path.join(tempfile.gettempdir(), 'cleanup_protect_harness.sh')
open(out, 'w', encoding='utf-8').write(harness)
print(out)
PYEOF
)

if [[ -z "$HARNESS" || ! -f "$HARNESS" ]]; then
  echo "!! 未能生成测试脚手架"; exit 1
fi

FAILED=0
run_case() {
  local desc="$1" dim="$2" status="$3" codes="$4" expected="$5"
  echo "--- $desc ---"
  local out actual
  out=$(D_REAL_DIM="$dim" D_REAL_STATUS="$status" PROTECTED_CODES="$codes" bash "$HARNESS" 2>&1)
  actual=$(printf '%s\n' "$out" | sed -n 's/^count=\([0-9]*\)$/\1/p' | head -1)
  if [[ "$actual" == "$expected" ]]; then
    echo "  ✓ count=$actual"
  else
    echo "  ✗ 期望 count=$expected，实际 count=${actual:-PARSE_FAIL}"
    FAILED=$((FAILED+1))
  fi
  printf '%s\n' "$out" | grep -E '^(SQL_IN|code=)' | sed 's/^/    /'
}

echo "=== 保护集合逻辑回归测试 ==="
echo "被测脚本：$TARGET"
echo

# 正常：两表各标一台 → 两台都受保护
run_case "正常：两表各标一台" "DA8119489" "DA4733319" "" 2
# 防线一：任一表标了 REAL 即受保护（单表漏更新不能导致误删）
run_case "防线一：仅 dim_camera 标记" "DA8119489" "" "" 1
run_case "防线一：仅 camera_status 标记" "" "DA4733319" "" 1
# 运维临时加码
run_case "运维加码 PROTECTED_CODES" "DA8119489" "DA4733319" "DA9999999,DA8888888" 4
# 防线二：全部误标 → 保护集合为空 → 脚本会中止本轮
run_case "防线二：全部误标（应为空）" "" "" "" 0
# 多台 REAL（回归：数组拆分不能只留最后一个）
run_case "dim_camera 有 3 台" "DA8119489
DA4733319
DA7777777" "" "" 3

echo
if [[ "$FAILED" -gt 0 ]]; then
  echo "✗ $FAILED 个用例失败"
  exit 1
fi
echo "✓ 全部通过"
