/**
 * 进行中请求的跨模块去重（2026-11-25 现场诊断 O-3）。
 *
 * 背景：日报页首屏 `/api/cascade/all` 被 Layout.vue 与 DailyReportView.vue
 * 各调一次（浏览器实测 2 次），两个模块各自发请求、各自解析同一份级联树。
 *
 * 设计：
 *  - 只合并「当前正在飞行」的同键请求，返回同一个 Promise；
 *  - 请求结束（成功或失败）立即清理，**不做 TTL 缓存** —— 配置页 CRUD 后
 *    必须立刻看到新的工厂/产线/工位，缓存 5 分钟会造成配置页显示旧数据；
 *  - 单例 sharedInflight 被 analytics.js / cascade.js 共用，因此不同 API
 *    模块对同一 URL 的并发调用也能合并；
 *  - loader 在微任务里执行，保证 set(key, pending) 先于请求发出，
 *    同步抛错也会被收敛成 rejected Promise 并正常清理。
 */
export function createInflightDedup() {
  const inflight = new Map()

  return function run(key, loader) {
    const existing = inflight.get(key)
    if (existing) return existing
    const pending = Promise.resolve()
      .then(loader)
      .finally(() => inflight.delete(key))
    inflight.set(key, pending)
    return pending
  }
}

/** 全局单例：所有 API 模块共享同一个 in-flight 表 */
export const sharedInflight = createInflightDedup()
