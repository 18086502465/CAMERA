/**
 * 跨模块 in-flight 请求去重单元测试（2026-11-25 O-3）。
 *
 * 回归现场现象：日报页首屏 `/api/cascade/all` 被 Layout 与 DailyReportView
 * 各发一次；统计页 line-summary 同参数并发多次 → 后端缓存被穿透。
 *
 * 锁死三条行为：
 *   ① 同键并发只调用一次 loader，两个调用方拿到同一个 Promise 和同一份结果；
 *   ② 请求结束后清理，后续调用重新发请求（绝不做 TTL 缓存，配置页要实时）；
 *   ③ 失败时同样清理，下一次可以重试；不同键互不干扰。
 *
 * 运行：node frontend/tests/inflight-dedup.test.mjs
 */
import { createInflightDedup } from '../src/utils/inflight.js'

let failed = 0
let passed = 0

function assert(name, ok, detail) {
  if (ok) {
    passed++
    console.log(`  ✓ ${name}${detail ? ' → ' + detail : ''}`)
  } else {
    failed++
    console.log(`  ✗ ${name}${detail ? ' → ' + detail : ''}`)
  }
}

console.log('=== 跨模块 in-flight 去重单元测试 ===\n')

// ① 同键并发合并
{
  const dedup = createInflightDedup()
  let calls = 0
  let resolveLoader
  const loader = () => {
    calls++
    return new Promise(resolve => { resolveLoader = resolve })
  }
  const a = dedup('/cascade/all', loader)
  const b = dedup('/cascade/all', loader)
  // loader 在微任务里执行（保证 set 先于请求发出），先让微任务跑完再断言
  await Promise.resolve()
  assert('同键并发只发一次请求', calls === 1, `loader 调用 ${calls} 次`)
  assert('同键返回同一个 Promise', a === b)
  resolveLoader({ code: 200 })
  const [ra, rb] = await Promise.all([a, b])
  assert('两个调用方拿到同一份结果', ra === rb && ra.code === 200)
}

// ② 完成后不缓存
{
  const dedup = createInflightDedup()
  let calls = 0
  const loader = () => Promise.resolve(++calls)
  const first = await dedup('/cascade/all', loader)
  const second = await dedup('/cascade/all', loader)
  assert('请求结束后再次调用会重新发起', first === 1 && second === 2, `结果 ${first}/${second}`)
}

// ③ 失败清理 + 不同键隔离
{
  const dedup = createInflightDedup()
  let failCalls = 0
  let otherCalls = 0
  const failing = dedup('/fail', () => { failCalls++; return Promise.reject(new Error('boom')) })
  const other = dedup('/other', () => { otherCalls++; return Promise.resolve('ok') })
  await Promise.resolve()
  assert('不同键互不合并', otherCalls === 1)
  let rejected = false
  try { await failing } catch { rejected = true }
  assert('失败被正常传递给调用方', rejected)
  const retry = await dedup('/fail', () => { failCalls++; return Promise.resolve('recovered') })
  assert('失败后清理，可重新请求', retry === 'recovered' && failCalls === 2, `loader 调用 ${failCalls} 次`)
  assert('不同键结果不受失败影响', (await other) === 'ok')
}

console.log(`\n结果：${passed} 通过 / ${failed} 失败`)
process.exit(failed === 0 ? 0 : 1)
