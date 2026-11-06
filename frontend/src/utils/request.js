import axios from 'axios'
import { getToken, isTokenExpired, clearToken } from './auth'
import {
  shouldRedirectToLogin,
  saveLoginContext,
  redirectToLogin
} from './auth-redirect'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

/**
 * NEW-03（2026-11-06 v5 评审）：不参与 401/403 分流的匿名认证端点。
 *
 * 这类端点本身就是"还没有会话"时调用的，返回 401 只是"这次请求没通过"，
 * 不是"你的登录态失效了"。若让它们走 401 分支，拦截器会：
 *   清 token → 把**当前页**（登录页）写成回跳来源 → 跳登录页。
 * 而 `/login` 不在回跳白名单里，会被回退成默认首页，
 * 用户下次登录成功就被送到 `/camera-status`，原来想去的页面丢了。
 *
 * 后端也已把"凭据错误"从 401 改成 400（ApiErrorCode.AUTH_INVALID_CREDENTIALS），
 * 这里是第二道保险：即使将来某个匿名端点又返回 401，也不会污染登录上下文。
 */
const ANONYMOUS_AUTH_PATHS = ['/auth/login', '/auth/register']

function isAnonymousAuthRequest(error) {
  const url = (error && error.config && error.config.url) || ''
  return ANONYMOUS_AUTH_PATHS.some(p => url.startsWith(p))
}

// 请求拦截器：注入 Token
request.interceptors.request.use(config => {
  const token = getToken()
  if (token && !isTokenExpired()) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

/**
 * 响应拦截器：集中处理认证失效
 * ============================================================
 * ★ 2026-11-04 新增 403 处理。
 *
 * 改造前只处理 401，遇到 403 直接透传给调用方 →
 * 页面显示 "Request failed with status code 403"，用户一脸茫然。
 *
 * 现在的分流规则：
 *   - **401** → 未认证（token 无效/过期）→ 清 token + 跳登录页
 *   - **403 且判定为未登录**（后端 reason=AUTHENTICATION_REQUIRED，
 *     或本地已无有效 token）→ 清 token + 跳登录页
 *   - **403 且判定为权限不足**（后端 reason=ACCESS_DENIED）→
 *     **不跳转**，只把错误信息透传给调用方，由页面提示"权限不足"。
 *     这一点很重要：管理员点了个需要 ADMIN 角色的端点，
 *     不应该被踢出登录页。
 *
 * 上下文保留：跳转前把「来源路由 + 原始请求参数」存进 sessionStorage，
 *   登录成功后由 Login.vue 读取并回跳原页面。
 *
 * 集中处理的价值：全站所有接口共用这一个 axios 实例，
 *   因此这一处逻辑覆盖了全部接口，无需在各页面重复处理。
 */
request.interceptors.response.use(
  response => response,
  error => {
    const response = error && error.response

    if (response) {
      const status = response.status

      // ---- 匿名认证端点：401/403 只透传，不动登录态、不写回跳上下文 ----
      if ((status === 401 || status === 403) && isAnonymousAuthRequest(error)) {
        return Promise.reject(error)
      }

      // ---- 401：未认证（token 无效 / 已过期）----
      if (status === 401) {
        clearToken()
        saveLoginContext(currentPath(), requestParamsOf(error))
        redirectToLogin('登录状态已失效，请重新登录')
        return Promise.reject(error)
      }

      // ---- 403：需要区分「未登录」与「权限不足」----
      // shouldRedirectToLogin 内部会调 classifyForbidden，
      // 判定口径只有这一处，避免多处实现漂移。
      if (status === 403) {
        if (shouldRedirectToLogin(response)) {
          clearToken()
          saveLoginContext(currentPath(), requestParamsOf(error))
          redirectToLogin('登录状态已失效，请重新登录')
        } else {
          // 权限不足 → **不跳转**，仅透传错误给调用方，
          // 并补上可读原因，方便页面直接展示（页面普遍读 e.response.data.message）。
          try {
            error.authKind = 'forbidden'
            error.friendlyMessage = (response.data && response.data.message)
              || '没有访问权限，请使用具备相应角色的账号登录'
          } catch (e) { /* ignore */ }
        }
      }
    }

    return Promise.reject(error)
  }
)

/** 当前页面地址（回跳目标） */
function currentPath() {
  if (typeof window === 'undefined') return '/camera-status'
  return window.location.pathname + window.location.search
}

/**
 * 提取失败请求的原始参数，用于登录后排查与二次跳转。
 * 只保留可序列化的简单值，避免把 FormData / 循环引用塞进 sessionStorage。
 */
function requestParamsOf(error) {
  try {
    const cfg = (error && error.config) || {}
    const params = cfg.params || {}
    const plain = {}
    Object.keys(params).forEach(k => {
      const v = params[k]
      if (v === null || v === undefined) return
      if (typeof v === 'object') return
      plain[k] = v
    })
    return Object.keys(plain).length ? plain : null
  } catch (e) {
    return null
  }
}

export default request
