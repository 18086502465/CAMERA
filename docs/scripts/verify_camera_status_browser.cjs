/**
 * 相机状态页 —— 各产线图片/缺陷数可加性浏览器回归（2026-11-05）
 *
 * 回归现场 bug：
 *   相机状态页顶部【近批次图片】【高危缺陷】在"不筛选产线"时比"筛选产线"
 *   多一整条产线（ZK54=0、ZK23=3133，但全量=6266）。
 *
 * 根因：现场 camera_name 跨产线重名，前端把 cameraName 也当匹配键，
 *   ZK54 的同名相机拿到了 ZK23 的统计 → 累加时多算。
 *
 * 验证点：
 *   ① 每台相机的「图/缺」按 camera_code 归属（重名相机不共享统计）
 *   ② 各产线的图片数之和 === 不筛选产线时的总数
 *   ③ 高危缺陷同理
 *   ④ 无 JS 错误
 *
 * 用法：node verify_camera_status_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const USER = 'admin'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const results = []
function check(name, ok, detail) {
  results.push({ name, ok })
  console.log(`${ok ? '  ✓' : '  ✗'} ${name}${detail ? ' → ' + detail : ''}`)
}

/** 读取顶部 KPI 卡片 */
async function readKpis(page) {
  return page.evaluate(() => {
    const out = {}
    document.querySelectorAll('.kpi').forEach((c) => {
      const label = (c.querySelector('span')?.textContent || '').trim()
      const value = (c.querySelector('strong')?.textContent || '').trim()
      if (label) out[label] = value
    })
    return out
  })
}

/** 读取表格里每台相机的 编号 / 图数 / 高危数 */
async function readRows(page) {
  return page.evaluate(() => {
    const rows = []
    document.querySelectorAll('.el-table__body-wrapper tbody tr').forEach((tr) => {
      const tds = Array.from(tr.querySelectorAll('td')).map((td) => (td.textContent || '').trim())
      if (tds.length < 4) return
      const codeIdx = tds.findIndex((t) => /^DA[A-Za-z0-9]+$/.test(t))
      const imgCell = tds.find((t) => /图\s*\d+/.test(t))
      const sevCell = tds.find((t) => /高\s*\d+/.test(t))
      const mImg = imgCell && imgCell.match(/图\s*(\d+)/)
      const mHigh = sevCell && sevCell.match(/高\s*(\d+)/)
      rows.push({
        cameraCode: codeIdx >= 0 ? tds[codeIdx] : '',
        imageCount: mImg ? Number(mImg[1]) : null,
        highCount: mHigh ? Number(mHigh[1]) : null,
      })
    })
    return rows
  })
}

/** 选择产线（通过顶栏 el-select 下拉） */
/**
 * 选中产线。
 *
 * ★ 踩坑：Element Plus 的 el-select 是 **toggle** 语义 —— 选完一项后弹层已关闭，
 *   此时再点 .el-select__wrapper 会把"已关闭"再 toggle 成"打开"… 但实测第二次
 *   点击后下拉项仍是不可见（visible 项为空），连续切换极不可靠。
 * ★ 稳妥做法：**每次筛选前重新加载页面**，从干净状态操作。
 */
async function pickLine(page, lineName) {
  await page.goto(`${BASE}/camera-status`, { waitUntil: 'networkidle2', timeout: 60000 })
  await new Promise((r) => setTimeout(r, 3500))
  // ★ 必须点 **.el-select__wrapper** 才会展开；点 .el-select 外层不会触发，
  //   下拉项会一直是隐藏的（offsetParent === null）。
  const opened = await page.evaluate(() => {
    const sels = Array.from(document.querySelectorAll('.el-select'))
    for (const s of sels) {
      const t = (s.textContent || '')
      if (t.includes('全部产线') || t.includes('ZK')) {
        const w = s.querySelector('.el-select__wrapper') || s
        w.click()
        return true
      }
    }
    return false
  })
  if (!opened) return false
  await new Promise((r) => setTimeout(r, 1200))
  const ok = await page.evaluate((name) => {
    // 只取**可见**的下拉项（隐藏项属于其它 select 的弹层）
    const opts = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .filter((o) => o.offsetParent !== null)
    const hit = opts.find((o) => (o.textContent || '').trim() === name)
    if (hit) { hit.click(); return true }
    return false
  }, lineName)
  await new Promise((r) => setTimeout(r, 3500))
  // 校验筛选是否真的生效：顶栏产线下拉应显示所选产线
  const applied = await page.evaluate((name) => {
    const sels = Array.from(document.querySelectorAll('.el-select'))
    for (const s of sels) {
      const t = (s.textContent || '')
      if (t.includes('全部产线') || t.includes('ZK')) return (t || '').trim().includes(name)
    }
    return false
  }, lineName)
  return ok && applied
}

async function pickLineUnused(page, lineName) {
  const opened = await page.evaluate(() => {
    const sels = Array.from(document.querySelectorAll('.el-select'))
    for (const s of sels) {
      const t = (s.textContent || '')
      if (t.includes('全部产线') || t.includes('ZK')) {
        const w = s.querySelector('.el-select__wrapper') || s
        w.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
        w.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
        w.click()
        return true
      }
    }
    return false
  })
  if (!opened) return false
  await new Promise((r) => setTimeout(r, 1000))
  // 只取**可见**的下拉项（隐藏项属于其它 select 的弹层）
  const ok = await page.evaluate((name) => {
    const opts = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .filter((o) => o.offsetParent !== null)
    const hit = opts.find((o) => (o.textContent || '').trim() === name)
    if (hit) { hit.click(); return true }
    return false
  }, lineName)
  await new Promise((r) => setTimeout(r, 3500))
  // 校验筛选是否真的生效：顶栏产线下拉应显示所选产线
  const applied = await page.evaluate((name) => {
    const sels = Array.from(document.querySelectorAll('.el-select'))
    for (const s of sels) {
      const t = (s.textContent || '')
      if (t.includes('全部产线') || t.includes('ZK')) return (t || '').trim().includes(name)
    }
    return false
  }, lineName)
  return ok && applied
}

/** 清掉产线筛选（选"全部产线"） */
async function clearLine(page) {
  await page.goto(`${BASE}/camera-status`, { waitUntil: 'networkidle2', timeout: 60000 })
  await new Promise((r) => setTimeout(r, 3500))
  const opened = await page.evaluate(() => {
    const sels = Array.from(document.querySelectorAll('.el-select'))
    for (const s of sels) {
      const t = (s.textContent || '')
      if (t.includes('全部产线') || t.includes('ZK')) {
        const w = s.querySelector('.el-select__wrapper') || s
        w.click()
        return true
      }
    }
    return false
  })
  if (!opened) return false
  await new Promise((r) => setTimeout(r, 1200))
  const ok = await page.evaluate(() => {
    const opts = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      .filter((o) => o.offsetParent !== null)
    const hit = opts.find((o) => (o.textContent || '').trim() === '全部产线')
    if (hit) { hit.click(); return true }
    return false
  })
  await new Promise((r) => setTimeout(r, 3500))
  return ok
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

    await page.goto(`${BASE}/camera-status`, { waitUntil: 'networkidle2', timeout: 60000 })
    await new Promise((r) => setTimeout(r, 4000))

    // ---- 不筛选 ----
    const allKpi = await readKpis(page)
    const allRows = await readRows(page)
    console.log('    [全部] KPI:', JSON.stringify(allKpi))
    check('不筛选读到「近批次图片」', '近批次图片' in allKpi, String(allKpi['近批次图片']))
    check('不筛选读到「高危缺陷」', '高危缺陷' in allKpi, String(allKpi['高危缺陷']))

    // ---- 逐产线累加 ----
    const lineNames = await page.evaluate(() => {
      const opts = Array.from(document.querySelectorAll('.el-select-dropdown__item'))
      return opts.map((o) => (o.textContent || '').trim()).filter((t) => /^ZK/.test(t))
    })
    console.log('    可选产线:', lineNames.join(', ') || '(未取到)')

    let sumImg = 0
    let sumHigh = 0
    const perLine = []
    for (const ln of lineNames) {
      const picked = await pickLine(page, ln)
      check(`产线筛选已生效：${ln}`, picked, picked ? '' : '下拉未展开或未应用')
      if (!picked) continue
      const kpi = await readKpis(page)
      const rows = await readRows(page)
      const img = Number(String(kpi['近批次图片'] || '0').replace(/,/g, ''))
      const high = Number(String(kpi['高危缺陷'] || '0').replace(/,/g, ''))
      sumImg += img
      sumHigh += high
      perLine.push({ line: ln, img, high, cams: rows.length })
      console.log(`    [${ln}] 近批次图片=${img} 高危缺陷=${high} 相机数=${rows.length}`)
    }
    console.log('    各产线累加:', JSON.stringify(perLine), '→ 图片', sumImg, '高危', sumHigh)

    // ---- 核心断言 ----
    const allImg = Number(String(allKpi['近批次图片'] || '0').replace(/,/g, ''))
    const allHigh = Number(String(allKpi['高危缺陷'] || '0').replace(/,/g, ''))
    // ★ 2026-11-07：原断言用严格相等，在现场实时写入下**必然假红**。
    //   allKpi 在遍历各产线之前取，而遍历每条产线都要重新加载页面（4s+ 等待），
    //   期间相机继续上传 → 各产线之和恒大于「不筛选总数」，差值约等于耗时×写入速率
    //   （实测 5~96 条，与本次遍历耗时成正比）。
    //   真正的 bug（重名相机共享统计）会让某条产线多出**接近一整条产线**的量
    //   （现场实测 3133），与几十条的时序噪声不在一个量级。
    //   所以：少算仍然严格报错（说明筛选丢了数据），多算只允许容差内。
    const tolImg = Math.max(200, Math.round(allImg * 0.02))
    const okImg = sumImg >= allImg && (sumImg - allImg) <= tolImg
    check('★ 各产线「近批次图片」之和 === 不筛选总数', okImg,
      `各产线合计 ${sumImg} vs 不筛选 ${allImg}（时序容差 ${tolImg}）`)
    const tolHigh = Math.max(50, Math.round(allHigh * 0.02))
    const okHigh = sumHigh >= allHigh && (sumHigh - allHigh) <= tolHigh
    check('★ 各产线「高危缺陷」之和 === 不筛选总数', okHigh,
      `各产线合计 ${sumHigh} vs 不筛选 ${allHigh}（时序容差 ${tolHigh}）`)

    // ---- 逐行归属检查：重名相机不得共享统计 ----
    await clearLine(page)
    await new Promise((r) => setTimeout(r, 3000))
    const rowsNow = await readRows(page)
    const byCode = new Map()
    rowsNow.forEach((r) => { if (r.cameraCode) byCode.set(r.cameraCode, r) })
    const names = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.el-table__body-wrapper tbody tr')).map((tr) => {
        const tds = Array.from(tr.querySelectorAll('td')).map((td) => (td.textContent || '').trim())
        const codeIdx = tds.findIndex((t) => /^DA[A-Za-z0-9]+$/.test(t))
        return { code: codeIdx >= 0 ? tds[codeIdx] : '', name: tds[0] }
      }))
    // ★ 重名检查必须用**全量相机**（表格分页只显示 10 台，会漏掉重名组）
    const camInfo = await page.evaluate(async () => {
      const token = localStorage.getItem('camera_token') || localStorage.getItem('token') || ''
      const res = await fetch('/api/cameras?page=1&size=999', {
        headers: { Authorization: 'Bearer ' + token }
      })
      const json = await res.json()
      const recs = (json.data && (json.data.records || json.data.list)) || []
      const groups = {}
      recs.forEach((r) => {
        const name = (r.cameraName || '').trim()
        if (!name) return
        if (!groups[name]) groups[name] = []
        groups[name].push(r.cameraCode || r.deviceId)
      })
      return { total: recs.length, dups: Object.entries(groups).filter(([, c]) => c.length > 1) }
    })
    console.log(`    全量相机 ${camInfo.total} 台，重名组: ${camInfo.dups.length}`)
    check('现场存在重名相机组（本次 bug 的触发条件）', camInfo.dups.length > 0,
      camInfo.dups.map(([n, c]) => `${n}:${c.join('/')}`).join(' | '))

    // 每台相机的「图/缺」必须按 camera_code 归属：重名组内不能共享同一个非零值
    const summaryApi = await page.evaluate(async () => {
      const token = localStorage.getItem('camera_token') || localStorage.getItem('token') || ''
      const res = await fetch('/api/v1/defect-images/camera-summaries?latestLimit=1', {
        headers: { Authorization: 'Bearer ' + token }
      })
      const json = await res.json()
      return (json.data || []).map((r) => ({
        code: r.cameraCode || r.cameraId, name: (r.cameraName || '').trim(),
        imageCount: Number(r.imageCount || 0)
      }))
    })
    let shared = 0
    camInfo.dups.forEach(([, codes]) => {
      const counts = codes
        .map((c) => summaryApi.find((s) => s.code === c)?.imageCount)
        .filter((v) => v != null)
      if (counts.length > 1 && new Set(counts).size === 1 && counts[0] > 0) shared++
    })
    check('★ 重名相机的图片数未被共享（无 >0 的相同值）', shared === 0,
      shared === 0 ? '无共享' : `${shared} 组共享了非零值`)

    // ---- JS 错误 ----
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
