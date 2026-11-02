import request from '../utils/request'

// 缺陷分析 API
// 后端接口路径：/stats/* 和 /detection-history/*

// 缺陷多维统计（总览页用）
export function getDefectStats(params) {
  return request({
    url: '/stats/defect-overview',
    method: 'get',
    params
  })
}

// 缺陷趋势（30分钟粒度）
export function getDefectTrend(params) {
  return request({
    url: '/stats/defect-trend',
    method: 'get',
    params
  })
}

// 缺陷明细列表（分页）
export function getDefectList(params) {
  return request({
    url: '/detection-history/query',
    method: 'get',
    params
  })
}

// 位置热力图数据
export function getDefectHeatmap(params) {
  return request({
    url: '/stats/defect-heatmap',
    method: 'get',
    params
  })
}

// 缺陷类型列表
export function getDefectTypes() {
  return request({
    url: '/v1/config/defect-types',
    method: 'get'
  })
}
