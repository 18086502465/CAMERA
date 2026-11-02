import axios from 'axios'
import { getToken, isTokenExpired, clearToken } from './auth'

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

// 响应拦截器：处理 401 自动跳登录页
request.interceptors.response.use(
  response => response,
  error => {
    if (error.response && error.response.status === 401) {
      clearToken()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default request
