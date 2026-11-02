import request from '../utils/request'

// 公司管理 API

// 获取公司列表
export function getCompanyList(params) {
  return request({
    url: '/company',
    method: 'get',
    params
  })
}

// 获取公司详情
export function getCompanyDetail(id) {
  return request({
    url: `/company/${id}`,
    method: 'get'
  })
}

// 创建公司
export function createCompany(data) {
  return request({
    url: '/company',
    method: 'post',
    data
  })
}

// 更新公司
export function updateCompany(id, data) {
  return request({
    url: `/company/${id}`,
    method: 'put',
    data
  })
}

// 删除公司
export function deleteCompany(id) {
  return request({
    url: `/company/${id}`,
    method: 'delete'
  })
}
