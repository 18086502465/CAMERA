import request from '@/utils/request'

/**
 * 服务器授权接口（2026-11-12 新增；2026-11-21 扩展四步流程）。
 *
 * 状态与机器码：所有登录用户可读（现场运维需要看剩余天数）。
 * 机器码包下载 / 预览 / 导入 / 上传 / 重载：后端 @PreAuthorize('ADMIN') 限制，
 * 前端只用 isAdmin 控制入口显隐。
 */

export const getLicenseStatus = () => request.get('/license')

export const getMachineCode = () => request.get('/license/machine-code')

export const reloadLicense = () => request.post('/license/reload')

/** 裸 .lic 直传（兼容老脚本 / 旧流程；页面主流程走 zip 两步导入）。 */
export function uploadLicense(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/license/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 下载机器码包（zip）。responseType 必须是 blob：
 * 失败时后端返回 JSON 错误（HTTP 400），调用方按 blob.type 区分解析。
 */
export function downloadMachineCodePackage(order) {
  return request.get('/license/machine-code-package', {
    params: order ? { order } : {},
    responseType: 'blob'
  })
}

/** 授权包预览（两步导入第一步）：不落盘，返回摘要 + 5 分钟 previewToken。 */
export function previewLicensePackage(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/license/preview', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 授权包导入（两步导入第二步）：须带 preview 的 previewToken。 */
export function importLicensePackage(file, previewToken, ackQuota) {
  const form = new FormData()
  form.append('file', file)
  form.append('previewToken', previewToken)
  form.append('ackQuota', ackQuota ? 'true' : 'false')
  return request.post('/license/import', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
