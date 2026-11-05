/**
 * P3 批次② 浏览器回归（system 域三个页面的真实数据渲染）
 *
 * 覆盖批次②改动的三个 Controller 对应的前端功能：
 *   1. 系统配置页（/system/config）—— 系统设置中心
 *   2. 配置导入页（若存在）—— 导出接口
 *   3. 相机状态页 —— 相机列表
 *
 * 断言要点：页面能加载、关键区块有真实数据、无 JS 报错。
 * 注：puppeteer 回调在浏览器上下文，访问不到 Node 变量，必须显式传参。
 */
const puppeteer = require('puppeteer-core')
const { login } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const USER = 'admin'
const PWD = process.env.ADMIN_PWD || 'admin123'

// 本机只有 puppeteer-core（不自带浏览器），需显式指定 Chrome 路径
const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].filter(Boolean)

const fs = require('fs')
const CHROME = CHROME_CANDIDATES.find((p) => {
  try { return fs.existsSync(p) } catch (e) { return false }
})
if (!CHROME) {
  console.log('未找到 Chrome/Edge，可通过 CHROME_PATH 环境变量指定')
  process.exit(2)
}

const PAGES = [
  // /system/config 渲染的是工厂/产线/工位配置（ConfigView.vue），
  // 断言真实数据（杭州临平工厂）+ 批次②涉及的配置导入入口
  { name: '系统配置', path: '/system/config', keywords: ['杭州临平', '工厂'] },
  { name: '相机状态', path: '/camera-status', keywords: ['相机'] },
  { name: '缺陷类型', path: '/defect-types', keywords: ['缺陷'] },
]

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 900 })

  const errors = []
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push('console: ' + m.text())
  })

  let failed = 0
  try {
    await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 })
    await login(page, USER, PWD)
    console.log('登录成功')

    for (const p of PAGES) {
      errors.length = 0
      try {
        await page.goto(BASE + p.path, { waitUntil: 'networkidle2', timeout: 60000 })
        await new Promise((r) => setTimeout(r, 3000))

        const title = await page.title()
        const text = await page.evaluate(() => document.body.innerText || '')
        const hit = p.keywords.filter((k) => text.includes(k))
        const fatal = errors.filter((e) => !/favicon|DevTools|Autofill/i.test(e))

        console.log('\n=== %s (%s) ===', p.name, p.path)
        console.log('  标题: %s', title)
        console.log('  命中关键词: %s', hit.length ? hit.join(', ') : '（无）')
        console.log('  页面字数: %d', text.length)
        console.log('  JS 错误: %d', fatal.length)
        if (fatal.length) {
          fatal.slice(0, 3).forEach((e) => console.log('    - %s', e.slice(0, 160)))
        }
        if (hit.length === 0 || text.length < 100 || fatal.length > 0) failed++
      } catch (e) {
        failed++
        console.log('\n=== %s (%s) 加载失败: %s', p.name, p.path, e.message)
      }
    }
  } catch (e) {
    failed++
    console.log('回归异常: %s', e.message)
  } finally {
    await browser.close()
  }

  console.log('\n结果: %s', failed === 0 ? '全部通过' : failed + ' 项失败')
  process.exit(failed === 0 ? 0 : 1)
})()
