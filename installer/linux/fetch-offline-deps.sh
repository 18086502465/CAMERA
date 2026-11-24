#!/usr/bin/env bash
# ==============================================================================
# 产线缺陷在线AI检测系统 —— 离线依赖包生成（在有外网的 Ubuntu 22.04 构建机上跑一次）
#
# 作用：把 deploy.sh p1 需要的全部 apt 依赖（含递归依赖）下载到
#       installer/linux/offline/debs/，随发布包一起分发。客户机无外网时，
#       deploy.sh --offline 直接用这里的 deb 本地安装，不再访问 apt 源。
#
# 为什么必须"在 Ubuntu 22.04 上生成"：
#   deb 与发行版/架构强绑定（libc、glibc 版本）。用 24.04 下的包装到 22.04
#   会报依赖版本不满足。本脚本强制校验 VERSION_CODENAME=jammy 且架构 amd64。
#   目标现场机器也应是 Ubuntu 22.04 amd64 —— 这是离线包的适用边界。
#
# 用法：
#   # 方式 A：直接在联网的 Ubuntu 22.04 机器上
#   sudo bash installer/linux/fetch-offline-deps.sh
#
#   # 方式 B：构建机是 Windows / 非 Ubuntu，用 Docker 生成（推荐）
#   bash installer/linux/fetch-offline-deps.sh --docker
#   （需要本机已装 docker；脚本会把仓库挂进 ubuntu:22.04 容器执行）
#
# 产出：
#   installer/linux/offline/debs/*.deb      全部依赖
#   installer/linux/offline/manifest.sha256 校验清单
#   installer/linux/offline/README.txt      适用说明
#
# 注意：
#   - 本脚本产出不入库（.gitignore 已排除 installer/linux/offline/debs/），
#     只在"做离线交付包"时生成，然后由 build-release.sh 打进 tar.gz。
#   - 扩大依赖清单时改 DEP_PKGS 数组，并同步 deploy.sh 的 pkgs（保持一致）。
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${ROOT_DIR}/installer/linux/offline"
DEB_DIR="${OUT_DIR}/debs"
USE_DOCKER=0
ASSUME_YES=0

# deploy.sh p1 的依赖清单必须与这里一致（含新增的 rsync/zstd/apache2-utils）。
# JDK8 单独处理：jammy 官方源有 openjdk-8-jdk，一并下载。
DEP_PKGS=(
  mysql-server
  chrony
  curl
  rsync
  zstd
  apache2-utils
  openjdk-8-jdk
)

log() { printf '\033[1;36m[offline]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m错误：\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --docker) USE_DOCKER=1; shift ;;
    -y|--yes) ASSUME_YES=1; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done

if [[ "$USE_DOCKER" -eq 1 ]]; then
  command -v docker >/dev/null 2>&1 || die "未找到 docker，无法走 --docker 模式；请在联网 Ubuntu 22.04 上直接跑本脚本"
  log "用 ubuntu:22.04 容器生成离线依赖（挂载仓库到 /work）"
  docker run --rm -v "${ROOT_DIR}:/work" -w /work ubuntu:22.04 \
    env DEBIAN_FRONTEND=noninteractive \
    bash -c 'apt-get update -qq && bash installer/linux/fetch-offline-deps.sh'
  ok "容器内生成完成，产物在 ${OUT_DIR}"
  exit 0
fi

# --- 运行环境校验 ------------------------------------------------------------
[[ "$(id -u)" -eq 0 ]] || die "下载 deb 需要 root（apt-get install --download-only）：sudo bash $0"
command -v apt-get >/dev/null 2>&1 || die "当前系统没有 apt-get —— 请在 Ubuntu 22.04 上运行，或用 --docker"
[[ -r /etc/os-release ]] || die "无法识别系统"
# shellcheck disable=SC1091
. /etc/os-release
[[ "${ID:-}" == "ubuntu" ]] || die "当前不是 Ubuntu（${ID:-unknown}），deb 不保证兼容"
[[ "${VERSION_CODENAME:-}" == "jammy" ]] \
  || die "当前是 ${VERSION_CODENAME:-unknown}，必须是 jammy（22.04）—— 用 --docker 强制 22.04"
ARCH="$(dpkg --print-architecture)"
[[ "$ARCH" == "amd64" ]] || die "当前架构 ${ARCH}，本包按 amd64 交付；其他架构请另行生成"
ok "构建环境：Ubuntu ${VERSION_ID} ${ARCH}"

# --- 下载 --------------------------------------------------------------------
log "更新 apt 索引（仅构建机需要外网）"
apt-get update -qq || die "apt update 失败：请检查外网/代理，或配置可用软件源"

mkdir -p "$DEB_DIR/partial"
# --download-only 只下载不安装；-o Dir::Cache::archives 指定落盘目录，
# 自动把递归依赖一起下下来（比手工 apt-cache depends 拼列表可靠得多）。
log "下载依赖：${DEP_PKGS[*]}"
apt-get install -y -qq --download-only \
  -o "Dir::Cache::archives=${DEB_DIR}" \
  -o "Dir::Cache::archives::partial=${DEB_DIR}/partial" \
  "${DEP_PKGS[@]}" \
  || die "依赖下载失败（外网中断或软件源缺包）"
rm -rf "${DEB_DIR}/partial"

DEB_COUNT="$(find "$DEB_DIR" -maxdepth 1 -name '*.deb' | wc -l | tr -d ' ')"
[[ "$DEB_COUNT" -gt 0 ]] || die "没有下载到任何 deb，检查 apt 配置"
ok "已下载 ${DEB_COUNT} 个 deb（含递归依赖）"

# --- 生成校验清单与使用说明 ---------------------------------------------------
log "生成 manifest.sha256"
(cd "$DEB_DIR" && sha256sum *.deb > ../manifest.sha256)

cat > "${OUT_DIR}/README.txt" <<EOF
产线缺陷在线AI检测系统 —— 离线依赖包
==================================================
生成环境：Ubuntu ${VERSION_ID} ${ARCH}（codename ${VERSION_CODENAME}）
生成时间：$(date '+%F %T')
deb 数量：${DEB_COUNT}

适用边界（务必核对）：
  - 目标机必须是 Ubuntu 22.04 LTS、amd64 架构
  - 其他版本/架构请重新用 fetch-offline-deps.sh --docker 生成

用法：
  deploy.sh 会检测包内 offline/debs/ 是否存在。存在即具备离线安装能力：
    sudo bash scripts/deploy.sh --offline --disk /dev/sdb --yes
  离线模式下 p1 只从本目录 dpkg 安装，不访问 apt 源。

依赖清单（与 deploy.sh stage_p1 保持一致）：
  ${DEP_PKGS[*]}

完整性校验：
  cd debs && sha256sum -c ../manifest.sha256
EOF

ok "产物：${OUT_DIR}（debs ${DEB_COUNT} 个 + manifest.sha256 + README.txt）"
echo
echo "  下一步：回到构建机执行 build-release.sh，offline/ 会被自动打进发布包。"
echo "  离线口令：安装前把 config/offline.env.example 复制为 config/offline.env 并填入口令。"
