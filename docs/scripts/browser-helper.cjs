/**
 * 浏览器验证共用辅助函数
 * ============================================================
 * 踩坑记录（2026-11-04）：
 *   1. `page.evaluate` 里用 `HTMLInputElement.prototype.value` 的 setter
 *      直接赋值 **不可靠** —— Vue 的 v-model 会在 input 事件里把值同步回去，
 *      表现为「setter 赋了值但读出来是空」。
 *   2. `page.type()` 会**追加**而非覆盖 —— 登录页用户名框有默认值 'admin'，
 *      直接 type 会得到 'adminadmin'。
 *   3. 三击选中（clickCount:3）在该环境不生效。
 *
 * 可靠做法：**focus + Ctrl+A + Backspace + type**（真实键盘事件）。
 */

const fs = require('fs')

const USERNAME_SEL = 'input[placeholder="请输入用户名"]'
const PASSWORD_SEL = 'input[placeholder="请输入密码"]'

/** 各平台常见安装位置（puppeteer-core 不自带浏览器，必须外挂一个） */
const CHROME_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
]

/**
 * 解析 Chrome/Chromium 可执行文件路径。
 *
 * 优先 `PUPPETEER_CHROME` 环境变量（换机器只需设这一项），否则按平台常见路径探测。
 * 全部失败时**在启动浏览器之前**抛出可执行的提示——否则 puppeteer 只会抛
 * ERR_LAUNCH 之类看不出原因的错，换机器时极难排查。
 */
function resolveChrome() {
  const envPath = process.env.PUPPETEER_CHROME
  if (envPath) {
    if (fs.existsSync(envPath)) return envPath
    throw new Error(
      'PUPPETEER_CHROME 指向的文件不存在：' + envPath + '\n' +
      '请改成真实的浏览器可执行文件路径。'
    )
  }
  for (const p of CHROME_CANDIDATES) {
    try {
      if (fs.existsSync(p)) return p
    } catch (_) {
      // 无权限访问的候选路径直接跳过
    }
  }
  throw new Error(
    '未找到 Chrome/Chromium。这些回归脚本用 puppeteer-core，不自带浏览器。\n' +
    '请先安装 Chrome，或设置环境变量 PUPPETEER_CHROME 指向可执行文件：\n' +
    '  Windows: set PUPPETEER_CHROME=C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\n' +
    '  Linux:   export PUPPETEER_CHROME=/usr/bin/google-chrome\n' +
    '依赖安装：cd docs/scripts && npm ci'
  )
}

/** 清空并输入某个框 */
async function fillInput(page, selector, text) {
  // ⚠️ 三个实测踩坑：
  //   1. 必须先 click（Element Plus 输入框有 overlay，只 focus 不可靠）
  //   2. 清空要用 Ctrl+A + Backspace（三击选中在该环境不生效）
  //   3. 输入必须用 **page.keyboard.type()** ——
  //      page.type(selector, ...) 在 password 类型输入框上会静默失效（值留空）
  const el = await page.$(selector)
  if (!el) throw new Error('未找到输入框: ' + selector)
  await el.click()
  await page.keyboard.down('Control')
  await page.keyboard.press('KeyA')
  await page.keyboard.up('Control')
  await page.keyboard.press('Backspace')
  if (text) {
    await page.keyboard.type(text, { delay: 30 })
  }
  // ⚠️ 校验：某些输入框（password 类型）会静默吞掉键盘输入，
  //    这里读回来确认真的写进去了，没写进去就再补一次。
  const actual = await page.evaluate((sel) => {
    const el = document.querySelector(sel)
    return el ? el.value : ''
  }, selector)
  if (actual !== text) {
    // 补一次：直接派发 input 事件写入（Vue v-model 会同步）
    await page.evaluate((sel, val) => {
      const el = document.querySelector(sel)
      if (!el) return
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      setter.call(el, val)
      el.dispatchEvent(new Event('input', { bubbles: true }))
      el.dispatchEvent(new Event('change', { bubbles: true }))
    }, selector, text)
  }
}

/** 读取当前所有输入框的值 */
async function inputValues(page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('input')).map(i => i.value))
}

/**
 * 在登录页完成登录。
 * @param {import('puppeteer-core').Page} page
 * @param {string} username
 * @param {string} password
 * @param {number} [waitMs] 登录后等待时间
 * @returns {Promise<{ok: boolean, url: string, values: string[], error: string}>}
 */
async function login(page, username, password, waitMs = 4500) {
  await page.waitForSelector(USERNAME_SEL, { timeout: 20000 })
  await fillInput(page, USERNAME_SEL, username)
  await fillInput(page, PASSWORD_SEL, password)
  const values = await inputValues(page)

  await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('button'))
      .find(b => /登录|登 录|login/i.test(b.textContent || ''))
    if (btn) btn.click()
  })
  await new Promise(r => setTimeout(r, waitMs))

  const state = await page.evaluate(() => ({
    url: window.location.href,
    token: localStorage.getItem('camera_token') || '',
    error: document.querySelector('.error-msg')?.textContent?.trim() || ''
  }))

  return {
    ok: !!state.token && !state.url.includes('/login'),
    url: state.url,
    values,
    error: state.error
  }
}

module.exports = { fillInput, inputValues, login, resolveChrome, USERNAME_SEL, PASSWORD_SEL }
