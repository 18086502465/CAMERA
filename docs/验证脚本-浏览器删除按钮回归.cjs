/**
 * 最终验证：配置页相机 tab，点击 aaa 相机（id=28，无依赖）的「删除」按钮，
 * 验证：① 二次确认框正常弹出 ② 名称显示正确 ③ 确认后删除成功 ④ 有 ElMessage 提示
 */
const puppeteer = require('puppeteer-core')
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = 'http://10.2.77.72:8091'
const wait = ms => new Promise(r => setTimeout(r, ms))

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const errors = []
  const requests = []
  page.on('console', m => { if (m.type() === 'error') errors.push('[console.error] ' + m.text()) })
  page.on('pageerror', e => errors.push('[pageerror] ' + e.message))
  page.on('response', async r => {
    const u = r.url()
    if (!u.includes('/api/')) return
    let body = ''
    try { body = (await r.text()).slice(0, 220) } catch (e) { body = '<no body>' }
    requests.push(`${r.request().method()} ${u.replace(BASE, '')} -> ${r.status()}  ${body}`)
  })

  console.log('=== 1. 登录 ===')
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
  await page.waitForSelector('input[type=text]', { timeout: 20000 })
  await page.evaluate(() => {
    const setNative = (el, val) => {
      Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(el, val)
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

  console.log('=== 2. 校验 ElMessage 解析 ===')
  const msgRes = await page.evaluate(async () => {
    const names = performance.getEntriesByType('resource').map(e => e.name).filter(n => /message-.*\.js/.test(n))
    if (!names.length) return { note: '未找到 message chunk（可能已内联）' }
    const m = await import(names[0].replace(location.origin, ''))
    return { chunk: names[0].split('/').pop(), exports: Object.keys(m) }
  })
  console.log('  ', JSON.stringify(msgRes))

  console.log('=== 3. 打开配置页相机 tab，用搜索框定位 aaa ===')
  await page.goto(`${BASE}/system/config?tab=camera`, { waitUntil: 'networkidle2', timeout: 60000 })
  await wait(3500)
  // 配置页相机 tab 没有搜索框，改用分页：把 pageSize 调大找到 aaa
  const jumped = await page.evaluate(async () => {
    const app = document.querySelector('#app').__vue_app__
    return true
  })
  // 找到 aaa 所在页，并点击分页器翻到那一页
  let targetPage = 0
  for (let p = 1; p <= 3; p++) {
    const hit = await page.evaluate(async (pg) => {
      const token = localStorage.getItem('camera_token')
      const r = await fetch(`/api/cameras?page=${pg}&size=10`, { headers: { Authorization: 'Bearer ' + token } })
      const d = await r.json()
      const recs = (d.data && d.data.records) || []
      return recs.some(x => x.cameraName === 'aaa')
    }, p)
    if (hit) { targetPage = p; break }
  }
  console.log('  aaa 在第', targetPage, '页')
  if (targetPage > 1) {
    await page.evaluate((tp) => {
      const nums = [...document.querySelectorAll('.el-pagination .el-pager li')]
      const li = nums.find(x => x.textContent.trim() === String(tp))
      if (li) li.click()
    }, targetPage)
    await wait(2500)
  }
  const found = await page.evaluate(() => {
    const tr = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')]
    for (const r of tr) {
      const txt = r.innerText.replace(/\s+/g, ' ').trim()
      if (txt.includes('aaa')) {
        const btn = [...r.querySelectorAll('button')].find(b => b.textContent.trim() === '删除')
        return { ok: !!btn, text: txt }
      }
    }
    return { ok: false, total: tr.length, texts: tr.map(r => r.innerText.replace(/\s+/g, ' ').trim()).slice(0, 3) }
  })
  console.log('  aaa 行:', JSON.stringify(found))

  console.log('=== 4. 点击 aaa 行的「删除」===')
  const before = requests.length
  await page.evaluate(() => {
    const tr = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')]
    for (const r of tr) {
      if (r.innerText.includes('aaa')) {
        ;[...r.querySelectorAll('button')].find(b => b.textContent.trim() === '删除').click()
        return
      }
    }
  })
  await wait(2500)
  const box = await page.evaluate(() => {
    const vis = [...document.querySelectorAll('.el-message-box')].filter(d => d.offsetParent !== null)
    return vis.map(d => d.innerText.replace(/\s+/g, ' ').trim())
  })
  console.log('  确认框:', JSON.stringify(box, null, 2))

  console.log('=== 5. 点击「确认删除」===')
  const clicked = await page.evaluate(() => {
    const c = [...document.querySelectorAll('.el-message-box button')].find(b => b.textContent.includes('确认删除'))
    if (c) { c.click(); return true }
    return false
  })
  console.log('  点到确认:', clicked)
  await wait(4000)
  const after = await page.evaluate(() => ({
    messages: [...document.querySelectorAll('.el-message')].map(m => m.innerText.trim()),
    hasAaa: [...document.querySelectorAll('.el-table__body-wrapper tbody tr')].some(r => r.innerText.includes('aaa')),
    total: document.querySelectorAll('.el-table__body-wrapper tbody tr').length
  }))
  console.log('  ElMessage 提示:', JSON.stringify(after.messages))
  console.log('  列表中还有 aaa 吗:', after.hasAaa, '| 当前页行数:', after.total)

  console.log('=== 6. 本次网络请求 ===')
  requests.slice(before).forEach(r => console.log('  ' + r))
  console.log('=== 7. 错误 ===')
  if (!errors.length) console.log('  （无）')
  errors.forEach(e => console.log('  ' + e))
  await page.screenshot({ path: 'C:/Users/41411/AppData/Local/Temp/deletefix2.png' })
  await browser.close()
})().catch(e => { console.error('脚本异常:', e); process.exit(1) })
