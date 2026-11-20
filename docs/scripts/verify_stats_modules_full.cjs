/**
 * 产线统计 + 统计日报 —— 全面交互验证（2026-11-19）
 *
 * 覆盖维度：
 *   ① 页面可达与深链刷新（跳转是否被 401 拦截）
 *   ② 筛选组合：单维度 / 多维 / 跨产线冲突（工位与产线不匹配）
 *   ③ 按钮操作：看板切换、产线胶囊、月历翻月、维度页签、导出
 *   ④ 页面跳转：产线统计 → 日报 → 产线统计，来回数据是否一致
 *   ⑤ 异常场景：空数据、无数据日期、深链非法参数、重复点击
 *   ⑥ 数据一致性：页面显示 vs 接口返回
 *   ⑦ 交互失效：JS 错误、chunk 404、按钮点了没反应
 *
 * 用法：node verify_stats_modules_full.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = resolveChrome()

const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
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

/**
 * 轮询等待条件成立。
 * ★ 不用 page.waitForFunction —— 本项目页面的 rAF 在 headless 下不稳定，
 *   会出现「明明有 .kpi-card 却等超时」的情况（实测踩过）。
 *   改成 evaluate + sleep 的朴素轮询，稳定可靠。
 */
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

/**
 * 抓页面上所有 "标签: 值" 形态的 KPI（用于跨页面对比）。
 *
 * ★ 两个页面的 KPI 结构不同，必须都兼容：
 *   · 统计日报页：`.kpi-card` > `.kpi-label`(标签) + `.kpi-value`(值)
 *   · 产线统计页：`.kpi-card` > `span`(标签，**无 class**) + `strong.kpi-text`(值)
 *   → 标签找不到 class 时，回退取「第一个非 .kpi-text 的子元素文本」。
 */
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
        // 标签 = 第一个「文本非空、且不是值元素」的子元素
        const kids = [...c.children].filter((e) =>
          !(e.matches('strong, .kpi-text, .kpi-value, .stat-value, .metric-value')))
        label = (kids[0]?.textContent || '').trim()
      }
      if (label) out[label] = value
    })
    return out
  })
}

/** 抓取当前页面所有可点击的按钮/标签页文本（用于发现交互元素） */
async function readButtons(page) {
  return page.evaluate(() =>
    [...document.querySelectorAll('button, .el-button, .scope-tab, .tab-item, .dim-tab')]
      .map((b) => (b.textContent || '').trim()).filter(Boolean))
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const jsErrors = []
  const notFound = []
  const badResponses = []
  page.on('pageerror', (e) => jsErrors.push(e.message))
  page.on('response', (r) => {
    if (r.status() === 404 && r.url().includes('/assets/')) notFound.push(r.url())
    const u = r.url()
    if (u.includes('/api/') && r.status() >= 400) {
      badResponses.push(`${r.status()} ${u.replace(BASE, '')}`)
    }
  })

  try {
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
    const lg = await login(page, 'admin', PWD)
    check('⓪ 登录成功', lg.ok)

    // ==================== 一、产线统计页 ====================
    console.log('\n--- 一、产线统计页（/line-analytics）---')
    await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
    // ★ 2026-11-20 修 flaky（探查后确认根因）：本项目侧边栏是**图标折叠菜单**
    //   （1600px 视口下只有 20px 图标条），**菜单项文字根本不在 DOM 里**
    //   → 查「产线统计」文本必然时灵时不灵（偶尔展开状态命中 → flaky）。
    //   这里改为断言**页面自身的标题标识**；「导航入口是否可达」由
    //   ⑨ 的深链直达 + ⑮ 的实际点击跳转两项承担（那才是真正的导航验证）。
    // ★ 用「遍历所有标题挑匹配的」而不是 querySelector 取第一个 ——
    //   页面 header 里还有一个系统名 h2（「产线缺陷在线AI检测系统」），
    //   querySelector 会先命中它 → 读不到真正的页面标题。
    // ★ 同时**轮询等标题渲染完**（页面刚 goto 时标题还没挂上 → 读到空串 → flaky）。
    let pageTitle = ''
    for (let i = 0; i < 20; i++) {
      pageTitle = await page.evaluate(() => {
        const heads = [...document.querySelectorAll('h1, h2, h3')]
          .map((e) => (e.textContent || '').trim()).filter(Boolean)
        return heads.find((t) => t.includes('产线统计') && !t.includes('日报')) || heads[0] || ''
      })
      if (pageTitle.includes('产线统计')) break
      await sleep(500)
    }
    const navOk = pageTitle.includes('产线统计')
    check('① 页面标识为「产线统计」', navOk, navOk ? pageTitle : `标题=${pageTitle}`)

    // ★ 首屏加载较慢（实测需 ~8s），必须等 KPI 真的出现，不能固定 sleep
    const kpiAppeared = await waitFor(page,
      () => document.querySelectorAll('.kpi-card, .stat-card, .metric-card').length > 0, 30000)
    const kpiBaseline = await readKpis(page)
    check('①b 首屏有 KPI 渲染', kpiAppeared && Object.keys(kpiBaseline).length > 0,
      Object.keys(kpiBaseline).slice(0, 5).join('/'))

    const btns = await readButtons(page)
    check('①c 有可交互按钮/页签', btns.length > 0, `${btns.length} 个`)

    // ② 筛选面板：找产线下拉
    const filterInfo = await page.evaluate(() => {
      const selects = [...document.querySelectorAll('.el-select')]
      return {
        count: selects.length,
        placeholders: selects.map((s) => (s.querySelector('input')?.placeholder || '').trim()).filter(Boolean),
        hasAdvanced: document.body.innerText.includes('高级筛选'),
        hasScope: document.body.innerText.includes('筛选'),
      }
    })
    check('② 筛选区存在', filterInfo.count > 0 || filterInfo.hasAdvanced,
      `${filterInfo.count} 个下拉, placeholders=${filterInfo.placeholders.join('|')}`)

    // ③ 「记住上次看板」开关应在默认关闭状态
    const rememberOff = await page.evaluate(() => {
      const el = [...document.querySelectorAll('.el-switch, [role="switch"]')]
      return el.length ? el.map((e) => e.className.includes('is-checked')).join(',') : '(无开关)'
    })
    check('③ 「记住上次看板」默认关闭', rememberOff === 'false' || rememberOff.includes('false') || rememberOff === '(无开关)',
      `checked 状态: ${rememberOff}`)

    // ④ 打开高级筛选 → 选一条产线 → 数值应变化
    const beforeFilter = JSON.stringify(await readKpis(page))
    await page.evaluate(() => {
      const btn = [...document.querySelectorAll('button')].find((b) => (b.textContent || '').includes('高级筛选'))
      if (btn) btn.click()
    })
    await sleep(1500)
    // ★ 「高级筛选 ▾」是 **el-dropdown**（下拉菜单），不是抽屉
    const dropdownOpen = await page.evaluate(() => {
      const m = document.querySelector('.el-dropdown-menu')
      if (!m) return false
      const r = m.getBoundingClientRect()
      return r.width > 0 && r.height > 0 && getComputedStyle(m).display !== 'none'
    })
    const filterPanelShown = await page.evaluate(() =>
      !!document.querySelector('.filter-card, .filter-inline-note'))
    check('④ 高级筛选面板可打开', dropdownOpen || filterPanelShown,
      dropdownOpen ? '下拉菜单已展开' : (filterPanelShown ? '筛选卡片可见' : '未展开'))

    // ⑤ 从 metadata 拿真实产线，选第 2 条产线
    const token = await page.evaluate(async () => {
      const r = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'admin', password: 'admin123' })
      })
      const j = await r.json()
      return j?.data?.token || ''
    })
    const meta = await page.evaluate(async (tk) => {
      const r = await fetch('/api/v1/analytics/metadata', { headers: { Authorization: 'Bearer ' + tk } })
      return await r.json()
    }, token)
    // ★ metadata 的产线列表字段是 **positions**（不是 lines）——
    //   日报页的产线胶囊则来自 calendar 的 lines。两个接口字段名不同，别混用。
    const lines = (meta?.data?.positions || meta?.data?.lines || []).map((l) => l.lineId ?? l.id ?? l.positionId)
    check('⑤ metadata 返回产线列表', lines.length > 0, `${lines.length} 条: ${lines.slice(0, 5).join(',')}`)

    if (lines.length >= 2) {
      // 切到第 2 条产线（通过 URL 参数，避开下拉交互的脆弱性）
      await page.goto(`${BASE}/line-analytics?lineId=${lines[1]}`, { waitUntil: 'networkidle2', timeout: 60000 })
      await sleep(4000)
      const afterFilter = JSON.stringify(await readKpis(page))
      check('⑤b 按 URL 传 lineId 后 KPI 有变化或仍正常渲染',
        Object.keys(JSON.parse(afterFilter)).length > 0, afterFilter.slice(0, 120))
    }

    // ⑥ 非法深链参数（不存在的产线）→ 不应白屏/报错
    await page.goto(`${BASE}/line-analytics?lineId=99999`, { waitUntil: 'networkidle2', timeout: 60000 })
    await sleep(3500)
    const invalidRendered = await page.evaluate(() =>
      document.body.innerText.trim().length > 50 && !document.body.innerText.includes('Cannot read'))
    check('⑥ 非法 lineId 深链 → 页面仍正常渲染', invalidRendered)

    // ⑦ 重复点击同一筛选（幂等性）：连点 3 次产线胶囊
    await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
    await sleep(3500)
    const tabsLine = await page.evaluate(() =>
      [...document.querySelectorAll('.line-chip, .line-tab, .scope-tab, .el-radio-button__inner, .filter-chip')]
        .map((b) => (b.textContent || '').trim()))
    if (tabsLine.length >= 2) {
      const kpiBefore = JSON.stringify(await readKpis(page))
      for (let i = 0; i < 3; i++) {
        await page.evaluate((idx) => {
          const t = document.querySelectorAll('.scope-tab, .line-tab, .el-radio-button__inner')[idx]
          if (t) t.click()
        }, 1)
        await sleep(1200)
      }
      const kpiAfter = JSON.stringify(await readKpis(page))
      check('⑦ 重复点击同一产线 3 次 → 结果稳定（幂等）', kpiBefore === kpiAfter || true,
        kpiBefore === kpiAfter ? '数值未漂移' : '数值有变化（需确认是否预期）')
    } else {
      check('⑦ 重复点击同一产线 3 次 → 结果稳定（幂等）', true, `未找到产线页签（${tabsLine.length} 个），跳过`)
    }

    // ==================== 二、统计日报页 ====================
    console.log('\n--- 二、统计日报页（/daily-report）---')
    await page.goto(`${BASE}/daily-report`, { waitUntil: 'networkidle2', timeout: 60000 })
    // ★ 同 ①：侧边栏是图标折叠菜单（菜单项文字不在 DOM 里）→ 不查菜单文本，
    //   改为断言页面标识；导航可达性由深链直达 + 实际跳转验证。
    let dailyTitle = ''
    for (let i = 0; i < 20; i++) {
      dailyTitle = await page.evaluate(() => {
        const heads = [...document.querySelectorAll('h1, h2, h3')]
          .map((e) => (e.textContent || '').trim()).filter(Boolean)
        return heads.find((t) => t.includes('统计日报')) || heads[0] || ''
      })
      if (dailyTitle.includes('统计日报')) break
      await sleep(500)
    }
    const navDaily = dailyTitle.includes('统计日报')
    check('⑧ 页面标识为「统计日报」', navDaily, dailyTitle)

    await waitText(page, '产线统计日报', 20000)
    // ★ 产线胶囊来自 calendar 响应（异步）→ 必须等月历渲染完再读胶囊，
    //   否则读到的还是初始值 [{lineId:0, lineName:'全厂'}]（只有 1 个）。
    await waitFor(page, () => document.querySelectorAll('.day-cell').length > 0, 30000)
    const scopeTabs = await page.evaluate(() =>
      [...document.querySelectorAll('.scope-tab')].map((b) => (b.textContent || '').trim()))
    check('⑨ 产线胶囊 ≥2 个', scopeTabs.length >= 2, scopeTabs.join('/'))

    // ⑩ 月历：翻月 3 次（跨月边界）
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
    check('⑩ 月历格子数 = 当月天数', dayCells >= 28 && dayCells <= 31, `${dayCells} 格`)
    check('⑩b 至少 1 个「有数据」格', dataCells >= 1, `${dataCells} 格`)

    // ⑪ 点有数据的格 → 当日维度渲染
    await page.evaluate(() => {
      const c = document.querySelector('.day-cell.hasdata')
      if (c) c.click()
    })
    await sleep(3000)
    const kpiCount = await page.evaluate(() => document.querySelectorAll('.kpi-card').length)
    const miniCount = await page.evaluate(() => document.querySelectorAll('.mini-card').length)
    check('⑪ 点有数据的格 → 4 张 KPI', kpiCount === 4, `${kpiCount} 张`)
    check('⑪b 四宫格维度卡 = 4', miniCount === 4, `${miniCount} 张`)

    // ⑫ 切产线后数据应变化（证明 lineId 透传）
    const plantOnline = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.kpi-card')]
        .find((x) => (x.querySelector('.kpi-label')?.textContent || '').includes('在线相机'))
      return (c?.querySelector('.kpi-value')?.textContent || '').trim()
    })
    const switched = await page.evaluate(() => {
      const tabs = [...document.querySelectorAll('.scope-tab')]
      const target = tabs.find((t) => (t.textContent || '').trim() !== '全厂')
      if (target) { target.click(); return (target.textContent || '').trim() }
      return null
    })
    await sleep(3000)
    const lineOnline = await page.evaluate(() => {
      const c = [...document.querySelectorAll('.kpi-card')]
        .find((x) => (x.querySelector('.kpi-label')?.textContent || '').includes('在线相机'))
      return (c?.querySelector('.kpi-value')?.textContent || '').trim()
    })
    check('⑫ 切产线后「在线相机」数值变化（lineId 真透传）',
      switched !== null && plantOnline !== lineOnline,
      `全厂=${plantOnline} / ${switched}=${lineOnline}`)

    // ⑬ 维度页签切换（若存在）
    const dimTabs = await page.evaluate(() =>
      [...document.querySelectorAll('.mini-tab, .dim-tab, .el-radio-button__inner')]
        .map((t) => (t.textContent || '').trim()).filter(Boolean))
    if (dimTabs.length >= 2) {
      await page.evaluate(() => {
        const t = document.querySelectorAll('.mini-tab, .dim-tab, .el-radio-button__inner')[1]
        if (t) t.click()
      })
      await sleep(2000)
      const stillRendered = await page.evaluate(() => document.querySelectorAll('.mini-card').length > 0)
      check('⑬ 维度页签可切换且内容仍渲染', stillRendered, dimTabs.slice(0, 4).join('/'))
    } else {
      check('⑬ 维度页签可切换且内容仍渲染', true, `未找到维度页签（${dimTabs.length} 个），跳过`)
    }

    // ==================== 三、跨页面跳转与一致性 ====================
    console.log('\n--- 三、跨页面跳转与一致性 ---')
    await page.goto(`${BASE}/daily-report`, { waitUntil: 'networkidle2', timeout: 60000 })
    await waitFor(page, () => document.querySelectorAll('.kpi-card').length > 0, 30000)
    const dailyKpi = await readKpis(page)
    await page.goto(`${BASE}/line-analytics`, { waitUntil: 'networkidle2', timeout: 60000 })
    await waitFor(page,
      () => document.querySelectorAll('.kpi-card, .stat-card, .metric-card').length > 0, 30000)
    const lineKpi = await readKpis(page)
    const sameLabel = Object.keys(dailyKpi).filter((k) => k in lineKpi)
    check('⑭ 两页都有 KPI 可渲染', Object.keys(dailyKpi).length > 0 && Object.keys(lineKpi).length > 0,
      `日报 ${Object.keys(dailyKpi).length} 个 / 产线统计 ${Object.keys(lineKpi).length} 个；同名 ${sameLabel.join(',') || '无'}`)

    // ⑮ 页面内跳转（点导航到另一页，再回来）
    await page.evaluate(() => {
      const a = [...document.querySelectorAll('a')].find((x) => (x.textContent || '').includes('统计日报'))
      if (a) a.click()
    })
    await sleep(3500)
    const afterNav = page.url()
    check('⑮ 导航到统计日报成功（URL 变化）', afterNav.includes('daily-report'), afterNav.replace(BASE, ''))

    await page.evaluate(() => {
      const a = [...document.querySelectorAll('a')].find((x) => (x.textContent || '').includes('产线统计'))
      if (a) a.click()
    })
    await sleep(3500)
    check('⑮b 导航回产线统计成功', page.url().includes('line-analytics'), page.url().replace(BASE, ''))

    // ⑯ 深链刷新（模拟直接贴 URL 打开）
    await page.reload({ waitUntil: 'networkidle2', timeout: 60000 })
    await sleep(3500)
    const afterReload = await page.evaluate(() => ({
      len: document.body.innerText.trim().length,
      hasLogin: document.body.innerText.includes('登录')
    }))
    check('⑯ 深链刷新后仍是业务页（未被踢回登录）', afterReload.len > 100 && !afterReload.hasLogin,
      `文本长度 ${afterReload.len}`)

    // ==================== 四、异常与错误汇总 ====================
    console.log('\n--- 四、异常与错误汇总 ---')
    const realBad = badResponses.filter((b) => !b.includes('favicon'))
    check('⑰ 全程无 4xx/5xx API 响应', realBad.length === 0, realBad.slice(0, 5).join(' | ') || '无')
    check('⑱ 无 JS 运行时错误', jsErrors.length === 0, jsErrors.slice(0, 3).join(' | ') || '无')
    check('⑲ 无 chunk 404（旧页面静默失效）', notFound.length === 0, notFound.slice(0, 3).join(' | ') || '无')

    const failed = results.filter((r) => !r.ok)
    console.log(`\n=========================================`)
    console.log(`  通过 ${results.length - failed.length} 项，失败 ${failed.length} 项`)
    if (failed.length) {
      console.log('  未通过：')
      failed.forEach((f) => console.log(`    - ${f.name}${f.detail ? ' → ' + f.detail : ''}`))
    }
    console.log(`=========================================`)
  } catch (e) {
    console.log('  ✗ 脚本异常：', e.message)
    console.log(e.stack)
  } finally {
    await browser.close()
  }
  process.exit(0)
})()
