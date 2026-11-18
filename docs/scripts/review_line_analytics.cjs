/**
 * 产线统计复核（2026-11-17 第二轮评审，只读不改现场）
 *
 * 复核点：
 *   ① 高级筛选里选「相机」后，切换产线 → 相机筛选是否残留导致整页空
 *   ② 切产线后相机下拉是否仍显示已失效（不在选项内）的相机
 *   ③ 图片事实表数据源下，顶部标签是否仍显示「30分钟聚合」（口径误导）
 *   ④ 「缺陷日历（近 30 天）」在时间范围=今天时实际只有 1 个数据点
 *   ⑤ 多选产线时相机候选是否只按第一条产线过滤（filteredCameras 取 lineIds[0]）
 *   ⑥ 无 JS 错误
 *
 * 用法：node review_line_analytics.cjs
 */
const puppeteer = require('puppeteer-core')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const USER = 'admin'
const PWD = process.env.ADMIN_PWD || 'admin123'

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

async function clearLines(page) {
  const tags = await page.$$('.sel-line .el-tag__close')
  for (const t of tags) { await t.click(); await sleep(500) }
  await sleep(1000)
}

async function pickLines(page, names, keep = false) {
  if (!keep) await clearLines(page)
  await page.click('.sel-line .el-select__wrapper')
  await sleep(800)
  for (const name of names) {
    await page.evaluate((name) => {
      const dropdowns = Array.from(document.querySelectorAll('.el-select-dropdown'))
      const visible = dropdowns.filter((d) => d.offsetParent !== null || getComputedStyle(d).display !== 'none')
      const scope = visible.length ? visible : dropdowns
      const it = scope.map((d) => Array.from(d.querySelectorAll('.el-select-dropdown__item'))).flat().find((i) => i.textContent.trim() === name)
      if (it) it.click()
    }, name)
    await sleep(700)
  }
  await page.keyboard.press('Escape')
  await page.mouse.click(840, 760)   // 点空白关闭 popper，避免残留面板干扰后续下拉
  await sleep(2500)
}

/** 打开「筛选区」某个下拉（0=产品 1=班次 2=相机 3=缺陷类型 4=严重度），返回选项文本 */
async function openFilterSelect(page, index) {
  await page.evaluate((i) => {
    const sel = document.querySelectorAll('.el-select.filter-ctrl')[i]
    sel?.querySelector('.el-select__wrapper')?.click()
  }, index)
  await sleep(900)
  return page.evaluate((i) => {
    const sel = document.querySelectorAll('.el-select.filter-ctrl')[i]
    const id = sel?.querySelector('[aria-controls]')?.getAttribute('aria-controls')
    const popper = id ? document.getElementById(id) : null
    const scope = popper || sel?.closest('.el-select')?.parentElement
    const items = popper
      ? Array.from(popper.querySelectorAll('.el-select-dropdown__item'))
      : Array.from(document.querySelectorAll('.el-select-dropdown__item'))
    return items.map((x) => x.textContent.trim()).filter(Boolean)
  }, index)
}

async function pickFilterOption(page, index, text) {
  await page.evaluate((args) => {
    const [i, t] = args
    const sel = document.querySelectorAll('.el-select.filter-ctrl')[i]
    const id = sel?.querySelector('[aria-controls]')?.getAttribute('aria-controls')
    const popper = id ? document.getElementById(id) : null
    const items = popper ? Array.from(popper.querySelectorAll('.el-select-dropdown__item')) : []
    const it = items.find((x) => x.textContent.trim() === t) || items[0]
    if (it) it.click()
  }, [index, text])
  await page.keyboard.press('Escape')
  await page.mouse.click(840, 760)
  await sleep(2800)
}

async function readFilterText(page, index) {
  return page.evaluate((i) => {
    const sel = document.querySelectorAll('.el-select.filter-ctrl')[i]
    if (!sel) return ''
    return (sel.querySelector('.el-select__selected-item')?.textContent || sel.textContent || '').replace(/\s+/g, ' ').trim()
  }, index)
}


/** 打开产线下拉并只点一项（连点两项会命中重渲染前的旧节点） */
async function pickOne(page, name) {
  await page.click('.sel-line .el-select__wrapper')
  await sleep(900)
  const ok = await page.evaluate((n) => {
    const ds = Array.from(document.querySelectorAll('.el-select-dropdown'))
      .filter((d) => d.offsetParent !== null || getComputedStyle(d).display !== 'none')
    const items = ds.map((d) => Array.from(d.querySelectorAll('.el-select-dropdown__item'))).flat()
    const it = items.find((i) => i.textContent.trim() === n)
    if (it) { it.click(); return true }
    return false
  }, name)
  await sleep(600)
  await page.keyboard.press('Escape')
  await sleep(1200)
  return ok
}

;(async () => {
  const browser = await puppeteer.launch({ executablePath: resolveChrome(), headless: 'new', args: ['--no-sandbox'] })
  const page = await browser.newPage()
  await page.setViewport({ width: 1680, height: 1050 })
  const jsErrors = []
  page.on('pageerror', (e) => jsErrors.push(String(e).slice(0, 160)))

  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
  await login(page, USER, PWD)
  await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
  await sleep(3000)

  // ---- ③ 数据源标签 ----
  const tagText = await page.evaluate(() => {
    const el = document.querySelector('.filter-card .el-tag')
    return el ? el.textContent.trim() : ''
  })
  check('③ 图片事实表下不应显示「30分钟聚合/日聚合」标签', !/30分钟聚合|日聚合/.test(tagText), `标签=${tagText || '(空)'}`)

  // ---- ④ 日历数据点 ----
  const calendarInfo = await page.evaluate(async () => {
    const tk = localStorage.getItem('camera_token')
    const now = new Date()
    const pad = (n) => String(n).padStart(2, '0')
    const day = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
    const q = new URLSearchParams({ startTime: `${day} 00:00:00`, endTime: `${day} 23:59:59` })
    const res = await fetch('/api/v1/analytics/line-summary?' + q.toString(), { headers: { Authorization: 'Bearer ' + tk } })
    const b = await res.json()
    return { points: (b.data?.dailyCalendar || []).length, total: b.data?.kpi?.totalCount }
  })
  check('④ 日历数据点 > 1（「近30天日历」应跨多天）', calendarInfo.points > 1, `points=${calendarInfo.points}, total=${calendarInfo.total}`)

  // 展开高级筛选
  await page.evaluate(() => {
    const b = Array.from(document.querySelectorAll('button')).find((x) => /高级筛选/.test(x.textContent))
    if (b) b.click()
  })
  await sleep(1200)

  // ---- ① ZK09 → 选该线相机 → 切 ZK06 ----
  await pickLines(page, ['ZK09'])
  const camOptsZK09 = await openFilterSelect(page, 2)
  await pickFilterOption(page, 2, camOptsZK09[0])
  const kpiB = await readKpi(page)
  const totalB = Number(String(kpiB['缺陷图片数'] || 0).replace(/,/g, ''))
  check('① ZK09 + 首个相机 有数据（前置条件）', totalB > 0, `KPI 缺陷图片数=${totalB}, 相机选择=${await readFilterText(page, 2)}`)

  await pickLines(page, ['ZK06'])
  const kpiC = await readKpi(page)
  const totalC = Number(String(kpiC['缺陷图片数'] || 0).replace(/,/g, ''))
  const camTextC = await readFilterText(page, 2)
  check('① 切产线后不应因相机残留而整页空', totalC > 0, `KPI 缺陷图片数=${totalC}, 相机选择=${camTextC}`)
  check('② 相机筛选不残留已失效相机', camTextC === '' || camTextC === '全部相机', `相机选择=${camTextC}`)

  // ---- ⑤ 多选产线时相机候选是否覆盖两条线 ----
  // 此时页面已选中 ZK06（上一步），直接追加 ZK09，避免 clearLines 后的面板状态干扰
  await pickOne(page, 'ZK09'); await sleep(2600)
  const tags = await page.evaluate(() => Array.from(document.querySelectorAll('.sel-line .el-tag')).map(t => t.textContent.trim()))
  const kpiMulti = await readKpi(page)
  const camOptsMulti = await openFilterSelect(page, 2)
  await page.keyboard.press('Escape')
  await sleep(400)
  const perLineCams = await page.evaluate(async () => {
    const tk = localStorage.getItem('camera_token')
    const res = await fetch('/api/v1/analytics/metadata', { headers: { Authorization: 'Bearer ' + tk } })
    const b = await res.json()
    const byLine = {}
    ;(b.data?.cameras || []).forEach((c) => { (byLine[c.lineId] = byLine[c.lineId] || []).push(c.cameraCode) })
    return byLine
  })
  const l10 = perLineCams['10'] || []
  const l11 = perLineCams['11'] || []
  const hasBoth = l10.every((code) => camOptsMulti.some((t) => t.includes(code))) &&
                  l11.every((code) => camOptsMulti.some((t) => t.includes(code)))
  check('⑤ 多选产线时相机候选覆盖全部所选产线', hasBoth,
    `已选=${JSON.stringify(tags)} KPI=${kpiMulti['缺陷图片数']} 候选=${JSON.stringify(camOptsMulti)}`)

  check('⑥ 无 JS 错误', jsErrors.length === 0, jsErrors.join(' | '))

  await browser.close()
  const failed = results.filter((r) => !r.ok)
  console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error(e); process.exit(1) })
