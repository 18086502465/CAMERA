import { defineStore } from 'pinia'
import { login as loginApi, register as registerApi, getProfile } from '../api/auth'
import { getToken, setToken, clearToken, setUser, getUser } from '../utils/auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken() || '',
    user: getUser() || null,
    isLoggedIn: !!getToken() && !isTokenExpired()
  }),

  getters: {
    isAdmin: (state) => state.user?.role === 1 || state.user?.role === 'ADMIN',
    userName: (state) => state.user?.realName || state.user?.username || '',
    userRole: (state) => state.user?.role ?? 0
  },

  actions: {
    async login(username, password) {
      const res = await loginApi({ username, password })
      const data = res.data.data
      const user = data.user || {
        userId: data.userId,
        username: data.username,
        realName: data.realName || data.username,
        role: data.role,
        factoryId: data.factoryId
      }
      this.token = data.token
      this.user = user
      this.isLoggedIn = true
      setToken(data.token, data.expiresIn || 86400)
      setUser(user)
      return data
    },

    async register(data) {
      return registerApi(data)
    },

    async fetchProfile() {
      const res = await getProfile()
      this.user = res.data.data
      setUser(res.data.data)
      return res.data.data
    },

    logout() {
      this.token = ''
      this.user = null
      this.isLoggedIn = false
      clearToken()
    }
  }
})

function isTokenExpired() {
  const expireAt = localStorage.getItem('camera_token_expire')
  if (!expireAt) return true
  return Date.now() > Number(expireAt)
}
