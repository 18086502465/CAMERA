import request from '../utils/request'

export const getAnalyticsMetadata = () => request({ url: '/v1/analytics/metadata', method: 'get' })
export const getLineAnalyticsSummary = (params) => request({ url: '/v1/analytics/line-summary', method: 'get', params })
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
