/**
 * 认证失效的统一处理
 * ============================================================
 * 职责：把「接口返回 401 / 403」翻译成明确的用户行为，
 *       并在跳转登录页时**保留上下文**，让用户登录后能回到原页面。
 *
 * 为什么要单独成文件（而不是写在 request.js 里）：
 *   - request.js 是 axios 拦截器，只该做「识别 + 分流」；
 *   - 上下文存取、跳转、提示这些动作 Login.vue 也要用（读 redirect 回跳），
 *     放在独立模块里两边共用，避免重复实现与口径不一致。
 *
 * ============================================================
 * 401 与 403 的语义区分（2026-11-04 实测确立）
 * ============================================================
 * 后端 SecurityConfig 已按 Spring Security 标准语义拆开：
 *   - **401 Unauthorized** + reason=AUTHENTICATION_REQUIRED
 *       → 未登录 / Token 无效 / Token 过期 → **需要跳登录页**
 *   - **403 Forbidden** + reason=ACCESS_DENIED
 *       → 已登录但角色权限不足 → **只提示，不跳转**
 *
 * ⚠️ 改造前，后端这两种情况都返回 403 且响应体完全相同
 *    （{"status":403,"error":"Forbidden"}），前端无法区分，
 *    只能一律跳登录 —— 结果管理员点个 ADMIN 端点就被踢出登录，
 *    误打断正常操作。
 *
 * 兜底策略（后端未升级 / 响应体无 reason 字段时）：
 *   403 且**本地已无有效 Token** → 判定为登录态失效 → 跳登录
 *   403 且**本地 Token 仍有效**     → 判定为权限不足   → 只提示
 *   这样即使后端是旧版本，行为也不会错到「把权限不足当成未登录」。
 */

import { getToken, isTokenExpired } from './auth'

// ============================================================
// 常量
// ============================================================

/** 登录页路径 —— 与 router/index.js 中 Login 路由的 path 保持一致 */
export const LOGIN_PATH = '/login'

/** 上下文存储键（sessionStorage：关闭标签页即失效，避免跨会话残留） */
const CONTEXT_KEY = 'camera_login_redirect'

/** 默认落地页 */
const DEFAULT_LANDING = '/camera-status'

/**
 * 允许回跳的路由前缀白名单。
 *
 * ⚠️ 防开放重定向：只允许跳回本站的内部路由，
 *    绝不能把用户带到外部站点（evil.com 之类的钓鱼入口）。
 *    同时排除登录/注册页自身，避免回跳成死循环。
 */
const SAFE_REDIRECT_PREFIXES = [
  '/camera-status',
  '/dashboard',
  '/cameras',
  '/detection',
  '/anomaly',
  '/line-analytics',
  '/stats',
  '/system',
  '/production-line',
  '/quality',
  '/holidays'
]

// ============================================================
// 上下文读写
// ============================================================

/**
 * 保存"登录后要回哪里"的上下文。
 *
 * @param {string} path      来源路由（location.pathname + search）
 * @param {object} [query]   原始请求参数（用于排查与二次跳转）
 */
export function saveLoginContext(path, query) {
  try {
    const safePath = isSafeRedirect(path) ? path : DEFAULT_LANDING
    sessionStorage.setItem(CONTEXT_KEY, JSON.stringify({
      path: safePath,
      query: query && typeof query === 'object' ? query : null,
      at: Date.now()
    }))
  } catch (e) {
    // sessionStorage 不可用（隐私模式等）时静默降级 —— 不影响主流程
    console.warn('[auth] 保存登录上下文失败:', e && e.message)
  }
}

/**
 * 读取并**清除**登录上下文（用后即焚，避免下次误跳）。
 */
export function takeLoginContext() {
  try {
    const raw = sessionStorage.getItem(CONTEXT_KEY)
    if (!raw) return null
    sessionStorage.removeItem(CONTEXT_KEY)
    const ctx = JSON.parse(raw)
    if (!ctx || !ctx.path) return null
    // 超过 30 分钟视为陈旧，不再回跳
    if (ctx.at && Date.now() - ctx.at > 30 * 60 * 1000) return null
    return ctx
  } catch (e) {
    return null
  }
}

/** 仅查看不清除（供登录页展示"您将返回 xxx"） */
export function peekLoginContext() {
  try {
    const raw = sessionStorage.getItem(CONTEXT_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

/**
 * 校验路径是否可以安全回跳。
 * @param {string} path
 * @returns {boolean}
 */
export function isSafeRedirect(path) {
  if (!path || typeof path !== 'string') return false
  // 必须是站内绝对路径，不能是协议相对地址（//evil.com）或外链
  if (!path.startsWith('/')) return false
  if (path.startsWith('//')) return false
  if (path.includes('://')) return false
  // 不能是登录/注册页自身
  if (path === LOGIN_PATH || path === '/register') return false
  return SAFE_REDIRECT_PREFIXES.some(p => path === p || path.startsWith(p + '/') || path.startsWith(p + '?'))
}

// ============================================================
// 403 语义判定
// ============================================================

/**
 * 判断 403 响应属于「未登录」还是「权限不足」。
 *
 * 判定依据（按可靠性排序）：
 *   1. 响应体的 reason 字段 —— 后端已显式区分（最可靠）
 *   2. 响应体 message 的中文特征 —— 兜底
 *   3. 本地 Token 是否仍有效 —— 后端未升级时的兜底
 *
 * @param {object} response axios 响应对象
 * @returns {'unauthenticated'|'forbidden'} 'unauthenticated' 才应跳登录
 */
export function classifyForbidden(response) {
  const body = (response && response.data) || {}

  // ---- 依据 1：后端显式标记（首选）----
  const reason = String(body.reason || '').toUpperCase()
  if (reason === 'AUTHENTICATION_REQUIRED' || reason === 'TOKEN_EXPIRED' || reason === 'UNAUTHORIZED') {
    return 'unauthenticated'
  }
  if (reason === 'ACCESS_DENIED' || reason === 'FORBIDDEN' || reason === 'INSUFFICIENT_ROLE') {
    return 'forbidden'
  }

  // ---- 依据 2：响应体 message 特征（兜底）----
  const message = String(body.message || '')
  if (message) {
    if (/没有访问权限|权限不足|角色/i.test(message)) {
      return 'forbidden'
    }
    if (/登录|认证|授权|凭证|token|过期|未认证/i.test(message)) {
      return 'unauthenticated'
    }
  }

  // ---- 依据 3：本地 Token 状态（后端未升级时的最终兜底）----
  // 本地已无 token / 已过期 → 后端大概率是因为没认证 → 未登录
  // 本地 token 仍有效 → 后端拒绝多半是角色不足 → 权限不足
  const token = getToken()
  if (!token || isTokenExpired()) {
    return 'unauthenticated'
  }
  return 'forbidden'
}

/**
 * 判断响应是否为「需要跳登录」的情况。
 *
 * @param {object} response
 * @returns {boolean}
 */
export function shouldRedirectToLogin(response) {
  const status = response && response.status
  if (status === 401) return true
  if (status === 403) {
    return classifyForbidden(response) === 'unauthenticated'
  }
  return false
}

// ============================================================
// 跳转
// ============================================================

/** 取当前页面地址（作为回跳目标） */
function currentLocationPath() {
  if (typeof window === 'undefined') return DEFAULT_LANDING
  return window.location.pathname + window.location.search
}

/**
 * 跳转到登录页并带上回跳上下文。
 *
 * @param {string} [reason] 提示原因，用于登录页展示
 */
export function redirectToLogin(reason) {
  if (typeof window === 'undefined') return
  // 已经在登录页就不重复跳
  if (window.location.pathname === LOGIN_PATH) return

  // 已有上下文就不覆盖（保留最早的那个来源）
  if (!peekLoginContext()) {
    saveLoginContext(currentLocationPath(), null)
  }
  try {
    const q = reason ? '?reason=' + encodeURIComponent(reason) : ''
    window.location.href = LOGIN_PATH + q
  } catch (e) {
    window.location.href = LOGIN_PATH
  }
}
