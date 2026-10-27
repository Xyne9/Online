/**
 * Day2 工作台 AI 追问验收（临时探针）
 *
 * 验证 PRD 微创修正 #4 / 03 指南 §5.2 的「两问一答」问题是否真的消除：
 *   同一页面里问两个不同的问题，必须得到两段不同的回复。
 * 同时验证 §5.3 的配额规则：
 *   前端不再自减 quotaLeft（submit 不重置、ask 不扣），配额耗尽时输入框禁用。
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
await new Promise(r => server.listen(8813, r));

const browser = await chromium.launch();
let pass = 0, fail = 0;
const report = [];
const check = (ok, label, detail) => {
  report.push({ pass: !!ok, label, detail: detail || null });
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  if (detail) console.log('      ' + detail);
};

// A: 两问两答 —— 问 TLE 与问边界，回复必须不同
{
  const ctx = await browser.newContext();
  // favicon.ico 在静态服务器上必然 404，会被当成 console error 判红（纯噪声）
  await ctx.route('**/favicon.ico', route => route.fulfill({ status: 204, body: '' }));
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource|net::ERR_|favicon/i.test(m.text())) errs.push(m.text()); });
  await page.goto('http://127.0.0.1:8813/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);

  const answers = await page.evaluate(async () => {
    const root = document.body;
    const d = window.Alpine.$data(root);
    const get = () => d.chat.filter(m => m.role === 'ai').map(m => m.text);
    const waitDone = async () => {
      const deadline = Date.now() + 12000;
      while (d.replying && Date.now() < deadline) await new Promise(r => setTimeout(r, 40));
      if (d.replying) throw new Error('AI SSE 未在 12s 内以 done/error/end 收尾');
    };

    d.ask('这段代码为什么 TLE');
    const quotaDuringReply = d.quotaLeft;
    await waitDone();
    const a1 = get().slice(-1)[0];

    d.ask('边界情况该怎么自己测');
    await waitDone();
    const a2 = get().slice(-1)[0];
    return { a1, a2, quotaDuringReply, quota: d.quotaLeft, quotaMax: d.quotaMax,
             questionCount: d.chat.filter(m => m.role === 'me').length, replyCount: get().length };
  });

  check(!!answers.a1 && !!answers.a2 && answers.a1 !== answers.a2 && answers.questionCount === 2 && answers.replyCount === 2,
    'A 两问两答（不同问题得到不同回复）',
    `q1 -> ${String(answers.a1).slice(0, 42)}...\n      q2 -> ${String(answers.a2).slice(0, 42)}...`);
  check(answers.quotaDuringReply === 2 && answers.quota === 1,
    'B 提问时不自减，两个 SSE done 后配额按服务端值更新',
    `发送后=${answers.quotaDuringReply}；两次完成后=${answers.quota}/${answers.quotaMax}`);
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

// I: 真实态属性绑定，受控契约响应只测前端，不访问真实网关或模型。
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const errs = [];
  const stages = [1, 2, 3, 4].map(stage => ({ stage, stageName: '真实阶段' + stage,
    unlocked: stage === 1, lockReason: stage === 4 ? '通过后复盘' : '此阶段暂未开放' }));
  const channels = [{ channelCode: 'local-qwen', channelName: '边缘教学', modelName: 'Tutor-Local-v2', healthStatus: 'ON' },
    { channelCode: 'cloud-ds', channelName: '远程教学', modelName: 'Tutor-Cloud-v3', healthStatus: 'DEGRADE' }];
  await ctx.addInitScript(() => { localStorage.setItem('OJ_TOKEN', 'workbench-contract-fixture'); });
  await ctx.route('**/favicon.ico', route => route.fulfill({ status: 204, body: '' }));
  await ctx.route('http://127.0.0.1:8080/api/**', async route => {
    const req = route.request(), pathname = new URL(req.url()).pathname;
    const headers = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'authorization,content-type,x-trace-id', 'Access-Control-Allow-Methods': 'GET,OPTIONS' };
    if (req.method() === 'OPTIONS') { await route.fulfill({ status: 204, headers, body: '' }); return; }
    let data;
    if (pathname === '/api/ai/workspace-init') data = { aiEnabled: true, modeDesc: '真实练习策略', remainingQuota: -1, maxQuota: -1, stageCapsules: stages, availableChannels: channels, chatHistory: [] };
    else if (pathname === '/api/ai/channels') data = { list: channels };
    else if (pathname === '/api/ai/quota') data = { aiEnabled: true, remaining: -1, max: -1, unlimited: true, stages };
    else if (pathname === '/api/ai/sessions') data = { list: [] };
    else if (pathname === '/api/problem/questions/1006') data = { codeTemplates: { cpp: 'int main(){}' }, myRecord: { submitted: false } };
    else { errs.push('未声明的契约路径：' + pathname); await route.abort(); return; }
    await route.fulfill({ status: 200, headers, json: { code: 200, message: 'frontend contract fixture', data } });
  });
  page.on('pageerror', e => errs.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await page.goto('http://127.0.0.1:8813/s-workbench.html?pid=1006', { waitUntil: 'networkidle' });
  await page.waitForFunction(() => Alpine.$data(document.body).aiReady);
  await page.waitForFunction(() => document.querySelectorAll('.panel')[0].getClientRects().length > 0);
  const initial = await page.evaluate(async () => {
    const d = Alpine.$data(document.body); d.askText = '问题'; d.modelMenu = true;
    await new Promise(r => d.$nextTick(r));
    const visible = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
    return { stage: d.stage, hiddenSamples: [...document.querySelectorAll('.ai-body,.ai-pin,.ai-next,.think')].every(el => !visible(el)),
      quota: document.querySelector('.rail-quota b').textContent, tipHidden: !visible(document.querySelector('.ask-tip')),
      inputTitle: document.querySelector('.ask-input').title, sendTitle: document.querySelector('.ask-btn').title,
      model: document.querySelector('.model-cur').textContent,
      menu: [...document.querySelectorAll('.mo-head,.mo-sub')].map(el => el.textContent).join('|') };
  });
  check(initial.stage === 1 && initial.hiddenSamples, 'I1 真实态默认选开放阶段，固定AI卡片全部隐藏');
  check(initial.quota === '不限' && initial.tipHidden && !initial.inputTitle.includes('消耗') && !initial.sendTitle.includes('消耗'),
    'I2 无限练习只显示不限，隐藏扣费提示且标题不声称消耗次数');
  check(initial.model === '边缘教学' && initial.menu.includes('Tutor-Local-v2') && initial.menu.includes('远程教学') && initial.menu.includes('Tutor-Cloud-v3'),
    'I3 模型菜单名称、副文案读取真实通道与模型字段');
  await page.evaluate(() => Alpine.$data(document.body).setStage(4));
  await page.waitForFunction(() => document.querySelector('.locked-panel')?.getClientRects().length > 0);
  const locked = await page.evaluate(() => {
    return { text: document.querySelector('.locked-panel').innerText, placeholder: document.querySelector('.ask-input').placeholder,
      disabled: document.querySelector('.ask-input').disabled };
  });
  check(locked.text.includes('通过后复盘') && locked.text.includes('暂无判题结果') && !locked.text.includes('80 / 100') && !locked.text.includes('第 3 号测试点'),
    'I4 真实锁定面板显示锁定原因，无虚构80分/第3点', JSON.stringify(locked));
  check(locked.disabled && locked.placeholder === '通过后复盘', 'I5 锁定阶段输入禁用且显示服务端原因');
  await page.evaluate(() => {
    const d = Alpine.$data(document.body);
    d.applyQuota({ remaining: -1, max: -1, unlimited: true, stages: [1, 2, 3, 4].map(stage => ({ stage, unlocked: true, stageName: '真实阶段' + stage })) });
    d.judgeStatus = 5; d.cases = [{ seqNo: 1, score: 10, gotScore: 10 }, { seqNo: 7, score: 10, gotScore: 0 }];
    d.setStage(3);
  });
  await page.waitForFunction(() => document.querySelectorAll('.panel')[2].getClientRects().length > 0);
  const diagnosis = await page.evaluate(() => {
    const third = [...document.querySelectorAll('.panel')][2];
    return { text: third.querySelector('.ctx-v').textContent, status: third.querySelector('.ctx-chip').textContent };
  });
  await page.evaluate(() => Alpine.$data(document.body).setStage(4));
  await page.waitForFunction(() => document.querySelectorAll('.panel')[3].getClientRects().length > 0);
  const actual = await page.evaluate(() => {
    const fourth = [...document.querySelectorAll('.panel')][3];
    return { stats: fourth.querySelector('.ctx-v').textContent,
      visibleSample: [...fourth.querySelectorAll('.ai-body,.ai-pin,.ai-next,.think')].some(el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)) };
  });
  check(diagnosis.text === '第 7 号测试点' && diagnosis.status === 'WA', 'I6 诊断区绑定真实失败点和判题状态');
  check(actual.stats === '1 / 2 个测试点' && !actual.visibleSample, 'I7 复盘统计使用真实测试点数且隐藏示例复杂度/推荐');
  check(errs.length === 0, 'I8 真实态属性绑定0页面错误（API为受控契约stub）', errs.join(' | ') || '0 错误');
  await ctx.close();
}

console.log(`\n===== 工作台验收：${pass} 通过 / ${fail} 失败 =====`);
fs.mkdirSync(path.resolve('logs/day8-frontend'), { recursive: true });
fs.writeFileSync(path.resolve('logs/day8-frontend/workbench-report.json'), JSON.stringify({ pass, fail, results: report }, null, 2));
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
