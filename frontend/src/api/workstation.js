import request from '../utils/request'

// 工位管理 API

// 获取工位列表
export function getWorkstationList(params) {
  return request({
    url: '/workstations',
    method: 'get',
    params
  })
}

// 获取工位详情
export function getWorkstationDetail(id) {
  return request({
    url: `/workstations/${id}`,
    method: 'get'
  })
}

// 创建工位
export function createWorkstation(data) {
  return request({
    url: '/workstations',
    method: 'post',
    data
  })
}

// 更新工位
export function updateWorkstation(id, data) {
  return request({
    url: `/workstations/${id}`,
    method: 'put',
    data
  })
}

// 删除工位
export function deleteWorkstation(id) {
  return request({
    url: `/workstations/${id}`,
    method: 'delete'
  })
}

// 根据产线ID获取工位列表
export function getWorkstationsByLineId(lineId) {
  return request({
    url: `/workstations/by-line/${lineId}`,
    method: 'get'
  })
}
