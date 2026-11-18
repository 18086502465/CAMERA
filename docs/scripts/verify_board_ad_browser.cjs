/**
 * 产线统计页 —— 看板 A / D 浏览器回归（2026-11-14）
 *
 * 验证点：
 *   ① KPI 行已换成 4 张有区分度的卡（缺陷图片数/首要缺陷/受影响工位/较上一时段）
 *      + 「良品样本 未接入」说明卡；恒等的 NG数/缺陷数/高危 三卡已移除
 *   ② 「时段×缺陷类型热力图」渲染出 canvas（HEATMAP 形态）
 *   ③ 「班次缺陷对比」渲染出 canvas（分组柱状）
 *   ④ 缺陷类型下拉选项是现场实际类型并带图片计数（不是 dim_defect_type 标准类型）
 *   ⑤ 选缺陷类型后 KPI 收缩到该类型（筛选真正落 SQL）
 *   ⑥ 0 JS 错误、0 个 4xx/5xx 接口响应
 *
 * 用法：node docs/scripts/verify_board_ad_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const path = require('path')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = resolveChrome()
const sleep = ms => new Promise(r => setTimeout(r, ms))

let STEP = 'init'
const results = []
function check(name, ok, detail) {
  results.push({ name, ok })
  console.log(`${ok ? '  ✓' : '  ✗'} ${name}${detail ? ' → ' + detail : ''}`)
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1600,1000']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })

  const jsErrors = []
  const badResponses = []
  page.on('pageerror', e => jsErrors.push('[step=' + STEP + '] ' + String(e.message || e) + ' @ ' + String((e.stack||'').split('\n').slice(0,4).join(' | '))))
  page.on('response', r => {
    const s = r.status()
    if (s >= 400 && !/favicon/.test(r.url())) badResponses.push(`${s} ${r.url().slice(-70)}`)
  })

  try {
    await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
    await login(page, 'admin', PWD, 5000)

    await page.goto(BASE + '/line-analytics', { waitUntil: 'domcontentloaded', timeout: 30000 })
    await sleep(6000)

    // 切到「近7天」，保证时间窗内有数据（默认「今天」在某些时段可能为空）
    const clicked = await page.evaluate(() => {
      const btns = [...document.querySelectorAll('.range-shortcuts button')]
      const hit = btns.find(b => (b.textContent || '').trim() === '近7天')
      if (hit) { hit.click(); return true }
      return false
    })
    if (clicked) { console.log('  已切到「近7天」'); await sleep(5000) }

    // ★ 2026-11-17 首屏重构适配：默认进内置「工厂质量看板（默认）」——
    //   只读（隐藏组件库/配置栏）、筛选收起、图表按「结论优先」固定（无班次图）。
    //   本脚本验的是配置能力与全部筛选，所以先切到可编辑模板并展开高级筛选，
    //   后续断言因此仍跑在与历史基线相同的页面上。
    STEP = 'switch-board'
    const boardSwitched = await page.evaluate(() => {
      const sel = document.querySelector('.hero-actions .el-select')
      if (!sel) return false
      ;(sel.querySelector('.el-select__wrapper') || sel).click()
      return true
    })
    await sleep(900)
    const pickedBoard = await page.evaluate(() => {
      const items = [...document.querySelectorAll('.el-select-dropdown__item')]
      const t = items.find(i => (i.textContent || '').includes('厂长视角'))
      if (t) { t.click(); return (t.textContent || '').trim() }
      return null
    })
    await sleep(6000)
    check('可切到用户模板（配置能力仍可用）', boardSwitched && !!pickedBoard, pickedBoard || '未找到可编辑模板')

    const drawerOpened = await page.evaluate(() => {
      const b = [...document.querySelectorAll('.filter-card button')]
        .find(x => (x.textContent || '').includes('高级筛选'))
      if (b) { b.click(); return true }
      return false
    })
    await sleep(1500)
    check('高级筛选可展开', drawerOpened)

    STEP = 'kpi'
    // ① KPI 卡
    const labels = await page.$$eval('.kpi-card span', els => els.map(e => (e.textContent || '').trim()))
    console.log('  KPI 标签：', labels.join(' | '))
    check('KPI 含「缺陷图片数」', labels.includes('缺陷图片数'))
    check('KPI 含「首要缺陷」', labels.includes('首要缺陷'))
    check('KPI 含「受影响工位」', labels.includes('受影响工位'))
    check('KPI 含「较上一时段」', labels.includes('较上一时段'))
    check('KPI 保留「良品样本 未接入」说明卡', labels.includes('良品样本'))
    check('旧恒等三卡已移除（NG数/缺陷数/高危缺陷）',
      !labels.includes('NG数') && !labels.includes('缺陷数') && !labels.includes('高危缺陷'))

    STEP = 'charts'
    // ②③ 图表卡片与画布
    const cards = await page.$$eval('.chart-card', els => els.map(e => ({
      title: (e.querySelector('.card-head h4')?.textContent || '').trim(),
      canvases: e.querySelectorAll('canvas').length,
      w: e.querySelector('canvas')?.width || 0,
      h: e.querySelector('canvas')?.height || 0,
      empty: /暂无数据|无数据/.test(e.textContent || '')
    })))
    console.log('  图表卡片：')
    cards.forEach(c => console.log(`    - ${c.title} canvas=${c.canvases} ${c.w}x${c.h}${c.empty ? ' [空]' : ''}`))

    const heat = cards.find(c => c.title.includes('热力图'))
    const shift = cards.find(c => c.title.includes('班次'))
    check('热力图卡片存在且出图', !!heat && heat.canvases > 0 && !heat.empty,
      heat ? `canvas=${heat.canvases} ${heat.w}x${heat.h}` : '未找到')
    check('热力图为整行大画布（宽>700）', !!heat && heat.w > 700, heat ? String(heat.w) : '-')
    check('班次对比卡片存在且出图', !!shift && shift.canvases > 0 && !shift.empty,
      shift ? `canvas=${shift.canvases} ${shift.w}x${shift.h}` : '未找到')

    STEP = 'defect-type-dropdown'
    // ④ 缺陷类型下拉：实际类型 + 计数
    const placeholders = await page.evaluate(() => {
      const selects = [...document.querySelectorAll('.filter-card .el-select')]
      return selects.map(s => (s.textContent || '').trim())
    })
    console.log('  筛选下拉占位：', placeholders.join(' | '))

    // 打开缺陷类型下拉
    await page.evaluate(() => {
      const selects = [...document.querySelectorAll('.filter-card .el-select')]
      const hit = selects.find(s => /全部缺陷类型/.test(s.textContent || ''))
      if (hit) hit.click()
    })
    await sleep(1200)
    // 注意：body 里可能同时残留其它下拉的选项，只取「类型名（计数）」形态的项
    const allOpts = await page.$$eval('.el-select-dropdown__item', els => els.map(e => (e.textContent || '').trim()))
    const typeOpts = allOpts.filter(o => /（\d+）$/.test(o))
    console.log('  缺陷类型选项：', typeOpts.join(' | '))
    check('缺陷类型选项带图片计数', typeOpts.length >= 5, `带计数 ${typeOpts.length} 个`)
    check('选项为现场实际类型（含「接头」）', typeOpts.some(o => o.startsWith('接头')), typeOpts.slice(0, 3).join(','))
    // 原断言「选项不是标准类型」基于错误前提：filter-options 返回的是「现场实际上报 ∪
    // dim_defect_type 标准类型」，两者都会出现。真正该锁的是「现场实际类型必须在列且带计数」，
    // 否则用户选「接头」会命中 0 张图（这正是 2026-11-14 修过的 bug）。
    check('选项含现场实际类型（接头 / 小污点）',
      typeOpts.some(o => o.startsWith('接头')) && typeOpts.some(o => o.startsWith('小污点')),
      typeOpts.slice(0, 3).join(','))

    STEP = 'filter-apply'
    // ⑤ 选「接头」→ KPI 收缩（选项计数是**全时段**口径，当前筛选是时间窗，故只比方向与首要缺陷）
    const before = await page.$$eval('.kpi-card strong', els => els.map(e => (e.textContent || '').trim()))
    const picked = await page.evaluate(() => {
      const items = [...document.querySelectorAll('.el-select-dropdown__item')]
      const hit = items.find(i => (i.textContent || '').trim().startsWith('接头'))
      if (hit) { hit.click(); return (hit.textContent || '').trim() }
      return ''
    })
    if (picked) {
      await sleep(5000)
      const after = await page.$$eval('.kpi-card strong', els => els.map(e => (e.textContent || '').trim()))
      console.log(`  筛「${picked}」前 KPI：${before.join(' / ')}`)
      console.log(`  筛「${picked}」后 KPI：${after.join(' / ')}`)
      const beforeNum = Number((before[0] || '').replace(/[^\d]/g, ''))
      const afterNum = Number((after[0] || '').replace(/[^\d]/g, ''))
      check('筛选缺陷类型后图片数收缩', afterNum > 0 && afterNum < beforeNum, `${beforeNum} → ${afterNum}`)
      check('KPI「首要缺陷」随筛选收敛为「接头」', after[1] === '接头', String(after[1]))
    } else {
      check('缺陷类型下拉可选中「接头」', false, '未找到选项')
    }

    STEP = 'filter-hint'
    // ⑦ P0-1：图片通道下「产品」筛选物理上不生效，必须就地标注（静默吞掉才是最伤信任的）
    const filterHint = await page.evaluate(() => {
      const box = document.querySelector('.filter-with-hint')
      return box ? (box.textContent || '').includes('不生效') : false
    })
    check('产品筛选标注「不生效」（图片通道）', filterHint === true)

    STEP = 'palette'
    // ⑧ P2：组件库出现占比饼图 / 日历热力图两种新形态
    const palette = await page.$$eval('.widget-palette .widget-btn b', els => els.map(e => (e.textContent || '').trim()))
    console.log('  组件库：', palette.join(' | '))
    check('组件库含「占比饼图」', palette.includes('占比饼图'))
    check('组件库含「日历热力图」', palette.includes('日历热力图'))

    STEP = 'wizard'
    // ⑨ P1：向导里隐藏「当前数据源下无意义」的指标入口，并给出说明
    await page.evaluate(() => {
      const btn = [...document.querySelectorAll('.canvas-actions button')].find(b => (b.textContent || '').trim() === '新增图表')
      if (btn) btn.click()
    })
    await sleep(1600)
    await page.evaluate(() => {
      const item = [...document.querySelectorAll('.el-dialog .el-form-item')]
        .find(i => /统计指标/.test(i.textContent || ''))
      const sel = item && item.querySelector('.el-select')
      if (sel) sel.click()
    })
    await sleep(1200)
    const metricOpts = await page.$$eval('.el-select-dropdown__item', els => els.map(e => (e.textContent || '').trim()))
    console.log('  指标下拉：', metricOpts.join(' | '))
    check('指标下拉已隐藏「核心质量指标」', !metricOpts.some(o => o.includes('核心质量指标')))
    check('指标下拉已隐藏「产品缺陷透视表」', !metricOpts.some(o => o.includes('产品缺陷透视表')))
    check('指标下拉含「缺陷类型占比」「日缺陷日历」',
      metricOpts.some(o => o.includes('缺陷类型占比')) && metricOpts.some(o => o.includes('日缺陷日历')))
    const hiddenNotice = await page.$eval('.metric-hidden-hint', el => (el.textContent || '').trim()).catch(() => '')
    check('给出隐藏指标的原因说明', /已隐藏/.test(hiddenNotice), hiddenNotice.slice(0, 40))

    STEP = 'preview-new-shapes'
    // ⑩ 两个新形态能真正出图（直接在指标下拉里切换，避免 Esc 关掉整个对话框）
    const pickMetric = async name => {
      await page.evaluate(() => {
        const item = [...document.querySelectorAll('.el-dialog .el-form-item')]
          .find(i => /统计指标/.test(i.textContent || ''))
        const sel = item && item.querySelector('.el-select')
        if (sel) sel.click()
      })
      await sleep(900)
      return page.evaluate(text => {
        // 只看当前可见的下拉浮层，避免点到其它 select 残留的 popper
        const poppers = [...document.querySelectorAll('.el-select-dropdown')].filter(p => p.offsetParent !== null)
        const scope = poppers.length ? poppers[poppers.length - 1] : document
        const hit = [...scope.querySelectorAll('.el-select-dropdown__item')]
          .find(i => (i.textContent || '').includes(text))
        if (hit) { hit.click(); return (hit.textContent || '').trim() }
        return ''
      }, name)
    }
    const piePick = await pickMetric('缺陷类型占比')
    await sleep(2200)
    const pieW = await page.$eval('.preview-panel canvas', el => el.width).catch(() => 0)
    check('「缺陷类型占比」预览出图', pieW > 0, `${piePick} → canvas w=${pieW}`)
    const calPick = await pickMetric('日缺陷日历')
    await sleep(2200)
    const calW = await page.$eval('.preview-panel canvas', el => el.width).catch(() => 0)
    check('「日缺陷日历」预览出图', calW > 0, `${calPick} → canvas w=${calW}`)
    await page.evaluate(() => {
      const b = [...document.querySelectorAll('.el-dialog__footer button')].find(x => (x.textContent || '').trim() === '取消')
      if (b) b.click()
    })
    await sleep(800)

    // ⑥ 错误面
    const shotDir = path.resolve(__dirname, '../../.workbuddy/tmp')
    await page.screenshot({ path: path.join(shotDir, 'board-ad-full.png'), fullPage: false })
    if (jsErrors.length) {
      const hist = await page.evaluate(() => (window.__chartHistory || []).slice(-8)).catch(() => [])
      console.log('  最近渲染历史（诊断）：')
      hist.forEach(h => console.log(`    - ${h.label} | ${h.series} | n=${h.n}`))
    }
    check('0 JS 错误', jsErrors.length === 0, jsErrors.slice(0, 2).join(' ; '))
    check('无 4xx/5xx 接口响应', badResponses.length === 0, badResponses.slice(0, 3).join(' ; '))
  } catch (e) {
    console.error('脚本异常：', e.message)
    check('脚本执行完成', false, e.message)
  } finally {
    await browser.close()
    const failed = results.filter(r => !r.ok)
    console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
    if (failed.length) {
      console.log('未通过：')
      failed.forEach(f => console.log('  - ' + f.name))
      process.exit(1)
    }
  }
})()
