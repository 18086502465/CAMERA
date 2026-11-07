/**
 * 登录回跳流程回归（NEW-03，2026-11-06 v5 评审）
 *
 * 守的是什么
 * ----------
 * 改造前：登录失败返回 HTTP 401，而前端拦截器把**任何** 401 都当成「会话过期」：
 *   清 token → 把**当前页**写进登录回跳上下文 → 跳登录页。
 * 登录页 `/login` 不在回跳白名单里，`saveLoginContext` 会把它回退成默认落地页
 * `/camera-status`。于是这个场景稳定复现：
 *   在 /line-analytics 干活 → token 失效被踢到登录页 → 先输错一次密码（401）
 *   → 上下文被改写成 /camera-status → 改对密码登录成功 → **被送到相机状态页**，
 *     刚才看的报表页丢了。
 *
 * 现在：凭据错误返回 400（不是 401），且拦截器对 `/auth/login`、`/auth/register`
 *       不参与 401/403 分流 —— 上下文不会被污染。
 *
 * 验证点
 * ------
 *   ① 正常登录后进入 /line-analytics
 *   ② 清掉 token 后点「刷新报表」→ 接口 401 → 被送到登录页（上下文已记录 /line-analytics）
 *   ③ 输错密码 → 仍停留登录页，提示「用户名或密码错误」
 *   ④ 再输对密码 → **回到 /line-analytics**（而不是默认页 /camera-status）
 *   ⑤ 无预期外的 JS 错误
 *
 * 用法：node verify_login_flow_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login, USERNAME_SEL } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const USER = 'admin'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const TARGET = '/line-analytics'
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
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,900']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 900 })
  const jsErrors = []
  page.on('pageerror', e => jsErrors.push(String(e.message || e)))
  page.on('console', m => {
    if (m.type() === 'error') jsErrors.push(m.text())
  })

  try {
    // ---- ① 正常登录 ----
    await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 30000 })
    const first = await login(page, USER, PWD, 4500)
    check('正常登录成功', first.ok, first.url)

    // ---- 进入目标页 ----
    await page.goto(BASE + TARGET, { waitUntil: 'networkidle2', timeout: 30000 })
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('button')).some(b => /刷新报表/.test(b.textContent || '')),
      { timeout: 20000 })
    check('进入 ' + TARGET, (await page.evaluate(() => window.location.pathname)) === TARGET)

    // ---- ② 模拟会话失效：清 token → 点「刷新报表」→ 接口 401 ----
    await page.evaluate(() => localStorage.removeItem('camera_token'))
    await page.evaluate(() => {
      const btn = Array.from(document.querySelectorAll('button')).find(b => /刷新报表/.test(b.textContent || ''))
      if (btn) btn.click()
    })
    await sleep(4000)

    const kicked = await page.evaluate(() => window.location.pathname)
    check('token 失效后接口 401 → 被送到登录页', kicked === '/login', kicked)
    const ctx = await page.evaluate(() => sessionStorage.getItem('camera_login_redirect'))
    check('回跳上下文已记录原页面', !!ctx && /line-analytics/.test(ctx), ctx || '(空)')

    // ---- ③ 先输错一次密码（这是触发原 bug 的关键一步）----
    const bad = await login(page, USER, 'definitely-wrong-pwd', 3500)
    const badState = await page.evaluate(() => ({
      path: window.location.pathname,
      error: (document.querySelector('.error-msg')?.textContent || '').trim(),
      ctx: sessionStorage.getItem('camera_login_redirect') || ''
    }))
    check('错误口令后仍停留登录页', badState.path === '/login', badState.path)
    check('错误提示为「用户名或密码错误」', /用户名或密码错误/.test(badState.error), badState.error || '(空)')
    check('错误口令未污染回跳上下文（仍是 line-analytics）',
      /line-analytics/.test(badState.ctx), badState.ctx || '(空)')
    check('错误口令未登录成功', !bad.ok)

    // ---- ④ 再输对密码 → 必须回到原页面 ----
    const ok = await login(page, USER, PWD, 5000)
    const finalPath = await page.evaluate(() => window.location.pathname)
    check('正确口令登录成功', ok.ok, ok.url)
    check('登录后回到原页面 ' + TARGET, finalPath === TARGET, finalPath)
    check('未被送到默认页 /camera-status', finalPath !== '/camera-status', finalPath)

    // ---- ⑤ JS 错误（过滤掉预期的 401/400/404 网络日志）----
    // 注意：axios 的错误文本有两种形态 —— "status of 401"（老）与
    // "status code 401"（AxiosError，现网实测）；两者都是 ② 步
    // 「主动清 token 制造 401」在 console 留下的必然日志，均属预期内。
    const unexpected = jsErrors.filter(e =>
      !/favicon|404 \(Not Found\)|status of 401|status code 401|status of 400|status code 400|Failed to load resource/i.test(e))
    check('无预期外 JS 错误', unexpected.length === 0, unexpected.slice(0, 2).join(' | ') || '无')
  } catch (e) {
    check('脚本执行异常', false, String((e && e.message) || e))
  } finally {
    await browser.close()
  }

  const passed = results.filter(r => r.ok).length
  console.log(`\n${passed === results.length ? '✓' : '✗'} ${passed}/${results.length} 项通过`)
  process.exit(passed === results.length ? 0 : 1)
})()
