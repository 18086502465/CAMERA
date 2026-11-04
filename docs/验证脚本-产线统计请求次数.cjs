/**
 * 实测：产线统计页面进入时的请求次数（验证 C4「独立图表数据重复请求」）
 * 用法：node docs/验证脚本-产线统计请求次数.cjs
 */
const puppeteer = require('puppeteer-core');
const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091';
// ★ 应用口令从环境变量读取（勿把真实口令写进仓库）
const ADMIN_PWD = process.env.CAMERA_ADMIN_PASSWORD || '';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH, headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 900 });

  const reqs = [];
  page.on('request', r => {
    const u = r.url();
    if (u.includes('/analytics/line-summary')) reqs.push(u.replace(BASE, ''));
  });

  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input', { timeout: 20000 });
  await page.evaluate((pwd) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    const els = document.querySelectorAll('input');
    setter.call(els[0], 'admin'); els[0].dispatchEvent(new Event('input', { bubbles: true }));
    setter.call(els[1], pwd); els[1].dispatchEvent(new Event('input', { bubbles: true }));
  }, ADMIN_PWD);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.evaluate(() => {
      const btn = document.querySelector('button[type=submit]') || document.querySelector('.el-button--primary');
      if (btn) btn.click();
    }),
  ]);

  reqs.length = 0;
  await page.goto(BASE + '/line-analytics', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 6000));
  console.log('当前 URL:', page.url());

  console.log('=== 进入产线统计页后的 summary 请求 ===');
  console.log('总次数:', reqs.length);
  reqs.forEach((u, i) => console.log(`  ${i + 1}. ${u}`));

  // 按参数归类
  const groups = {};
  reqs.forEach(u => {
    const q = u.split('?')[1] || '';
    const key = q ? '带筛选参数' : '无参数';
    groups[key] = (groups[key] || 0) + 1;
  });
  console.log('归类:', JSON.stringify(groups));

  // 检查是否有重复的完全相同请求
  const uniq = new Set(reqs);
  console.log('唯一请求:', uniq.size, ' 重复次数:', reqs.length - uniq.size);
  if (reqs.length > uniq.size) {
    const seen = {};
    reqs.forEach(u => { seen[u] = (seen[u] || 0) + 1; });
    Object.entries(seen).filter(([, c]) => c > 1).forEach(([u, c]) => console.log(`  重复 x${c}: ${u}`));
  }

  await browser.close();
})().catch(e => { console.error('执行失败:', e); process.exit(1); });
