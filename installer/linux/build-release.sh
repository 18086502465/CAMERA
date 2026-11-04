#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— Linux 发布包构建脚本
#
# 在构建机上执行（可用 Windows + WSL/Git Bash，或 Linux）：
#   bash installer/linux/build-release.sh
#
# 产出：dist-release/linux/camera-detection-system-<版本>.tar.gz
#       解压后即为服务器上的安装源目录，直接 sudo ./install.sh 即可。
#
# 步骤（与 Windows 版 build-release.ps1 的 8 个阶段对应）：
#   ① 前端构建        ② 前端产物并入后端静态目录
#   ③ 后端打包        ④ 契约测试
#   ⑤ 组装发布目录    ⑥ 复制建库脚本
#   ⑦ 复制 Linux 部署脚本与 systemd unit
#   ⑧ 打包 tar.gz
# ==============================================================================
set -euo pipefail

VERSION="${VERSION:-1.0.0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRONTEND_DIR="${ROOT_DIR}/frontend"
BACKEND_DIR="${ROOT_DIR}/fact-product"
LINUX_DIR="${ROOT_DIR}/installer/linux"
STAGE_DIR="${ROOT_DIR}/dist-release/linux/stage/camera-detection-system"
OUT_DIR="${ROOT_DIR}/dist-release/linux"

log() { printf '\033[1;36m[build]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }

cd "$ROOT_DIR"

# --- ① 前端构建 -------------------------------------------------------------
log "① 前端构建"
cd "$FRONTEND_DIR"
[[ -d node_modules ]] || npm install
# ★ 必须先清空 dist/assets —— vite build **不清空**输出目录，
#   会把上一次的 chunk 一起留在 dist 里。后果有两个：
#     1) 打进 JAR 的产物里混着陈旧 chunk（JAR 体积虚增、来源不明）
#     2) 「当前入口是哪个」无法判定 —— dist 里有多个 index-*.js，
#        版本识别会出错，历史 chunk 保留机制形同虚设。
rm -rf "${FRONTEND_DIR}/dist/assets"
mkdir -p "${FRONTEND_DIR}/dist/assets"
npm run build
ok "前端产物：frontend/dist"
NEW_ENTRY=$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' "${FRONTEND_DIR}/dist/index.html" 2>/dev/null | head -1 | sed -E 's#assets/index-([A-Za-z0-9_-]+)\.js#\1#')
ok "本次构建的入口版本：${NEW_ENTRY:-未知}"

# --- ② 前端产物并入后端 ------------------------------------------------------
log "② 同步前端产物到后端 static 目录"
# 这两份是同一份 Vite 产物的两份拷贝，必须一致，否则 JAR 里的页面会与 dist 不符
#
# ★ 2026-11-04：保留**上一版**的静态资源（与新版本共存于同一目录）
#   原因（现场故障复现）：Vite 产物的 chunk 文件名带内容哈希，
#   每次构建**全部改名**。用户浏览器里开着部署前打开的页面时，
#   它的路由懒加载仍指向旧 chunk 名（如 ImagesView-3Im0_kdo.js），
#   而新构建已把旧文件替换掉 → 404 →
#     "Failed to fetch dynamically imported module" →
#   vue-router 导航 Promise 被 reject → **点击完全没反应**（URL 不变、无后端请求）。
#
#   解法：把历史 chunk 保留在 static/assets/ 下（文件名带哈希，不会与新版本冲突），
#   旧页面便仍能命中旧 chunk —— 现场不必强制刷新页面。
#   代价：JAR 体积增加（每版约 +1.5MB），保留版数由 KEEP_STATIC_VERSIONS 控制（默认 3）。
#
#   实现：先把旧 assets 移到临时目录 → 写入新 assets → 再把旧的**平铺**拷回，
#   并用一个 .versions 清单记录每个 chunk 属于哪一版，便于按版清理。
KEEP_STATIC_VERSIONS="${KEEP_STATIC_VERSIONS:-3}"
STATIC_DIR="${BACKEND_DIR}/src/main/resources/static"
STATIC_ASSETS="${STATIC_DIR}/assets"

OLD_ASSETS_TMP=""
if [[ -d "${STATIC_ASSETS}" ]]; then
  # ★ 上一版的入口 chunk 名：
  #   static/assets 是**上一轮**同步进来的产物，其 index.html 指向的入口就是上一版入口。
  #   取它，而不是扫描 assets 里任意一个 index-*.js ——
  #   因为 static/assets 里可能并存多个历史版本的入口文件。
  PREV_ENTRY_NAME=$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' "${STATIC_DIR}/index.html" 2>/dev/null | head -1 | sed -E 's#assets/index-([A-Za-z0-9_-]+)\.js#\1#')
  # 若 index.html 指向的入口不在 static 里（首次构建 / 已被覆盖），
  # 退而求其次：取 assets 里与本次入口**不同**的那个 index-*.js
  if [[ -z "$PREV_ENTRY_NAME" || ! -f "${STATIC_ASSETS}/index-${PREV_ENTRY_NAME}.js" ]]; then
    PREV_ENTRY_NAME=""
    while IFS= read -r cand; do
      h=$(basename "$cand" | sed -E 's#index-([A-Za-z0-9_-]+)\.js#\1#')
      if [[ "$h" != "$NEW_ENTRY" ]]; then PREV_ENTRY_NAME="$h"; break; fi
    done < <(find "${STATIC_ASSETS}" -maxdepth 1 -type f -name 'index-*.js' 2>/dev/null | sort)
  fi
  OLD_ENTRY="$PREV_ENTRY_NAME"
  if [[ -n "$OLD_ENTRY" ]]; then
    OLD_ASSETS_TMP="$(mktemp -d)"
    cp -r "${STATIC_ASSETS}/." "$OLD_ASSETS_TMP/" 2>/dev/null || true
    ok "已暂存上一版静态资源（版本 ${OLD_ENTRY}）"
  else
    ok "无可保留的上一版静态资源（首次构建）"
  fi
fi

rm -rf "${STATIC_ASSETS}"
mkdir -p "${STATIC_ASSETS}"
cp -r "${FRONTEND_DIR}/dist/assets/." "${STATIC_ASSETS}/"
cp "${FRONTEND_DIR}/dist/index.html" "${STATIC_DIR}/index.html"
if [[ -n "$OLD_ASSETS_TMP" && -d "$OLD_ASSETS_TMP" ]]; then
  # 先读入已有清单
  declare -A STATIC_VERSIONS=()
  if [[ -f "${STATIC_ASSETS}/.versions" ]]; then
    while IFS='=' read -r fname ver; do
      [[ -n "$fname" ]] && STATIC_VERSIONS["$fname"]="$ver"
    done < "${STATIC_ASSETS}/.versions"
  fi
  # 历史文件：文件名 → 版本
  # ⚠️ 顺序很关键：必须**先**标当前版本（只针对本次 dist 的文件），
  #    **再**标历史版本。否则历史文件会被后面的"标 current"覆盖成 current，
  #    导致版本统计恒为 0、淘汰逻辑失效。
  # 新版本文件（只取本次构建产物 dist 里的）
  while IFS= read -r f; do
    b=$(basename "$f")
    STATIC_VERSIONS["$b"]="current"
  done < <(find "${FRONTEND_DIR}/dist/assets" -maxdepth 1 -type f \( -name '*.js' -o -name '*.css' \) 2>/dev/null)

  # 历史文件：文件名 → 版本（不覆盖已是 current 的）
  while IFS= read -r f; do
    b=$(basename "$f")
    case "$b" in .versions) continue ;; esac
    [[ -f "${FRONTEND_DIR}/dist/assets/$b" ]] && continue   # 本次产物里已有同名（哈希相同）
    [[ -n "${STATIC_VERSIONS[$b]:-}" ]] && continue        # 已在清单里（更早的版本），保留原标记
    install -m 0644 "$f" "${STATIC_ASSETS}/$b" 2>/dev/null || true
    STATIC_VERSIONS["$b"]="${OLD_ENTRY}"
  done < <(find "$OLD_ASSETS_TMP" -maxdepth 1 -type f \( -name '*.js' -o -name '*.css' \) 2>/dev/null)

  # 按版本保留最近 N 版（current 永远保留）
  if [[ "${#STATIC_VERSIONS[@]}" -gt 0 ]]; then
    mapfile -t ALL_VERSIONS < <(printf '%s\n' "${STATIC_VERSIONS[@]}" | sort -u)
    # 找出需要淘汰的版本（从旧到新）
    mapfile -t HIST_VERSIONS < <(printf '%s\n' "${ALL_VERSIONS[@]}" | grep -v '^current$' | sort -u)
    if [[ "${#HIST_VERSIONS[@]}" -gt "$KEEP_STATIC_VERSIONS" ]]; then
      DROP_VERSIONS=("${HIST_VERSIONS[@]:0:$(( ${#HIST_VERSIONS[@]} - KEEP_STATIC_VERSIONS ))}")
      for v in "${DROP_VERSIONS[@]}"; do
        for fname in "${!STATIC_VERSIONS[@]}"; do
          if [[ "${STATIC_VERSIONS[$fname]}" == "$v" ]]; then
            rm -f "${STATIC_ASSETS}/${fname}"
            unset "STATIC_VERSIONS[$fname]"
          fi
        done
        ok "清理过旧静态版本: ${v}"
      done
    fi
    # 写清单
    : > "${STATIC_ASSETS}/.versions"
    for fname in "${!STATIC_VERSIONS[@]}"; do
      echo "${fname}=${STATIC_VERSIONS[$fname]}" >> "${STATIC_ASSETS}/.versions"
    done
    ok "静态资源历史版本保留数: ${#HIST_VERSIONS[@]}（上限 ${KEEP_STATIC_VERSIONS}）"
  fi
  rm -rf "$OLD_ASSETS_TMP"
fi

ok "已同步到 fact-product/src/main/resources/static"

# --- ③ 后端打包 -------------------------------------------------------------
log "③ 后端打包"
cd "$BACKEND_DIR"
mvn -q -DskipTests clean package
JAR="${BACKEND_DIR}/target/time-bucket-demo.jar"
[[ -f "$JAR" ]] || die "未产出 JAR：$JAR"
ok "JAR：${JAR}"

# --- ④ 契约测试 -------------------------------------------------------------
log "④ 运行测试"
mvn -q test
ok "测试通过"

# --- ⑤⑥⑦ 组装发布目录 -------------------------------------------------------
log "⑤ 组装发布目录"
rm -rf "$STAGE_DIR"
mkdir -p "${STAGE_DIR}/app" "${STAGE_DIR}/config" "${STAGE_DIR}/sql" \
         "${STAGE_DIR}/scripts" "${STAGE_DIR}/systemd" "${STAGE_DIR}/docs"

install -m 0644 "$JAR" "${STAGE_DIR}/app/"

# 外部配置模板：装到服务器后按现场参数修改
install -m 0644 "${ROOT_DIR}/installer/templates/application.yml" "${STAGE_DIR}/config/"

# 建库脚本：4 段式，缺一不可
log "⑥ 复制建库脚本"
install -m 0644 "${BACKEND_DIR}/src/main/resources/sql/deploy/"*.sql "${STAGE_DIR}/sql/"
SQL_COUNT=$(find "${STAGE_DIR}/sql" -name "*.sql" | wc -l | tr -d ' ')
[[ "$SQL_COUNT" -ge 4 ]] || die "建库脚本应为 4 个，实际 ${SQL_COUNT} 个"
ok "建库脚本 ${SQL_COUNT} 个"

log "⑦ 复制 Linux 部署脚本与 systemd unit"
install -m 0755 "${LINUX_DIR}/scripts/"*.sh "${STAGE_DIR}/scripts/"
# 注意：除 *.service 外还要打包 *.timer（备份定时器）——
# 只匹配 *.service 会漏掉 timer，导致 install.sh 找不到它而跳过定时备份安装。
install -m 0644 "${LINUX_DIR}/systemd/"*.service "${STAGE_DIR}/systemd/"
if compgen -G "${LINUX_DIR}/systemd/*.timer" > /dev/null; then
  install -m 0644 "${LINUX_DIR}/systemd/"*.timer "${STAGE_DIR}/systemd/"
fi
# 显式补执行位：部分文件系统（如 Windows 上的 Git Bash / NTFS）不保留 Unix 权限位，
# 打包到 tar 后脚本会变成 644，服务器上直接 ./install.sh 会报 Permission denied。
chmod 0755 "${STAGE_DIR}/scripts/"*.sh
chmod 0644 "${STAGE_DIR}/systemd/"*.service "${STAGE_DIR}/systemd/"*.timer 2>/dev/null || true

# 行尾必须是 LF：带 CRLF 的 shell 脚本在 Linux 上会报
#   "set: pipefail\r: invalid option name"（set -euo pipefail 那行解析失败），
#   且 shebang 会变成 "bad interpreter: /usr/bin/env bash\r"。
# 这里直接检查并失败，避免把坏包发到服务器上才发现。
CRLF_FILES=""
for f in "${STAGE_DIR}/scripts/"*.sh "${STAGE_DIR}/systemd/"*.service; do
  if grep -qU $'\r' "$f" 2>/dev/null; then
    CRLF_FILES="${CRLF_FILES} $(basename "$f")"
  fi
done
if [[ -n "$CRLF_FILES" ]]; then
  die "以下文件含 CRLF 行尾，必须转为 LF：${CRLF_FILES}
     修复：sed -i 's/\r$//' <文件>   （Windows 上编辑脚本最容易踩这个坑）"
fi
ok "脚本行尾检查通过（全部 LF）"

# 文档
for d in "${ROOT_DIR}/docs/部署方案-Ubuntu22.04.md" "${ROOT_DIR}/docs/部署方案-精简版.md"; do
  [[ -f "$d" ]] && cp "$d" "${STAGE_DIR}/docs/" || true
done
cp "${ROOT_DIR}/CODEBUDDY.md" "${STAGE_DIR}/docs/" 2>/dev/null || true

# 目录结构与 Windows 版一致（安装脚本会创建，这里预置空目录）
mkdir -p "${STAGE_DIR}/data/uploads/images" "${STAGE_DIR}/data/uploads/defect-images" "${STAGE_DIR}/logs"

# --- ⑧ 打包 -----------------------------------------------------------------
log "⑧ 打包"
mkdir -p "$OUT_DIR"
TARBALL="${OUT_DIR}/camera-detection-system-${VERSION}.tar.gz"
rm -f "$TARBALL"

# ⚠️ 为什么不用 `tar -czf`（2026-10-31 实测踩坑）：
#
#   1) **权限位丢失**：在 Windows + Git Bash 下构建时，文件系统不保留 Unix 权限位，
#      `tar -czf` 会把 scripts/*.sh 打成 **644** —— 解压到服务器后
#      `./scripts/install.sh` 直接 Permission denied。
#      （`install.sh` 内部有 `chmod 0755 *.sh` 兜底，但兜底只对**后续**脚本生效，
#      install.sh 自己本身必须是 755 才能被 sudo 执行。）
#
#   2) **路径分隔符被污染**：Windows 的 `os.path.relpath` 返回反斜杠，
#      打进 tar 后路径变成 `camera-detection-system\app\xxx.jar`，
#      Linux 解压后会生成**名字带反斜杠的单个文件**，目录结构完全塌掉。
#
#   下面用 Python 的 tarfile 手工设 mode 并强制正斜杠路径，两个坑一起解决。
if command -v python3 >/dev/null 2>&1; then
  STAGE_DIR_ABS="$STAGE_DIR" TARBALL_ABS="$TARBALL" python3 - <<'PYEOF'
import os, tarfile

stage = os.environ["STAGE_DIR_ABS"]
out = os.environ["TARBALL_ABS"]
base = os.path.dirname(stage)

def mode_for(rel):
    r = "/" + rel.replace("\\", "/")
    if "/scripts/" in r and r.endswith(".sh"):
        return 0o755
    return 0o644

with tarfile.open(out, "w:gz", compresslevel=6) as tf:
    for root, dirs, files in os.walk(stage):
        dirs.sort(); files.sort()
        for name in files:
            full = os.path.join(root, name)
            # ★ 必须转正斜杠，否则 Windows 上会打出反斜杠路径
            rel = os.path.relpath(full, base).replace("\\", "/")
            st = os.stat(full)
            ti = tarfile.TarInfo(name=rel)
            ti.size = st.st_size
            ti.mtime = int(st.st_mtime)
            ti.mode = mode_for(rel)
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"
            with open(full, "rb") as f:
                tf.addfile(ti, f)
print("    tarfile 打包完成，脚本权限 755")
PYEOF
else
  log "警告：未找到 python3，回退到 tar -czf（脚本权限可能不正确）"
  tar -czf "$TARBALL" -C "$(dirname "$STAGE_DIR")" "camera-detection-system"
fi

# 打包后自检：脚本必须是 755，路径必须不含反斜杠
if tar -tvzf "$TARBALL" | grep -q '\\'; then
  die "产物里出现了反斜杠路径 —— 打包逻辑有问题，中止"
fi
SH_MODE=$(tar -tvzf "$TARBALL" | grep 'scripts/install.sh' | awk '{print $1}')
# ★ 2026-11-04 修正：`tar -tv` 输出的权限位是 **10 个字符**（含文件类型位），
#   例如 `-rwxr-xr-x`。原实现拿它与 9 字符的 `rwxr-xr-x` 比较，
#   永远不相等 → 打包在最后一步误报失败（产物本身权限是正确的）。
#   现在改成去掉首位文件类型位后再比较。
SH_MODE_PERM="${SH_MODE: -9}"
if [[ "$SH_MODE_PERM" != "rwxr-xr-x" ]]; then
  die "scripts/install.sh 权限是 ${SH_MODE}（权限位 ${SH_MODE_PERM}），应为 rwxr-xr-x —— 解压后无法执行"
fi
ok "产物权限自检通过（install.sh 为 755，无反斜杠路径）"

SIZE_MB=$(du -m "$TARBALL" | cut -f1)
ok "产物：${TARBALL}（约 ${SIZE_MB} MB）"

echo
log "部署到服务器："
echo "    scp ${TARBALL} user@server:/tmp/"
echo "    ssh user@server"
echo "    sudo tar -xzf /tmp/camera-detection-system-${VERSION}.tar.gz -C /tmp"
echo "    cd /tmp/camera-detection-system && sudo ./scripts/install.sh"
