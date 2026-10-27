/**
 * Day2 工作台 AI 追问验收（临时探针）
 *
 * 验证 PRD 微创修正 #4 / 03 指南 §5.2 的「两问一答」问题是否真的消除：
 *   同一页面里问两个不同的问题，必须得到两段不同的回复。
 * 同时验证 §5.3 的配额规则：
 *   前端不再自减 quotaLeft（submit 不重置、ask 不扣），配额耗尽时输入框禁用。
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
await new Promise(r => server.listen(8813, r));

const browser = await chromium.launch();
let pass = 0, fail = 0;
const check = (ok, label, detail) => {
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  if (detail) console.log('      ' + detail);
};

// A: 两问两答 —— 问 TLE 与问边界，回复必须不同
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await page.goto('http://127.0.0.1:8813/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);

  const answers = await page.evaluate(async () => {
    const root = document.body;
    const d = window.Alpine.$data(root);
    const get = () => d.chat.filter(m => m.role === 'ai').map(m => m.text);

    d.ask('这段代码为什么 TLE');
    await new Promise(r => setTimeout(r, 1100));
    const a1 = get().slice(-1)[0];

    d.ask('边界情况该怎么自己测');
    await new Promise(r => setTimeout(r, 1100));
    const a2 = get().slice(-1)[0];
    return { a1, a2, quota: d.quotaLeft, quotaMax: d.quotaMax };
  });

  check(!!answers.a1 && !!answers.a2 && answers.a1 !== answers.a2,
    'A 两问两答（不同问题得到不同回复）',
    `q1 -> ${String(answers.a1).slice(0, 42)}...\n      q2 -> ${String(answers.a2).slice(0, 42)}...`);
  check(answers.quota === 2, 'B 前端不再自减配额（问两次后 quotaLeft 仍由服务端裁决）',
    `quotaLeft=${answers.quota}/${answers.quotaMax}（Stage 0 占位值，阶段 2 由 SSE done 写入）`);
  check(errs.length === 0, 'C 追问全流程无 console/pageerror', errs.join(' | ') || '0 错误');
  await ctx.close();
}

// D: submit() 不重置配额
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8813/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1600);
  const r = await page.evaluate(async () => {
    const d = window.Alpine.$data(document.body);
    d.quotaLeft = 0;                  // 模拟服务端已扣到 0
    d.submit();                        // 提交判题
    await new Promise(x => setTimeout(x, 2000));
    const after = d.quotaLeft;
    const inputDisabled = document.querySelector('.ask-input').disabled;
    const btnDisabled = document.querySelector('.ask-btn').disabled;
    return { after, inputDisabled, btnDisabled };
  });
  check(r.after === 0, 'D submit() 不再把配额重置回 2', `quotaLeft=${r.after}`);
  check(r.inputDisabled && r.btnDisabled, 'E 配额耗尽后输入框与发送按钮禁用',
    `input.disabled=${r.inputDisabled} btn.disabled=${r.btnDisabled}`);
  await ctx.close();
}

// F: 代码行不再假装与测试点联动（focusFromLine 已删）
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8813/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const r = await page.evaluate(() => {
    const d = window.Alpine.$data(document.body);
    return {
      hasFocusFromLine: typeof d.focusFromLine === 'function',
      hasFocusLine: Object.getOwnPropertyDescriptor(d, 'focusLine') !== undefined ||
                    (function () { try { return d.focusLine !== undefined; } catch (e) { return true; } })(),
      focusCase: d.focusCase
    };
  });
  check(!r.hasFocusFromLine, 'F focusFromLine() 已删除（代码行不再假装与测试点相关）');
  check(r.hasFocusLine === false, 'G focusLine getter 已删除');
  await ctx.close();
}

// H: 测试点维度联动仍然可用（点第 5 格 → body[data-focus] 变 ok）
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:8813/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  await page.evaluate(() => {
    document.querySelectorAll('.chain .cell')[4].dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
  });
  await page.waitForTimeout(300);
  const focus = await page.evaluate(() => document.body.getAttribute('data-focus'));
  check(focus === 'ok', 'H 测试点维度联动仍生效（悬停第 5 格 → data-focus=ok）', `data-focus=${focus}`);
  await ctx.close();
}

console.log(`\n===== 工作台验收：${pass} 通过 / ${fail} 失败 =====`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
