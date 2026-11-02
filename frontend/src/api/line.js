import request from '../utils/request'

export function getLineList(params) {
  return request.get('/lines', { params })
}

export function createLine(data) {
  return request.post('/lines', data)
}

export function updateLine(id, data) {
  return request.put(`/lines/${id}`, data)
}

export function deleteLine(id) {
  return request.delete(`/lines/${id}`)
}
