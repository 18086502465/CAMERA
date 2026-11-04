/**
 * 验证「403 权限不足 → 前端拦截器不跳转登录页」
 * ============================================================
 * 方法：用 Puppeteer 的请求拦截，把页面自身发出的某个接口
 *       响应替换成后端格式的 403（reason=ACCESS_DENIED），
 *       然后观察前端拦截器的行为：
 *         - 不应跳转登录页
 *         - 不应清除 token
 *       随后再把同一接口换成 401（AUTHENTICATION_REQUIRED），
 *       验证此时**应该**跳转登录页。
 *
 * 这样两条路径都经过**真实的前端 axios 拦截器**，而非绕过。
 */
const puppeteer = require('puppeteer-core');
const { login } = require('./scripts/browser-helper.cjs');
const BASE = process.env.BASE_URL || 'http://10.2.77.72:8091';
const ADMIN_PWD = process.env.CAMERA_ADMIN_PASSWORD || '';

let pass = 0, fail = 0;
const log = (s) => console.log(s);
const assert = (cond, name, extra = '') => {
  if (cond) { pass++; log(`  [通过] ${name}`); }
  else { fail++; log(`  [失败] ${name} ${extra}`); }
};

const FORBIDDEN_BODY = JSON.stringify({
  code: 403,
  message: '没有访问权限，请使用具备相应角色的账号登录',
  reason: 'ACCESS_DENIED',
  path: '/api/cameras'
});

const UNAUTHORIZED_BODY = JSON.stringify({
  code: 401,
  message: '登录状态已失效，请重新登录',
  reason: 'AUTHENTICATION_REQUIRED',
  path: '/api/cameras'
});

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
  const loginRes = await login(page, 'admin', ADMIN_PWD);
  log(`登录后 URL: ${loginRes.url}`);
  log(`输入框值: ${JSON.stringify(loginRes.values)}`);
  if (loginRes.error) log(`错误提示: ${loginRes.error}`);
  assert(loginRes.ok, '登录成功', `(实际: ${loginRes.url})`);

  // =========================================================
  // 场景 A：403 ACCESS_DENIED → 不跳转、不清 token
  // =========================================================
  log('\n--- A. 403 ACCESS_DENIED（权限不足）---');
  await page.setRequestInterception(true);
  let mode = '403';
  page.on('request', (req) => {
    const u = req.url();
    // 拦截相机列表接口（页面加载必调，且普通用户可访问 → 换成 403 后能验证拦截器分支）
    if (mode === '403' && u.includes('/api/cameras?')) {
      return req.respond({
        status: 403,
        contentType: 'application/json;charset=UTF-8',
        body: FORBIDDEN_BODY
      });
    }
    if (mode === '401' && u.includes('/api/cameras?')) {
      return req.respond({
        status: 401,
        contentType: 'application/json;charset=UTF-8',
        body: UNAUTHORIZED_BODY
      });
    }
    return req.continue();
  });

  await page.goto(BASE + '/camera-status', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 5000));

  const after403 = await page.evaluate(() => ({
    path: window.location.pathname + window.location.search,
    token: localStorage.getItem('camera_token') || '',
    ctx: sessionStorage.getItem('camera_login_redirect') || ''
  }));
  log(`  URL: ${after403.path}`);
  log(`  token: ${after403.token ? '仍在（未被清除）' : '已被清除'}`);
  log(`  上下文: ${after403.ctx || '(无，未写入)'}`);
  assert(!after403.path.includes('/login'),
    '403 权限不足时**未**跳转登录页', `(实际: ${after403.path})`);
  assert(!!after403.token, '403 时 token 未被清除（未误判为未登录）');
  assert(!after403.ctx, '403 时未写入登录回跳上下文');

  // =========================================================
  // 场景 B：401 AUTHENTICATION_REQUIRED → 跳转 + 清 token
  // =========================================================
  log('\n--- B. 401 AUTHENTICATION_REQUIRED（未登录）---');
  mode = '401';
  await page.goto(BASE + '/camera-status', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 5000));

  const after401 = await page.evaluate(() => ({
    path: window.location.pathname,
    search: window.location.search,
    token: localStorage.getItem('camera_token') || '',
    notice: document.querySelector('.notice-msg')?.textContent?.trim() || '',
    ctx: sessionStorage.getItem('camera_login_redirect') || ''
  }));
  log(`  URL: ${after401.path}${after401.search}`);
  log(`  token: ${after401.token ? '仍在' : '已被清除'}`);
  log(`  登录页提示: ${after401.notice || '(无)'}`);
  log(`  上下文: ${after401.ctx || '(无)'}`);
  assert(after401.path === '/login', '401 时跳转到登录页', `(实际: ${after401.path})`);
  assert(!after401.token, '401 时 token 被清除');
  assert(after401.notice.length > 0, '登录页显示失效提示', `(实际: "${after401.notice}")`);
  assert(after401.ctx.includes('/camera-status'), '保留了来源路由上下文', after401.ctx);

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
