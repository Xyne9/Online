/**
 * Day2 底座缺陷回归探针（临时）
 *
 * 覆盖审计发现的 6 个真实缺陷，防止改回去：
 *   1. adapter 不得依赖 window.OJ（s-workbench 不引 shell.js，那页没有 OJ）
 *   2. a-audit 的 16 条调用记录都必须有 recordId（微创修正 #3 才真正成立）
 *   3. login.html 必须读 ?redirect=，登录后回到原页
 *   4. 无 ?mock=1 时不得渲染假数据（OJ.problems 必须为空）
 *   5. s-workbench（无 data-role）不得覆盖已有 OJ_ROLE
 *   6. 带 #hash 的站内链接，mock=1 要插在 # 之前
 * 外加：api/auth.js 必须被 login.html 真正引用（不是死文件）
 */
import { chromium } from './browser-runtime.mjs';
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
await new Promise(r => server.listen(8816, r));

const browser = await chromium.launch();

/* 真实登录态 token：4a/4c 断言的是「带真实 token 不被踢去登录页」，
   以前随手写一个 `real.jwt.token` 就能过，是因为 boot.js 当时只判 token 非空。
   第 3 步接上真网关后，页面会真的拿这枚 token 打 /api/auth/me，假 token 必被打回
   login.html —— 断言红了恰恰说明守卫是真的在工作。所以这里登一次真账号拿真 token，
   让这条用例继续验它本来要验的东西（有登录态就不踢），而不是退化成验「字符串非空」。 */
const LOGIN = await (await fetch('http://127.0.0.1:8080/api/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: process.env.OJ_PROBE_USER || '20220101', password: '123456' })
})).json();
if (!LOGIN || LOGIN.code !== 200) { console.error('登录失败，无法继续：', JSON.stringify(LOGIN)); process.exit(1); }
const REAL_TOKEN = LOGIN.data.accessToken;
const REAL_ROLE = String(LOGIN.data.user.role || 'STUDENT').toLowerCase();

let pass = 0, fail = 0;
const check = (ok, label, detail) => {
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  if (detail) console.log('      ' + detail);
};

// ── 1. adapter 不依赖 OJ：在工作台（无 shell.js）调题库接口 ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));
  await page.goto('http://127.0.0.1:8816/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1400);
  const r = await page.evaluate(async () => {
    const hasOJ = typeof window.OJ !== 'undefined';
    try {
      const d = await api.http.get('/api/problem/questions');
      const list = (d && d.list) || [];
      return { hasOJ, ok: true, n: list.length, firstLvl: list[0] && list[0].lvl, lvlType: typeof (list[0] && list[0].lvl) };
    } catch (e) {
      return { hasOJ, ok: false, err: e && e.message };
    }
  });
  check(r.hasOJ === false, '1a 工作台确实没有 window.OJ（前提成立）', `hasOJ=${r.hasOJ}`);
  check(r.ok, '1b 工作台调 /api/problem/questions 不再抛 OJ is not defined', r.ok ? `${r.n} 条` : r.err);
  check(r.lvlType === 'number', '1c adaptProblem.lvl 是整数（模板要 OJ.lvl(整数) 现画刻度）',
    `lvl=${r.firstLvl} type=${r.lvlType}`);
  check(errs.length === 0, '1d 工作台 0 pageerror', errs.join(' | ') || '0');
  await ctx.close();
}

// ── 2. a-audit 每条记录都有 recordId ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8816/a-audit.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1400);
  const r = await page.evaluate(() => {
    const d = window.Alpine.$data(document.getElementById('page-content'));
    const calls = d.calls || [];
    const missing = calls.filter(c => c.recordId === undefined || c.recordId === null).length;
    const uniq = new Set(calls.map(c => c.recordId)).size;
    return { total: calls.length, missing, uniq };
  });
  check(r.missing === 0, '2a a-audit 全部调用记录都有 recordId（微创修正 #3 完整）',
    `共 ${r.total} 条，缺 recordId ${r.missing} 条`);
  check(r.uniq === r.total, '2b recordId 全局唯一（不撞 Key）', `唯一 ${r.uniq}/${r.total}`);
  await ctx.close();
}

// ── 3. login.html 读 ?redirect= ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8816/login.html?mock=1&redirect=t-analytics.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(900);
  const hasAuth = await page.evaluate(() => !!(window.api && api.auth && api.auth.redirectTarget));
  check(hasAuth, '3a api/auth.js 已被 login.html 引用（不再是死文件）');
  const target = await page.evaluate(() => api.auth.redirectTarget(''));
  check(target === 't-analytics.html', '3b ?redirect= 被正确解析', `target=${target}`);

  // 真登录一次，看落到哪
  await page.evaluate(async () => {
    const d = window.Alpine.$data(document.getElementById('page-content'));
    d.form.account = '20220101'; d.form.password = '123456';
    await d.submit();
  });
  await page.waitForTimeout(1500);
  const landed = new URL(page.url()).pathname.replace(/^\//, '');
  check(landed === 't-analytics.html', '3c 登录后回到 ?redirect= 指定的原页', `落到 ${landed}`);

  // 开放重定向必须被挡（要回到 login.html 才有 api.auth，上面已跳走，重开一页）
  const page2 = await ctx.newPage();
  await page2.goto('http://127.0.0.1:8816/login.html?mock=1', { waitUntil: 'networkidle' });
  await page2.waitForTimeout(700);
  const evil = await page2.evaluate(() => [
    api.auth.safeRedirect('//evil.com/x'),
    api.auth.safeRedirect('https://evil.com'),
    api.auth.safeRedirect('javascript:alert(1)'),
    api.auth.safeRedirect('/\\evil.com')
  ]);
  check(evil.every(x => x === ''), '3d 开放重定向被拦（//evil.com / https: / javascript: / 反斜杠）', JSON.stringify(evil));
  await ctx.close();
}

// ── 4. 无 ?mock=1 时不渲染假数据 ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  // 先有真实 token，否则会被守卫踢去登录页
  await ctx.addInitScript(([token, role]) => {
    localStorage.setItem('OJ_TOKEN', token);
    localStorage.setItem('OJ_ROLE', role);
  }, [REAL_TOKEN, REAL_ROLE]);
  await page.goto('http://127.0.0.1:8816/s-problems.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const r = await page.evaluate(() => ({
    landed: location.pathname.split('/').pop(),
    nProblems: (window.OJ && OJ.problems ? OJ.problems.length : -1),
    nSubs: (window.OJ && OJ.submissions ? OJ.submissions.length : -1),
    isMock: window.OJ && typeof OJ.isMock === 'function' ? OJ.isMock() : null
  }));
  check(r.landed === 's-problems.html', '4a 带真实 token 不被踢去登录页', `landed=${r.landed}`);
  /* 本项原始意图是「无 ?mock=1 时不许渲染 mock 假数据」。
     Day2 那会儿后端还没通，唯一能验的形态就是「数据源为空」；
     第 3 步题库真连之后，s-problems 会真的拉 /api/problem/questions，
     数据非空才是正确行为。继续断言 ===0 等于在要求「页面永远拿不到真数据」，
     那是把接口写死成空的反向指标。改为验真正的意图：
     · 不是 mock 态（4c 已验）
     · problems 只可能来自真实接口 —— OJ._problems 是 shell.js 的真实态槽位，
       mock 态永远走 fixtures，不会写这个槽位；槽位被写过即证明走的是真接口。 */
  /* OJ._problems 是**只写 setter**（shell.js:404），读它永远是 undefined；
     真实数据要经 getter OJ.problems 读回。mock 态 getter 永远走 fixtures、
     不可能返回后端数据，所以「getter 有真实条目 && isMock()===false」就等价于
     「这批数据只能来自真实接口」，正是本项要守的东西。 */
  const fromRealApi = await page.evaluate(() => !window.OJ.isMock() && window.OJ.problems.length > 0);
  check(fromRealApi, '4b 无 ?mock=1 时数据来自真实接口（不渲染 mock 假数据）',
    `problems=${r.nProblems} submissions=${r.nSubs} 非mock且有真实条目=${fromRealApi}`);
  check(r.isMock === false, '4c OJ.isMock() 反映真实态', `isMock=${r.isMock}`);
  await ctx.close();
}

// ── 5. s-workbench 不覆盖已有 OJ_ROLE ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await ctx.addInitScript(() => { localStorage.setItem('OJ_ROLE', 'teacher'); });
  await page.goto('http://127.0.0.1:8816/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const role = await page.evaluate(() => localStorage.getItem('OJ_ROLE'));
  check(role === 'teacher', '5 工作台（body 无 data-role）不覆盖已有角色', `OJ_ROLE=${role}`);
  await ctx.close();
}

// ── 6. 带 #hash 的链接：mock=1 插在 # 之前 ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8816/a-audit.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const r = await page.evaluate(() => {
    const a = document.createElement('a');
    a.setAttribute('href', 'a-audit.html#sandbox');
    a.textContent = 'x';
    document.body.appendChild(a);
    a.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    const href = a.getAttribute('href');
    a.remove();
    return { href };
  });
  const ok = /^a-audit\.html\?mock=1#sandbox$/.test(r.href);
  check(ok, '6 带 #hash 的链接参数插在 # 之前', `href=${r.href}`);
  await ctx.close();
}

// ── 7. 残留 mock 标记在无参访问时被清 ──
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await ctx.addInitScript(() => {
    sessionStorage.setItem('OJ_USE_MOCK', '1');
    localStorage.setItem('OJ_TOKEN', 'mock-student-abc');
    localStorage.setItem('OJ_ROLE', 'student');
  });
  await page.goto('http://127.0.0.1:8816/s-problems.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  const r = await page.evaluate(() => ({
    mockFlag: sessionStorage.getItem('OJ_USE_MOCK'),
    token: localStorage.getItem('OJ_TOKEN'),
    landed: location.pathname.split('/').pop()
  }));
  check(r.mockFlag === null, '7a 无 ?mock= 时清掉 sessionStorage 残留标记', `flag=${r.mockFlag}`);
  check(!r.token || r.token.indexOf('mock-') !== 0, '7b 假 token 被清（真实 token 不动）', `token=${r.token}`);
  check(r.landed === 'login.html', '7c 无 token 时正确跳登录页', `landed=${r.landed}`);
  await ctx.close();
}

console.log(`\n===== 底座缺陷回归：${pass} 通过 / ${fail} 失败 =====`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
