import request from '../utils/request'

/**
 * 服务器资源指标（CPU / 内存 / 磁盘空间 / 磁盘 I/O 速率与 IOPS）。
 *
 * <p>后端是「后台定时采集 + 内存环形缓存」，接口只读缓存，
 * 因此响应里直接带着最近 N 个历史采样点，前端无需自己累积。
 *
 * <p>⚠️ 响应拦截器<b>不解包</b>，调用方要读 <code>res.data.data</code>。
 *
 * <p>⚠️ 首次采样时 <code>diskIo.readBytesPerSec</code> 等速率字段为
 * <b>null</b>（不是 0）—— 因为磁盘 I/O 计数是累计值，首次没有做差基准。
 * 返回 0 会被误读成「磁盘完全空闲」，前端应显示 <code>--</code>。
 *
 * @param {Object} params 可选，{ history: Number } 指定返回的历史点数
 */
export const getServerMetrics = (params = {}) =>
  request({ url: '/v1/system/server/metrics', method: 'get', params })
