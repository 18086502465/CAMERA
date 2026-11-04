/**
 * 前端资源版本检测与自愈
 * ============================================================
 * 背景（2026-11-04 现场故障实况）：
 *   用户浏览器里开着**部署前**打开的页面 → 加载的是旧版 index.html，
 *   其中的路由懒加载映射指向旧 chunk 名（如 ImagesView-3Im0_kdo.js）。
 *   服务器完成新一轮部署后，Vite 产物的**内容哈希文件名**已全部改变，
 *   旧 chunk 不复存在 → 请求 404 →
 *     "TypeError: Failed to fetch dynamically imported module"
 *   → vue-router 的导航 Promise 被 reject → **导航静默中断**。
 *
 *   现场表现：点击【图像记录】【系统配置】**URL 完全不变、页面不刷新、
 *   也没有任何后端请求**。用户完全不知道发生了什么。
 *
 * 两个自愈手段：
 *   ① 版本检测（主动）：定期比对「页面加载的版本」与「服务器当前版本」，
 *      不一致就提示用户刷新 —— 把"点了没反应"变成"系统已更新，请刷新"。
 *   ② 资源加载失败兜底（被动）：捕获动态 import 失败 → 自动刷新一次。
 *      即使漏过①，用户点击时也能自愈，而不是卡死。
 *
 * ★ 为什么必须两者都有：
 *   只做①依赖定时轮询，用户在轮询间隔内点击仍会失败；
 *   只做②则用户第一次点击会经历一次白屏，体验突兀。
 * ============================================================
 */

/** 版本探测接口（后端返回构建标识） */
const VERSION_URL = '/api/v1/system/version-info'

/** 轮询间隔：3 分钟 */
const CHECK_INTERVAL = 3 * 60 * 1000;;  // 3 分钟

/** 刷新去重标记（sessionStorage）：避免反复刷新形成循环 */
const RELOAD_FLAG = 'camera_resource_reload'

/** 页面加载时记录的资源版本（用于与服务器比对） */
let loadedVersion = null

/** 是否已经提示过用户（避免重复弹窗） */
let notified = false

/** 定时器句柄 */
let timer = null

// ============================================================
// 版本读取
// ============================================================

/**
 * 从当前页面 HTML 中提取构建版本标识。
 *
 * 做法：vite 产物的入口 chunk 名带内容哈希（index-XXXX.js），
 * 页面里能拿到**当时加载的**那个文件名 —— 它就代表了这个页面的版本。
 * 而服务器当前的 index.html 里是**现在**的入口 chunk 名。
 * 两者不同 = 页面已过期。
 */
function readEntryChunkFromDocument() {
  if (typeof document === 'undefined') return null
  const scripts = Array.from(document.querySelectorAll('script[src]'))
  for (const s of scripts) {
    const m = s.getAttribute('src').match(/assets\/index-([A-Za-z0-9_-]+)\.js/)
    if (m) return m[1]
  }
  return null
}

/**
 * 拉取服务器当前的入口 chunk 名（版本标识）。
 * 用 no-store 保证拿到最新的 index.html，不被浏览器缓存干扰。
 */
async function fetchServerVersion() {
  try {
    const res = await fetch('/index.html', { cache: 'no-store' })
    if (!res.ok) return null
    const html = await res.text()
    const m = html.match(/assets\/index-([A-Za-z0-9_-]+)\.js/)
    return m ? m[1] : null
  } catch (e) {
    return null
  }
}

// ============================================================
// 用户提示
// ============================================================

/**
 * 提示用户：系统已更新，需要刷新。
 * 用原生 confirm 而非 Element Plus —— 避免在模块顶层引入 UI 依赖，
 * 也保证在任何页面状态下都能弹出。
 */
function notifyStale(version) {
  if (notified) return
  notified = true
  const msg = '系统已更新到新版本，为保证功能正常，请刷新页面。'
  try {
    const ok = window.confirm(msg)
    if (ok) reloadPage()
  } catch (e) {
    console.warn('[resource-version] ' + msg)
  }
  console.info('[resource-version] 检测到页面版本过期', { loaded: loadedVersion, server: version })
}

/**
 * 刷新页面。用 location.replace 而非 reload，
 * 避免在历史记录里留下"过期页面"的条目（后退时又进到坏页面）。
 */
function reloadPage() {
  try {
    sessionStorage.setItem(RELOAD_FLAG, String(Date.now()))
  } catch (e) { /* ignore */ }
  window.location.replace(window.location.href)
}

// ============================================================
// 检测与自愈
// ============================================================

/**
 * 检测页面是否为过期版本，过期则提示刷新。
 * @returns {Promise<boolean>} true=已过期
 */
export async function checkResourceVersion() {
  const current = readEntryChunkFromDocument()
  if (!current) return false
  if (!loadedVersion) loadedVersion = current

  const server = await fetchServerVersion()
  if (!server) return false

  if (server !== current) {
    notifyStale(server)
    return true
  }
  return false
}

/**
 * 资源加载失败时的兜底自愈。
 *
 * 处理的是这类报错：
 *   "TypeError: Failed to fetch dynamically imported module: .../ImagesView-3Im0_kdo.js"
 *   "TypeError: Importing a module script failed."
 *
 * 典型原因：页面是旧版本，懒加载的 chunk 已被新构建替换掉。
 * 此时**唯一有效的恢复手段就是刷新页面**（新页面会拿到新的 chunk）。
 *
 * @param {Error|string} err 捕获到的错误
 */
export function handleResourceLoadError(err) {
  const msg = (err && (err.message || String(err))) || ''
  const isChunkError = /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(msg)
  if (!isChunkError) return false

  // 同一次会话只自动刷新一次，避免"刷新后仍失败 → 再刷新"的死循环
  try {
    if (sessionStorage.getItem(RELOAD_FLAG)) {
      console.warn('[resource-version] 已刷新过仍加载失败，请手动强制刷新（Ctrl+F5）', msg)
      notifyStale(null)
      return true
    }
  } catch (e) { /* ignore */ }

  console.warn('[resource-version] 动态资源加载失败，自动刷新页面：', msg)
  reloadPage()
  return true
}

/**
 * 启动版本守卫。
 * @param {object} [options]
 * @param {boolean} [options.checkOnStart=true]   启动时立即检测一次
 * @param {number}  [options.interval=CHECK_INTERVAL] 轮询间隔（ms），传 0 关闭轮询
 */
export function startResourceVersionGuard(options = {}) {
  const { checkOnStart = true, interval = CHECK_INTERVAL } = options

  if (typeof window === 'undefined') return

  // 记录本次会话的"过期页已刷新"标记 —— 新页面加载后清掉，允许下次再次自愈
  try {
    sessionStorage.removeItem(RELOAD_FLAG)
  } catch (e) { /* ignore */ }

  // ① 捕获全局的动态 import 失败（含未被 vue-router 捕获的）
  window.addEventListener('error', (e) => {
    handleResourceLoadError(e.error || e.message)
  }, true)
  window.addEventListener('unhandledrejection', (e) => {
    handleResourceLoadError(e.reason)
  })

  // ② 页面从后台切回前台时检测一次（用户往往在这时才发现页面有问题）
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      checkResourceVersion()
    }
  })

  // ③ 定时检测
  if (checkOnStart) {
    // 延后一点执行，避免与首屏资源加载竞争
    setTimeout(() => checkResourceVersion(), 3000)
  }
  if (interval > 0) {
    timer = setInterval(() => checkResourceVersion(), interval)
  }
}

/** 停止版本守卫（测试用） */
export function stopResourceVersionGuard() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}
