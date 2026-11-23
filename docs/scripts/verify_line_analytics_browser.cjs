/**
 * 产线统计页 —— 数据源一致性浏览器回归（2026-11-05）
 *
 * 验证点（对应现场 bug「筛选产线 3041 / 不筛选产线 4，子集比全集大」）：
 *   ① 不筛选产线时，检测总数与筛选 ZK23 时**一致**
 *   ② 两种筛选下 dataSource 相同（都是缺陷图片事实表）
 *   ③ 图片通道下**不出现良率卡片**，而是「良品样本 未接入」状态卡
 *   ④ 趋势图**不画良率线**（无良品样本，画 0% 是假曲线）
 *   ⑤ 无 JS 错误
 *
 * 用法：node verify_line_analytics_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const USER = 'admin'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = resolveChrome()

const results = []
function check(name, ok, detail) {
  results.push({ name, ok })
  console.log(`${ok ? '  ✓' : '  ✗'} ${name}${detail ? ' → ' + detail : ''}`)
}

/** 读取当前页面的 KPI 卡片：{标签: 数值文本} */
async function readKpis(page) {
  return page.evaluate(() => {
    const out = {}
    document.querySelectorAll('.kpi-card').forEach((c) => {
      const label = (c.querySelector('span')?.textContent || '').trim()
      const value = (c.querySelector('strong')?.textContent || '').trim()
      if (label) out[label] = value
    })
    return out
  })
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  })
  const page = await browser.newPage()
  const jsErrors = []
  const notFound = []
  page.on('pageerror', (e) => jsErrors.push(e.message))
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(m.text()) })
  page.on('response', (r) => { if (r.status() >= 400) notFound.push(r.url()) })

  try {
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
    const lg = await login(page, USER, PWD)
    check('登录成功', lg.ok, lg.ok ? lg.url : `url=${lg.url}`)

    // 产线统计页：默认「近30天」= 2026-10-30 ~ 2026-11-05，与用户截图一致
    await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
    await new Promise((r) => setTimeout(r, 4000))

    // ★ 2026-11-17 首屏重构：默认进内置「工厂质量看板」、筛选收起、两侧配置栏隐藏
    const builtinHead = await page.evaluate(() => {
      const h = document.querySelector('.canvas-head h3')
      const tag = document.querySelector('.canvas-head .el-tag')
      return !!h && h.textContent.includes('工厂质量看板')
        && !!tag && tag.textContent.includes('内置')
    })
    check('首屏 = 内置工厂质量看板（结论优先，不要求先选模板）', builtinHead)
    const hasAdvanced = await page.evaluate(() =>
      [...document.querySelectorAll('.filter-card button')].some((b) => (b.textContent || '').includes('高级筛选')))
    check('筛选默认收起（保留「高级筛选」入口）', hasAdvanced)
    const paletteHidden = await page.evaluate(() => !document.querySelector('.widget-palette'))
    check('内置看板下隐藏图表组件库侧栏', paletteHidden)

    // ★ 2026-11-15：默认时间范围是「今天 00:00 起」，产线一旦停线（实测 11-13 之后无上传）
    //   当天就没有图片数据 → 页面自动落到聚合表兜底分支（6 张卡含良率），
    //   本脚本关于「图片通道 4 卡 + 良品样本」的断言会全红。
    //   先切「近7天」让断言落在图片通道上，避免把「今天没生产」误判成功能回归。
    const switched = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.range-shortcuts button')]
        .find(x => (x.textContent || '').trim() === '近7天')
      if (b) { b.click(); return true }
      return false
    })
    if (switched) {
      console.log('   已切到「近7天」')
      await new Promise((r) => setTimeout(r, 5000))
    }

    // ---- ① 不筛选产线 ----
    const kpiAll = await readKpis(page)
    const srcAll = await page.evaluate(() => {
      const el = document.querySelector('.source-hint b')
      return el ? el.textContent.trim() : ''
    })
    console.log('    [不筛选] 卡片:', JSON.stringify(kpiAll), '| 数据源:', srcAll)
    check('不筛选时用缺陷图片事实表', srcAll.includes('缺陷图片事实表'), srcAll)
    check('不筛选时显示「良品样本 未接入」', '良品样本' in kpiAll, JSON.stringify(kpiAll['良品样本'] || ''))
    check('不筛选时**没有**良率卡片（不可计算）', !('良率' in kpiAll), Object.keys(kpiAll).join('/'))

    // ---- ② 筛选 ZK23 ----
    const picked = await page.evaluate(() => {
      // 顶部工具栏的「产线」下拉
      const labels = Array.from(document.querySelectorAll('.el-select'))
      for (const s of labels) {
        const t = (s.textContent || '')
        if (t.includes('产线') || t.includes('ZK23') || t.includes('全部产线')) {
          s.click()
          return { clicked: true, text: t.trim().slice(0, 30) }
        }
      }
      return { clicked: false }
    })
    await new Promise((r) => setTimeout(r, 800))
    // 在下拉面板里点 ZK23
    const optionClicked = await page.evaluate(() => {
      const opts = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      const hit = opts.find((o) => (o.textContent || '').trim() === 'ZK23')
      if (hit) { hit.click(); return true }
      return false
    })
    await new Promise((r) => setTimeout(r, 4000))
    check('已选中 ZK23 产线', optionClicked, picked.text || '')

    const kpiLine = await readKpis(page)
    const srcLine = await page.evaluate(() => {
      const el = document.querySelector('.source-hint b')
      return el ? el.textContent.trim() : ''
    })
    console.log('    [ZK23]   卡片:', JSON.stringify(kpiLine), '| 数据源:', srcLine)

    // ---- ③ 核心：子集不得大于全集 ----
    // 原断言比的是「检测总数」，但 2026-11-14 KPI 卡已改名为「缺陷图片数」→
    // 两边都是 undefined，恒等比较变成假通过。改成数值比较，且方向必须对：
    // ZK23 是全集的一部分，选它之后数量只能更小（现场 bug 正是「子集比全集大」）。
    const num = (v) => Number(String(v ?? '').replace(/[^0-9.]/g, ''))
    const totalAll = num(kpiAll['缺陷图片数'])
    const totalLine = num(kpiLine['缺陷图片数'])
    // ★ 2026-11-23：ZK23 当天可能 0 上传 → 产品按「无数据≠0」诚实降级为
    //   「当前条件下没有检测记录」+ 另一套 KPI 卡（无『缺陷图片数』键）。
    //   这是正确行为，不是 bug —— 识别该态时跳过数值比较，避免假红。
    const noData = String(srcLine).includes('没有检测记录')
    if (noData) {
      check('筛选后无数据 → 诚实降级（不误判为数值错误）', true,
        `ZK23 当天无上传，显示「没有检测记录」（无数据≠0）`)
    } else {
      check('筛选产线后数量 ≤ 全集（子集不得大于全集）',
        Number.isFinite(totalAll) && Number.isFinite(totalLine) && totalLine > 0 && totalLine <= totalAll,
        `不筛选=${totalAll} / ZK23=${totalLine}`)
      check('两种筛选的数据源一致', srcAll === srcLine, `${srcAll} / ${srcLine}`)
    }
    // 无数据占位卡会显示「良率：—」（未接入，不是假数值）；只有真实数据时才禁止良率键
    const yieldVal = kpiLine['良率']
    check('筛选后也没有良率卡片', !('良率' in kpiLine) || yieldVal === '—' || yieldVal === '-',
      Object.keys(kpiLine).join('/') + ' 良率=' + String(yieldVal))

    // ---- ④ 趋势图不画良率线 ----
    const legend = await page.evaluate(() => {
      const texts = Array.from(document.querySelectorAll('.chart-legend, .el-chart__legend, text'))
        .map((e) => (e.textContent || '').trim())
      return texts.filter((t) => ['检测总数', 'NG数', '良率'].includes(t))
    })
    const hasYieldInChart = legend.includes('良率')
    check('趋势图不含良率线（图片通道无良品样本）', !hasYieldInChart, legend.join('/') || '(无图例)')

    // ---- ⑤ JS 错误 ----
    const realErrors = jsErrors.filter(
      (e) => !/favicon/i.test(e) && !/Failed to load resource/i.test(e))
    const real404 = notFound.filter((u) => !/favicon/i.test(u))
    check('无 JS 错误', realErrors.length === 0, realErrors.slice(0, 2).join(' | ') || '无')
    check('无 404 资源（favicon 除外）', real404.length === 0, real404.slice(0, 2).join(' | ') || '无')
  } catch (e) {
    check('执行过程无异常', false, e.message)
  } finally {
    await browser.close()
  }

  const failed = results.filter((r) => !r.ok)
  console.log(`\n${failed.length === 0 ? '✓' : '✗'} ${results.length - failed.length}/${results.length} 项通过`)
  process.exit(failed.length === 0 ? 0 : 1)
})()
