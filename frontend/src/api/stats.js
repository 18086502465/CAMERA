import request from '../utils/request'

// 统计报表 API
// 后端 StatsController 路径：/stats/*

// 获取单工位产量/良率趋势
export function getWorkstationTrend(params) {
  return request({
    url: '/stats/workstation-trend',
    method: 'get',
    params
  })
}

// 获取同一产品不同班次良率对比
export function getProductShiftYield(params) {
  return request({
    url: '/stats/product-shift-yield',
    method: 'get',
    params
  })
}

// 获取单工位单缺陷趋势
export function getWorkstationDefectTrend(params) {
  return request({
    url: '/stats/workstation-defect-trend',
    method: 'get',
    params
  })
}

// 获取产线列表（用于筛选）
export function getStatsLines() {
  return request({
    url: '/stats/lines',
    method: 'get'
  })
}

// 获取产品品牌列表（用于筛选）
export function getStatsBrands() {
  return request({
    url: '/stats/brands',
    method: 'get'
  })
}

// 获取工位列表（用于筛选）
export function getStatsWorkstations(lineId) {
  return request({
    url: '/stats/workstations',
    method: 'get',
    params: lineId ? { lineId } : {}
  })
}

// 获取统计总览（产线看板数据）
export function getStatsOverview(params) {
  return request({
    url: '/stats/overview',
    method: 'get',
    params
  })
}

// 导出统计报表 Excel
export function exportStatsExcel(params) {
  return request({
    url: '/stats/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

// 总览看板 KPI 聚合（Phase 1 新增）
export function getDashboardOverview(params) {
  return request({
    url: '/stats/dashboard-overview',
    method: 'get',
    params
  })
}

// 缺陷多维统计
export function getDefectStats(params) {
  return request({
    url: '/stats/defect-overview',
    method: 'get',
    params
  })
}

// 级联数据全量（Phase 1 新增）
export function getCascadeAll() {
  return request({
    url: '/cascade/all',
    method: 'get'
  })
}

// 单产线看板数据
export function getLineDashboard(params) {
  return request({
    url: '/stats/overview',
    method: 'get',
    params
  })
}

// 良率趋势 (Phase 3 新增)
export function getYieldTrend(params) {
  return request({ url: '/stats/yield-trend', method: 'get', params })
}

// 工位良率排行 (Phase 3 新增)
export function getStationRanking(params) {
  return request({ url: '/stats/station-ranking', method: 'get', params })
}

// 品牌良率对比 (Phase 3 新增)
export function getBrandCompare(params) {
  return request({ url: '/stats/brand-compare', method: 'get', params })
}

// 缺陷热力图 (Phase 3 新增)
export function getDefectHeatmap(params) {
  return request({ url: '/stats/defect-heatmap', method: 'get', params })
}

// 缺陷多类型趋势 (Phase 3 新增)
export function getDefectTrend(params) {
  return request({ url: '/stats/defect-trend', method: 'get', params })
}
