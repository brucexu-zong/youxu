// 在真实的安卓 WebView 里跑一遍主要流程（Playwright 通过 adb 连接模拟器）
const { _android } = require('playwright');
const fs = require('fs');
const OUT = 'out', PKG = 'app.youxu.planner';
const R = [];
const check = (n, ok, extra) => { R.push({ n, ok: !!ok, extra: extra || '' }); console.log((ok ? '✓ ' : '✗ ') + n + (extra ? '  ' + extra : '')); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// 在 WebView 里 Playwright 判断不了元素是否可见，所以直接在页面里输入和回车
const typeIn = (p, text) => p.evaluate((t) => { const c = document.querySelector('#cap'); c.focus(); c.value = t; c.dispatchEvent(new Event('input', { bubbles: true })); c.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); }, text);
const save = () => fs.writeFileSync(OUT + '/results.json', JSON.stringify(R, null, 1));
(async () => {
  const [device] = await _android.devices();
  // 无窗口模拟器里网页不一定及时重绘：截图前先在空白处轻划一下
  const shot = async (name) => { await device.shell('input swipe 540 1500 540 1400 300').catch(() => {}); await sleep(1500); await device.screenshot({ path: `${OUT}/${name}.png` }).catch(() => {}); };
  const page = async () => { const wv = await device.webView({ pkg: PKG }, { timeout: 90000 }); const p = await wv.page(); await p.waitForSelector('#cap', { state: 'attached', timeout: 60000 }); await sleep(800); return p; };
  let p = await page();

  const env = await p.evaluate(() => ({ origin: location.origin, native: !!window.YouxuNative, subtle: !!(window.crypto && crypto.subtle), zip: typeof CompressionStream, tab: window.__youxu.ui.tab, items: window.__youxu.state.items.length, demo: window.__youxu.state.meta.demo, ver: window.YouxuNative.webVersion(), app: window.YouxuNative.appVersion(), ua: navigator.userAgent.match(/Chrome\/[\d.]+/)[0] }));
  check('runs inside the app from built-in files', env.origin === 'https://appassets.androidplatform.net' && env.native && env.ver.length === 40, JSON.stringify(env));
  check('encryption and compression available', env.subtle && env.zip === 'function');
  check('first launch: no demo data, opens the device setup', env.tab === 'butler' && env.items === 0 && !env.demo);
  await shot('02-first-run');

  const enc = await p.evaluate(async () => { const salt = 'AAAAAAAAAAAAAAAAAAAAAA=='; const k = await Sync.deriveKey('test-pass-1234', Sync.unb64(salt)); const sealed = await Sync.seal(k, salt, { a: '中文测试' }); return (await Sync.unseal(k, salt, sealed)).a; });
  check('encrypt → decrypt round trip in the WebView', enc === '中文测试');

  const net = await p.evaluate(async () => { try { const r = await fetch('https://api.github.com/repos/brucexu-zong/youxu/contents/index.html?ref=main', { headers: { Accept: 'application/vnd.github+json' }, cache: 'no-store' }); const j = await r.json(); return r.status + ' ' + String(j.sha || j.message).slice(0, 12); } catch (e) { return 'fail ' + e.message; } });
  check('GitHub API reachable from the app', /^200 /.test(net), net);

  await p.evaluate(() => document.querySelector('.tab[data-v="today"]').click());
  await typeIn(p, '明天下午3点到5点 开组会'); await sleep(600);
  check('quick capture', await p.evaluate(() => window.__youxu.state.items.some((i) => i.title === '开组会' && i.time === '15:00' && i.est === 120)));
  await typeIn(p, '周五6点40去机场接人'); await sleep(600);
  await shot('03-captured');
  await p.evaluate(() => document.activeElement && document.activeElement.blur());

  await device.shell(`am force-stop ${PKG}`); await sleep(800);
  await device.shell(`am start -n ${PKG}/.MainActivity`); await sleep(4000);
  p = await page();
  check('data survives an app restart', await p.evaluate(() => window.__youxu.state.items.filter((i) => !i.deleted).length === 2));

  const saved = await p.evaluate(() => window.YouxuNative.saveFile('youxu-ci.json', 'application/json', '{"ok":1}'));
  const ls = String(await device.shell('find /sdcard/Download -name "youxu-ci*"'));
  check('saving a file to Downloads', /^ok/.test(saved) && /youxu-ci/.test(ls), saved + ' | ' + ls.trim());

  await p.evaluate(() => document.querySelector('.tab[data-v="today"]').click()); await shot('03b-today');
  await p.evaluate(() => { document.querySelector('.tab[data-v="butler"]').click(); document.querySelector('[data-a="seg"][data-v="data"]').click(); document.querySelector('[data-a="backup"]').click(); });
  await sleep(500); await shot('04-sheet');
  await device.shell('input keyevent 4'); await sleep(900);
  check('back key closes the open sheet first', await p.evaluate(() => !window.__youxu.ui.sheet && window.__youxu.ui.tab === 'butler'));
  await device.shell('input keyevent 4'); await sleep(900);
  check('back key then returns to Today', await p.evaluate(() => window.__youxu.ui.tab === 'today'));

  await device.shell(`am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "meeting at 3pm" -n ${PKG}/.MainActivity`); await sleep(2000);
  check('text shared from another app lands in the capture box', (await p.inputValue('#cap')) === 'meeting at 3pm');
  await p.evaluate(() => { const c = document.querySelector('#cap'); c.value = ''; c.dispatchEvent(new Event('input', { bubbles: true })); c.blur(); });

  const code = 'YXC1.' + Buffer.from(JSON.stringify({ repo: 'brucexu-zong/youxu-data', adv: 'AAAA' })).toString('base64');
  await p.evaluate((c) => window.YouxuNative.copyText(c), code); await sleep(500);
  await device.shell('input keyevent 3'); await sleep(1500);
  await device.shell(`am start -n ${PKG}/.MainActivity`); await sleep(3000);
  check('pairing code on the clipboard is filled in automatically', ((await p.inputValue('#sync-code').catch(() => '')) || '').startsWith('YXC1.'));
  await shot('05-pairing');

  const src = await p.evaluate(async () => (await fetch('/index.html')).text());
  const inst = await p.evaluate((h) => window.YouxuNative.installWeb(h.replace('<title>', '<meta name="build" content="OTA-TEST"><title>'), 'a'.repeat(40)), src);
  await p.evaluate(() => location.reload()); await sleep(1500); await p.waitForSelector('#cap', { state: 'attached', timeout: 30000 }); await sleep(800);
  check('updated interface is used after reload', inst === 'ok' && await p.evaluate(() => (document.querySelector('meta[name=build]') || {}).content === 'OTA-TEST' && window.YouxuNative.webVersion() === 'a'.repeat(40)));

  await p.evaluate(() => window.YouxuNative.installWeb('<!DOCTYPE html><html><body>broken 有序</body></html>', 'b'.repeat(40)));
  await p.evaluate(() => location.reload());
  await sleep(14000);
  const back = await p.evaluate(() => ({ cap: !!document.querySelector('#cap'), ver: window.YouxuNative.webVersion() })).catch((e) => ({ err: e.message }));
  check('a broken update falls back to the built-in version by itself', back.cap && back.ver !== 'b'.repeat(40) && back.ver !== 'a'.repeat(40), JSON.stringify(back));
  check('data intact after all that', await p.evaluate(() => window.__youxu.state.items.filter((i) => !i.deleted).length === 2).catch(() => false));

  await device.shell('cmd uimode night yes'); await sleep(5000);
  await shot('06a-dark-live');
  await device.shell(`am force-stop ${PKG}`); await sleep(800);
  await device.shell(`am start -n ${PKG}/.MainActivity`); await sleep(4000);
  p = await page();
  await shot('06-dark');
  check('dark mode follows the system', await p.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches || document.documentElement.getAttribute('data-theme') === 'dark').catch(() => false), await p.evaluate(() => JSON.stringify({ mq: matchMedia('(prefers-color-scheme: dark)').matches, attr: document.documentElement.getAttribute('data-theme'), bg: getComputedStyle(document.body).backgroundColor })).catch((e) => e.message));
  check('data intact after switching to dark mode', await p.evaluate(() => window.__youxu.state.items.filter((i) => !i.deleted).length === 2).catch(() => false));
  await device.shell('cmd uimode night no'); await sleep(3000);

  save(); await device.close(); process.exit(0);
})().catch((e) => { R.push({ n: 'test crashed', ok: false, extra: String(e && e.stack || e).slice(0, 800) }); save(); console.error(e); process.exit(1); });
