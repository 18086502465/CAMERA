/**
 * 全站回归：验证四类删除按钮（工厂/产线/工位/相机）+ ElMessage 提示是否正常。
 */
const puppeteer = require('puppeteer-core')
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = 'http://10.2.77.72:8091'
const wait = ms => new Promise(r => setTimeout(r, ms))

;(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox'] })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const errors = []
  page.on('console', m => { if (m.type() === 'error') errors.push('[console.error] ' + m.text()) })
  page.on('pageerror', e => errors.push('[pageerror] ' + e.message))

  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
  await page.waitForSelector('input[type=text]', { timeout: 20000 })
  await page.evaluate(() => {
    const setNative = (el, v) => {
      Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, v)
      el.dispatchEvent(new Event('input', { bubbles: true }))
    }
    setNative(document.querySelector('input[type=text]'), 'admin')
    setNative(document.querySelector('input[type=password]'), 'admin123')
  })
  await Promise.all([
    page.waitForResponse(r => r.url().includes('/api/auth/login'), { timeout: 30000 }),
    page.evaluate(() => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '登录').click())
  ])
  await wait(2500)

  const tabs = [
    { key: 'factory', label: '工厂' },
    { key: 'line', label: '产线' },
    { key: 'workstation', label: '工位' },
    { key: 'camera', label: '相机' },
    { key: 'user', label: '用户' }
  ]
  console.log('=== 逐个 tab 点击删除，检查弹窗是否出现 ===')
  for (const t of tabs) {
    await page.goto(`${BASE}/system/config?tab=${t.key}`, { waitUntil: 'networkidle2', timeout: 60000 })
    await wait(3000)
    const r = await page.evaluate(async () => {
      const tr = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')]
      if (!tr.length) return { rows: 0 }
      const btn = [...tr[0].querySelectorAll('button')].find(b => b.textContent.trim() === '删除')
      if (!btn) return { rows: tr.length, noBtn: true }
      btn.click()
      await new Promise(r => setTimeout(r, 1500))
      const vis = [...document.querySelectorAll('.el-message-box')].filter(d => d.offsetParent !== null)
      const msgs = [...document.querySelectorAll('.el-message')].map(m => m.innerText.trim())
      // 关掉弹窗
      const c = [...document.querySelectorAll('.el-message-box button')].find(b => /取消|我知道了/.test(b.textContent))
      if (c) c.click()
      return {
        rows: tr.length,
        box: vis.map(d => d.innerText.replace(/\s+/g, ' ').trim().slice(0, 150)),
        msgs
      }
    })
    console.log(`  [${t.label}] ${JSON.stringify(r)}`)
  }

  console.log('=== ElMessage 提示回归：触发一个成功提示 ===')
  await page.goto(`${BASE}/camera-status`, { waitUntil: 'networkidle2', timeout: 60000 })
  await wait(2500)
  console.log('=== 错误汇总 ===')
  if (!errors.length) console.log('  （无）')
  errors.forEach(e => console.log('  ' + e))
  await browser.close()
})().catch(e => { console.error('脚本异常:', e); process.exit(1) })
