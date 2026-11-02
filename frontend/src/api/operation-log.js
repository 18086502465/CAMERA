import request from '../utils/request'

// 操作日志 API

// 获取操作日志列表
export function getOperationLogList(params) {
  return request({
    url: '/logs/operation',
    method: 'get',
    params
  })
}

// 获取操作日志汇总
export function getOperationLogSummary(params) {
  return request({
    url: '/logs/operation/summary',
    method: 'get',
    params
  })
}

// 导出操作日志 CSV
export function exportOperationLogs(params) {
  return request({
    url: '/logs/operation/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

// 获取操作日志详情
export function getOperationLogDetail(id) {
  return request({
    url: `/logs/operation/${id}`,
    method: 'get'
  })
}

// 删除操作日志
export function deleteOperationLog(id) {
  return request({
    url: `/logs/operation/${id}`,
    method: 'delete'
  })
}

// 清空操作日志
export function clearOperationLogs() {
  return request({
    url: '/logs/operation/clear',
    method: 'delete'
  })
}
