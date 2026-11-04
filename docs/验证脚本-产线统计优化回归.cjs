/**
 * 浏览器级验证：产线统计页面优化（严重度中文化 / KPI 重排 / 时间快捷档 / 测试图表清理）
 * 用法：node docs/验证脚本-产线统计优化回归.cjs
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
  const jsErrors = [];
  page.on('pageerror', e => jsErrors.push('pageerror: ' + e.message));

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
    const kpis = Array.from(document.querySelectorAll('.kpi-card')).map(k => ({
      label: (k.querySelector('span')?.textContent || '').trim(),
      value: (k.querySelector('strong')?.textContent || '').trim(),
      hint: (k.querySelector('small')?.textContent || '').trim(),
      cls: k.className,
    }));
    const charts = Array.from(document.querySelectorAll('.chart-grid .chart-card')).map(c =>
      (c.querySelector('h4')?.textContent || '').trim());
    const rangeBtns = Array.from(document.querySelectorAll('.range-shortcuts button')).map(b => ({
      text: b.textContent.trim(),
      active: b.classList.contains('el-button--primary'),
    }));
    const dashOptions = Array.from(document.querySelectorAll('.hero-actions .el-select')).map(s => s.textContent.trim());
    return { kpis, charts, rangeBtns, dashOptions };
  });

  // 严重度下拉：需在 evaluate 外部点击（evaluate 回调不能 await）
  let sevOptions = [];
  const sevSel = await page.evaluateHandle(() => {
    return Array.from(document.querySelectorAll('.filter-card .el-select'))
      .find(s => (s.textContent || '').includes('严重度')) || null;
  });
  const sevEl = sevSel.asElement();
  if (sevEl) {
    await sevEl.click();
    await new Promise(r => setTimeout(r, 700));
    sevOptions = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.el-select-dropdown__item')).map(o => o.textContent.trim()));
    await page.mouse.click(10, 10);
    await new Promise(r => setTimeout(r, 400));
  }

  console.log('=== 1. KPI 指标卡（重排后）===');
  info.kpis.forEach(k => console.log(`  ${k.label.padEnd(8)} ${k.value.padEnd(10)} ${k.hint}`));
  console.log('');
  console.log('=== 2. 看板图表清单 ===');
  info.charts.forEach((c, i) => console.log(`  ${i + 1}. ${c}`));
  console.log('');
  console.log('=== 3. 时间范围快捷档 ===');
  info.rangeBtns.forEach(b => console.log(`  ${b.text}  ${b.active ? '[当前选中]' : ''}`));
  console.log('');
  console.log('=== 4. 严重度下拉选项 ===');
  console.log('  ', (sevOptions || []).join(' / ') || '(未取到)');
  console.log('');
  console.log('=== 5. 看板模板下拉 ===');
  console.log('  ', info.dashOptions.join(' | ') || '(未取到)');

  console.log('\n===== 断言 =====');
  const hasDash = info.kpis.some(k => k.label === '良率' && k.value === '—');
  const hasPending = info.kpis.some(k => k.label === '良品样本');
  console.log('[断言1] 无良品样本时不再出现「良率=—」占位卡:', !hasDash ? '通过' : '失败');
  console.log('[断言2] 改为「良品样本」状态卡:', hasPending ? '通过' : '失败');
  console.log('[断言3] KPI 卡数量为 5（4 指标 + 1 状态）:', info.kpis.length === 5 ? '通过' : '失败 ' + info.kpis.length);

  const dupCharts = info.charts.filter(c => c.includes('按钮验证') || c === '自定义图表');
  console.log('[断言4] 测试图表已从默认看板清除:', dupCharts.length === 0 ? '通过' : '失败 ' + JSON.stringify(dupCharts));
  console.log('[断言5] 看板图表数为 6:', info.charts.length === 6 ? '通过' : '失败 ' + info.charts.length);

  const sevZh = (sevOptions || []).some(o => o.includes('高危')) && (sevOptions || []).some(o => o.includes('轻微'));
  const sevEn = (sevOptions || []).some(o => o.trim() === 'HIGH');
  console.log('[断言6] 严重度显示中文（高危/中等/轻微）:', sevZh && !sevEn ? '通过' : '失败 ' + JSON.stringify(sevOptions));

  console.log('[断言7] 时间快捷档位存在（今天/昨天/近7天/近30天/本月）:',
    ['今天', '昨天', '近7天', '近30天', '本月'].every(t => info.rangeBtns.some(b => b.text === t)) ? '通过' : '失败');

  console.log('[断言8] 默认选中「今天」:', info.rangeBtns.some(b => b.text === '今天' && b.active) ? '通过' : '失败');

  const shot = 'D:\\031_45344camera\\docs\\产线统计优化后.png';
  await page.screenshot({ path: shot });
  console.log('\n截图已保存:', shot);

  console.log('\n===== JS 错误 =====');
  console.log(jsErrors.length ? jsErrors.slice(0, 8).join('\n') : '无 JS 错误');

  await browser.close();
})().catch(e => { console.error('执行失败:', e); process.exit(1); });
