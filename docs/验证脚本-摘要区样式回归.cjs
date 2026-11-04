/**
 * 浏览器级验证：配置页摘要区样式修复
 * 用法：node docs/验证脚本-摘要区样式回归.cjs
 */
const puppeteer = require('puppeteer-core');
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

  const log = (...a) => console.log(...a);

  // ---------- 登录 ----------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input', { timeout: 20000 });
  // ★ 统一用辅助函数登录：Element Plus 的 password 输入框会静默吞掉
  //   page.type() 的输入（实测值为空），必须用 focus+Ctrl+A+Backspace+keyboard.type。
  const { login: doLogin } = require('./scripts/browser-helper.cjs');
  const loginRes = await doLogin(page, 'admin', ADMIN_PWD);
  if (!loginRes.ok) log(`  [提示] 登录未成功（${loginRes.url}）：${loginRes.error}`);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.evaluate(() => {
      const btn = document.querySelector('button[type=submit]') || document.querySelector('.el-button--primary');
      if (btn) btn.click();
    }),
  ]);
  log('登录后 URL:', page.url());

  // ---------- 进入配置页（相机管理 tab） ----------
  await page.goto(BASE + '/system/config?tab=camera', { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 3500));

  // ---------- 读取摘要区样式与布局 ----------
  const summaryInfo = await page.evaluate(() => {
    const wrap = document.querySelector('.delivery-summary');
    if (!wrap) return { found: false };
    const wrapCS = getComputedStyle(wrap);
    const groups = Array.from(wrap.querySelectorAll('.summary-group')).map(g => ({
      title: (g.querySelector('.summary-group-title')?.textContent || '').trim(),
      cardCount: g.querySelectorAll('.summary-card').length,
    }));
    const cards = Array.from(wrap.querySelectorAll('.summary-card')).map(c => {
      const cs = getComputedStyle(c);
      const strong = c.querySelector('strong');
      const span = c.querySelector('span');
      return {
        label: (span?.textContent || '').trim(),
        value: (strong?.textContent || '').trim(),
        cls: c.className,
        display: cs.display,
        borderRadius: cs.borderRadius,
        boxShadow: cs.boxShadow,
        background: cs.backgroundColor,
        borderColor: cs.borderColor,
        padding: cs.padding,
        labelFontSize: span ? getComputedStyle(span).fontSize : '',
        valueFontSize: strong ? getComputedStyle(strong).fontSize : '',
        valueColor: strong ? getComputedStyle(strong).color : '',
        valueFontWeight: strong ? getComputedStyle(strong).fontWeight : '',
        rect: c.getBoundingClientRect().toJSON(),
      };
    });
    return {
      found: true,
      wrapDisplay: wrapCS.display,
      wrapGap: wrapCS.gap,
      wrapPadding: wrapCS.padding,
      wrapBorderRadius: wrapCS.borderRadius,
      wrapBoxShadow: wrapCS.boxShadow,
      groups,
      cards,
    };
  });

  log('\n===== 摘要区容器 =====');
  if (!summaryInfo.found) { log('!! 未找到 .delivery-summary'); }
  else {
    log('  display      =', summaryInfo.wrapDisplay);
    log('  gap          =', summaryInfo.wrapGap);
    log('  padding      =', summaryInfo.wrapPadding);
    log('  border-radius=', summaryInfo.wrapBorderRadius);
    log('  box-shadow   =', summaryInfo.wrapBoxShadow);
    log('  语义组:', JSON.stringify(summaryInfo.groups));

    log('\n===== 摘要卡片 =====');
    summaryInfo.cards.forEach(c => {
      log(`  ${c.label.padEnd(10)} ${String(c.value).padEnd(4)} display=${c.display} radius=${c.borderRadius} pad=${c.padding}`);
      log(`      标签 ${c.labelFontSize} / 数值 ${c.valueFontSize} w${c.valueFontWeight} 色=${c.valueColor}`);
      log(`      底色=${c.background} 边框=${c.borderColor} 阴影=${c.boxShadow.slice(0, 46)}`);
    });

    // ---- 断言 ----
    log('\n===== 断言 =====');
    const isCard = summaryInfo.cards.every(c => c.display === 'flex');
    log('[断言1] 卡片为 flex 纵向布局（非裸文本行）:', isCard ? '通过' : '失败 ' + JSON.stringify(summaryInfo.cards.map(c => c.display)));

    const hasRadius = summaryInfo.cards.every(c => c.borderRadius !== '0px');
    log('[断言2] 卡片有圆角:', hasRadius ? '通过' : '失败');

    const hasShadow = summaryInfo.cards.every(c => c.boxShadow && c.boxShadow !== 'none');
    log('[断言3] 卡片有阴影:', hasShadow ? '通过' : '失败');

    const bigNum = summaryInfo.cards.every(c => parseFloat(c.valueFontSize) >= 18);
    log('[断言4] 数值字号 ≥18px（与标签有视觉层级）:', bigNum ? '通过' : '失败 ' + summaryInfo.cards.map(c => c.valueFontSize).join(','));

    const labelsSmall = summaryInfo.cards.every(c => parseFloat(c.labelFontSize) <= 13);
    log('[断言5] 标签字号 ≤13px（小于数值）:', labelsSmall ? '通过' : '失败 ' + summaryInfo.cards.map(c => c.labelFontSize).join(','));

    // 卡片是否横向排列（按组判断：同组内卡片 top 相同即横向成排）
    const groups = await page.evaluate(() => {
      return Array.from(document.querySelectorAll('.delivery-summary .summary-group')).map(g => {
        const cards = Array.from(g.querySelectorAll('.summary-card'));
        const tops = cards.map(c => Math.round(c.getBoundingClientRect().top));
        return { count: cards.length, sameRow: new Set(tops).size === 1, tops };
      });
    });
    const horizontalOk = groups.every(g => g.count <= 1 || g.sameRow);
    log('[断言6] 每组内卡片横向成排:', horizontalOk ? '通过 ' + JSON.stringify(groups.map(g => g.count + '张/行' + (g.sameRow ? '1' : '多'))) : '失败 ' + JSON.stringify(groups));

    // 零值卡片应为中性绿
    const zeroCards = summaryInfo.cards.filter(c => c.value === '0');
    const zeroGreen = zeroCards.every(c => c.cls.includes('is-clear'));
    log('[断言7] 值为 0 的告警卡片切换为中性态 is-clear:', zeroGreen ? '通过' : '失败 ' + JSON.stringify(zeroCards.map(c => c.cls)));

    // 语义组分隔
    log('[断言8] 分为 2 个语义组:', summaryInfo.groups.length === 2 ? '通过' : '失败 ' + JSON.stringify(summaryInfo.groups));
  }

  // ---------- 截图 ----------
  const shot = 'D:\\031_45344camera\\docs\\摘要区样式优化后.png';
  await page.screenshot({ path: shot });
  log('\n截图已保存:', shot);

  // ---------- 响应式：窄屏 ----------
  await page.setViewport({ width: 700, height: 900 });
  await new Promise(r => setTimeout(r, 1200));
  const narrow = await page.evaluate(() => {
    const wrap = document.querySelector('.delivery-summary');
    if (!wrap) return null;
    const cs = getComputedStyle(wrap);
    return { flexDirection: cs.flexDirection, cardCount: wrap.querySelectorAll('.summary-card').length };
  });
  log('\n===== 响应式（700px 宽）=====');
  log('  容器 flex-direction =', narrow && narrow.flexDirection, ' 卡片数 =', narrow && narrow.cardCount);
  const shot2 = 'D:\\031_45344camera\\docs\\摘要区样式优化后-窄屏.png';
  await page.screenshot({ path: shot2 });
  log('窄屏截图已保存:', shot2);

  log('\n===== JS 错误 =====');
  log(jsErrors.length ? jsErrors.slice(0, 8).join('\n') : '无 JS 错误');

  await browser.close();
})().catch(e => { console.error('执行失败:', e); process.exit(1); });
