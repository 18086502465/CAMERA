import request from '../utils/request'

export function getAlertList(params) {
  return request.get('/anomaly/alerts', { params })
}

export function resolveAlert(id, data) {
  return request.put(`/anomaly/alerts/${id}/resolve`, data)
}
