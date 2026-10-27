/**
 * 第 3 步前端真连验收（真实后端，不用 ?mock=1）
 *
 * 守住三件事，缺一不可：
 *   A. s-problems 真实态：题库列表从 /api/problem/questions 取到真实数据，
 *      筛选/搜索/详情抽屉都能用，且**不泄漏 official_solution**。
 *   B. s-workbench 真实态：点「提交评测」→ 真实 POST /api/judge/submit
 *      → 1s 轮询 /api/judge/status → 判分链格子由真实 caseDetails 点亮。
 *      这是《6 步手册》第 3 步的验收标志。
 *   C. s-submissions 真实态：列表、抽屉、逐测试点输入/期望/实际都来自真实接口。
 *
 * 用法：node scripts/_probe_day4_frontend.mjs
 * 前置：4 个服务已启动（gateway/teaching/problem/judge）
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
  const u = decodeURIComponent((req.url || '/').split('?')[0]);
  const fp = path.join(APP, u === '/' ? 'index.html' : u);
  fs.readFile(fp, (e, b) => {
    if (e) { res.writeHead(404).end('nf'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream' });
    res.end(b);
  });
});
await new Promise(r => server.listen(8823, r));
const ORIGIN = 'http://127.0.0.1:8823';

let pass = 0, fail = 0;
const check = (ok, label, detail) => {
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  if (detail) console.log('      ' + detail);
};

/* ── 真登录拿 token ── */
const lr = await fetch('http://127.0.0.1:8080/api/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: '20220101', password: '123456' })
});
const lj = await lr.json();
if (!lj || lj.code !== 200) { console.error('登录失败，无法继续：', JSON.stringify(lj)); process.exit(1); }
const TOKEN = lj.data.accessToken;
const USER = lj.data.user;
console.log(`登录成功：${USER.realName} (${USER.role})  token ${TOKEN.length} 字符\n`);

const browser = await chromium.launch();

/** 开一个已登录的页面上下文 */
async function newPage(url) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('pageerror: ' + e.message));
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const t = m.text();
    if (/Failed to load resource|net::ERR_|favicon/i.test(t)) return;
    errs.push('console: ' + t);
  });
  await ctx.addInitScript((a) => {
    localStorage.setItem('OJ_TOKEN', a.token);
    localStorage.setItem('OJ_ROLE', 'student');
    localStorage.setItem('OJ_USER', JSON.stringify(a.user));
  }, { token: TOKEN, user: USER });
  await page.goto(ORIGIN + url, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1800);
  return { ctx, page, errs };
}

/* ══════════════════════════════════════════════════════════════════
   A. s-problems 真实态
   ══════════════════════════════════════════════════════════════════ */
console.log('── A. s-problems 真实态 ──');
{
  const { ctx, page, errs } = await newPage('/s-problems.html');
  const st = await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    return {
      live: d.live, loading: d.loading, loadErr: d.loadErr,
      count: d.OJ.problems.length,
      filtered: d.filtered.length,
      solved: d.solved, tried: d.tried,
      heroTitle: d.hero.title,
      firstLvl: d.OJ.problems[0] && d.OJ.problems[0].lvl,
      firstStatus: d.OJ.problems[0] && d.OJ.problems[0].status,
      stats: d.OJ.stats.problems,
      rows: document.querySelectorAll('.li:not(.sk-row)').length
    };
  });
  check(st.live && !st.loadErr, 'A1 真实态已激活且无错误', `live=${st.live} loadErr=${st.loadErr}`);
  check(st.count > 0, 'A2 题库列表加载真实数据', `${st.count} 道题（DOM ${st.rows} 行）`);
  check(st.firstLvl >= 1 && st.firstLvl <= 5, 'A3 难度是 1-5 整数（刻度能画出来）',
        `lvl=${st.firstLvl}`);
  check(['none', 'ac', 'wa', 'tle', 'mle', 're', 'ce', 'pac'].includes(st.firstStatus),
        'A4 我的状态映射到 CSS class', `status=${st.firstStatus}`);
  check(!!st.heroTitle && st.heroTitle !== '暂无题目', 'A5 主角题显示真实题目', st.heroTitle);

  /* 难度筛选：点「入门」应只剩 lvl=1 */
  await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    d.lvls = [1];
  });
  await page.waitForTimeout(900);
  const f1 = await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    return { n: d.filtered.length, allLvl1: d.filtered.every(p => p.lvl === 1) };
  });
  check(f1.allLvl1, 'A6 难度筛选真实生效（lvl=1 全是入门）', `剩余 ${f1.n} 道`);

  /* 详情抽屉：拉 #7 应带题面与样例，且不含 officialSolution */
  await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    d.lvls = [];
    d.openDetail(d.OJ.problems[0]);
  });
  await page.waitForTimeout(1200);
  const dd = await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    return {
      hasDetail: !!d.detail,
      p1: d.detailP1, limit: d.detailLimit, mem: d.detailMem, cases: d.detailCases,
      hasSamples: !!(d.detail && d.detail.samples && d.detail.samples.length),
      rawKeys: d.detail ? Object.keys(d.detail) : [],
      leaks: JSON.stringify(d.detail || {}).includes('officialSolution')
    };
  });
  check(dd.hasDetail, 'A7 详情抽屉可打开');
  check(dd.hasSamples, 'A8 详情含真实样例', `p1=${String(dd.p1).slice(0, 40)}…`);
  check(/ms/.test(dd.limit) && /MB/.test(dd.mem), 'A9 时限/内存来自真实题目',
        `${dd.limit} / ${dd.mem} / ${dd.cases}`);
  check(!dd.leaks, 'A10 详情不泄漏 officialSolution');
  check(errs.length === 0, 'A11 全流程 0 pageerror / 0 console error', errs.slice(0, 3).join(' | '));
  await ctx.close();
}

/* ══════════════════════════════════════════════════════════════════
   B. s-workbench 真实判题（第 3 步核心验收标志）
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── B. s-workbench 真实判题闭环 ──');
{
  /* 用 A+B 题（1006）：3 个测试点，确定性强。
     注意：工作台的编辑器代码现在「跟题走」——非母题会用后端下发的
     codeTemplates.cpp（骨架），所以这里**直接往编辑器里写一份正确的 A+B 解**，
     模拟学生答题的真实动作，而不是依赖页面预填的代码。 */
  const AB_SOLUTION = `#include <iostream>
int main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;return 0;}
`;
  const { ctx, page, errs } = await newPage('/s-workbench.html?pid=1006');
  const init = await page.evaluate(() => {
    const d = Alpine.$data(document.body);
    return { live: d.live, pid: d.problemId, result: d.result,
             codeLen: (d.code || '').length, hasProblem: !!d.problem };
  });
  check(init.live, 'B1 工作台真实态已激活', `pid=${init.pid} live=${init.live}`);
  check(init.hasProblem, 'B1b 已拉到当前题详情', `code ${init.codeLen} 字符`);

  /* 写入正确的 A+B 解并提交（模拟学生答题） */
  await page.evaluate((sol) => {
    const d = Alpine.$data(document.body);
    d.code = sol;
    d.codeFixed = sol;
    d.fixed = true;
    d.submit();
  }, AB_SOLUTION);

  /* 轮询页面自身状态直到终态（页面内部已在轮询接口，这里只等它落定） */
  let final = null;
  for (let i = 0; i < 90; i++) {
    await page.waitForTimeout(500);
    const s = await page.evaluate(() => {
      const d = Alpine.$data(document.body);
      return { result: d.result, judging: d.judging, cases: d.cases.length,
               score: d.score, lit: d.lit, err: d.errMsg, displayNo: d.displayNo };
    });
    if (!s.judging && s.result) { final = s; break; }
  }
  check(!!final, 'B2 判题在 45s 内落定', final ? `result=${final.result}` : '超时');
  if (final) {
    /* 分数是 480ms 滚动动画，终态刚到时可能还在中间值（实测抓到 97）。
       再等一拍读稳定值，否则会把「动画未结束」误判成「分数不对」。 */
    await page.waitForTimeout(900);
    const settled = await page.evaluate(() => {
      const d = Alpine.$data(document.body);
      return { score: d.score, scoreNum: d.scoreNum };
    });
    check(final.result === 'ac', 'B3 正确代码 → 页面显示 AC',
          `result=${final.result} score=${settled.score}`);
    check(final.cases === 3, 'B4 判分链格子数 = 真实测试点数(3)', `${final.cases} 个`);
    check(settled.score === 100, 'B5 分数滚动结束后 = 100', `${settled.score}`);
    check(!!final.displayNo, 'B6 显示真实提交号', final.displayNo);
    check(!final.err, 'B7 无错误信息', final.err || '(无)');
  }

  /* 判分链 DOM：3 格全绿 */
  const cells = await page.evaluate(() => {
    const els = [...document.querySelectorAll('.chain .cell')];
    return els.map(e => ({ cls: e.className, sub: (e.querySelector('span') || {}).textContent }));
  });
  check(cells.length === 3, 'B8 DOM 判分链渲染 3 格', `${cells.length} 格`);
  check(cells.every(c => /ac/.test(c.cls)), 'B9 3 格全部点亮为 AC',
        cells.map(c => c.cls.trim()).join(' | '));

  /* 阶段④解锁（AC 后应自动解锁） */
  const unlocked = await page.evaluate(() => {
    const d = Alpine.$data(document.body);
    return { stage4: d.stage4Unlocked, stage: d.stage };
  });
  check(unlocked.stage4, 'B10 AC 后阶段④解锁', `stage=${unlocked.stage}`);

  check(errs.length === 0, 'B11 全流程 0 pageerror / 0 console error', errs.slice(0, 3).join(' | '));
  await ctx.close();
}

/* ══════════════════════════════════════════════════════════════════
   B2. 错误代码 → 页面必须显示未通过（绝不假 AC）
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── B2. 错误代码不得假 AC ──');
{
  const WRONG = `#include <iostream>
int main(){long long a,b;std::cin>>a>>b;std::cout<<a-b<<std::endl;return 0;}
`;
  const { ctx, page, errs } = await newPage('/s-workbench.html?pid=1006');
  /* 等 3 秒防抖锁过期（上一段刚提交过） */
  await page.waitForTimeout(3400);
  await page.evaluate((src) => {
    const d = Alpine.$data(document.body);
    d.code = src;
    d.codeFixed = src;
    d.fixed = true;             // 即使标记为「已修正」，代码本身也是错的
    d.submit();
  }, WRONG);
  let final = null;
  for (let i = 0; i < 90; i++) {
    await page.waitForTimeout(500);
    const s = await page.evaluate(() => {
      const d = Alpine.$data(document.body);
      return { result: d.result, judging: d.judging, cases: d.cases.length,
               score: d.score, err: d.errMsg };
    });
    if (!s.judging && s.result) { final = s; break; }
  }
  check(!!final, 'B12 错误代码判题落定', final ? `result=${final.result}` : '超时');
  if (final) {
    check(final.result !== 'ac', 'B13 错误代码 → 页面不显示 AC（不造假）',
          `result=${final.result} score=${final.score}`);
    check(final.score < 100, 'B14 分数 < 100', `${final.score}`);
  }
  check(errs.length === 0, 'B15 0 pageerror / 0 console error', errs.slice(0, 3).join(' | '));
  await ctx.close();
}

/* ══════════════════════════════════════════════════════════════════
   C. s-submissions 真实态
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── C. s-submissions 真实态 ──');
{
  const { ctx, page, errs } = await newPage('/s-submissions.html');
  const st = await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    return {
      live: d.live, loadErr: d.loadErr, errMsg: d.errMsg,
      total: d.TOTAL, allLen: d.all.length, paged: d.paged.length,
      rows: document.querySelectorAll('tr.rowclick').length,
      first: d.paged[0] ? {
        no: d.paged[0].no, title: d.paged[0].title, lang: d.paged[0].lang,
        status: d.paged[0].status, pass: d.paged[0].pass,
        time: d.paged[0].time, mem: d.paged[0].mem, at: d.paged[0].at
      } : null
    };
  });
  check(st.live && !st.loadErr, 'C1 真实态已激活且无错误', st.errMsg || '');
  check(st.allLen > 0, 'C2 提交记录加载真实数据', `${st.allLen} 条（DOM ${st.rows} 行）`);
  check(st.rows > 0, 'C3 DOM 渲染出表格行', `${st.rows} 行`);
  if (st.first) {
    const f = st.first;
    check(!!f.no && !!f.title, 'C4 行含展示号与题名', `${f.no} · ${f.title}`);
    check(/^(C\+\+ 17|Java 17|Python 3)$/.test(f.lang), 'C5 语言展示名规范', f.lang);
    check(/^\d+\/\d+$/.test(f.pass), 'C6 通过数格式 n/m', f.pass);
    check(f.at !== '—' && /\d{2}-\d{2}/.test(f.at), 'C7 提交时间已格式化', f.at);
  }

  /* 打开抽屉：应拉 #23 拿代码与逐测试点 */
  await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    d.open(d.paged[0]);
  });
  await page.waitForTimeout(1500);
  const dt = await page.evaluate(() => {
    const d = Alpine.$data(document.getElementById('page-content'));
    return {
      hasDetail: !!d.detail,
      codeLines: d.codeLines.length,
      chainLen: d.chain.length,
      caseIo: d.caseIo.length,
      firstCase: d.caseIo[0] || null,
      passCount: d.passCount,
      hasCode: !!(d.detail && d.detail.code)
    };
  });
  check(dt.hasDetail, 'C8 抽屉可打开');
  check(dt.chainLen === 3 || dt.chainLen === 10, 'C9 判分链渲染', `${dt.chainLen} 格`);
  check(dt.caseIo > 0, 'C10 逐测试点输入/期望/实际可读', `${dt.caseIo} 组`);
  check(dt.hasCode || dt.codeLines > 0, 'C11 代码回看可用', `${dt.codeLines} 行`);
  if (dt.firstCase) {
    check(dt.firstCase.in !== undefined && dt.firstCase.out !== undefined,
          'C12 测试点含 input/expected/actual 三栏',
          `in=${String(dt.firstCase.in).slice(0, 20)}… out=${String(dt.firstCase.out).slice(0, 20)}…`);
  }
  check(errs.length === 0, 'C13 0 pageerror / 0 console error', errs.slice(0, 3).join(' | '));
  await ctx.close();
}

console.log(`\n══════════════════════════════════════════════════`);
console.log(`  第 3 步前端真连验收：${pass} 通过 / ${fail} 失败`);
console.log(`══════════════════════════════════════════════════`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
