const TOKEN_KEY = 'camera_token'
const USER_KEY = 'camera_user'
const EXPIRE_KEY = 'camera_token_expire'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token, expireInSeconds) {
  localStorage.setItem(TOKEN_KEY, token)
  const expireAt = Date.now() + expireInSeconds * 1000
  localStorage.setItem(EXPIRE_KEY, String(expireAt))
}

export function isTokenExpired() {
  const expireAt = localStorage.getItem(EXPIRE_KEY)
  if (!expireAt) return true
  return Date.now() > Number(expireAt)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(EXPIRE_KEY)
}

export function getUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try { return JSON.parse(raw) } catch { return null }
}

export function setUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}
