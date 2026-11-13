/**
 * 服务器授权页回归（2026-11-12 新增）。
 *
 * 冻结的是一组「改了文案/改了解析就会静默失效」的点：
 *  - 页面能打开且标题正确（路由 + SPA 白名单是否漏配）
 *  - 状态横幅、有效期、机器码、过期策略都渲染出来
 *  - 机器码格式为 16 位大写十六进制（或明确显示「无法采集」，不能是空白）
 *  - 页面显示的状态与 /api/license 返回的 status 对得上（防止前端自己编状态）
 *  - 管理员能看到上传区
 *  - 0 个 JS 错误（chunk 缺失/接口 500 会在这里暴露）
 *
 * 用法：node verify_license_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）、SHOT（截图保存路径）
 */
const puppeteer = require('puppeteer-core')
const { login, resolveChrome } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = resolveChrome()
const sleep = ms => new Promise(r => setTimeout(r, ms))

const STATUS_TEXT = {
  DISABLED: '授权校验已关闭',
  MISSING: '未安装授权',
  INVALID: '授权无效',
  MACHINE_MISMATCH: '授权与本机不符',
  NOT_YET: '授权尚未生效',
  VALID: '授权正常',
  EXPIRING: '授权即将到期',
  EXPIRED: '授权已过期'
}

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
    await page.goto(BASE + '/system/license', { waitUntil: 'networkidle2', timeout: 30000 })
    await sleep(3000)

    // 1. 标题
    const title = await page.$eval('.page-head h2', e => (e.textContent || '').trim()).catch(() => '')
    check('页面标题包含「服务器授权」', title.includes('服务器授权'), title)

    // 2. 状态横幅
    const banner = await page.$('.status-banner')
    check('状态横幅已渲染', !!banner)

    const statusTitle = await page.$eval('.status-title', e => (e.textContent || '').trim()).catch(() => '')
    check('状态标题非空', statusTitle.length > 0, statusTitle)

    // 3. 授权信息：到期日期
    const labels = await page.$$eval('.el-descriptions__label', els => els.map(e => (e.textContent || '').trim()))
    check('出现「到期日期」', labels.includes('到期日期'))
    check('出现「过期策略」', labels.includes('过期策略'))

    // 4. 机器码（16 位大写十六进制，或明确提示无法采集）
    const mc = await page.$eval('.mc-value .mono.big', e => (e.textContent || '').trim()).catch(() => '')
    const mcOk = /^[0-9A-F]{16}$/.test(mc) || mc === '无法采集'
    check('机器码格式合法', mcOk, mc || '(空)')

    // 5. 策略取值合法
    const bodyText = await page.$eval('body', e => (e.textContent || ''))
    const modeOk = /warn|readonly|block/.test(bodyText)
    check('过期策略取值可见', modeOk)

    // 6. 管理员上传区
    const uploadBtn = await page.evaluate(() =>
      Array.from(document.querySelectorAll('button')).some(b => (b.textContent || '').includes('上传并生效')))
    check('管理员可见上传入口', uploadBtn)

    // 7. 与接口对账
    const lr = await fetch(BASE + '/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: PWD })
    })
    const tok = ((await lr.json()).data || {}).token
    const ar = await fetch(BASE + '/api/license', { headers: { Authorization: 'Bearer ' + tok } })
    const apiStatus = (((await ar.json()).data) || {}).status
    const expectText = STATUS_TEXT[apiStatus] || ''
    const consistent = !!expectText && statusTitle === expectText
    check('页面状态与接口一致', consistent, `接口=${apiStatus} 期望文案=${expectText || '?'} 实际=${statusTitle}`)

    // 8. JS 错误
    check('无 JS 错误', jsErrors.length === 0, jsErrors.slice(0, 2).join(' | '))

    if (process.env.SHOT) {
      await page.screenshot({ path: process.env.SHOT, fullPage: true })
      console.log('  截图：', process.env.SHOT)
    }
  } catch (e) {
    check('执行未抛异常', false, String(e.message || e))
  } finally {
    await browser.close()
  }

  const pass = results.filter(r => r.ok).length
  console.log(`\n结果：${pass}/${results.length} 通过`)
  process.exit(pass === results.length ? 0 : 1)
})()
