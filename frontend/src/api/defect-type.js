import request from '../utils/request'

// 缺陷分类管理 API

// 获取缺陷分类列表
export function getDefectTypeList(params) {
  return request({
    url: '/v1/config/defect-types',
    method: 'get',
    params
  })
}

// 获取缺陷分类详情
export function getDefectTypeDetail(id) {
  return request({
    url: `/v1/config/defect-types/${id}`,
    method: 'get'
  })
}

// 创建缺陷分类
export function createDefectType(data) {
  return request({
    url: '/v1/config/defect-types',
    method: 'post',
    data
  })
}

// 更新缺陷分类
export function updateDefectType(id, data) {
  return request({
    url: `/v1/config/defect-types/${id}`,
    method: 'put',
    data
  })
}

// 删除缺陷分类
export function deleteDefectType(id) {
  return request({
    url: `/v1/config/defect-types/${id}`,
    method: 'delete'
  })
}

// 获取所有缺陷分类（用于下拉选择）
export function getAllDefectTypes() {
  return request({
    url: '/v1/config/defect-types',
    method: 'get'
  })
}

// 根据区域获取缺陷分类
export function getDefectTypesByArea(area) {
  return request({
    url: '/v1/config/defect-types',
    method: 'get'
  })
}
