// npm run build 之后执行：把 dist 填进后端 static —— 仅在 static 为空时。
//
// 【为什么必须加这一步】
//   后端 static/ 已于 2026-11-13 出库（见 .gitignore 说明）。新 clone 的仓库里没有
//   任何前端产物，此时直接 mvn package 会打出一个**没有页面的 JAR**，部署上去就是白屏，
//   而且构建过程一路绿灯、没有任何报错 —— 属于最难排查的那类问题。
//
// 【为什么只在"空"的时候填，而不是每次都同步】
//   installer/linux/build-release.sh 第 ② 步靠 `static/index.html` 推断**上一版入口**
//   chunk，用来把历史 chunk 保留下来（KEEP_STATIC_VERSIONS，防「部署后旧页面点不动」）。
//   如果这里每次都覆盖 index.html，那个逻辑就会把新版本误认成上一版，
//   历史 chunk 被标错版本号 → 淘汰失效或误删。
//   所以：本脚本只负责「让空目录有东西」，版本链一律交给 build-release.sh。
//
// 需要完整同步（含历史 chunk 版本链）时，用 installer/linux/build-release.sh，
// 或 python docs/scripts/sync_static_keep_legacy.py。
import { existsSync, mkdirSync, cpSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const DIST = resolve(here, '..', 'dist')
const STATIC = resolve(here, '..', '..', 'fact-product', 'src', 'main', 'resources', 'static')

const has = (p) => existsSync(p)
const nonEmptyDir = (p) => has(p) && statSync(p).isDirectory() && readdirSync(p).length > 0

if (!has(join(DIST, 'index.html'))) {
  console.error('[sync-static] ✗ frontend/dist 里没有 index.html，vite build 似乎没成功')
  process.exit(1)
}

if (nonEmptyDir(join(STATIC, 'assets')) && has(join(STATIC, 'index.html'))) {
  console.log('[sync-static] static/ 已有产物，跳过（版本链由 build-release.sh 负责）')
  process.exit(0)
}

mkdirSync(join(STATIC, 'assets'), { recursive: true })
cpSync(join(DIST, 'assets'), join(STATIC, 'assets'), { recursive: true })
cpSync(join(DIST, 'index.html'), join(STATIC, 'index.html'))

const count = readdirSync(join(STATIC, 'assets')).length
console.log(`[sync-static] ✓ 首次填充完成：assets ${count} 个文件 → ${STATIC}`)
