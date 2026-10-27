/**
 * Day2 登录守卫验收（临时探针）
 *
 * 验证 4 条红线：
 *   1. 无 token 打开业务页 → 跳 login.html?redirect=...
 *   2. ?mock=1 打开业务页 → 正常渲染，自动发假 token，身份取本页 data-role
 *   3. ?mock=1 时点击站内链接 → 点击瞬间补 mock=1，不掉回真实接口
 *   4. 全流程 0 个 console error / pageerror
 *
 * 判定"正常渲染"用 Alpine 是否完成挂载（#app 有 _x_dataStack）而非数 .card，
 * 因为 16 页的骨架类名各不相同，数某个类名会误判。
 */
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const APP = path.resolve('prototype/app');
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.json': 'application/json; charset=utf-8'
};
const server = http.createServer((req, res) => {
  const url = decodeURIComponent((req.url || '/').split('?')[0]);
  const fp = path.join(APP, url === '/' ? 'index.html' : url);
  fs.readFile(fp, (e, b) => {
    if (e) { res.writeHead(404).end('nf'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream' });
    res.end(b);
  });
});
await new Promise(r => server.listen(8812, r));

const browser = await chromium.launch();

/** Alpine 挂载完成 = #page-content 上有 _x_dataStack，或页面上有 x-data 的元素被处理过 */
const MOUNT_PROBE = () => {
  const root = document.getElementById('page-content') || document.querySelector('[x-data]');
  if (!root) return { mounted: false, why: 'no #page-content / [x-data]' };
  const hasStack = !!(root._x_dataStack && root._x_dataStack.length);
  const children = root.querySelectorAll('*').length;
  return { mounted: hasStack, children, why: hasStack ? '' : '_x_dataStack empty' };
};

const cases = [
  ['A 无token 开 s-problems', 's-problems.html', null, 'login.html', false],
  ['B 无token 开 t-dashboard', 't-dashboard.html', null, 'login.html', false],
  ['C 无token 开 s-workbench', 's-workbench.html', null, 'login.html', false],
  ['D mock 开 s-problems', 's-problems.html?mock=1', null, 's-problems.html', true],
  ['E mock 开 t-dashboard', 't-dashboard.html?mock=1', null, 't-dashboard.html', true],
  ['F mock 开 a-models', 'a-models.html?mock=1', null, 'a-models.html', true],
  ['G mock 开 s-workbench', 's-workbench.html?mock=1', null, 's-workbench.html', true],
  ['H mock 开 s-exam', 's-exam.html?mock=1', null, 's-exam.html', true],
  ['I mock 开 t-problem-edit', 't-problem-edit.html?mock=1', null, 't-problem-edit.html', true],
  ['J mock 开 t-monitor', 't-monitor.html?mock=1', null, 't-monitor.html', true],
  // 用真实形态的 JWT（不以 mock- 开头）。boot.js 会清掉 mock- 前缀的假 token，
  // 所以这里若写 'mock-token'，K 测的就不再是「已登录」而是「演示态残留」。
  ['K 有token 开 s-problems', 's-problems.html', 'eyJhbGciOiJIUzI1NiJ9.real.jwt', 's-problems.html', true],
  // index.html 是唯一没有 Alpine 的静态门户页（03 指南 §4：全文 <script 命中 0），
  // 所以只验它不被守卫踢走，不验挂载。
  ['L 白名单 index', 'index.html', null, 'index.html', false],
];

let pass = 0, fail = 0;
for (const [label, target, token, wantPage, wantMounted] of cases) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
  if (token) await ctx.addInitScript(t => { localStorage.setItem('OJ_TOKEN', t); }, token);
  await page.goto('http://127.0.0.1:8812/' + target, { waitUntil: 'networkidle', timeout: 20000 });
  await page.waitForTimeout(700);
  const u = new URL(page.url());
  const gotPage = u.pathname.replace(/^\//, '');
  const role = await page.evaluate(() => document.body.getAttribute('data-role'));
  const probe = await page.evaluate(MOUNT_PROBE);
  const tok = await page.evaluate(() => localStorage.getItem('OJ_TOKEN'));
  const okPage = gotPage === wantPage;
  const okErr = errs.length === 0;
  const okMount = wantMounted ? probe.mounted : true;
  const okTok = wantMounted && wantPage !== 'login.html' ? !!tok : true;
  const ok = okPage && okErr && okMount && okTok;
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  console.log(`      -> ${gotPage}${u.search}  role=${role}  mounted=${probe.mounted}(${probe.children} nodes)  token=${tok ? 'yes' : 'no'}`);
  if (!okErr) console.log('      ERR ' + errs.join(' | '));
  if (!okPage) console.log(`      want=${wantPage} got=${gotPage}`);
  if (!okMount) console.log('      ' + probe.why);
  await ctx.close();
}

// M: mock 链接透传 —— 点一下就应带上 mock=1（boot.js 在捕获阶段改写 href）
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8812/s-problems.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);
  const res = await page.evaluate(() => {
    const a = Array.from(document.querySelectorAll('.side a[href]'))
      .find(x => /s-assignments\.html/.test(x.getAttribute('href')));
    if (!a) return { found: false };
    const before = a.getAttribute('href');
    // 模拟真实点击：派发一个可取消的 click 事件，让 boot.js 的捕获监听先跑
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return { found: true, before, after: a.getAttribute('href') };
  });
  const ok = res.found && /[?&]mock=1/.test(res.after || '');
  console.log(`${ok ? 'PASS' : 'FAIL'}  M 侧栏链接点击瞬间补 mock=1`);
  if (res.found) console.log(`      before="${res.before}"  after="${res.after}"`);
  if (ok) pass++; else fail++;
  await ctx.close();
}

// N: sessionStorage 跨页透传
// 语义：在 mock 态下站内跳转，链接会被补上 mock=1，所以下一页仍是 mock。
// 直接手打无参 URL 则视为「切回真实态」——boot.js 会清掉 sessionStorage 标记
// （答辩现场要能靠删参数演示真实接口，见 _probe_stage0.mjs 用例 7）。
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8812/s-problems.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  const flag = await page.evaluate(() => sessionStorage.getItem('OJ_USE_MOCK'));

  // ① 站内点击跳转：应带 mock=1，仍在 mock 态
  const viaLink = await page.evaluate(() => {
    const a = document.createElement('a');
    a.setAttribute('href', 't-dashboard.html');
    document.body.appendChild(a);
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    const href = a.getAttribute('href');
    a.remove();
    return href;
  });
  const linkOk = /[?&]mock=1/.test(viaLink);

  // ② 手打无参 URL：应切回真实态（清标记、跳登录页）
  const p2 = await ctx.newPage();
  await p2.goto('http://127.0.0.1:8812/t-dashboard.html', { waitUntil: 'networkidle' });
  await p2.waitForTimeout(700);
  const flag2 = await p2.evaluate(() => sessionStorage.getItem('OJ_USE_MOCK'));
  const landed2 = new URL(p2.url()).pathname.replace(/^\//, '');

  const ok = flag === '1' && linkOk && flag2 === null && landed2 === 'login.html';
  console.log(`${ok ? 'PASS' : 'FAIL'}  N mock 透传语义（站内跳转保留 / 手打无参切回真实）`);
  console.log(`      初始 flag=${flag}  站内链接=${viaLink}  手打无参后 flag=${flag2} 落到=${landed2}`);
  if (ok) pass++; else fail++;
  await ctx.close();
}

console.log(`\n===== 守卫验收：${pass} 通过 / ${fail} 失败 =====`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
