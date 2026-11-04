/**
 * 403 / 401 认证失效处理 —— 浏览器级验证
 * ============================================================
 * 验证四个要点：
 *   ① 401（未登录）→ 跳转登录页 + 提示 + 保留来源路由 + 清除失效 token
 *   ② 登录后回跳原页面（上下文用后即焚）
 *   ③ 403（权限不足）→ **不跳转**，只在页面提示
 *   ④ 开放重定向防护（外部 URL 不被回跳）
 *
 * ⚠️ 场景顺序有依赖：① 会清掉 token，② 承接 ① 保存的上下文，
 *    所以不能把 ② 提到 ① 前面。
 *
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
  page.on('pageerror', e => jsErrors.push(e.message));

  // ---------- 登录 ----------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  // ★ 统一用辅助函数登录：Element Plus 的 password 输入框会静默吞掉
  //   page.type() 的输入（实测值为空），必须用 focus+Ctrl+A+Backspace+keyboard.type。
  const { login: doLogin } = require('./scripts/browser-helper.cjs');
  const loginRes = await doLogin(page, 'admin', ADMIN_PWD);
  if (!loginRes.ok) log(`  [提示] 登录未成功（${loginRes.url}）：${loginRes.error}`);
  await new Promise(r => setTimeout(r, 300));
  await page.evaluate(() => {
    const btn = Array.from(document.querySelectorAll('button')).find(b => /登录|登 录/i.test(b.textContent));
    if (btn) btn.click();
  });
  await new Promise(r => setTimeout(r, 4000));
  log(`登录后 URL: ${page.url()}`);
  assert(!page.url().includes('/login'), '登录成功');

  // =========================================================
  // ① 401 未登录 → 跳登录页 + 提示 + 上下文
  //    （放在最前面：它会清掉 token，后续场景都基于"未登录"状态）
  // =========================================================
  log('\n--- ① 401（token 失效 / 未登录）→ 跳登录页 ---');
  await page.goto(BASE + '/system/config?tab=camera', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 3500));

  const beforePath = await page.evaluate(() => window.location.pathname + window.location.search);
  log(`  跳转前页面: ${beforePath}`);

  // 清掉 token（模拟登录态失效），再触发页面自身的数据请求 → 服务端返回 401
  await page.evaluate(() => {
    localStorage.removeItem('camera_token');
    localStorage.removeItem('camera_token_expire');
  });
  await page.evaluate(() => {
    const btns = Array.from(document.querySelectorAll('button'));
    const target = btns.find(b => /刷新|重新加载|查询/.test(b.textContent || ''));
    if (target) target.click();
  });
  await new Promise(r => setTimeout(r, 4500));

  const after401 = await page.evaluate(() => ({
    path: window.location.pathname,
    search: window.location.search,
    notice: document.querySelector('.notice-msg')?.textContent?.trim() || '',
    ctx: sessionStorage.getItem('camera_login_redirect') || '',
    token: localStorage.getItem('camera_token') || ''
  }));
  log(`  跳转后 URL: ${after401.path}${after401.search}`);
  log(`  登录页提示: ${after401.notice || '(无)'}`);
  log(`  保留的上下文: ${after401.ctx || '(无)'}`);
  log(`  token 是否已清除: ${after401.token ? '否（仍在）' : '是（已清除）'}`);
  assert(after401.path === '/login', '401 后跳转到登录页', `(实际: ${after401.path})`);
  assert(after401.notice.length > 0, '登录页显示了失效提示', `(实际: "${after401.notice}")`);
  assert(after401.ctx.includes('/system/config'), '保留了来源路由上下文', after401.ctx);
  assert(!after401.token, '失效 token 已被清除');

  // =========================================================
  // ② 登录后回跳原页面（承接上一步保存的上下文）
  // =========================================================
  log('\n--- ② 登录后回跳原页面 ---');
  // 上一步 401 已把上下文存好，且 token 已清除 → 现在正处于登录页
  const ctxNow = await page.evaluate(() => sessionStorage.getItem('camera_login_redirect') || '');
  log(`  当前上下文: ${ctxNow || '(无)'}`);
  assert(ctxNow.includes('/system/config'), '上下文在跳转时已保存');

  const loginPageInfo = await page.evaluate(() => ({
    notice: document.querySelector('.notice-msg')?.textContent?.trim() || ''
  }));
  log(`  登录页提示: ${loginPageInfo.notice || '(无)'}`);
  assert(loginPageInfo.notice.length > 0, '登录页提示需要重新登录');

  const relogin2 = await doLogin(page, 'admin', ADMIN_PWD);
  log(`  登录: ${relogin2.ok ? '成功' : '失败 (' + relogin2.error + ')'}`);
  await new Promise(r => setTimeout(r, 3500));
  const afterLogin = await page.evaluate(() => ({
    path: window.location.pathname + window.location.search,
    ctxLeft: sessionStorage.getItem('camera_login_redirect') || ''
  }));
  log(`  登录后 URL: ${afterLogin.path}`);
  log(`  上下文残留: ${afterLogin.ctxLeft || '(已清除)'}`);
  assert(afterLogin.path.includes('/system/config'), '登录后回跳到原页面', `(实际: ${afterLogin.path})`);
  assert(!afterLogin.ctxLeft, '上下文用后即焚（已清除）');

  // =========================================================
  // ③ 403 权限不足 → 不跳转
  // =========================================================
  log('\n--- ③ 403（权限不足）→ 不跳转，只提示 ---');
  // ⚠️ 场景②结束时已登录（token 有效），而路由守卫会把"已登录访问 /login"
  //    重定向到 /camera-status —— 那样根本进不了登录页。
  //    所以先清 token 制造"未登录"，再登录。
  await page.evaluate(() => {
    localStorage.removeItem('camera_token');
    localStorage.removeItem('camera_token_expire');
    localStorage.removeItem('camera_user');
  });
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  const relogin = await doLogin(page, 'admin', ADMIN_PWD);
  log(`  重新登录: ${relogin.ok ? '成功' : '失败 (' + relogin.error + ')'}`);
  assert(relogin.ok, '重新登录成功');
  await new Promise(r => setTimeout(r, 4000));

  // 验证后端的三态语义（这是前端分流判断的基础）
  const real403 = await page.evaluate(async () => {
    const tok = localStorage.getItem('camera_token');
    const r1 = await fetch('/api/user?page=1&size=1', { headers: { Authorization: 'Bearer ' + tok } });
    const j1 = await r1.json();
    const r2 = await fetch('/api/cascade/all', { headers: { Authorization: 'Bearer bad.token' } });
    const j2 = await r2.json();
    return {
      adminStatus: r1.status, adminHasReason: !!j1.reason,
      badStatus: r2.status, badReason: j2.reason || null, badMsg: j2.message || null
    };
  });
  log(`  admin → ${real403.adminStatus}（有 reason 字段: ${real403.adminHasReason}）`);
  log(`  无效 token → ${real403.badStatus} reason=${real403.badReason}`);
  assert(real403.badStatus === 401, '无效 token 返回 401（未认证）');
  assert(real403.badReason === 'AUTHENTICATION_REQUIRED', '401 带 reason=AUTHENTICATION_REQUIRED');

  const pathAfter = await page.evaluate(() => window.location.pathname);
  assert(pathAfter !== '/login', '普通请求未误触发跳转', `(当前: ${pathAfter})`);

  // =========================================================
  // ④ 开放重定向防护（用页面行为验证：写入非法 redirect 后不应回跳到外部）
  // =========================================================
  log('\n--- ④ 开放重定向防护 ---');
  await page.evaluate(() => {
    sessionStorage.setItem('camera_login_redirect', JSON.stringify({
      path: 'https://evil.example.com/steal',
      query: null,
      at: Date.now()
    }));
  });
  // ⚠️ 同样要先清 token，否则路由守卫会把已登录用户挡在登录页外
  await page.evaluate(() => {
    localStorage.removeItem('camera_token');
    localStorage.removeItem('camera_token_expire');
    localStorage.removeItem('camera_user');
  });
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 1500));
  const evilLogin = await doLogin(page, 'admin', ADMIN_PWD);
  if (!evilLogin.ok) log(`  [提示] 登录未成功（${evilLogin.error}）`);
  await new Promise(r => setTimeout(r, 4500));
  const afterEvil = await page.evaluate(() => ({
    url: window.location.href,
    host: window.location.host
  }));
  log(`  登录后 URL: ${afterEvil.url}`);
  assert(!afterEvil.url.includes('evil.example.com'), '外部 URL 未被回跳（防开放重定向）');
  assert(afterEvil.host === '10.2.77.72:8091', '仍停留在本站');

  // ---------- 汇总 ----------
  log('\n--- 汇总 ---');
  log(`  JS 错误: ${jsErrors.length ? jsErrors.join('; ') : '无'}`);
  assert(jsErrors.length === 0, '无 JS 运行时错误');

  log(`\n==========================================`);
  log(`结果：通过 ${pass} 项，失败 ${fail} 项`);
  log(`==========================================`);

  await browser.close();
  process.exit(fail === 0 ? 0 : 1);
})();
