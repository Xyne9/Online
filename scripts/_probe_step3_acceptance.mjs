/**
 * 第 3 步「验收标志」逐字复现探针
 *
 * 手册原文：
 *   你在工作台输入一段故意把初始值写成 `ans = 0` 的 C++ 代码，点击【提交评测】，
 *   1 秒后 10 个测试点格子真实点亮 8 个绿格、第 3 个红格（WA，得分 80），
 *   点击第 3 格能看到真实的输入输出对比！
 *
 * 本探针不读任何硬编码期望分，期望值全部从真实判题接口推导，只回答：
 *   Q1 打开「最大子段和」母题时，题面 / 代码 / 判题是否三者同源
 *   Q2 提交 ans=0 缺陷代码后：10 格、8 绿、得分 80
 *   Q3 点红格能看到真实的输入 / 期望 / 实际对比
 *
 * 用法：node scripts/_probe_step3_acceptance.mjs [pid]
 *   pid 缺省取题库里 title == '最大子段和' 那道题的真实 ID（不写死）
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
function check(pass, label, detail = '') {
  results.push({ pass, label, detail: detail === undefined ? '' : String(detail) });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${label}${detail ? '\n      ' + detail : ''}`);
}

/* ── 真实登录 ── */
const lr = await fetch(`${GATEWAY}/api/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: '20220101', password: '123456' })
});
const lj = await lr.json();
if (!lj.data || !lj.data.accessToken) throw new Error('登录失败: ' + JSON.stringify(lj).slice(0, 300));
const TOKEN = lj.data.accessToken;
console.log(`登录成功，token ${TOKEN.length} 字符`);

async function apiGet(p) {
  const r = await fetch(`${GATEWAY}${p}`, { headers: { Authorization: 'Bearer ' + TOKEN } });
  return r.json();
}

/* 找出「最大子段和」的真实 ID（绝不写死；列表按 100 条分页，必须走 kw 搜索） */
async function findMother() {
  for (let page = 1; page <= 12; page++) {
    const r = await apiGet(`/api/problem/questions?page=${page}&pageSize=100`);
    const hit = (r.data.list || []).find(p => p.title === '最大子段和');
    if (hit) return hit;
    if (!r.data.hasNext) break;
  }
  return null;
}
const kw = await apiGet('/api/problem/questions?page=1&pageSize=20&kw=' + encodeURIComponent('最大子段和'));
const mother = (kw.data.list || []).find(p => p.title === '最大子段和') || await findMother();
if (!mother) throw new Error('题库里找不到「最大子段和」');
const PID = Number(process.argv[2] || mother.id);
console.log(`「最大子段和」真实 ID = ${mother.id}（本次用 pid=${PID}）\n`);

const detail0 = (await apiGet(`/api/problem/questions/${PID}`)).data;
const REAL_CASES = detail0.testCaseCount || detail0.cases || null;
console.log(`后端声明：测试点 ${REAL_CASES} 个，时限 ${detail0.timeLimit}ms / 内存 ${detail0.memoryLimit}MB\n`);

/* ── 浏览器 ── */
const browser = await chromium.launch();
const errors = [];
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
await ctx.addInitScript(t => {
  localStorage.setItem('OJ_TOKEN', t);
  localStorage.setItem('OJ_ROLE', 'student');
  localStorage.setItem('OJ_USER', JSON.stringify({
    realName: '林昭', role: 'STUDENT', className: '计算机 2201', avatarText: '林'
  }));
}, TOKEN);
const page = await ctx.newPage();
page.on('pageerror', e => errors.push('pageerror: ' + e.message));
page.on('console', m => {
  if (m.type() !== 'error') return;
  const t = m.text();
  if (/Failed to load resource|net::ERR_|favicon/i.test(t)) return;
  errors.push('console: ' + t);
});
const D = `Alpine.$data(document.getElementById('page-content') || document.body)`;

/* 手册里那段「故意把初始值写成 ans = 0」的 C++ 代码 */
const BUGGY = [
  '#include <iostream>',
  '#include <algorithm>',
  'using namespace std;',
  'int main() {',
  '    int n;',
  '    cin >> n;',
  '    int dp = 0, ans = 0;',          // ← 缺陷：ans 初值 0，全负数会输出 0
  '    for (int i = 0; i < n; i++) {',
  '        int x;',
  '        cin >> x;',
  '        dp = max(dp + x, x);',
  '        ans = max(ans, dp);',
  '    }',
  '    cout << ans << endl;',
  '    return 0;',
  '}'
].join('\n');

/* ══════════════════════════════════════════════════════════════════
   Q1 题面 / 代码 / 判题三者同源
   ══════════════════════════════════════════════════════════════════ */
console.log('── Q1 打开「最大子段和」，题面与判题同源 ──');
await page.goto(`http://127.0.0.1:${PORT}/s-workbench.html?pid=${PID}`, { waitUntil: 'networkidle', timeout: 30000 });
await page.waitForTimeout(3000);

const init = await page.evaluate(`(() => {
  const d = ${D};
  return { live: d.live, pid: d.problemId, title: d.problem && d.problem.title,
           code: d.code || '', cases: (d.cases || []).length,
           total: d.totalCount, hasProblem: !!d.problem };
})()`);
check(init.live, 'Q1.1 工作台处于真实态', `live=${init.live} pid=${init.pid}`);
check(init.hasProblem, 'Q1.2 已拉到当前题详情', `title=${init.title}`);
check(init.title === '最大子段和', 'Q1.3 打开的确实是「最大子段和」', `#${init.pid} ${init.title}`);

/* ══════════════════════════════════════════════════════════════════
   Q2 写入手册那段缺陷代码 → 提交 → 真实判题
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── Q2 提交 ans=0 缺陷代码 → 真实判题 ──');
await page.evaluate(`(() => {
  const d = ${D};
  d.code = ${JSON.stringify(BUGGY)};
  d.codeFixed = ${JSON.stringify(BUGGY)};
  d.fixed = true;
})()`);

await page.waitForTimeout(3200);          // 3 秒防抖锁
await page.evaluate(`(() => { const d = ${D}; d.submit(); })()`);

let t0 = Date.now(), final = null;
for (let i = 0; i < 150; i++) {
  await page.waitForTimeout(500);
  const s = await page.evaluate(`(() => {
    const d = ${D};
    return { result: d.result, judging: d.judging, cases: (d.cases||[]).length,
             score: d.score, lit: d.lit, err: d.errMsg, displayNo: d.displayNo };
  })()`);
  if (!s.judging && s.result) { final = { ...s, elapsed: Date.now() - t0 }; break; }
}
await page.waitForTimeout(1000);          // 分数滚动动画落定
const settled = await page.evaluate(`(() => {
  const d = ${D};
  return { score: d.score, pass: d.passCount, total: d.totalCount, sub: d.submissionId };
})()`);

check(!!final, 'Q2.1 判题落定（60s 内）',
      final ? `${final.elapsed}ms result=${final.result} 提交号=${final.displayNo}` : '超时');
check(!!final && final.result === 'wa', 'Q2.2 缺陷代码 → 未通过（不造假 AC）',
      final ? `result=${final.result}` : '-');

const expectCells = Number(REAL_CASES) || 10;
check(final && final.cases === expectCells, `Q2.3 判分链格子数 = 真实测试点数 ${expectCells}`,
      final ? `${final.cases} 格` : '-');
const domCells = await page.evaluate(
  `[...document.querySelectorAll('.chain .cell')].map(e => e.className.trim())`);
check(domCells.length === expectCells, 'Q2.4 DOM 判分链渲染格数一致', `${domCells.length} 格`);

check(settled.pass === 8, 'Q2.5 绿格数 = 8', `${settled.pass} / ${settled.total}`);
check(settled.score === 80, 'Q2.6 得分 = 80', `${settled.score}`);

/* ══════════════════════════════════════════════════════════════════
   Q3 点红格 → 真实输入/期望/实际
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── Q3 点红格 → 真实输入 / 期望 / 实际对比 ──');
const casesDump = await page.evaluate(`(() => {
  const d = ${D};
  return (d.cases || []).map(c => ({
    seqNo: c.seqNo, status: c.status,
    inP: c.inputPreview, exP: c.expectedPreview, acP: c.actualPreview,
    in2: c.input, ex2: c.expected, ac2: c.actual
  }));
})()`);
const redIdx = casesDump.findIndex(c => String(c.status).toUpperCase() !== 'AC');
check(redIdx >= 0, 'Q3.1 存在红格（未通过测试点）',
      redIdx >= 0 ? `首个红格 = 测试点 ${casesDump[redIdx].seqNo}` : '(全绿，不符合缺陷代码预期)');

if (redIdx >= 0) {
  const cell = casesDump[redIdx];
  await page.evaluate(`document.querySelectorAll('.chain .cell')[${redIdx}].click()`);
  await page.waitForTimeout(900);
  const focus = await page.evaluate(`(() => { const d = ${D}; return d.focusCase; })()`);
  check(Number(focus) === redIdx + 1, 'Q3.2 点击后页面聚焦到该测试点',
        `focusCase=${focus}（期望 ${redIdx + 1}）`);

  const inp = cell.inP || cell.in2 || '';
  const exp = cell.exP || cell.ex2 || '';
  const act = cell.acP || cell.ac2 || '';
  check(!!String(inp).trim(), `Q3.3 测试点 ${cell.seqNo} 有真实输入`,
        String(inp).slice(0, 60).replace(/\r?\n/g, '⏎'));
  check(!!String(exp).trim(), `Q3.4 测试点 ${cell.seqNo} 有真实期望输出`,
        String(exp).slice(0, 60).replace(/\r?\n/g, '⏎'));
  check(!!String(act).trim(), `Q3.5 测试点 ${cell.seqNo} 有真实实际输出`,
        String(act).slice(0, 60).replace(/\r?\n/g, '⏎'));
  check(String(exp).trim() !== String(act).trim(), 'Q3.6 期望 ≠ 实际（确实是 WA）',
        `期望 ${String(exp).trim().slice(0, 20)} / 实际 ${String(act).trim().slice(0, 20)}`);
}

check(errors.length === 0, 'Q4 全流程 0 pageerror / 0 console error', errors.slice(0, 3).join(' | '));

await ctx.close();
await browser.close();
server.close();

const pass = results.filter(r => r.pass).length;
console.log(`\n══════════════════════════════════════════════════`);
console.log(`  第 3 步「验收标志」逐字复现：${pass} 通过 / ${results.length - pass} 失败（共 ${results.length} 项）`);
console.log(`══════════════════════════════════════════════════`);

const outDir = path.resolve('logs/step3-acceptance');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({
  pid: PID, motherId: mother.id, expectCells, pass, total: results.length, results
}, null, 2));

process.exit(pass === results.length ? 0 : 1);
