#!/usr/bin/env bash
# 仓库瘦身：把「曾经跟踪过、现已出库」的构建产物从提交历史里彻底删除。
#
# 【为什么需要】
#   .git 已涨到 421MB，而当前 HEAD 树里最大的文件只有 1.8MB。
#   差额全在历史里：dist-release 的 exe/jar/tar.gz 共 383MB、static 的前端 chunk 44MB，
#   都是早期「发布产物全部入库」决策留下的，现在早已不跟踪，但对象还在。
#   副作用：clone 慢、GitHub 仓库超限、每次 fetch 都拖着这些死重量。
#
# 【会改什么】
#   重写全部提交的树（内容不变，只是少了那两个目录的历史版本），因此
#   **所有 commit hash 都会变**，本地 main 与 origin/main 分叉 → 需要强推。
#   不改工作区文件，不改当前 HEAD 的文件内容。
#
# 【安全措施】
#   ① 要求工作区干净（有未提交改动就拒绝，避免拿未保存的工作去赌一个不可逆操作）；
#   ② 自动 git bundle 全量备份到**仓库外**（唯一恢复来源）；
#   ③ 收尾做对象回收校验，确认旧产物真的被 gc 掉了；
#   ④ 不自动 push —— 强推命令只打印，由人工执行。
#
# 【为什么不打 backup 标签】
#   打过一次：`git tag backup/pre-slim-* HEAD` 指向重写前的 HEAD，
#   于是旧历史对象全部被这个 ref 保住 → gc --prune=now 一个字节都回收不了，
#   .git 体积纹丝不动（等于白干）。恢复一律走仓库外的 bundle。
#
# 用法：
#   bash docs/scripts/git_slim.sh            # 只报告能回收多少（默认 dry-run）
#   bash docs/scripts/git_slim.sh --apply    # 真正执行重写
set -euo pipefail

# 历史里要清除的路径前缀（都已出库，删历史不影响任何现存文件）
PRUNE_PATHS=(
  "dist-release"
  "fact-product/src/main/resources/static"
)

APPLY=0
case "${1:-}" in
  --apply) APPLY=1 ;;
  ""|--dry-run) APPLY=0 ;;
  -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "未知参数：$1（可用 --apply / --dry-run）" >&2; exit 2 ;;
esac

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "不在 git 仓库内" >&2; exit 1; }
cd "$REPO_ROOT"

git_dir_size() { du -sm .git 2>/dev/null | cut -f1; }

# 单个路径在**全部历史**中占用的 blob 体积（MB）
path_size_mb() {
  local prefix="$1"
  git rev-list --objects --all 2>/dev/null \
    | git cat-file --batch-check='%(objecttype) %(objectsize) %(rest)' 2>/dev/null \
    | awk -v p="${prefix}/" '$1=="blob" && index($3,p)==1 { s+=$2 } END{ printf "%.1f", s/1048576 }'
}

echo "仓库：$REPO_ROOT"
echo "提交数：$(git rev-list --count --all)    .git 当前体积：$(git_dir_size) MB"
echo ""
echo "可清除的历史路径："
TOTAL=0
for p in "${PRUNE_PATHS[@]}"; do
  MB=$(path_size_mb "$p")
  printf "  %8s MB  %s\n" "$MB" "$p"
  TOTAL=$(awk -v a="$TOTAL" -v b="$MB" 'BEGIN{printf "%.1f", a+b}')
done
printf "  %8s MB  合计\n" "$TOTAL"
echo ""
echo "（这些路径当前跟踪文件数：$(git ls-files "${PRUNE_PATHS[@]}" | wc -l) —— 为 0 说明删历史不会动任何现存文件）"
echo ""

if [[ "$APPLY" -eq 0 ]]; then
  echo "dry-run 结束。确认无误后执行：bash docs/scripts/git_slim.sh --apply"
  exit 0
fi

# ---------- 以下为实际重写 ----------
if [[ -n "$(git status --porcelain)" ]]; then
  cat >&2 <<'EOF'
✗ 工作区有未提交的改动，拒绝重写历史。

  重写期间会用 git rm --cached 逐提交操作索引，脏工作区下容易把未提交内容卷进去，
  而且这一步不可逆。请先提交（或 stash）再执行：

      git add -A && git commit -m "..."     # 或  git stash push -u
EOF
  exit 3
fi

STAMP=$(date +%Y%m%d-%H%M%S)
BUNDLE="${REPO_ROOT}/../camera-git-backup-${STAMP}.bundle"

echo "① 全量备份到仓库外（唯一的恢复来源）："
git bundle create "$BUNDLE" --all >/dev/null
echo "   $BUNDLE（$(du -m "$BUNDLE" | cut -f1) MB）"
echo "   ⚠️ 备份必须放在仓库外 —— 仓库内打 tag 会让旧对象一直被引用，"
echo "      gc 之后一个字节都回收不了，瘦身等于没做。"

echo "② 重写历史（$(git rev-list --count --all) 个提交）..."
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --index-filter \
  "git rm -r --cached --ignore-unmatch ${PRUNE_PATHS[*]}" \
  --prune-empty -- --all >/dev/null 2>&1

echo "③ 清理旧引用并重新打包（可能需要几十秒）..."
rm -rf .git/refs/original
git reflog expire --expire=now --all
git for-each-ref --format='%(refname)' refs/tags | while IFS= read -r t; do
  case "$t" in */backup/pre-slim-*) git tag -d "${t#refs/tags/}" >/dev/null 2>&1 || true ;; esac
done
git gc --prune=now --aggressive >/dev/null 2>&1

echo "④ 校验：旧对象是否真的被回收"
LEFTOVER=$(git rev-list --objects --all 2>/dev/null \
  | awk '{
      p = $0
      sub(/^[0-9a-f]+ /, "", p)          # 去掉前导 "<sha> "
      if (index(p, "dist-release/") == 1) c++
      else if (index(p, "fact-product/src/main/resources/static/") == 1) c++
    } END { print c + 0 }')
echo "   仍被引用的产物对象：${LEFTOVER}（应为 0）"

echo ""
echo "完成：.git $(git_dir_size) MB（重写前请对照上面的数字）"
echo "提交数：$(git rev-list --count --all)"
echo ""
echo "下一步（需人工执行）："
echo "  git push --force-with-lease origin main"
echo ""
echo "恢复办法（若结果不对）："
echo "  git clone ${BUNDLE} camera-restored"
echo "  然后把 camera-restored 的 main 推回远端"
echo ""
echo "注意：历史已改写，其他机器/协作者必须重新 clone，不能 pull。"
