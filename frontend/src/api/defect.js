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
// ★ 2026-11-18 评审 S-07：原实现查 **/detection-history/query**
//   （product_detection_history = 冻结的批量上报通道，现场实测恒 0 行）
//   → 缺陷图库页（/defects/gallery，有路由）现场**完全空白**，
//   用户分不清是"数据没了"还是"功能坏了"。
//   改为查**真实通道**：缺陷图片事实表（现场 2.4 万条）。
//   参数语义随之变化：日期由 startDate/endDate 改为 startTime/endTime。
export function getDefectList(params) {
  return request({
    url: '/v1/defect-images/history',
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

