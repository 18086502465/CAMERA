/**
 * P0/P1 修复浏览器回归验证
 * 覆盖：① 用户管理页（原 404 空白）② 异常告警页（原假数据）③ 检测记录页（原假数据）
 * 环境变量：CAMERA_ADMIN_PASSWORD
 */
const puppeteer = require('puppeteer-core');
const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091';
const ADMIN_PWD = process.env.CAMERA_ADMIN_PASSWORD || '';

let pass = 0, fail = 0;
const log = (s) => console.log(s);
const assert = (cond, name, extra = '') => {
  if (cond) { pass++; log(`  [通过] ${name}`); }
  else { fail++; log(`  [失败] ${name} ${extra}`); }
};

(async () => {
  const browser = await puppeteer.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: 'new',
    args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 900 });

  const jsErrors = [];
  const failedReq = [];
  page.on('pageerror', e => jsErrors.push(e.message));
  page.on('response', r => {
    if (r.status() >= 400 && !r.url().includes('favicon')) {
      failedReq.push(`${r.status()} ${r.url().replace(BASE, '')}`);
    }
  });

  // ---------- 登录 ----------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  // ★ 统一用辅助函数登录：Element Plus 的 password 输入框会静默吞掉
  //   page.type() 的输入（实测值为空），必须用 focus+Ctrl+A+Backspace+keyboard.type。
  const { login: doLogin } = require('./scripts/browser-helper.cjs');
  const loginRes = await doLogin(page, 'admin', ADMIN_PWD);
  if (!loginRes.ok) log(`  [提示] 登录未成功（${loginRes.url}）：${loginRes.error}`);
  log(`\n登录后 URL: ${page.url()}`);
  assert(!page.url().includes('/login'), '登录成功');

  const apiCalls = [];
  page.on('request', r => {
    const u = r.url().replace(BASE, '');
    if (u.includes('/api/')) apiCalls.push(u);
  });

  // =========================================================
  // ① 用户管理页
  // =========================================================
  // ⚠️ 路由核实：/system/users 是**重定向兼容桩**（redirect → /system/config），
  //    用户管理功能已并入配置页，UserList.vue 本身不被路由加载。
  //    因此这里验证的是**接口层修复**（/api/users 404 → /api/user 200），
  //    通过直接调用页面内同源接口来确认，不依赖页面渲染。
  log('\n--- ① 用户管理接口（原 /api/users → 404）---');
  const userApi = await page.evaluate(async () => {
    // ⚠️ token 存储键是 'camera_token'（见 frontend/src/utils/auth.js 的 TOKEN_KEY）
    const tok = localStorage.getItem('camera_token');
    const call = async (u) => {
      const r = await fetch(u, { headers: { Authorization: 'Bearer ' + tok } });
      return { url: u, status: r.status };
    };
    const wrong = await call('/api/users?page=1&size=5');
    const right = await call('/api/user?page=1&size=5');
    const body = await (await fetch('/api/user?page=1&size=5', { headers: { Authorization: 'Bearer ' + tok } })).json();
    const recs = (body.data && body.data.records) || [];
    return {
      wrong, right,
      count: recs.length,
      usernames: recs.map(r => r.username),
      first: recs[0] ? { username: recs[0].username, role: recs[0].role, realName: recs[0].realName } : null
    };
  });
  log(`  旧路径 /api/users -> ${userApi.wrong.status}（应为 404，说明前端已不再用它）`);
  log(`  新路径 /api/user  -> ${userApi.right.status}（应为 200）`);
  log(`  返回用户数: ${userApi.count} | 用户名: ${userApi.usernames.join(', ')}`);
  assert(userApi.right.status === 200, '/api/user 返回 200（路径修正生效）');
  assert(userApi.count > 0, '返回了真实用户数据');
  // ⚠️ 不要断言"第一条就是 admin" —— 库里可能有多个用户，
  //    顺序由分页决定。只需确认 admin 账号在其中。
  assert(userApi.usernames.includes('admin'), '返回的用户中包含真实 admin 账号',
    `(实际: ${userApi.usernames.join(', ')})`);

  // =========================================================
  // ② 异常告警页
  // =========================================================
  log('\n--- ② 异常告警页（原为模拟数据）---');
  apiCalls.length = 0;
  await page.goto(BASE + '/anomaly', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 4000));

  const alertInfo = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('.el-table__body-wrapper tbody tr'))
      .filter(r => !r.querySelector('.el-table__empty-block'));
    const emptyText = document.querySelector('.el-table__empty-text')?.textContent?.trim() || '';
    const body = document.body.innerText;
    return {
      rowCount: rows.length,
      emptyText,
      hasMock: body.includes('XX卫生用品') || body.includes('尿不湿产线A') || body.includes('接头检测工位'),
      hasEmptyHint: /暂无|没有数据|无数据/.test(emptyText || '')
    };
  });
  log(`  请求: ${apiCalls.filter(u => u.includes('alert')).join(', ') || '(无)'}`);
  log(`  表格行数: ${alertInfo.rowCount} | 空提示: ${alertInfo.emptyText}`);
  assert(apiCalls.some(u => u.includes('/api/alerts/active')), '调用了正确的 /api/alerts/active 接口');
  assert(!alertInfo.hasMock, '不含模拟数据（XX卫生用品 / 尿不湿产线A）');
  assert(alertInfo.rowCount > 0 || alertInfo.hasEmptyHint, '有数据行或显示了空状态提示');

  // =========================================================
  // ③ 检测记录页
  // =========================================================
  log('\n--- ③ 检测记录页（原为模拟数据）---');
  apiCalls.length = 0;
  await page.goto(BASE + '/detection', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 5000));

  const detInfo = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('.el-table__body-wrapper tbody tr'))
      .filter(r => !r.querySelector('.el-table__empty-block'));
    const cells = rows.length ? Array.from(rows[0].querySelectorAll('td')).map(c => c.textContent.trim()) : [];
    const emptyText = document.querySelector('.el-table__empty-text')?.textContent?.trim() || '';
    const pager = document.querySelector('.el-pagination .el-pager')?.textContent?.trim() || '';
    const total = document.querySelector('.el-pagination__total')?.textContent?.trim() || '';
    return { rowCount: rows.length, cells, emptyText, pager, total, body: document.body.innerText };
  });
  log(`  请求: ${apiCalls.filter(u => u.includes('history') || u.includes('detection')).join(', ') || '(无)'}`);
  log(`  表格行数: ${detInfo.rowCount} | 分页: ${detInfo.total}`);
  log(`  首行: ${detInfo.cells.slice(0, 6).join(' | ')}`);
  assert(apiCalls.some(u => u.includes('/api/v1/defect-images/history')),
    '调用了真实数据源 /api/v1/defect-images/history');
  assert(detInfo.rowCount > 0, '检测记录表格有数据行', `(空文本: ${detInfo.emptyText})`);
  assert(!detInfo.body.includes('008小护-K8'), '不含模拟数据（008小护-K8）');
  assert(detInfo.cells.some(c => c.includes('—')), '无良品样本时 OK/良率显示「—」而非 0');

  // ---------- 汇总 ----------
  log('\n--- 汇总 ---');
  log(`  失败请求: ${failedReq.length ? failedReq.join('; ') : '无'}`);
  log(`  JS 错误: ${jsErrors.length ? jsErrors.join('; ') : '无'}`);
  assert(jsErrors.length === 0, '无 JS 运行时错误');
  const realFails = failedReq.filter(f => !f.includes('404'));
  assert(realFails.length === 0, '无失败接口请求（404 除外）', realFails.join('; '));

  log(`\n==========================================`);
  log(`结果：通过 ${pass} 项，失败 ${fail} 项`);
  log(`==========================================`);

  await browser.close();
  process.exit(fail === 0 ? 0 : 1);
})();
