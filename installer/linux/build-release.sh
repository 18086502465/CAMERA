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
npm run build
ok "前端产物：frontend/dist"

# --- ② 前端产物并入后端 ------------------------------------------------------
log "② 同步前端产物到后端 static 目录"
# 这两份是同一份 Vite 产物的两份拷贝，必须一致，否则 JAR 里的页面会与 dist 不符
rm -rf "${BACKEND_DIR}/src/main/resources/static/assets"
cp -r "${FRONTEND_DIR}/dist/assets" "${BACKEND_DIR}/src/main/resources/static/assets"
cp "${FRONTEND_DIR}/dist/index.html" "${BACKEND_DIR}/src/main/resources/static/index.html"
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
if [[ "$SH_MODE" != "rwxr-xr-x" ]]; then
  die "scripts/install.sh 权限是 ${SH_MODE}，应为 rwxr-xr-x —— 解压后无法执行"
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
