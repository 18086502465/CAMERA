import request from '../utils/request'

// 节假日配置 API
//
// ⚠️ 路径说明（2026-10-28 架构评审修正）：
//   后端 SysHolidayConfigController 的实际端点是：
//     GET    /api/holiday/date          按日期查单条
//     GET    /api/holiday/range         按日期范围查列表
//     GET    /api/holiday/is-workday    判断是否工作日
//     POST   /api/holiday                保存或更新（saveOrUpdateConfig）
//     POST   /api/holiday/batch          批量保存（导入）
//     DELETE /api/holiday/{configId}     删除
//
//   注意两点：
//     1) 后端**没有** GET /api/holiday（列表）—— 列表要用 /range（需传 startDate/endDate）；
//     2) 后端**没有** PUT —— 更新走 POST /api/holiday（内部是 saveOrUpdateConfig）。

// 获取节假日配置列表
// 后端实现：GET /api/holiday/range（需 startDate / endDate）
export function getHolidayConfigList(params) {
  return request({
    url: '/holiday/range',
    method: 'get',
    params
  })
}

// 获取节假日配置详情（按日期查单条）
// 后端实现：GET /api/holiday/date?date=yyyy-MM-dd
export function getHolidayConfigDetail(id) {
  return request({
    url: '/holiday/date',
    method: 'get',
    params: { date: id }
  })
}

// 创建节假日配置
// 后端实现：POST /api/holiday
export function createHolidayConfig(data) {
  return request({
    url: '/holiday',
    method: 'post',
    data
  })
}

// 更新节假日配置
// 后端**没有** PUT —— 更新走 POST（内部 saveOrUpdateConfig），与创建同一个端点
export function updateHolidayConfig(id, data) {
  return request({
    url: '/holiday',
    method: 'post',
    data: { ...data, configId: id }
  })
}

// 删除节假日配置
// 后端实现：DELETE /api/holiday/{configId}
export function deleteHolidayConfig(id) {
  return request({
    url: `/holiday/${id}`,
    method: 'delete'
  })
}

// 批量导入节假日
// 后端实现：POST /api/holiday/batch
export function importHolidays(data) {
  return request({
    url: '/holiday/batch',
    method: 'post',
    data
  })
}
