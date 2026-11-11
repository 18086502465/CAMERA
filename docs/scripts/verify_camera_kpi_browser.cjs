/**
 * 相机状态页 KPI 文案回归：确认「累计图片/累计高危缺陷」已生效，
 * 且数值与 camera-summaries 接口一致（文案改了但取数没动）。
 * 用法：node verify_camera_kpi_browser.cjs      （SHOT=/tmp/kpi.png 可另存截图）
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const sleep = ms => new Promise(r => setTimeout(r, ms))

const results = []
const check = (name, ok, detail) => {
  results.push({ name, ok })
  console.log(`${ok ? '  ✓' : '  ✗'} ${name}${detail ? ' → ' + detail : ''}`)
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,900']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  const jsErrors = []
  page.on('pageerror', e => jsErrors.push(String(e.message || e)))

  try {
    await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
    await login(page, 'admin', PWD, 5000)
    await page.goto(BASE + '/camera-status', { waitUntil: 'networkidle2', timeout: 30000 })
    await sleep(3500)

    const labels = await page.$$eval('.kpi span', els => els.map(e => (e.textContent || '').trim()))
    console.log('  KPI 标签：', labels.join(' | '))
    check('出现「累计图片」', labels.includes('累计图片'))
    check('出现「累计高危缺陷」', labels.includes('累计高危缺陷'))
    check('不再出现「近批次图片」', !labels.includes('近批次图片'))

    const note = await page.$eval('.kpi-note', e => (e.textContent || '').trim()).catch(() => '')
    check('口径说明可见', note.includes('全部历史'), note.slice(0, 40))

    const values = await page.$$eval('.kpi strong', els => els.map(e => (e.textContent || '').trim()))
    const imgCount = Number(values[labels.indexOf('累计图片')]) || 0
    const highCount = Number(values[labels.indexOf('累计高危缺陷')]) || 0
    check('累计图片有数值', imgCount > 0, String(imgCount))
    check('累计高危缺陷有数值', highCount > 0, String(highCount))

    // 与接口对账：不传时间参数 → 应完全一致
    const lr = await fetch(BASE + '/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: PWD })
    })
    const tok = ((await lr.json()).data || {}).token
    const sr = await fetch(BASE + '/api/v1/defect-images/camera-summaries?latestLimit=1', {
      headers: { Authorization: 'Bearer ' + tok }
    })
    const list = ((await sr.json()).data) || []
    const api = {
      images: list.reduce((s, x) => s + (Number(x.imageCount) || 0), 0),
      highs: list.reduce((s, x) => s + (Number(x.highCount) || 0), 0)
    }
    console.log('  接口按相机构成：')
    list.slice().sort((a, b) => (b.imageCount || 0) - (a.imageCount || 0)).forEach(x => {
      console.log(`    ${x.cameraCode || '-'} ${x.cameraName || ''} 图=${x.imageCount} 高危=${x.highCount}`)
    })
    // 现场每秒都有新图上传，页面与接口取值时刻不同 → 只比量级，允许漂移
    const drift = Math.abs(api.images - imgCount)
    check('接口与页面同口径（漂移 ≤ 30）', drift <= 30, `接口 ${api.images} / 页面 ${imgCount} / 差 ${drift}`)
    check('接口高危数 = 页面高危数', api.highs === highCount, `接口 ${api.highs} / 页面 ${highCount}`)

    check('无 JS 报错', jsErrors.length === 0, jsErrors.slice(0, 2).join(' / '))
    if (process.env.SHOT) {
      await page.screenshot({ path: process.env.SHOT, fullPage: false })
      console.log('  截图：' + process.env.SHOT)
    }
  } catch (e) {
    check('执行未抛异常', false, String(e.message || e))
  } finally {
    await browser.close()
    const fail = results.filter(r => !r.ok).length
    console.log(`\n  ${results.length - fail}/${results.length} 通过${fail ? `，${fail} 失败` : ''}`)
    process.exit(fail ? 1 : 0)
  }
})()
