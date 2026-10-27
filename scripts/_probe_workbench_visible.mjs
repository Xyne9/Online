/**
 * 工作台「看到的 = 判的」一致性探针（用户视角 DOM）
 *
 * 只回答两个问题，全部读**用户真正看到的 DOM 文本**，不读内部变量：
 *   P1 左侧题面面板是否跟 ?pid= 走（还是永远写着 #1024 最大子段和）
 *   P2 提交后首个失败点的说明卡，是否显示真实的输入 / 期望 / 实际
 *
 * 用法：node scripts/_probe_workbench_visible.mjs
 */
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const APP = path.resolve('prototype/app');
const GATEWAY = 'http://127.0.0.1:8080';
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.json': 'application/json; charset=utf-8', '.woff2': 'font/woff2'
};
const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  const file = path.join(APP, url === '/' ? '/index.html' : url);
  if (!file.startsWith(APP) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404); res.end('not found'); return;
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const PORT = server.address().port;

const results = [];
const check = (pass, label, detail = '') => {
  results.push({ pass, label, detail: String(detail) });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${label}${detail ? '\n      ' + detail : ''}`);
};

const lr = await fetch(`${GATEWAY}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: '20220101', password: '123456' })
});
const TOKEN = (await lr.json()).data.accessToken;

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
await ctx.addInitScript(t => {
  localStorage.setItem('OJ_TOKEN', t);
  localStorage.setItem('OJ_ROLE', 'student');
  localStorage.setItem('OJ_USER', JSON.stringify({
    realName: '林昭', role: 'STUDENT', className: '计算机 2201', avatarText: '林'
  }));
}, TOKEN);
const D = `Alpine.$data(document.getElementById('page-content') || document.body)`;

/* ══════════════════════════════════════════════════════════════════
   P1 左侧题面面板是否跟 ?pid= 走
   ══════════════════════════════════════════════════════════════════ */
console.log('── P1 左侧题面面板是否跟 ?pid= 走 ──');
async function panelOf(pid) {
  const page = await ctx.newPage();
  await page.goto(`http://127.0.0.1:${PORT}/s-workbench.html?pid=${pid}`,
    { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(2600);
  const v = await page.evaluate(`(() => {
    const d = ${D};
    const t = (sel) => { const e = document.querySelector(sel); return e ? e.textContent.trim() : null; };
    return {
      pidFromData: d.problemId,
      apiTitle: d.problem && d.problem.title,
      panelPid: t('.prob-title .pid'),
      panelTitle: t('.prob-title h1'),
      panelMeta: t('.prob-meta'),
      panelFirstPara: (t('.prob-body > p') || '').slice(0, 50),
      sample1: (t('.prob-body .eg .io pre') || '').slice(0, 40),
      infoCount: (t('.prob-info') || '').replace(/\\s+/g, ' ').slice(0, 80)
    };
  })()`);
  await page.close();
  return v;
}

const p1006 = await panelOf(1006);
console.log(`  ?pid=1006  左栏显示「${p1006.panelPid} ${p1006.panelTitle}」，接口题名「${p1006.apiTitle}」`);
check(p1006.panelTitle === p1006.apiTitle,
      'P1.1 左栏题名 = 接口题名（题面不串题）',
      `左栏「${p1006.panelTitle}」 vs 接口「${p1006.apiTitle}」`);
check(String(p1006.panelPid).replace('#', '') === String(p1006.pidFromData),
      'P1.2 左栏题号 = 当前题号',
      `左栏 ${p1006.panelPid} vs problemId ${p1006.pidFromData}`);

const p1001 = await panelOf(1001);
console.log(`  ?pid=1001  左栏显示「${p1001.panelPid} ${p1001.panelTitle}」，接口题名「${p1001.apiTitle}」`);
check(p1001.panelTitle === p1001.apiTitle,
      'P1.3 换一道题，左栏跟着换（不是写死）',
      `左栏「${p1001.panelTitle}」 vs 接口「${p1001.apiTitle}」`);

/* ══════════════════════════════════════════════════════════════════
   P2 提交后首个失败点说明卡，是否显示真实输入/期望/实际
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── P2 提交缺陷代码 → 首个失败点说明卡内容 ──');
const BUGGY = [
  '#include <iostream>', '#include <algorithm>', 'using namespace std;', '',
  'int main() {', '    int n;', '    cin >> n;',
  '    int dp = 0, ans = 0;',
  '    for (int i = 0; i < n; i++) {',
  '        int x;', '        cin >> x;',
  '        dp = max(dp + x, x);', '        ans = max(ans, dp);',
  '    }', '    cout << ans << endl;', '    return 0;', '}'
].join('\n');

const page = await ctx.newPage();
await page.goto(`http://127.0.0.1:${PORT}/s-workbench.html?pid=1001`,
  { waitUntil: 'networkidle', timeout: 30000 });
await page.waitForTimeout(2600);

await page.evaluate(`(() => {
  const d = ${D};
  d.code = ${JSON.stringify(BUGGY)};
  d.codeFixed = ${JSON.stringify(BUGGY)};
  d.fixed = true;
})()`);
await page.waitForTimeout(3200);
await page.evaluate(`(() => { const d = ${D}; d.submit(); })()`);

let ok = false;
for (let i = 0; i < 120; i++) {
  await page.waitForTimeout(500);
  const r = await page.evaluate(`(() => { const d = ${D}; return { judging: d.judging, result: d.result }; })()`);
  if (!r.judging && r.result) { ok = true; break; }
}
await page.waitForTimeout(1200);
/* 说明卡可能因 bandOpen 自动收起，手动展开 */
await page.evaluate(`(() => { const d = ${D}; d.bandOpen = true; })()`);
await page.waitForTimeout(900);

const callout = await page.evaluate(`(() => {
  const d = ${D};
  const el = document.querySelector('.callout');
  const codes = el ? [...el.querySelectorAll('code')].map(c => c.textContent.trim()) : [];
  return {
    result: d.result, firstFailSeq: d.firstFailSeq,
    calloutPresent: !!el,
    calloutText: el ? el.textContent.replace(/\\s+/g, ' ').trim() : null,
    codes
  };
})()`);
console.log(`  判题结果=${callout.result} 首个失败点=#${callout.firstFailSeq}`);
console.log(`  说明卡文本：${callout.calloutText}`);

check(callout.calloutPresent, 'P2.1 首个失败点说明卡存在', callout.calloutText || '(没有 .callout)');
check(callout.codes.length >= 2 && callout.codes.some(c => c && c !== '—' && c !== '（隐藏）'),
      'P2.2 说明卡里有真实的输入/期望/实际（不是「—」占位）',
      `code 片段：${JSON.stringify(callout.codes)}`);

await ctx.close();
await browser.close();
server.close();

const pass = results.filter(r => r.pass).length;
console.log(`\n══════════════════════════════════════════════`);
console.log(`  工作台「看到的 = 判的」：${pass} 通过 / ${results.length - pass} 失败`);
console.log(`══════════════════════════════════════════════`);
fs.mkdirSync(path.resolve('logs/step3-acceptance'), { recursive: true });
fs.writeFileSync(path.resolve('logs/step3-acceptance/visible.json'),
  JSON.stringify({ pass, total: results.length, results }, null, 2));
process.exit(pass === results.length ? 0 : 1);
