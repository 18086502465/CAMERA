/**
 * 浏览器级验证：ZK19 空产线修复 + 树徽标单位显示
 * 用法：node docs/验证脚本-ZK19产线修复回归.cjs
 */
const puppeteer = require('puppeteer-core');
const CHROME = 'C:\\Program Files\\Google\\ Chrome\\Application\\chrome.exe';
const CHROME_PATH = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091';
// ★ 应用口令从环境变量读取（勿把真实口令写进仓库）
const ADMIN_PWD = process.env.CAMERA_ADMIN_PASSWORD || '';

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: 'new',
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 900 });

  const jsErrors = [];
  page.on('pageerror', e => jsErrors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') jsErrors.push('console: ' + m.text()); });

  const log = (...a) => console.log(...a);

  // ---------- 登录 ----------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input', { timeout: 20000 });
  const setVal = async (idx, val) => {
    await page.evaluate((i, v) => {
      const el = document.querySelectorAll('input')[i];
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, idx, val);
  };
  const inputs = await page.$$('input');
  if (inputs.length >= 2) { await setVal(0, 'admin'); await setVal(1, ADMIN_PWD); }
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.evaluate(() => {
      const btn = document.querySelector('button[type=submit]') || document.querySelector('.el-button--primary');
      if (btn) btn.click();
    }),
  ]);
  log('登录后 URL:', page.url());

  // ---------- 进入配置页 ----------
  await page.goto(BASE + '/system/config?tab=camera', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 3500));

  // ---------- 展开层级树（默认只展开根节点） ----------
  const expanded = await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('.tree-tools button'))
      .find(b => b.textContent.includes('展开层级'));
    if (btn) { btn.click(); return true; }
    return false;
  });
  log('展开层级:', expanded ? '已点击「展开层级」' : '未找到按钮');
  await new Promise(r => setTimeout(r, 1200));

  // ---------- 1. 树内容检查 ----------
  const treeInfo = await page.evaluate(() => {
    const nodes = Array.from(document.querySelectorAll('.config-tree .tree-node'));
    return nodes.map(n => ({
      label: (n.querySelector('.tree-label')?.textContent || '').trim(),
      meta: (n.querySelector('.tree-meta')?.textContent || '').trim(),
      count: (n.querySelector('.tree-count')?.textContent || '').trim(),
      title: n.querySelector('.tree-count')?.getAttribute('title') || '',
    })).filter(x => x.label);
  });

  log('\n===== 左侧层级树 =====');
  treeInfo.forEach(n => log(`  ${n.label.padEnd(16)} [${n.meta}] 徽标="${n.count}"  title="${n.title}"`));

  const zk19 = treeInfo.filter(n => n.label.includes('ZK19'));
  log('\n[断言1] ZK19 是否已从树中消失:', zk19.length === 0 ? '通过（已消失）' : '失败 -> ' + JSON.stringify(zk19));

  const lineNode = treeInfo.find(n => n.label === 'ZK23');
  log('[断言2] ZK23 产线徽标带单位:', lineNode ? `"${lineNode.count}" title="${lineNode.title}"` : '未找到 ZK23');

  const stationNodes = treeInfo.filter(n => n.meta === '工位');
  const withUnit = stationNodes.filter(n => /机/.test(n.count));
  log('[断言3] 工位徽标带「机」单位:', withUnit.length + '/' + stationNodes.length + ' 个工位带单位',
      stationNodes.slice(0, 4).map(s => s.label + '=' + s.count).join(', '));

  const zk23Stations = treeInfo.filter(n => n.meta === '工位' && n.count.includes('1'));
  log('[断言4] 仍有相机的工位徽标为 1机:', zk23Stations.length, '个');

  // ---------- 2. 点击工位检查右侧列表 ----------
  log('\n===== 点击「产品正面胶污检测」工位 =====');
  const clicked = await page.evaluate(() => {
    const nodes = Array.from(document.querySelectorAll('.config-tree .tree-node'));
    // 找 ZK23 分支下的「产品正面胶污检测」
    for (const n of nodes) {
      const meta = (n.querySelector('.tree-meta')?.textContent || '').trim();
      const label = (n.querySelector('.tree-label')?.textContent || '').trim();
      if (meta === '工位' && label === '产品正面胶污检测') {
        const cnt = (n.querySelector('.tree-count')?.textContent || '').trim();
        if (cnt.startsWith('1')) { n.click(); return { ok: true, label, cnt }; }
      }
    }
    return { ok: false };
  });
  log('  点击结果:', JSON.stringify(clicked));

  await new Promise(r => setTimeout(r, 2500));

  const tableInfo = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('.cfg-table .el-table__body-wrapper tbody tr'));
    const total = document.querySelector('.toolbar-count')?.textContent?.trim() || '';
    const empty = document.querySelector('.el-table__empty-text')?.textContent?.trim() || '';
    return {
      total,
      empty,
      rowCount: rows.length,
      rows: rows.slice(0, 5).map(tr => Array.from(tr.querySelectorAll('td')).map(td => td.textContent.trim()).join(' | ')),
    };
  });
  log('  右侧:', JSON.stringify(tableInfo, null, 2));
  log('[断言5] 点击后应列出相机（不再是空表）:', tableInfo.rowCount > 0 ? '通过' : '失败');

  // ---------- 3. 截图 ----------
  const shot = 'D:\\031_45344camera\\docs\\ZK19修复后验证.png';
  await page.screenshot({ path: shot, fullPage: false });
  log('\n截图已保存:', shot);

  log('\n===== JS 错误 =====');
  log(jsErrors.length ? jsErrors.slice(0, 10).join('\n') : '无 JS 错误');

  await browser.close();
})().catch(e => { console.error('执行失败:', e); process.exit(1); });
