/**
 * 相机身份 code-first 匹配 —— 单元测试（2026-11-05）
 *
 * 回归现场 bug：相机状态页「近批次图片 / 高危缺陷」在"不筛选产线"时
 * 比"筛选产线"多一整条产线（3133 → 6266）。
 *
 * 根因：现场 camera_name **跨产线重名**（ZK54 的 CV1_Cam1/CV2_Cam1 与
 * ZK23 的同名相机），前端把 cameraName 也当匹配键 → ZK54 的相机拿到了
 * ZK23 同名相机的统计 → 累加时多算。
 *
 * 本测试锁死三条规则：
 *   ① 匹配键只用 code 类字段；
 *   ② 名称兜底必须**两侧都唯一**；
 *   ③ 重名时宁可匹配不到（显示 0），也不能错配。
 *
 * 运行：node frontend/tests/camera-identity.test.mjs
 *   （纯 ESM 工具函数，无需测试框架；退出码非 0 表示失败）
 */
import {
  buildCameraSummaryIndex,
  countCameraNames,
  findCameraSummary,
  cameraCodeKeys,
} from '../src/utils/camera-identity.js'

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

function eq(name, actual, expected) {
  assert(name, actual === expected, `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
}

// ============================================================
console.log('=== 相机身份 code-first 匹配单元测试 ===\n')

// ---------- 现场真实数据 ----------
const SUMMARIES = [
  { cameraId: 'DA4733319', cameraCode: 'DA4733319', cameraName: 'CV2_Cam1', lineId: 5, imageCount: 1416, highCount: 1416 },
  { cameraId: 'DA8119489', cameraCode: 'DA8119489', cameraName: 'CV1_Cam1', lineId: 5, imageCount: 1717, highCount: 1717 },
]
// 16 台相机列表：ZK54 的 CV1_Cam1 / CV2_Cam1 与 ZK23 同名
const CAMERAS = [
  { cameraCode: 'DA8119487', cameraName: 'CV1_Cam1', lineId: 3 },
  { cameraCode: 'DA9200622', cameraName: 'CV2_Cam1', lineId: 3 },
  { cameraCode: 'DA8119489', cameraName: 'CV1_Cam1', lineId: 5 },
  { cameraCode: 'DA4733319', cameraName: 'CV2_Cam1', lineId: 5 },
  { cameraCode: 'DA9200611', cameraName: 'CV7_Cam1', lineId: 3 },
]

// ---------- ① 匹配键只用 code ----------
console.log('--- 规则①：匹配键只用 code 类字段 ---')
const keys = cameraCodeKeys({ cameraCode: 'DA8119489', cameraName: 'CV1_Cam1', cameraId: 123 })
assert('不含 cameraName', !keys.includes('CV1_Cam1'), keys.join(','))
assert('含 cameraCode', keys.includes('DA8119489'), keys.join(','))
assert('含 cameraId', keys.includes('123'), keys.join(','))
eq('代码顺序：cameraCode 在前', keys[0], 'DA8119489')

// ---------- ② 重名相机不得错配 ----------
console.log('\n--- 规则②③：重名相机不得拿到别人的统计（现场 bug 回归）---')
const index = buildCameraSummaryIndex(SUMMARIES)
const nameCounts = countCameraNames(CAMERAS)

const zk54Cv1 = findCameraSummary(index, CAMERAS[0], nameCounts)   // DA8119487 / CV1_Cam1
assert('ZK54 的 CV1_Cam1 匹配不到（宁可 0 不错配）', !zk54Cv1, zk54Cv1 ? `错误匹配到 ${zk54Cv1.cameraCode}` : 'null')

const zk54Cv2 = findCameraSummary(index, CAMERAS[1], nameCounts)   // DA9200622 / CV2_Cam1
assert('ZK54 的 CV2_Cam1 匹配不到', !zk54Cv2, zk54Cv2 ? `错误匹配到 ${zk54Cv2.cameraCode}` : 'null')

const zk23Cv1 = findCameraSummary(index, CAMERAS[2], nameCounts)   // DA8119489 / CV1_Cam1
eq('ZK23 的 CV1_Cam1 匹配到自己的统计', zk23Cv1?.cameraCode, 'DA8119489')
eq('  其 imageCount 正确', zk23Cv1?.imageCount, 1717)

const zk23Cv2 = findCameraSummary(index, CAMERAS[3], nameCounts)   // DA4733319 / CV2_Cam1
eq('ZK23 的 CV2_Cam1 匹配到自己的统计', zk23Cv2?.cameraCode, 'DA4733319')
eq('  其 imageCount 正确', zk23Cv2?.imageCount, 1416)

// ---------- ③ 累加口径：筛选产线之和 == 不筛选 ----------
console.log('\n--- 现场核心断言：各产线累加之和 == 不筛选时的总数 ---')
const sumByLine = (lineId) => CAMERAS
  .filter((c) => c.lineId === lineId)
  .reduce((sum, c) => sum + (findCameraSummary(index, c, nameCounts)?.imageCount || 0), 0)
const zk54 = sumByLine(3)
const zk23 = sumByLine(5)
const all = CAMERAS.reduce((sum, c) => sum + (findCameraSummary(index, c, nameCounts)?.imageCount || 0), 0)
eq('ZK54 累加', zk54, 0)
eq('ZK23 累加', zk23, 3133)
eq('不筛选累加', all, 3133)
assert('★ ZK54 + ZK23 === 不筛选（修复前 0+3133 ≠ 6266）', zk54 + zk23 === all, `${zk54} + ${zk23} = ${zk54 + zk23}，不筛选 = ${all}`)

// ---------- ③-b 唯一性基准必须取「相机列表」，不能取 summaries ----------
// ★ 实施中踩到的坑：若把 countCameraNames 传成 **summaries** 的名称计数，
//   'CV1_Cam1' 在 summaries 里只出现 1 次（只有真实相机）→ 被误判为"唯一"
//   → 放行名称兜底 → ZK54 的同名相机又拿到 ZK23 的统计 → 问题依旧。
//   正确基准是**相机列表**：'CV1_Cam1' 出现 2 次 → 判定重名 → 不兜底。
console.log('\n--- 规则③：唯一性基准必须是相机列表（实施中踩坑回归）---')
const summarySideCounts = countCameraNames(SUMMARIES)          // ❌ 错误基准
const wrongFallback = findCameraSummary(index, CAMERAS[0], summarySideCounts)
assert('用 summaries 侧计数会误放行兜底（这正是 bug）', !!wrongFallback,
  wrongFallback ? `错误匹配到 ${wrongFallback.cameraCode}` : 'null')
assert('★ 用相机列表侧计数则正确拦截', !findCameraSummary(index, CAMERAS[0], nameCounts))

// ---------- ④ 名称唯一时才允许兜底 ----------
console.log('\n--- 规则②：名称唯一时仍允许兜底（不牺牲兼容性）---')
const uniqueSummaries = [{ cameraId: '', cameraCode: '', cameraName: 'CV99_Cam1', imageCount: 42 }]
const uniqueIndex = buildCameraSummaryIndex(uniqueSummaries)
const uniqueNames = countCameraNames([{ cameraCode: 'X1', cameraName: 'CV99_Cam1' }])
const fallback = findCameraSummary(uniqueIndex, { cameraCode: 'X1', cameraName: 'CV99_Cam1' }, uniqueNames)
eq('code 缺失但名称唯一 → 兜底命中', fallback?.imageCount, 42)

// 重名时即使 summaries 侧唯一，cameras 侧重名也不兜底
const dupNames = countCameraNames([
  { cameraCode: 'X1', cameraName: 'CV99_Cam1' },
  { cameraCode: 'X2', cameraName: 'CV99_Cam1' },
])
const noFallback = findCameraSummary(uniqueIndex, { cameraCode: 'X1', cameraName: 'CV99_Cam1' }, dupNames)
assert('cameras 侧重名 → 不兜底', !noFallback, noFallback ? '错误兜底' : 'null')

// ---------- ⑤ 无关项不回归 ----------
console.log('\n--- 其它行为不变 ---')
assert('无 code 无 name → 匹配不到', !findCameraSummary(index, {}, nameCounts))
// byUniqueName 只保证「summaries 侧唯一」（此处 CV1_Cam1 在 summaries 中只出现一次）；
// 「cameras 侧唯一」由 findCameraSummary 的 cameraNameCounts 强制 —— 两者结合才是两侧都唯一。
assert('byUniqueName 含 summaries 侧唯一的名称键', index.byUniqueName.has('CV1_Cam1'))
assert('★ 但 cameras 侧重名 → 该键不会被采用', !findCameraSummary(index, CAMERAS[0], nameCounts))
assert('byCode 含 camera_code 键', index.byCode.has('DA8119489'))
assert('byCode 含 cameraId 键', index.byCode.has('DA4733319'))

console.log(`\n${failed === 0 ? '✓' : '✗'} ${passed}/${passed + failed} 项通过`)
process.exit(failed === 0 ? 0 : 1)
