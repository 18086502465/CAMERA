import request from '../utils/request'
import { sharedInflight } from '../utils/inflight'

/**
 * 级联树全量（工厂 → 产线 → 工位）。
 *
 * 2026-11-25 O-3：Layout.vue 与日报/图像记录页会在首屏同时调用本接口
 * （浏览器实测日报页 2 次）。这里与 analytics.js 共用全局 in-flight 表，
 * 同一时刻只发一个请求；不做 TTL 缓存，保证配置页 CRUD 后能看到最新级联。
 */
export function getCascadeAll() {
  return sharedInflight('/cascade/all', () => request({ url: '/cascade/all', method: 'get' }))
}

export function getFactoryList(params) { return request({ url: '/factories', method: 'get', params }) }
export function getLineList(params) { return request({ url: '/lines', method: 'get', params }) }
export function getWorkstationList(params) { return request({ url: '/stats/workstations', method: 'get', params }) }
