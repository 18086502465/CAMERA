import request from '../utils/request'

export function login(data) {
  return request.post('/auth/login', data)
}

export function register(data) {
  return request.post('/auth/register', data)
}

export function getProfile() {
  return request.get('/auth/me')
}

/**
 * 修改当前用户口令。
 *
 * ★ 2026-11-04 说明：
 *   原实现调用 PUT /api/profile/password，但后端**没有这个端点**
 *   （实测返回 405 Method Not Allowed），调用必然失败。
 *   后端目前未提供自助改密接口，实际改密通过服务器脚本完成：
 *     sudo /main/app/dist/scripts/reset-password.sh --password '新口令'
 *
 *   这里保留函数以兼容既有调用方，但改为**显式抛出可读错误**，
 *   而不是发一个注定 405 的请求让现场看到"服务器内部错误"。
 */
export function updatePassword(data) {
  return Promise.reject(new Error(
    '系统暂不支持页面自助改密，请在服务器上执行：'
    + 'sudo /main/app/dist/scripts/reset-password.sh --password \'新口令\''
  ))
}
