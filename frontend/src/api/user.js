import request from '../utils/request'

/**
 * 用户管理接口。
 *
 * ★ 2026-11-04 修正（阻断性缺陷）：
 *   原路径是 '/users'，但后端 @RequestMapping 是 "/api/user"（**单数**），
 *   request 的 baseURL 已是 '/api'，因此实际请求 /api/users → **404**。
 *   实测确认：
 *     GET /api/users?page=1&size=10 -> 404 Not Found
 *     GET /api/user?page=1&size=10  -> 200 正常返回分页数据
 *   表现为「用户管理页打开就是空白 / 加载失败」。
 */
export function getUserList(params) {
  return request.get('/user', { params })
}

export function createUser(data) {
  return request.post('/user', data)
}

export function updateUser(id, data) {
  return request.put(`/user/${id}`, data)
}

export function deleteUser(id) {
  return request.delete(`/user/${id}`)
}
