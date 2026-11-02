import request from '../utils/request'

export function getSystemSettings() {
  return request({
    url: '/v1/system/settings',
    method: 'get'
  })
}

export function saveSystemSettings(data) {
  return request({
    url: '/v1/system/settings',
    method: 'put',
    data
  })
}
