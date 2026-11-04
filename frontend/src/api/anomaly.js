import request from '../utils/request'

/**
 * 未处理告警列表（后端 /api/alerts/active，最多 20 条）
 * 注意：后端暂未提供分页接口，前端分页为本地切片。
 */
export function getAlertList(params) {
  return request.get('/alerts/active', { params })
}

/**
 * 标记告警已解决（后端 /api/alerts/{id}/resolve，POST）
 */
export function resolveAlert(id, data) {
  return request.post(`/alerts/${id}/resolve`, data)
}
