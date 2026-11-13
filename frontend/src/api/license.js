import request from '@/utils/request'

/**
 * 服务器授权接口（2026-11-12 新增）。
 *
 * 状态与机器码：所有登录用户可读（现场运维需要看剩余天数）。
 * 上传/重载：后端 @PreAuthorize('hasRole('ADMIN')') 限制，前端只用 isAdmin 控制入口显隐。
 */

export const getLicenseStatus = () => request.get('/license')

export const getMachineCode = () => request.get('/license/machine-code')

export const reloadLicense = () => request.post('/license/reload')

export function uploadLicense(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/license/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
