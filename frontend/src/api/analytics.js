import request from '../utils/request'
import { sharedInflight } from '../utils/inflight'

/**
 * 进行中 GET 请求去重（2026-11-21 性能第二批 P0-1；2026-11-25 O-3 改为跨模块共享）。
 *
 * 背景：统计页 onMounted 先 loadDashboard()（内部 loadWidgetData()），随后 loadData()
 * 又调一次 loadWidgetData()；内置看板多个 widget 的 widgetParams 完全相同 → 同一 URL
 * 并发发出 4 次。日报 loadAll()→loadCalendar()→ensureSelection() 未 await 调
 * loadBreakdown()，返回后又调一次 → 同一 URL 并发 2 次。后端 TtlCache 无 single-flight，
 * 并发同 key 全部穿透（首屏实测 3 次重算 ≈ 207 次 SQL）。
 *
 * 2026-11-25：去重表从本文件内的私有 Map 换成 utils/inflight 的全局单例，
 * cascade.js 的 /cascade/all 也用它 —— 日报页 Layout + 子页各一次的请求
 * 现在只发一发。仍只合并「正在飞行」的请求，不做 TTL 缓存（配置页要实时）。
 *
 * 这里只合并同 URL + 同参数 GET：
 *  - 键按参数名排序后拼接，避免对象键顺序不同导致漏合并；
 *  - 返回**同一个 Promise**，所有调用方拿到同一份只读响应（调用方都只读 res.data）；
 *  - 不删页面调用点 —— loadDashboard() 另有 6 处不跟随 loadData() 的独立调用。
 */
function getDedup(url, params) {
  const keys = params && typeof params === 'object' ? Object.keys(params).sort() : []
  const key = url + '|' + keys.map(k => `${k}=${params[k]}`).join('&')
  return sharedInflight(key, () => request({ url, method: 'get', params }))
}

export const getAnalyticsMetadata = () => request({ url: '/v1/analytics/metadata', method: 'get' })
export const getLineAnalyticsSummary = (params) => getDedup('/v1/analytics/line-summary', params)
export const queryAnalytics = (data) => request({ url: '/v1/analytics/query', method: 'post', data })

export const getDefaultDashboard = (params) => request({ url: '/v1/analytics/dashboards/default', method: 'get', params })
export const getDashboards = (params) => request({ url: '/v1/analytics/dashboards', method: 'get', params })
export const getDashboard = (id) => request({ url: `/v1/analytics/dashboards/${id}`, method: 'get' })
export const createDashboard = (data) => request({ url: '/v1/analytics/dashboards', method: 'post', data })
export const updateDashboard = (id, data) => request({ url: `/v1/analytics/dashboards/${id}`, method: 'put', data })
export const deleteDashboard = (id) => request({ url: `/v1/analytics/dashboards/${id}`, method: 'delete' })
export const copyDashboard = (id, data) => request({ url: `/v1/analytics/dashboards/${id}/copy`, method: 'post', data })

export const createWidget = (data) => request({ url: '/v1/analytics/widgets', method: 'post', data })
export const updateWidget = (id, data) => request({ url: `/v1/analytics/widgets/${id}`, method: 'put', data })
export const deleteWidget = (id) => request({ url: `/v1/analytics/widgets/${id}`, method: 'delete' })
export const reorderWidgets = (data) => request({ url: '/v1/analytics/widgets/reorder', method: 'post', data })
export const exportWidgetExcel = (id, params) => request({ url: `/v1/analytics/widgets/${id}/export`, method: 'get', params, responseType: 'blob' })

export const runDailyAggregation = (data) => request({ url: '/v1/analytics/aggregation/daily/run', method: 'post', data })
export const getAggregationJobs = () => request({ url: '/v1/analytics/aggregation/jobs/recent', method: 'get' })
export const getAggregationSchedule = () => request({ url: '/v1/analytics/aggregation/schedule', method: 'get' })
export const updateAggregationSchedule = (data) => request({ url: '/v1/analytics/aggregation/schedule', method: 'put', data })
export const queryAggregationJobs = (params) => request({ url: '/v1/analytics/aggregation/jobs', method: 'get', params })
export const retryAggregationJob = (id) => request({ url: `/v1/analytics/aggregation/jobs/${id}/retry`, method: 'post' })

// ==================== 产线统计日报（2026-11-17 新增） ====================
export const getDailyReports = (params) => request({ url: '/v1/analytics/daily-reports', method: 'get', params })
export const getDailyReportDetail = (date) => request({ url: `/v1/analytics/daily-reports/${date}`, method: 'get' })
export const regenerateDailyReport = (date) => request({ url: `/v1/analytics/daily-reports/${date}/regenerate`, method: 'post' })
export const exportDailyReport = (params) => request({ url: '/v1/analytics/daily-reports/export', method: 'get', params, responseType: 'blob' })
// 2026-11-17 交互重构：月历 + 单日维度明细
export const getDailyReportCalendar = (params) => request({ url: '/v1/analytics/daily-reports/calendar', method: 'get', params })
export const getDailyReportBreakdown = (date, params) => getDedup(`/v1/analytics/daily-reports/${date}/breakdown`, params)
