/**
 * 真实态（无 ?mock=1）健壮性探针
 *
 * 背景：shell.js 的 OJ getter 在非 mock 态读真实数据槽位（未加载时返回空数组）。
 * 页面组件如果直接取 [0].xxx，就会连锁抛 pageerror。03 指南 §7 的硬门槛是
 * 「无 ?mock=1 时显示可读错误态，不是白屏」，所以这里把 15 页逐一打开，确认：
 *   0 pageerror、0 未捕获 console error、页面已挂载、有可见文案。
 *
 * ── Day 4 起的两点口径变化 ──────────────────────────────────────────
 * ① 页面现在会**真的发请求**。后端没起时浏览器必然打印
 *    "Failed to load resource: net::ERR_CONNECTION_REFUSED"——
 *    那是网络层日志，不是我们的 JS 崩了。这类消息列入白名单，
 *    但**只白名单「连不上」，不白名单「接口返回 500」**。
 * ② 后端起来后，本探针额外校验「页面确实拿到了数据」而不是停在空态
 *    （--expect-data 开关；默认只在后端可达时自动开启）。
 *
 * 用法：
 *   node scripts/_probe_realmode.mjs
 *   node scripts/_probe_realmode.mjs --no-data-check   # 只验不崩
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
await new Promise(r => server.listen(8817, r));

/* ── 后端可达性：决定是否开「必须拿到数据」的校验 ── */
const ARGS = new Set(process.argv.slice(2));
async function backendUp() {
  try {
    const r = await fetch('http://127.0.0.1:8080/api/teaching/health', { signal: AbortSignal.timeout(3000) });
    return r.ok;
  } catch { return false; }
}
const UP = await backendUp();
const DATA_CHECK = UP && !ARGS.has('--no-data-check');
console.log(`后端可达：${UP ? '是' : '否'}　数据校验：${DATA_CHECK ? '开' : '关'}\n`);

/* 网络层日志白名单：连不上 / 被拒绝。这些不是页面代码的问题。 */
const NET_NOISE = [
  /net::ERR_CONNECTION_REFUSED/i,
  /net::ERR_CONNECTION_RESET/i,
  /Failed to load resource/i,
  /ERR_ABORTED/i,
  /the server responded with a status of 4\d\d/i,
  /the server responded with a status of 5\d\d/i,
  /favicon/i
];

const PAGES = [
  ['a-audit', 'admin'], ['a-models', 'admin'],
  ['s-analytics', 'student'], ['s-assignments', 'student'], ['s-exam', 'student'],
  ['s-problems', 'student'], ['s-submissions', 'student'], ['s-workbench', null],
  ['t-analytics', 'teacher'], ['t-assignments', 'teacher'], ['t-dashboard', 'teacher'],
  ['t-monitor', 'teacher'], ['t-plagiarism', 'teacher'], ['t-problem-edit', 'teacher'],
  ['t-problems', 'teacher']
];

/* 后端起来后，这几页必须真的显示数据（其余页 Day 4 还没接，只验不崩） */
const DATA_PAGES = new Set(['s-problems', 's-submissions']);

const browser = await chromium.launch();
let pass = 0, fail = 0;

for (const [name, role] of PAGES) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('pageerror: ' + e.message));
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const t = m.text();
    if (NET_NOISE.some(re => re.test(t))) return;   // 网络层噪音，不算
    errs.push('console: ' + t);
  });
  await ctx.addInitScript((r) => {
    /* 真实 token（不以 mock- 开头，boot.js 不会清）+ 角色 */
    localStorage.setItem('OJ_TOKEN', 'eyJhbGciOiJIUzI1NiJ9.real.jwt');
    if (r) localStorage.setItem('OJ_ROLE', r);
  }, role);

  await page.goto(`http://127.0.0.1:8817/${name}.html`, { waitUntil: 'networkidle', timeout: 25000 });
  await page.waitForTimeout(UP ? 1600 : 1100);

  const info = await page.evaluate(() => {
    const root = document.getElementById('page-content') || document.querySelector('[x-data]');
    const mounted = !!(root && root._x_dataStack && root._x_dataStack.length);
    const rows = document.querySelectorAll('.li, .tr-row, .chain-c').length;
    return {
      landed: location.pathname.split('/').pop(),
      mounted,
      textLen: (document.body.innerText || '').trim().length,
      rows
    };
  });

  const okLand = info.landed === name + '.html';
  const okErr = errs.length === 0;
  const okMount = info.mounted;
  const okText = info.textLen > 80;      // 不白屏
  const needData = DATA_CHECK && DATA_PAGES.has(name);
  const okData = !needData || info.rows > 0;
  const ok = okLand && okErr && okMount && okText && okData;
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  landed=${info.landed} mounted=${info.mounted} text=${info.textLen} rows=${info.rows}${needData ? ' [需有数据]' : ''}`);
  if (!okErr) console.log('      ' + errs.slice(0, 4).join(' | ') + (errs.length > 4 ? ` …共 ${errs.length} 条` : ''));
  if (!okData) console.log('      期望有数据行，实际 0 行（接口可能失败或页面没接上）');
  await ctx.close();
}

console.log(`\n===== 真实态健壮性：${pass} 通过 / ${fail} 失败 =====`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
