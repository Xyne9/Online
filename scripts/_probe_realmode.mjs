/**
 * 真实态（无 ?mock=1）空数据健壮性探针（临时）
 *
 * 背景：shell.js 的 OJ getter 在非 mock 态返回空数组（正确行为——不能拿假数据
 * 冒充真实数据）。但页面组件如果直接取 [0].xxx，就会连锁抛 pageerror。
 * 03 指南 §7 的硬门槛是「无 ?mock=1 时显示可读错误态，不是白屏」，
 * 所以这里把 16 页逐一用真实 token 打开，确认：0 pageerror、0 console error。
 *
 * 注意：这不是「必须显示完整内容」的验收——真实接口 Day 4 才接，
 * 本探针只守住「不白屏、不报错」这条底线。
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

const PAGES = [
  ['a-audit', 'admin'], ['a-models', 'admin'],
  ['s-analytics', 'student'], ['s-assignments', 'student'], ['s-exam', 'student'],
  ['s-problems', 'student'], ['s-submissions', 'student'], ['s-workbench', null],
  ['t-analytics', 'teacher'], ['t-assignments', 'teacher'], ['t-dashboard', 'teacher'],
  ['t-monitor', 'teacher'], ['t-plagiarism', 'teacher'], ['t-problem-edit', 'teacher'],
  ['t-problems', 'teacher']
];

const browser = await chromium.launch();
let pass = 0, fail = 0;

for (const [name, role] of PAGES) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
  // 真实 token（不以 mock- 开头，boot.js 不会清）+ 角色
  await ctx.addInitScript((r) => {
    localStorage.setItem('OJ_TOKEN', 'eyJhbGciOiJIUzI1NiJ9.real.jwt');
    if (r) localStorage.setItem('OJ_ROLE', r);
  }, role);

  await page.goto(`http://127.0.0.1:8817/${name}.html`, { waitUntil: 'networkidle', timeout: 25000 });
  await page.waitForTimeout(1100);

  const info = await page.evaluate(() => {
    const root = document.getElementById('page-content') || document.querySelector('[x-data]');
    const mounted = !!(root && root._x_dataStack && root._x_dataStack.length);
    return {
      landed: location.pathname.split('/').pop(),
      mounted,
      textLen: (document.body.innerText || '').trim().length
    };
  });

  const okLand = info.landed === name + '.html';
  const okErr = errs.length === 0;
  const okMount = info.mounted;
  const okText = info.textLen > 80;      // 不白屏
  const ok = okLand && okErr && okMount && okText;
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  landed=${info.landed} mounted=${info.mounted} text=${info.textLen}`);
  if (!okErr) console.log('      ' + errs.slice(0, 4).join(' | ') + (errs.length > 4 ? ` …共 ${errs.length} 条` : ''));
  await ctx.close();
}

console.log(`\n===== 真实态健壮性：${pass} 通过 / ${fail} 失败 =====`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
