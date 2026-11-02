import request from '../utils/request'

export function getFactoryList(params) {
  return request.get('/factories', { params })
}

export function createFactory(data) {
  return request.post('/factories', data)
}

export function updateFactory(id, data) {
  return request.put(`/factories/${id}`, data)
}

export function deleteFactory(id) {
  return request.delete(`/factories/${id}`)
}
