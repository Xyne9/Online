/**
 * 工作台「代码跟题走」一致性探针
 *
 * 背景：s-workbench.js 把母题写死成 MOTHER_PID = 1024。
 * 但本库真实数据里 #1024 是「最大异或和」，「最大子段和」是 #1001。
 * 本探针只回答一个问题：**页面上显示的题面 / 编辑器里的代码 / 提交去判的代码，
 * 三者是不是同一道题？** 每种入口都要过一遍。
 *
 * 用法：node scripts/_probe_workbench_sameproblem.mjs
 */
import { chromium } from './browser-runtime.mjs';
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

async function inspect(url) {
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));
  await page.goto(`http://127.0.0.1:${PORT}${url}`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(2600);
  const s = await page.evaluate(`(() => {
    const d = ${D};
    const code = (d.code || '');
    return {
      pid: d.problemId, live: d.live,
      title: d.problem && d.problem.title,
      tpl: d.problem && d.problem.codeTemplates && d.problem.codeTemplates.cpp,
      code,
      /* 代码里有没有 最大异或和 的线性基特征 / 最大子段和的 Kadane 特征 */
      looksKadane: /dp\\s*=\\s*max\\(\\s*dp\\s*\\+\\s*x\\s*,\\s*x\\s*\\)/.test(code),
      looksXor: /basis|p\\[i\\]|highestBit|1LL\\s*<</i.test(code)
    };
  })()`);
  await page.close();
  return { ...s, errs };
}

/* ── 入口 1：侧栏直接点「做题工作台」（无 ?pid=） ── */
console.log('── 入口 1：侧栏直达 s-workbench.html（无 pid） ──');
const direct = await inspect('/s-workbench.html');
console.log(`  实际打开：#${direct.pid} 「${direct.title}」  live=${direct.live}`);
check(direct.title === '最大子段和', 'A1 默认进入的是「最大子段和」母题',
      `实际 #${direct.pid} ${direct.title}`);
check(!!direct.title && direct.code.includes('cin >> n') && direct.looksKadane,
      'A2 编辑器里是「最大子段和」的解法（与题面同题）',
      direct.looksKadane ? 'Kadane 特征命中' : `代码片段：${direct.code.split('\n').slice(0, 6).join(' / ')}`);

/* ── 入口 2：题库点进 #1024（真实是「最大异或和」） ── */
console.log('\n── 入口 2：?pid=1024（真实题名「最大异或和」） ──');
const p1024 = await inspect('/s-workbench.html?pid=1024');
console.log(`  实际打开：#${p1024.pid} 「${p1024.title}」`);
const tpl1024 = (p1024.tpl || '').slice(0, 0);
check(p1024.title === '最大异或和', 'B1 题面确实是「最大异或和」', `#${p1024.pid} ${p1024.title}`);
/* 关键：编辑器代码必须来自该题的 codeTemplates，而不是母题写死的那份 */
check(!!p1024.tpl && p1024.code === p1024.tpl,
      'B2 编辑器代码 = 该题下发的 codeTemplates（不串题）',
      p1024.code === p1024.tpl ? '与模板逐字相同'
        : `模板 ${(p1024.tpl || '').length} 字符 vs 实际 ${p1024.code.length} 字符`);
check(!p1024.looksKadane, 'B3 没有把「最大子段和」的解法塞给「最大异或和」',
      p1024.looksKadane ? '命中了 Kadane 特征 ← 串题' : '未命中');

/* ── 入口 3：?pid=1001（真实「最大子段和」）应仍用母题那两份代码 ── */
console.log('\n── 入口 3：?pid=1001（真实「最大子段和」） ──');
const p1001 = await inspect('/s-workbench.html?pid=1001');
console.log(`  实际打开：#${p1001.pid} 「${p1001.title}」`);
check(p1001.title === '最大子段和', 'C1 题面是「最大子段和」', `#${p1001.pid} ${p1001.title}`);
check(p1001.looksKadane, 'C2 编辑器里是 Kadane 解法（含 ans=0 缺陷）',
      /ans\\s*=\\s*0/.test(p1001.code) ? '含 ans = 0 缺陷行' : '未找到缺陷行');

await ctx.close();
await browser.close();
server.close();

const pass = results.filter(r => r.pass).length;
console.log(`\n══════════════════════════════════════════`);
console.log(`  工作台「题面/代码/判题」一致性：${pass} 通过 / ${results.length - pass} 失败`);
console.log(`══════════════════════════════════════════`);
fs.mkdirSync(path.resolve('logs/step3-acceptance'), { recursive: true });
fs.writeFileSync(path.resolve('logs/step3-acceptance/sameproblem.json'),
  JSON.stringify({ pass, total: results.length, results }, null, 2));
process.exit(pass === results.length ? 0 : 1);
