/**
 * 相机类型隔离 —— 前端浏览器回归（2026-11-05）
 *
 * 验证点：
 *   ① 配置页相机列表出现「相机类型」列，且真实/测试相机标签正确
 *   ② 相机编辑弹窗出现「相机类型」下拉，且回填为后端返回的值
 *   ③ 页面无 JS 错误
 *
 * 用法：node verify_camera_type_browser.cjs
 * 环境变量：BASE_URL（默认 http://10.2.77.72:8091）、ADMIN_PWD（默认 admin123）
 */
const puppeteer = require('puppeteer-core')
const { login } = require('./browser-helper.cjs')

const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091'
const USER = 'admin'
const PWD = process.env.ADMIN_PWD || 'admin123'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? '  ✓' : '  ✗'} ${name}${detail ? ' → ' + detail : ''}`)
}

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  })
  const page = await browser.newPage()
  const jsErrors = []
  const notFound = []
  page.on('pageerror', (e) => jsErrors.push(e.message))
  page.on('console', (m) => { if (m.type() === 'error') jsErrors.push(m.text()) })
  // ★ 404 单独收集：favicon 是浏览器自动请求的，404 属正常，不该算作页面错误。
  //   console 的 "Failed to load resource" 消息里**不含 URL**，无法据此过滤，
  //   所以必须用 response 事件拿 URL。
  page.on('response', (r) => { if (r.status() >= 400) notFound.push(r.url()) })

  try {
    // 登录（用 browser-helper 的可靠实现：placeholder 选择器 + 真实键盘事件）
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 60000 })
    const lg = await login(page, USER, PWD)
    check('登录成功', lg.ok, lg.ok ? lg.url : `url=${lg.url} err=${lg.error}`)

    // 进入系统配置 → 相机菜单
    await page.goto(`${BASE}/system/config?tab=camera`, { waitUntil: 'networkidle2', timeout: 60000 })
    await new Promise((r) => setTimeout(r, 3000))

    // ① 相机类型列
    const headers = await page.$$eval('.el-table__header th', (ths) =>
      ths.map((t) => (t.textContent || '').trim()))
    check('相机列表有「相机类型」列', headers.some((h) => h.includes('相机类型')),
      headers.filter((h) => h.includes('相机')).join(' | '))

    // 标签内容：真实产线 / 测试相机
    const tags = await page.$$eval('.el-table__body .el-tag', (els) =>
      els.map((e) => (e.textContent || '').trim()))
    const realCount = tags.filter((t) => t.includes('真实产线')).length
    const testCount = tags.filter((t) => t.includes('测试相机')).length
    check('存在「真实产线」标签', realCount > 0, `真实=${realCount}`)
    check('存在「测试相机」标签', testCount > 0, `测试=${testCount}`)

    // ② 编辑弹窗：相机类型下拉 + 回填
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'))
      const edit = btns.find((b) => (b.textContent || '').includes('编辑'))
      if (edit) edit.click()
    })
    await new Promise((r) => setTimeout(r, 1500))

    const dialogText = await page.evaluate(() => {
      const d = document.querySelector('.el-dialog')
      return d ? (d.textContent || '') : ''
    })
    check('编辑弹窗打开', dialogText.length > 0, dialogText.slice(0, 30))
    check('弹窗有「相机类型」下拉', dialogText.includes('相机类型'))

    const typeSelectValue = await page.evaluate(() => {
      const items = Array.from(document.querySelectorAll('.el-dialog .el-form-item'))
      const item = items.find((i) => (i.textContent || '').includes('相机类型'))
      if (!item) return null
      // ★ Element Plus 的 el-select 里有**多个** .el-select__selected-item：
      //   一个是隐藏的搜索框容器（is-hidden，文本恒空），一个是真正显示值的。
      //   所以要遍历全部取第一个非空文本；取 input 会永远拿到空串。
      const selected = Array.from(item.querySelectorAll('.el-select__selected-item'))
        .map((e) => (e.textContent || '').trim())
        .find((t) => !!t)
      if (selected) return selected
      const input = item.querySelector('input')
      return input ? input.value : null
    })
    check('相机类型已回填具体值', !!typeSelectValue, typeSelectValue || '(空)')

    // ③ JS 错误（favicon 404 属正常，忽略）
    //   console 的 "Failed to load resource" 消息里不含 URL，无法据此判断是否 favicon，
    //   而 404 已由上面的 response 检查单独覆盖 → 这里只过滤该类资源加载消息，
    //   真正的 JS 异常（pageerror）一律算失败。
    const realErrors = jsErrors.filter(
      (e) => !/favicon/i.test(e) && !/Failed to load resource/i.test(e))
    const real404 = notFound.filter((u) => !/favicon/i.test(u))
    check('无 JS 错误', realErrors.length === 0, realErrors.slice(0, 2).join(' | ') || '无')
    check('无 404 资源（favicon 除外）', real404.length === 0, real404.slice(0, 2).join(' | ') || '无')
  } catch (e) {
    check('执行过程无异常', false, e.message)
  } finally {
    await browser.close()
  }

  const failed = results.filter((r) => !r.ok)
  console.log(`\n${failed.length === 0 ? '✓' : '✗'} ${results.length - failed.length}/${results.length} 项通过`)
  process.exit(failed.length === 0 ? 0 : 1)
})()
