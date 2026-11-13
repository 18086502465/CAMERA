/**
 * 验证：旧页面（加载旧 index.html）点击【图像记录】【系统配置】能正常跳转
 */
const puppeteer = require('puppeteer-core');
const { login } = require('./browser-helper.cjs');
const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091';
const ADMIN_PWD = process.env.CAMERA_ADMIN_PASSWORD || '';

const OLD_INDEX_HTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>产线缺陷在线AI检测系统</title>
  <script type="module" crossorigin src="/assets/index-BD6QAUp3.js"></script>
  <link rel="stylesheet" crossorigin href="/assets/index-OLD.css">
</head>
<body><div id="app"></div></body>
</html>`;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: 'C://Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: 'new',
    args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1142, height: 889 });

  const jsErrors = [];
  const failedReq = [];
  page.on('pageerror', e => jsErrors.push(e.message));
  page.on('requestfailed', r => failedReq.push(`${r.failure()?.errorText} ${r.url().replace(BASE, '')}`));
  page.on('response', r => { if (r.status() >= 400) failedReq.push(`HTTP${r.status()} ${r.url().replace(BASE, '')}`); });

  let intercepting = true;
  await page.setRequestInterception(true);
  page.on('request', (req) => {
    if (!intercepting) { return req.continue(); }
    const url = req.url();
    if (req.resourceType() === 'document' &&
        (url === BASE + '/' || url === BASE + '/index.html' || url.endsWith('/line-analytics'))) {
      return req.respond({ status: 200, contentType: 'text/html;charset=UTF-8', body: OLD_INDEX_HTML });
    }
    return req.continue();
  });

  console.log('=== 用「旧版 index.html」加载（模拟现场场景）===');
  await page.goto(BASE + '/line-analytics', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 4000));
  const loaded = await page.evaluate(() => {
    const s = document.querySelector('script[src*="/assets/index-"]');
    return s ? s.getAttribute('src') : '(无)';
  });
  console.log('  页面加载的入口:', loaded);

  console.log('\n=== 旧 chunk 可访问性（旧页面能工作的前提）===');
  for (const n of ['ImagesView-3Im0_kdo.js']) {
    const r = await page.evaluate(async (name) => {
      const res = await fetch('/assets/' + name, { cache: 'no-store' });
      return { status: res.status, size: (await res.blob()).size };
    }, n);
    console.log(`  /assets/${n} -> HTTP ${r.status} (${r.size} bytes) ${r.status === 200 ? '✓' : '✗'}`);
  }

  console.log('\n=== 新页面完整链路 ===');
  intercepting = false;
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  const lr = await login(page, 'admin', ADMIN_PWD);
  console.log('  登录:', lr.ok ? '成功' : '失败 ' + lr.error);

  await page.goto(BASE + '/line-analytics', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 5000));

  for (const t of [{ name: '图像记录', expect: '/stats/images' }, { name: '系统配置', expect: '/system/config' }]) {
    const box = await page.evaluate((name) => {
      const el = name === '系统配置'
        ? document.querySelector('.syscfg-btn')
        : Array.from(document.querySelectorAll('.tb-item')).find(e => e.textContent.includes(name));
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    }, t.name);
    if (!box) { console.log(`  ${t.name}: 未找到`); continue; }
    await page.mouse.click(box.x, box.y);
    await new Promise(r => setTimeout(r, 3500));
    const ok = page.url().includes(t.expect);
    console.log(`  点击【${t.name}】-> ${page.url()} ${ok ? '✓' : '✗'}`);
    if (ok) {
      await page.goto(BASE + '/line-analytics', { waitUntil: 'networkidle2', timeout: 60000 });
      await new Promise(r => setTimeout(r, 3500));
    }
  }

  console.log('\n=== 错误汇总 ===');
  const realFails = failedReq.filter(f => !f.includes('index-BD6QAUp3') && !f.includes('index-OLD'));
  console.log('  失败请求:', realFails.length ? realFails.join(' | ') : '无（模拟旧入口 404 已排除）');
  console.log('  JS 错误:', jsErrors.length ? jsErrors.join(' | ') : '无');
  await browser.close();
})();
