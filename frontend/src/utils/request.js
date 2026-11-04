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
