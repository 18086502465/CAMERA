/**
 * 产线统计日报 —— 浏览器回归（2026-11-17 交互重构后重写）
 *
 * 页面结构已从「区间表格 + 详情」改为三段式：
 *   ① 产线胶囊 Tab（全厂 / 各 REAL 产线）
 *   ② 当月月历（点日期切换，格底色按当月缺陷量分位）
 *   ③ 当日维度（4 KPI + 缺陷类型 TOP5 / 相机维度 / 时段分布 / 当日异常）
 *
 * 验证点：
 *   ① 顶部导航含「统计日报」，深链 /daily-report 可达（未被 401 挡住）
 *   ② 产线胶囊 ≥2 个（全厂 + 至少一条产线），切换后仍能渲染
 *   ③ 月历格子数 = 当月天数（28~31），且至少有 1 个「有数据」格
 *   ④ 点击有数据的格 → 当日维度出现 4 张 KPI + ≥4 张维度卡 + 口径页脚
 *   ⑤ 换产线后数据跟着变（KPI 数值与全厂不同）—— 证明 lineId 真的透传到后端
 *   ⑥ 无 JS 运行时错误 / 无 chunk 404
 *
 * 用法：node verify_daily_report_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = resolveChrome()

const results = []
function check(name, ok, detail) {
  results.push({ name, ok })
  console.log(`${ok ? '  ✓' : '  ✗'} ${name}${detail ? ' → ' + detail : ''}`)
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitText(page, text, timeoutMs = 15000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const has = await page.evaluate((t) => document.body.innerText.includes(t), text)
    if (has) return true
    await sleep(400)
  }
  return false
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
  page.on('response', (r) => {
    if (r.status() === 404 && r.url().includes('/assets/')) notFound.push(r.url())
  })

  try {
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
    const lg = await login(page, 'admin', PWD)
    check('⓪ 登录成功', lg.ok)

    await page.goto(`${BASE}/daily-report`, { waitUntil: 'networkidle2', timeout: 60000 })
    const hasTab = await page.evaluate(() =>
      [...document.querySelectorAll('a')].some((a) => (a.textContent || '').includes('统计日报')))
    check('① 顶部导航含「统计日报」（深链未被 401 拦截）', hasTab)

    // ② 产线胶囊
    await waitText(page, '产线统计日报', 20000)
    // ★ 2026-11-19：等 calendar 接口真正返回再断言 —— 产线胶囊与月历都来自它，
    //   不等就会读到初始值（胶囊只有「全厂」、月历 0 格）→ 随机假红。
    for (let i = 0; i < 40; i++) {
      const ready = await page.evaluate(() =>
        document.querySelectorAll('.scope-tab').length > 1
        && document.querySelectorAll('.day-cell').length > 0)
      if (ready) break
      await sleep(500)
    }
    let tabs = await page.evaluate(() =>
      [...document.querySelectorAll('.scope-tab')].map((b) => (b.textContent || '').trim()))
    check('② 产线胶囊 ≥2 个（全厂 + 产线）', tabs.length >= 2, tabs.join('/') || '无')

    // ③ 月历：默认当月；若当月无数据（跨月跑脚本）就往前翻，最多翻 3 个月
    let dayCells = 0
    let dataCells = 0
    for (let i = 0; i < 4; i++) {
      dayCells = await page.evaluate(() => document.querySelectorAll('.day-cell:not(.blank)').length)
      dataCells = await page.evaluate(() => document.querySelectorAll('.day-cell.hasdata').length)
      if (dataCells > 0) break
      const flipped = await page.evaluate(() => {
        const b = document.querySelector('.month-nav')
        if (b) { b.click(); return true }
        return false
      })
      if (!flipped) break
      await sleep(2500)
    }
    check('③ 月历格子数 = 当月天数（28~31）', dayCells >= 28 && dayCells <= 31, `${dayCells} 格`)
    check('③b 月历至少有 1 个「有数据」格', dataCells >= 1, `${dataCells} 格`)

    // 月历格底色分档（有数据格应挂 lv1~lv4 之一）
    const graded = await page.evaluate(() =>
      document.querySelectorAll('.day-cell.lv1,.day-cell.lv2,.day-cell.lv3,.day-cell.lv4').length)
    check('③c 有数据格带热力分档（lv1~lv4）', graded >= 1, `${graded} 格`)

    // ④ 点一格 → 当日维度
    // ★ 2026-11-19：点**最后一个**有数据的格（最近的一天），而不是第一个。
    //   原因：最早的几天可能**只有一条产线在上传**（实测 11-03 全厂检测数 854
    //   与 ZK23 完全相同）→ 切产线后所有 KPI 都不变 → 断言必然误判。
    //   最近的日期通常多产线同时上报，才能真正验证「lineId 透传」。
    await page.evaluate(() => {
      const cells = [...document.querySelectorAll('.day-cell.hasdata')]
      const c = cells.length ? cells[cells.length - 1] : null
      if (c) c.click()
    })
    await sleep(3000)
    const kpiCount = await page.evaluate(() => document.querySelectorAll('.kpi-card').length)
    const miniCount = await page.evaluate(() => document.querySelectorAll('.mini-card').length)
    check('④ 当日 KPI 卡 = 4', kpiCount === 4, `${kpiCount} 张`)
    check('④b 四宫格维度卡 = 4', miniCount === 4, `${miniCount} 张`)
    check('④c 口径页脚存在', await waitText(page, '统计口径', 10000))

    // 对比用 KPI：取「在线相机」——前三个是计数差分，历史日恒为「待积累」，
    // 用它做对比永远相同，会把「lineId 没透传」和「计数没积累」混为一谈。
    const readOnlineKpi = () => page.evaluate(() => {
      const cards = [...document.querySelectorAll('.kpi-card')]
      const t = cards.find((c) => (c.querySelector('.kpi-label')?.textContent || '').includes('在线相机'))
      return (t?.querySelector('.kpi-value')?.textContent || '').trim()
    })
    // ★ 2026-11-19：新增「多指标快照」—— 断言不能只看「在线相机」一个指标。
    //   实测某些日期（如 11-03）**当天只有 ZK23 在上传** → 全厂与 ZK23 的
    //   在线相机数、检测图片数、缺陷数**全部相同** → 只比一个指标必然误判成
    //   「lineId 没透传」。改为「任一指标不同即算透传成功」。
    const readKpiSnapshot = () => page.evaluate(() => {
      const out = {}
      document.querySelectorAll('.kpi-card').forEach((c) => {
        const label = (c.querySelector('.kpi-label')?.textContent || '').trim()
        const value = (c.querySelector('.kpi-value')?.textContent || '').trim()
        if (label) out[label] = value
      })
      return out
    })
    const plantKpi = await readOnlineKpi()
    // ★ 必须在**切产线之前**取全厂快照 —— 取晚了读到的是已切过去的产线值，
    //   与切换后的值相同 → 永远判定为「没变化」。
    const plantSnap = await readKpiSnapshot()

    // ⑤ 切到第一条真实产线（跳过「全厂」）
    const switched = await page.evaluate(() => {
      const tabs = [...document.querySelectorAll('.scope-tab')]
      const target = tabs.find((t) => !(t.textContent || '').includes('全厂'))
      if (!target) return null
      const name = (target.textContent || '').trim()
      target.click()
      return name
    })
    // ★ 2026-11-19：切产线后**轮询等待**接口刷新完成，而不是固定 sleep 4000 ——
    //   固定等待会在接口慢时读到**切换前的旧值** → 误判成「lineId 没透传」
    //   （实测踩过：全厂=2/18 与 ZK23=2/18 读的是同一份旧数据）。
    //   现场实测切换后应变为 全厂=16/18、ZK23=0/2（ZK23 两台相机 11-16 已停产）。
    let lineSnap = plantSnap
    let changed = false
    for (let i = 0; i < 20; i++) {
      await sleep(600)
      lineSnap = await readKpiSnapshot()
      changed = Object.keys(plantSnap).some((k) => plantSnap[k] !== lineSnap[k])
      if (changed) break
    }
    const lineKpi = await readOnlineKpi()
    const activeName = await page.evaluate(() =>
      (document.querySelector('.scope-tab.active')?.textContent || '').trim())
    check('⑤ 切换产线生效（active 状态切换）', !!switched && activeName === switched, `${activeName}`)
    // ★ 用「多指标任一变化」判定透传 —— 单指标会被「该产线当天恰好等于全厂」误伤
    const diffKeys = Object.keys(plantSnap).filter((k) => plantSnap[k] !== lineSnap[k])
    check('⑤b 切产线后 KPI 发生变化（lineId 真透传）',
      !!switched && changed,
      changed ? `变化字段=${diffKeys.join('/')}（全厂在线=${plantKpi} / ${activeName}=${lineKpi}）`
              : `全厂与 ${activeName} 的所有 KPI 完全相同 —— 该日期该产线本就等于全厂（数据实况，非透传问题）`)

    // ⑥ 错误
    check('⑥ 无 JS 运行时错误', jsErrors.length === 0, jsErrors[0] || '')
    check('⑥b 无 chunk 404', notFound.length === 0, notFound[0] || '')
  } catch (e) {
    check('执行过程无异常', false, e.message)
  } finally {
    await browser.close()
  }

  const failed = results.filter((r) => !r.ok)
  console.log(`\n${failed.length === 0 ? '✓' : '✗'} ${results.length - failed.length}/${results.length} 项通过`)
  process.exit(failed.length === 0 ? 0 : 1)
})().catch((e) => {
  console.error('脚本异常:', e.message)
  process.exit(2)
})
