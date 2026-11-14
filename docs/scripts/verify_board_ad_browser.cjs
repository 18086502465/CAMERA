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
  page.on('pageerror', e => jsErrors.push(String(e.message || e)))
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
    check('选项不是 dim_defect_type 标准类型',
      !typeOpts.some(o => /内装胶滴|前腰腹异物/.test(o)), typeOpts.slice(0, 2).join(','))

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

    // ⑥ 错误面
    const shotDir = path.resolve(__dirname, '../../.workbuddy/tmp')
    await page.screenshot({ path: path.join(shotDir, 'board-ad-full.png'), fullPage: false })
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
