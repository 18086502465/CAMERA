/**
 * 多产线筛选专项回归（2026-11-17）
 *
 * 验证点（对应现场反馈「多选几次产线后数据为空」「责任相机定位多选产线不全」）：
 *   ① 顶部级联勾 ZK09+ZK06 → KPI 缺陷图片数 = 两线并集（后端 line_id IN 汇总），不为空
 *   ② 全部 line-summary 请求（含「产线缺陷排行」的 dim=line 独立请求）都携带
 *      lineId+lineIds —— 修复前带拆分维的图表无视产线筛选，选了 ZK09 仍画全部产线
 *   ③ 排行接口响应只含勾选产线（ZK09 / ZK06）
 *   ④ 清空选择 → 恢复全产线（数值回到全集口径）
 *   ⑤ 快速连续 清空→选ZK06→清空→选ZK09（竞态场景）→ 最终=ZK09（最后选择生效）
 *   ⑥ 无 JS 错误
 *
 * 用法：node verify_line_multi_browser.cjs
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function readKpi(page) {
  return page.evaluate(() => {
    const cards = {}
    document.querySelectorAll('.kpi-card').forEach((c) => {
      const label = (c.querySelector('span')?.textContent || '').trim()
      const value = (c.querySelector('strong')?.textContent || '').trim()
      if (label) cards[label] = value
    })
    return cards
  })
}

/** 产线多选：在「可见」下拉面板里精确匹配选项（页面还有工厂/工位/看板下拉，避免命中隐藏面板的同名项） */
async function pickLines(page, names) {
  await page.click('.sel-line .el-select__wrapper')
  await sleep(800)
  await page.evaluate((names) => {
    const dropdowns = Array.from(document.querySelectorAll('.el-select-dropdown'))
    const visible = dropdowns.filter((d) => d.offsetParent !== null || getComputedStyle(d).display !== 'none')
    const scope = visible.length ? visible : dropdowns
    for (const name of names) {
      const it = scope.map((d) => Array.from(d.querySelectorAll('.el-select-dropdown__item'))).flat().find((i) => i.textContent.trim() === name)
      if (it) it.click()
    }
  }, names)
  await sleep(400)
  await page.keyboard.press('Escape')
  await sleep(600)
}

/** 清空产线多选：逐个点掉已选 tag（连点会命中 Vue 重渲染前的旧节点） */
async function clearLines(page) {
  for (let round = 0; round < 6; round++) {
    const remain = await page.evaluate(() => document.querySelectorAll('.sel-line .el-tag__close').length)
    if (!remain) break
    await page.evaluate(() => {
      const c = document.querySelector('.sel-line .el-tag__close')
      if (c) c.click()
    })
    await sleep(500)
  }
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--window-size=1680,1050']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1680, height: 1050 })
  const jsErrors = []
  page.on('pageerror', (e) => jsErrors.push(String(e)))

  // 抓取页面真实发出的 line-summary 请求与响应
  const reqs = []         // {dimLine, url}
  const respByDim = []    // dim=line 请求的响应 defectRanking 名称列表
  page.on('request', (r) => {
    const u = r.url()
    if (u.includes('/api/v1/analytics/line-summary')) reqs.push({ url: u, dimLine: /[?&]dim=line/.test(u) })
  })
  page.on('response', async (r) => {
    if (r.url().includes('/api/v1/analytics/line-summary') && /[?&]dim=line/.test(r.url())) {
      try {
        const body = (await r.json()).data?.data || {}
        respByDim.push((body.defectRanking || []).map((x) => String(x.defectName)))
      } catch { /* 忽略非 JSON */ }
    }
  })

  try {
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
    await login(page, USER, PWD)
    await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
    await sleep(3000)
    reqs.length = 0
    respByDim.length = 0

    // ---- ① 多选 ZK09 + ZK06（逐次勾选：同一 evaluate 里连点两项会命中 Vue 重渲染前的旧节点） ----
    await pickLines(page, ['ZK09'])
    await sleep(2500)
    await pickLines(page, ['ZK06'])
    // 等 KPI 稳定（两次读数一致视为加载完成）
    let multi = await readKpi(page)
    for (let i = 0; i < 8; i++) {
      await sleep(1500)
      const next = await readKpi(page)
      if (next['缺陷图片数'] === multi['缺陷图片数']) break
      multi = next
    }
    const multiTotal = Number(String(multi['缺陷图片数']).replace(/,/g, ''))
    check('① 多选 ZK09+ZK06 出数（不为空）', multiTotal > 300, `缺陷图片数=${multi['缺陷图片数']}`)

    // ---- ② dim=line 的独立请求也携带 lineIds ----
    const dimReqs = reqs.filter((r) => r.dimLine)
    const withBoth = dimReqs.filter((r) => r.url.includes('lineIds=') && r.url.includes('lineId='))
    check('② 产线缺陷排行（dim=line）请求携带 lineId+lineIds', dimReqs.length > 0 && withBoth.length > 0,
      `dim=line 请求 ${dimReqs.length} 个，带 lineIds ${withBoth.length} 个`)

    // ---- ③ 排行响应只含勾选产线（页面上下文内用同参请求复核，比拦截响应体更可靠） ----
    const rankProbe = await page.evaluate(async () => {
      const now = new Date()
      const pad = (n) => String(n).padStart(2, '0')
      const end = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} 23:59:59`
      const q = new URLSearchParams({ startTime: `${end.slice(0, 10)} 00:00:00`, endTime: end, lineId: '11', lineIds: '11,10', dim: 'line' })
      const res = await fetch('/api/v1/analytics/line-summary?' + q.toString(), { headers: { Authorization: 'Bearer ' + localStorage.getItem('camera_token') } })
      const body = await res.json()
      const rows = body.data?.defectRanking
      return { status: res.status, code: body.code, rows: (rows || []).map((x) => String(x.defectName)), rawLen: JSON.stringify(rows) }
    })
    const rankNames = rankProbe.rows
    const okRank = rankProbe.status === 200 && rankNames.length > 0 && rankNames.every((n) => n === 'ZK09' || n === 'ZK06')
    check('③ 排行接口（lineIds=11,10）只含勾选产线', okRank, `status=${rankProbe.status} code=${rankProbe.code} rows=${JSON.stringify(rankNames)} raw=${rankProbe.rawLen}`)

    // ---- ④ 清空恢复全集 ----
    await clearLines(page)
    await sleep(3000)
    const all = await readKpi(page)
    const allTotal = Number(String(all['缺陷图片数']).replace(/,/g, ''))
    check('④ 清空后恢复全产线（≥ 并集）', allTotal >= multiTotal && allTotal > 0, `全集=${all['缺陷图片数']} / 并集=${multiTotal}`)

    // ---- ⑤ 快速连续切换（竞态守卫）：最后选择必须生效 ----
    // ★ 2026-11-19：原断言**硬编码**「ZK09 当日 ~311 张」（写脚本那天的凌晨产量），
    //   现场数据随时间增长（当日实测已到 1741 张）→ 断言必然假红。
    //   改为测**这个用例的本意**（竞态守卫）：
    //     ① 先稳定地单选 ZK09 → 记基准 finZ09
    //     ② 快速切 2 轮（ZK06 → ZK09）→ 记 finFast
    //     ③ 断言 finFast === finZ09 —— 若竞态没守住会停在 ZK06，值就不同。
    //   不依赖任何硬编码数字 → 不会随数据增长而失效。
    await clearLines(page)
    await pickLines(page, ['ZK09'])
    await sleep(3500)
    const finZ09 = await readKpi(page)
    const zk09Total = Number(String(finZ09['缺陷图片数']).replace(/,/g, ''))

    reqs.length = 0
    await clearLines(page)
    await pickLines(page, ['ZK06'])
    await sleep(300)
    await clearLines(page)
    await pickLines(page, ['ZK09'])
    await sleep(3500)
    const fin = await readKpi(page)
    const finTotal = Number(String(fin['缺陷图片数']).replace(/,/g, ''))
    const okFin = zk09Total > 0 && finTotal === zk09Total
    check('⑤ 快速切 2 轮，最终=最后选择的 ZK09（竞态守卫）', okFin,
      `稳定单选 ZK09=${zk09Total} / 快速切换后=${finTotal}`)

    check('⑥ 无 JS 运行时错误', jsErrors.length === 0, jsErrors.slice(0, 3).join(' | ') || '无')

    const pass = results.filter((r) => r.ok).length
    console.log(`\n${pass === results.length ? '✓' : '✗'} ${pass}/${results.length} 项通过`)
    process.exit(pass === results.length ? 0 : 1)
  } finally {
    await browser.close()
  }
})().catch((e) => { console.error('脚本失败:', e); process.exit(1) })
