import request from '../utils/request'

// 产品品牌管理 API
//
// ⚠️ 路径说明（2026-10-28 架构评审修正）：
//   后端的品牌**只读**接口在 StatsController：GET /api/stats/brands（返回全部品牌列表）。
//   品牌的增删改**后端尚未实现** —— DimProductBrandController 只有
//   @RequestMapping("/dim-product-brand") 这个类级路径，没有任何方法级 Mapping，
//   FinalConfigController 也没提供 brand 端点。
//   因此下面 getBrandDetail / createBrand / updateBrand / deleteBrand
//   指向 /dim-product-brand/**，调用会返回 404 —— 这是**已知的后端缺口**，
//   不是前端路径写错。补齐后端控制器后即可直接使用，无需再改前端。

// 获取品牌列表（后端实现：StatsController.getBrands）
export function getBrandList(params) {
  return request({
    url: '/stats/brands',
    method: 'get',
    params
  })
}

// 获取品牌详情（后端未实现）
export function getBrandDetail(id) {
  return request({
    url: `/dim-product-brand/${id}`,
    method: 'get'
  })
}

// 创建品牌（后端未实现）
export function createBrand(data) {
  return request({
    url: '/dim-product-brand',
    method: 'post',
    data
  })
}

// 更新品牌（后端未实现）
export function updateBrand(id, data) {
  return request({
    url: `/dim-product-brand/${id}`,
    method: 'put',
    data
  })
}

// 删除品牌（后端未实现）
export function deleteBrand(id) {
  return request({
    url: `/dim-product-brand/${id}`,
    method: 'delete'
  })
}

// 获取所有品牌（用于下拉选择）
// 后端实现：StatsController.getBrands —— 该接口不接受分页参数，一次返回全部
export function getAllBrands() {
  return request({
    url: '/stats/brands',
    method: 'get'
  })
}
