/**
 * 实测：产线统计页面默认看板的实际渲染（图表数量 / 名称 / 指标）
 * 用法：node docs/验证脚本-产线统计看板渲染.cjs
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
  await page.setViewport({ width: 1600, height: 1000 });

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

  await page.goto(BASE + '/line-analytics', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 7000));

  const info = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.chart-grid .chart-card'));
    const kpis = Array.from(document.querySelectorAll('.kpi-card')).map(k => ({
      label: (k.querySelector('span')?.textContent || '').trim(),
      value: (k.querySelector('strong')?.textContent || '').trim(),
      hint: (k.querySelector('small')?.textContent || '').trim(),
    }));
    const dashboardName = document.querySelector('.canvas-head h3')?.textContent?.trim() || '';
    const widgets = Array.from(document.querySelectorAll('.widget-palette .widget-btn')).map(b => (b.querySelector('b')?.textContent || '').trim());
    const hint = document.querySelector('.source-hint')?.textContent?.replace(/\s+/g, ' ').trim() || '';
    return {
      dashboardName,
      chartCount: cards.length,
      chartTitles: cards.map(c => ({
        name: (c.querySelector('h4')?.textContent || '').trim(),
        sub: (c.querySelector('.card-head span')?.textContent || '').trim(),
        hasChart: !!c.querySelector('.echart-box'),
        empty: (c.querySelector('.empty-state, .el-empty')?.textContent || '').replace(/\s+/g, ' ').trim(),
      })),
      kpis,
      widgetPalette: widgets,
      hint,
    };
  });

  console.log('=== 当前看板 ===');
  console.log('名称:', info.dashboardName);
  console.log('图表卡片数:', info.chartCount);
  console.log('');
  console.log('=== 图表清单 ===');
  info.chartTitles.forEach((t, i) => {
    console.log(`  ${i + 1}. ${t.name}  |  ${t.sub}`);
    console.log(`      图表渲染:${t.hasChart ? '有' : '无'}  ${t.empty ? '空态:' + t.empty : ''}`);
  });
  console.log('');
  console.log('=== 顶部 KPI ===');
  info.kpis.forEach(k => console.log(`  ${k.label.padEnd(8)} ${k.value.padEnd(8)} ${k.hint}`));
  console.log('');
  console.log('=== 数据源提示 ===');
  console.log(' ', info.hint || '(无)');
  console.log('');
  console.log('=== 组件库可选类型 ===');
  console.log(' ', info.widgetPalette.join(' / '));

  const shot = 'D:\\031_45344camera\\docs\\产线统计页面现状.png';
  await page.screenshot({ path: shot });
  console.log('\n截图已保存:', shot);

  await browser.close();
})().catch(e => { console.error('执行失败:', e); process.exit(1); });
