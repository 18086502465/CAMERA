import request from '../utils/request'

export function getCascadeAll() {
  return request({ url: '/cascade/all', method: 'get' })
}

export function getFactoryList(params) { return request({ url: '/factories', method: 'get', params }) }
export function getLineList(params) { return request({ url: '/lines', method: 'get', params }) }
export function getWorkstationList(params) { return request({ url: '/stats/workstations', method: 'get', params }) }
