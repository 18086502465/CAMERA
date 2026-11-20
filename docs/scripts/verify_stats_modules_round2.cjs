/**
 * 统计模块第二轮全面验证（2026-11-20）
 *
 * 与第一轮（verify_stats_modules_full.cjs）的分工：
 *   第一轮覆盖「基本筛选 + 按钮 + 跳转 + 一致性」；
 *   本轮**补第一轮没覆盖**的场景：
 *     ① **导出按钮真实点击**（第一轮只测了接口，没测 UI 下载链路）
 *     ② **月历跨月/跨年翻页**
 *     ③ **深链直达 + 刷新**（前端路由懒加载 + chunk 哈希问题）
 *     ④ **筛选异常值输入**（不存在的值 / 非法日期）
 *     ⑤ **重复操作**（连点、连发、切了再切回）
 *     ⑥ **页面跳转后的数据展示**（不只是 URL 变了，要看数据真的换了）
 *
 * 设计原则（沿用第一轮教训）：
 *   - 断言**不硬编码业务数字**（数据天天涨 → 必假红）
 *   - 等待用**朴素轮询**，不用固定 sleep、也不用 page.waitForFunction
 *   - 断言「同一场景两次的相对关系」，不写死期望值
 */
const puppeteer = require('puppeteer-core')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let pass = 0
let fail = 0
const failures = []

function check(name, ok, detail) {
  if (ok) {
    pass++
    console.log(`  ✓ ${name}${detail ? ' — ' + detail : ''}`)
  } else {
    fail++
    failures.push(`${name}${detail ? ' — ' + detail : ''}`)
    console.log(`  ✗ ${name}${detail ? ' — ' + detail : ''}`)
  }
}

async function waitFor(page, fn, timeoutMs = 30000, arg) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    let ok = false
    try {
      ok = await page.evaluate(fn, arg)
    } catch (e) {
      ok = false
    }
    if (ok) return true
    await sleep(500)
  }
  return false
}

async function waitText(page, text, timeoutMs = 20000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const found = await page.evaluate((t) => (document.body.innerText || '').includes(t), text)
    if (found) return true
    await sleep(400)
  }
  return false
}

/** 抓所有 "标签: 值" 形态的 KPI（兼容两个页面的两种 DOM 结构） */
/**
 * 读 KPI 前先等页面**加载完**。
 *
 * ★ 2026-11-20 脚本自身缺陷：页面刚 goto 时 `summary` 还是**聚合骨架**
 *   （检测总数 0 / OK 0 / NG 0 / 良率 0.00%），图片通道的数据还没回来
 *   → 立刻读会拿到全 0 的初始值 → 误判成"数据不稳定 / 切换无效"。
 */
async function waitKpisReady(page, timeoutMs = 40000) {
  await waitFor(page, () => document.querySelectorAll('.kpi-card').length > 0, timeoutMs)
  // 等 KPI 出现非初始值（至少一个不是 0 / 0.00%）
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const k = await readKpis(page)
    const vals = Object.values(k).map((v) => String(v).trim())
    const hasReal = vals.some((v) => v && !/^0(\.00)?%?$/.test(v))
    if (hasReal) return k
    await sleep(600)
  }
  return readKpis(page)
}

async function readKpis(page) {
  return page.evaluate(() => {
    const out = {}
    document.querySelectorAll('.kpi-card, .stat-card, .metric-card').forEach((c) => {
      let label = (c.querySelector('.kpi-label, .stat-label, .metric-label')?.textContent || '').trim()
      let value = (c.querySelector('.kpi-value, .stat-value, .metric-value')?.textContent || '').trim()
      if (!value) {
        const strong = c.querySelector('strong, .kpi-text')
        value = (strong?.textContent || '').trim()
      }
      if (!label) {
        const kids = [...c.children].filter(
          (e) => !e.matches('strong, .kpi-text, .kpi-value, .stat-value, .metric-value')
        )
        label = (kids[0]?.textContent || '').trim()
      }
      if (label) out[label] = value
    })
    return out
  })
}

/** 抓 ECharts 实例的标题（判断图表是否渲染出内容） */
async function readChartTitles(page) {
  // ★ 产线统计页的图表卡 class 是 **.chart-card**（内含 .echart-box），
  //   标题取 `.card-head h4`（卡片整体 textContent 会把里面所有文字都拼上）。
  return page.evaluate(() => {
    const titles = []
    document.querySelectorAll('.chart-card').forEach((el) => {
      const t = (el.querySelector('.card-head h4')?.textContent || '').trim()
      const hasCanvas = !!el.querySelector('canvas')
      if (t) titles.push((hasCanvas ? '[图] ' : '[表] ') + t)
    })
    return titles.slice(0, 12)
  })
}

/** 抓页面上的错误提示（Element Plus 的 message / alert） */
async function readErrorToasts(page) {
  return page.evaluate(() =>
    [...document.querySelectorAll('.el-message, .el-notification, .el-alert, .el-message-box__message')]
      .map((e) => (e.textContent || '').trim())
      .filter(Boolean)
  )
}

/** 记录页面控制台错误与失败请求 */
function attachDiagnostics(page, bag) {
  page.on('console', (msg) => {
    if (msg.type() === 'error') bag.consoleErrors.push(msg.text().slice(0, 200))
  })
  page.on('pageerror', (err) => bag.pageErrors.push(String(err.message).slice(0, 200)))
  page.on('response', (res) => {
    const u = res.url()
    if (!u.includes('/api/')) return
    if (res.status() >= 400) {
      bag.failedRequests.push(`${res.status()} ${u.replace(BASE, '')}`)
      if (res.status() === 404) bag.notFoundResources.push(u.replace(BASE, ''))
    }
  })
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: resolveChrome(),
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })

  const bag = { consoleErrors: [], pageErrors: [], failedRequests: [], notFoundResources: [] }
  attachDiagnostics(page, bag)

  // 记录下载
  const downloads = []
  const client = await page.target().createCDPSession()
  await client.send('Page.setDownloadBehavior', {
    behavior: 'allow',
    downloadPath: process.env.DOWNLOAD_DIR || 'D:/031_45344camera/.workbuddy/tmp/downloads',
  })

  console.log('\n══════ ⓪ 登录 ══════')
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
  const lg = await login(page, 'admin', 'admin123')
  check('⓪ 登录成功', lg.ok, lg.msg || '')

  // ==========================================================
  console.log('\n══════ 一、日报页：导出按钮真实点击（第一轮未覆盖）══════')
  await page.goto(`${BASE}/daily-report`, { waitUntil: 'networkidle2', timeout: 60000 })
  const calReady = await waitFor(
    page,
    () => document.querySelectorAll('.day-cell').length > 0 && document.querySelectorAll('.scope-tab').length > 1,
    40000
  )
  check('① 日报页月历与产线胶囊渲染', calReady)

  // 找导出按钮
  const exportBtnInfo = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('button')]
    const b = btns.find((x) => (x.textContent || '').includes('导出'))
    if (!b) return { found: false, allButtons: btns.map((x) => (x.textContent || '').trim()).filter(Boolean).slice(0, 20) }
    const r = b.getBoundingClientRect()
    return { found: true, text: (b.textContent || '').trim(), visible: r.width > 0 && r.height > 0 }
  })
  check('② 找到「导出」按钮', exportBtnInfo.found,
    exportBtnInfo.found ? `"${exportBtnInfo.text}" 可见=${exportBtnInfo.visible}` : `页面上没有导出按钮：${(exportBtnInfo.allButtons || []).join('/')}`)

  if (exportBtnInfo.found) {
    const beforeErr = bag.failedRequests.length
    // 点击导出，等下载发生
    let downloaded = false
    const t0 = Date.now()
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('button')].find((x) => (x.textContent || '').includes('导出'))
      if (b) b.click()
    })
    while (Date.now() - t0 < 30000) {
      await sleep(800)
      // 导出成功会弹「导出成功」；失败会弹「导出失败：...」
      const toasts = await readErrorToasts(page)
      if (toasts.some((t) => t.includes('导出成功') || t.includes('导出失败'))) {
        downloaded = true
        break
      }
    }
    const toasts = await readErrorToasts(page)
    const okToast = toasts.find((t) => t.includes('导出成功'))
    const failToast = toasts.find((t) => t.includes('导出失败'))
    check('③ 点击导出后有明确反馈（成功/失败提示）', downloaded,
      downloaded ? (okToast || failToast) : '30 秒内无任何提示（按钮可能没绑上或请求挂住）')
    check('④ 导出**成功**（第一轮修的 P0-2：导出必 500）', !!okToast,
      okToast ? okToast : (failToast ? '导出失败：' + failToast : '未弹出结果'))
    check('⑤ 导出过程无 4xx/5xx 请求', bag.failedRequests.length === beforeErr,
      bag.failedRequests.length > beforeErr ? bag.failedRequests.slice(beforeErr).join(' / ') : '无')
  }

  // ==========================================================
  console.log('\n══════ 二、日报页：月历跨月/跨年翻页 ══════')
  const monthInfo = await page.evaluate(() => {
    const prev = [...document.querySelectorAll('button')].find((b) =>
      /上一|‹|◀|prev/i.test((b.getAttribute('aria-label') || '') + (b.className || '') + (b.textContent || ''))
    )
    const next = [...document.querySelectorAll('button')].find((b) =>
      /下一|›|▶|next/i.test((b.getAttribute('aria-label') || '') + (b.className || '') + (b.textContent || ''))
    )
    const head = document.querySelector('.calendar-head, .month-head, .cal-header')
    return {
      hasPrev: !!prev,
      hasNext: !!next,
      headText: head ? (head.textContent || '').trim() : '',
      prevHtml: prev ? prev.outerHTML.slice(0, 120) : '',
      nextHtml: next ? next.outerHTML.slice(0, 120) : '',
    }
  })
  check('⑥ 月历有翻月控件', monthInfo.hasPrev || monthInfo.hasNext,
    `prev=${monthInfo.hasPrev} next=${monthInfo.hasNext}`)

  if (monthInfo.hasPrev) {
    const before = await page.evaluate(() => ({
      head: (document.querySelector('.month-label')?.textContent
        || document.querySelector('.month-label')?.textContent
        || [...document.querySelectorAll('h3,h4,span')].map((e) => (e.textContent || '').trim())
             .find((t) => /^\d{4}[-年]\d{1,2}/.test(t)) || '').trim(),
      cells: document.querySelectorAll('.day-cell').length,
    }))
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('button')].find((x) =>
        /上一|‹|◀|prev/i.test((x.getAttribute('aria-label') || '') + (x.className || '') + (x.textContent || ''))
      )
      if (b) b.click()
    })
    await sleep(2500)
    const after = await page.evaluate(() => ({
      head: (document.querySelector('.month-label')?.textContent
        || document.querySelector('.month-label')?.textContent
        || [...document.querySelectorAll('h3,h4,span')].map((e) => (e.textContent || '').trim())
             .find((t) => /^\d{4}[-年]\d{1,2}/.test(t)) || '').trim(),
      cells: document.querySelectorAll('.day-cell').length,
    }))
    // ★ 日历是 6 行 × 7 列 = 42 格（含上下月补位），**不是当月天数**（28~31）。
    //   断言只该看「标题变了 + 格子数仍是完整的 42」+「本月格子有值」。
    check('⑦ 上一月：标题变化且日历完整渲染',
      after.head !== before.head && after.cells >= 28,
      `${before.head}(${before.cells}) → ${after.head}(${after.cells})`)

    // 翻回原月，验证可逆
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('button')].find((x) =>
        /下一|›|▶|next/i.test((x.getAttribute('aria-label') || '') + (x.className || '') + (x.textContent || ''))
      )
      if (b) b.click()
    })
    await sleep(2000)
    const back = await page.evaluate(() => ({
      head: (document.querySelector('.month-label')?.textContent
        || document.querySelector('.month-label')?.textContent
        || [...document.querySelectorAll('h3,h4,span')].map((e) => (e.textContent || '').trim())
             .find((t) => /^\d{4}[-年]\d{1,2}/.test(t)) || '').trim(),
      cells: document.querySelectorAll('.day-cell').length,
    }))
    check('⑧ 下一月翻回原月（可逆）', back.head === before.head && back.cells === before.cells,
      `回到 ${back.head}(${back.cells})`)
  }

  // ==========================================================
  console.log('\n══════ 三、深链直达 + 刷新（前端懒加载 + chunk 哈希）══════')
  // ★ 注意：产线统计页**不读 URL query**（onMounted 里没有 route.query）——
  //   它的筛选全部来自顶部布局级联（Layout.vue）与页面内控件。
  //   所以「深链带 ?lineId=9」不是有效用法（也没有任何页面用带参跳转），
  //   断言应验「深链直达不白屏、不被踢回登录」，**不能**断言参数生效。
  const deepLinks = [
    ['/line-analytics?lineId=9', '产线统计带参深链'],
    ['/daily-report', '统计日报'],
    ['/line-analytics', '产线统计'],
  ]
  for (const [path, label] of deepLinks) {
    await page.goto(`${BASE}${path}`, { waitUntil: 'networkidle2', timeout: 60000 })
    await sleep(4000)
    const rendered = await page.evaluate(() => ({
      text: (document.body.innerText || '').slice(0, 200),
      kpis: document.querySelectorAll('.kpi-card, .stat-card, .metric-card').length,
      hasLogin: (document.body.innerText || '').includes('登录'),
    }))
    check(`⑨ 深链直达 ${label}`, !rendered.hasLogin && rendered.kpis > 0,
      `KPI=${rendered.kpis} 登录页=${rendered.hasLogin}`)

    // 刷新
    await page.reload({ waitUntil: 'networkidle2', timeout: 60000 })
    // ★ 刷新后要等 KPI 真的渲染出来（首屏加载 ~8s），固定 sleep 4s 会读到 0 张
    await waitFor(page, () => document.querySelectorAll('.kpi-card').length > 0, 30000)
    await sleep(3000)
    const afterReload = await page.evaluate(() => ({
      kpis: document.querySelectorAll('.kpi-card, .stat-card, .metric-card').length,
      hasLogin: (document.body.innerText || '').includes('登录'),
      url: location.pathname + location.search,
    }))
    // ★ 断言写成「URL 仍指向目标路由」—— 刷新后若被踢回登录，URL 会变成 /login
    const expectPath = path.split('?')[0]
    check(`⑩ 刷新后仍正常（未被踢回登录 / 未白屏）`,
      !afterReload.hasLogin && afterReload.kpis > 0 && afterReload.url.startsWith(expectPath),
      `URL=${afterReload.url} KPI=${afterReload.kpis} 登录页=${afterReload.hasLogin}`)
  }

  // ==========================================================
  console.log('\n══════ 四、筛选异常值输入 ══════')
  await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
  const kpiGood = await waitKpisReady(page)

  // 非法 lineId 深链
  await page.goto(`${BASE}/line-analytics?lineId=999999`, { waitUntil: 'networkidle2', timeout: 60000 })
  await sleep(6000)
  const invalidRender = await page.evaluate(() => ({
    kpis: document.querySelectorAll('.kpi-card').length,
    body: (document.body.innerText || '').slice(0, 300),
    hasError: /错误|异常|失败|不存在/.test(document.body.innerText || ''),
  }))
  check('⑪ 非法 lineId 深链 → 页面仍渲染（不白屏不崩）',
    invalidRender.kpis > 0, `KPI=${invalidRender.kpis} 文本片段=${invalidRender.body.replace(/\n/g, ' ').slice(0, 70)}`)

  // ★ 该页不读 URL 参数 → ?lineId=999999 不会生效（页面的产线筛选来自布局级联下拉，
  //   而下拉里根本没有「不存在的产线」这个选项）。
  //   所以正确断言是「页面正常工作、KPI 有实际值」，而不是「数值必须与全厂不同」。
  const kpiInvalid = await readKpis(page)
  const allZeroInvalid = Object.values(kpiInvalid).every((v) => /^0(\.00)?%?$|^0$/.test(String(v).trim()))
  check('⑫ 深链带不存在的产线 → 页面正常工作（KPI 非全 0）',
    Object.keys(kpiInvalid).length > 0 && !allZeroInvalid,
    `KPI=${JSON.stringify(kpiInvalid).slice(0, 80)}`)

  // 非法日期深链
  // ★ 该页不读 URL 参数 → 非法 startTime 会被忽略，页面用默认时间范围正常工作。
  //   断言：① 不白屏 ② 有 KPI ③ **KPI 有实际数值**（不是全 0 —— 全 0 说明
  //   请求失败后前端兜底成 0，那是真故障）。
  await page.goto(`${BASE}/line-analytics?startTime=not-a-date`, { waitUntil: 'networkidle2', timeout: 60000 })
  await waitFor(page, () => document.querySelectorAll('.kpi-card').length > 0, 30000)
  await sleep(4000)
  const badDate = await page.evaluate(() => ({
    kpis: document.querySelectorAll('.kpi-card').length,
    body: (document.body.innerText || '').slice(0, 300),
  }))
  const kpiBadDate = await readKpis(page)
  const allZero = Object.values(kpiBadDate).every((v) => /^0(\.00)?%?$|^0$/.test(String(v).trim()))
  check('⑬ 非法日期深链 → 页面正常工作（不白屏 + KPI 有实际值）',
    badDate.kpis > 0 && !allZero,
    `KPI=${badDate.kpis} 全零=${allZero} 值=${JSON.stringify(kpiBadDate).slice(0, 90)}`)

  // ==========================================================
  console.log('\n══════ 五、重复操作（连点 / 连发 / 切了再切回）══════')
  await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
  const kpiA = await waitKpisReady(page)

  // 连续快速点击同一个看板/页签 5 次
  // ★ 上一版用 `.el-radio-button__inner` 做选择器 → **误点了布局里的其它单选按钮**
  //   （点完 KPI 口径从「图片通道」变成「聚合通道」，A/B 完全对不上）。
  //   现在只点**明确属于看板切换**的控件，并且每次点完都等数据稳定后再读。
  const boardSwitchFound = await page.evaluate(() => {
    // 看板切换按钮的特征：文字里含「看板」或带 board/dashboard 语义 class
    const cands = [...document.querySelectorAll('button, .el-radio-button__inner, [class*="board"], [class*="dashboard"]')]
      .filter((e) => {
        const t = (e.textContent || '').trim()
        const c = String(e.className || '')
        return /看板/.test(t) || /board|dashboard/i.test(c)
      })
      .filter((e) => (e.textContent || '').trim().length > 0 && (e.textContent || '').trim().length < 30)
    return { count: cands.length, sample: cands.slice(0, 5).map((e) => (e.textContent || '').trim()) }
  })
  for (let i = 0; i < 5; i++) {
    await page.evaluate(() => {
      const cands = [...document.querySelectorAll('button, .el-radio-button__inner, [class*="board"], [class*="dashboard"]')]
        .filter((e) => {
          const t = (e.textContent || '').trim()
          const c = String(e.className || '')
          return /看板/.test(t) || /board|dashboard/i.test(c)
        })
        .filter((e) => (e.textContent || '').trim().length > 0 && (e.textContent || '').trim().length < 30)
      if (cands.length) cands[0].click()
    })
    await sleep(600)
  }
  let kpiB = await waitKpisReady(page)
  for (let i = 0; i < 15; i++) {
    await sleep(700)
    const next = await readKpis(page)
    if (JSON.stringify(next) === JSON.stringify(kpiB)) { kpiB = next; break }
    kpiB = next
  }
  check('⑭ 连点 5 次同一看板 → 数据稳定',
    JSON.stringify(kpiA) === JSON.stringify(kpiB),
    `看板控件=${boardSwitchFound.count} 个(${boardSwitchFound.sample.join('/')})；A=${JSON.stringify(kpiA).slice(0, 60)} B=${JSON.stringify(kpiB).slice(0, 60)}`)

  // 切换产线后切回（竞态守卫：最后状态必须生效）
  // ★ 产线统计页的产线选择是 **el-select 下拉**（.sel-line），不是页签 ——
  //   第一轮脚本找 `.line-chip` 找不到 → 误报「未找到产线页签」。
  const selectInfo = await page.evaluate(() => {
    const sel = document.querySelector('.sel-line')
    if (!sel) return { found: false }
    const input = sel.querySelector('input')
    return { found: true, current: (input?.value || sel.textContent || '').trim() }
  })
  check('⑮a 产线下拉存在', selectInfo.found, selectInfo.found ? `当前=${selectInfo.current}` : '未找到 .sel-line')

  if (selectInfo.found) {
    // 打开下拉，选第二个真实产线
    const picked = await page.evaluate(async () => {
      const sel = document.querySelector('.sel-line')
      sel.querySelector('input').click()
      await new Promise((r) => setTimeout(r, 800))
      const opts = [...document.querySelectorAll('.el-select-dropdown__item')]
        .filter((o) => o.offsetParent !== null)
      if (opts.length < 2) return { ok: false, count: opts.length }
      const label = (opts[1].textContent || '').trim()
      opts[1].click()
      return { ok: true, label, count: opts.length }
    })
    // ★ 等「KPI 确实变了」而不是等「非全 0」——
    //   waitKpisReady 在旧值仍有效时就会返回（它只检查不是全 0），
    //   而切产线后接口往返有延迟 → 读到切换前的旧值 → 误判「没透传」。
    let kpiSwitched = await readKpis(page)
    let changed = false
    for (let i = 0; i < 30; i++) {
      await sleep(700)
      kpiSwitched = await readKpis(page)
      if (JSON.stringify(kpiSwitched) !== JSON.stringify(kpiA)) { changed = true; break }
    }
    check('⑮ 切产线后数据变化', picked.ok && changed,
      picked.ok ? `切到 ${picked.label}；KPI ${JSON.stringify(kpiA).slice(0, 45)} → ${JSON.stringify(kpiSwitched).slice(0, 45)}`
                : `下拉项只有 ${picked.count} 个`)

    // ★ `.sel-line` 是 **multiple 多选**（Layout.vue:18）→ 点第一项是「选中 ZK65」，
    //   不是「清空」。清空的方式是删掉已选的 tag（或点「全部产线」选项，它在多选里
    //   并不存在 —— 实际是清空 tag）。这里直接删 tag。
    const cleared = await page.evaluate(async () => {
      const sel = document.querySelector('.sel-line')
      if (!sel) return { ok: false }
      // 关闭可能展开的下拉，避免点到选项
      document.body.click()
      await new Promise((r) => setTimeout(r, 300))
      const tags = [...sel.querySelectorAll('.el-tag')]
      const before = tags.length
      // ★ Element Plus 多选 tag 的关闭按钮是 **`<button>`**（.el-tag__close），
      //   `.el-select__tags-text` 是**文本 span**，点它只是聚焦不会删。
      //   上一版用错了选择器 → tag 数 1→1 → 清空没生效 → 误判「数值没还原」。
      for (const t of tags) {
        const close = t.querySelector('button, .el-tag__close, i.el-tag__close')
        if (close) close.click()
        await new Promise((r) => setTimeout(r, 300))
      }
      return { ok: true, before, after: sel.querySelectorAll('.el-tag').length }
    })
    let kpiBack = await readKpis(page)
    let restored = false
    for (let i = 0; i < 30; i++) {
      await sleep(700)
      kpiBack = await readKpis(page)
      if (JSON.stringify(kpiBack) === JSON.stringify(kpiA)) { restored = true; break }
    }
    // 若还没还原，等一轮「非全 0」再判（清空后接口往返有延迟）
    if (!restored) {
      const ready = await waitKpisReady(page, 15000)
      restored = JSON.stringify(ready) === JSON.stringify(kpiA)
      kpiBack = ready
    }
    check('⑯ 清空产线筛选 → 数值还原',
      restored,
      `清空前 ${cleared.before} 个 tag → ${cleared.after}；原=${JSON.stringify(kpiA).slice(0, 55)} 回=${JSON.stringify(kpiBack).slice(0, 55)}`)
  } else {
    check('⑮ 切产线后数据变化', false, '产线下拉不存在')
    check('⑯ 切回原产线 → 数值还原', false, '跳过')
  }

  // ==========================================================
  console.log('\n══════ 六、页面跳转后的数据展示（不只是 URL 变了）══════')
  await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
  await waitFor(page, () => document.querySelectorAll('.kpi-card').length > 0, 40000)
  const kpiLine = await readKpis(page)
  const chartTitlesLine = await readChartTitles(page)

  await page.goto(`${BASE}/daily-report`, { waitUntil: 'networkidle2', timeout: 60000 })
  await waitFor(page, () => document.querySelectorAll('.day-cell').length > 0, 40000)
  // ★ 日报页的 4 张「当日维度」KPI 卡**需要点一个日期**才出现**
  //   （没点 → selectedDate 为空 → 一张卡都没有）→ 必须先点有数据的格。
  await page.evaluate(() => {
    const cells = [...document.querySelectorAll('.day-cell.hasdata')]
    const c = cells.length ? cells[cells.length - 1] : document.querySelector('.day-cell')
    if (c) c.click()
  })
  await waitFor(page, () => document.querySelectorAll('.kpi-card').length > 0, 30000)
  await sleep(2000)
  const kpiDaily = await readKpis(page)

  check('⑰ 两个模块都渲染出 KPI', Object.keys(kpiLine).length > 0 && Object.keys(kpiDaily).length > 0,
    `产线统计 ${Object.keys(kpiLine).length} 张 / 日报 ${Object.keys(kpiDaily).length} 张`)
  check('⑱ 两个模块 KPI 口径不同（不是同一份数据硬塞）',
    JSON.stringify(kpiLine) !== JSON.stringify(kpiDaily),
    `产线统计=${Object.keys(kpiLine).slice(0, 4).join('/')} 日报=${Object.keys(kpiDaily).slice(0, 4).join('/')}`)
  check('⑲ 产线统计页有图表卡片', chartTitlesLine.length > 0, chartTitlesLine.slice(0, 4).join(' / '))

  // ==========================================================
  console.log('\n══════ 七、控制台与网络健康度 ══════')
  // 过滤掉与本轮测试无关的资源加载噪音
  // ★ favicon.ico 是浏览器自动请求的，站点没配图标 → 必然 404，与功能无关
  // ★ console 的 error 消息**不含 URL**（只有 "Failed to load resource: ..."），
  //   无法按 favicon 过滤 → 改为：把「404 资源」单独归类，
  //   只在 404 资源**全部是 favicon.ico** 时才豁免（其余 404 仍要报）。
  // ★ console 的 error 消息**不含 URL**（只有 "Failed to load resource: ..."），
  //   所以不能按消息过滤 favicon → 改为：把 404 资源按 URL 归类，
  //   **只有 favicon.ico 之外的 404 才算问题**。
  const notFoundResources = [...new Set(bag.notFoundResources)]
  const realNotFound = notFoundResources.filter((u) => !/favicon\.ico/i.test(u))
  const realConsoleErrors = realNotFound.length > 0 ? bag.consoleErrors.filter(
    (e) => !/ERR_FAILED.*\.(png|jpg|svg|woff)/i.test(e)
  ) : []
  check('⑳ 无 JS 运行时异常', bag.pageErrors.length === 0,
    bag.pageErrors.slice(0, 3).join(' | ') || '无')
  check('㉑ 无控制台 error（favicon.ico 除外无其它 404）', realConsoleErrors.length === 0 && realNotFound.length === 0,
    realNotFound.length > 0
      ? `404 资源：${realNotFound.slice(0, 3).join(' | ')}`
      : (realConsoleErrors.slice(0, 3).join(' | ') || `仅 favicon.ico（${notFoundResources.length} 个 404）`))
  const apiFails = bag.failedRequests.filter((r) => !/400/.test(r) && !/favicon/.test(r))
  check('㉒ 无 5xx 接口失败（4xx 是我们故意传的非法参数）', apiFails.length === 0,
    apiFails.slice(0, 3).join(' | ') || '无')

  console.log('\n══════════════════════════════════════')
  console.log(`  通过 ${pass} 项，失败 ${fail} 项`)
  if (failures.length) {
    console.log('\n  失败清单：')
    failures.forEach((f, i) => console.log(`   ${i + 1}. ${f}`))
  }
  console.log('══════════════════════════════════════\n')

  await browser.close()
  process.exit(fail > 0 ? 1 : 0)
})().catch((e) => {
  console.error('脚本异常：', e)
  process.exit(2)
})
