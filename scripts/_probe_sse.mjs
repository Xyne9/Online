/**
 * Day2 SSE 六事件验收（临时探针）
 *
 * PRD 模块二 AC-2.1：6 种事件在工作台均有对应 UI 表现。
 * 本探针直接驱动 api/sse.js 的 MOCK 分支，确认六事件都能被触发与解析：
 *   router_step / tool_calling / fallback_notice / token / guardrail_rewrite / done
 *
 * 为什么要专门测：mock 只吐 4 个事件时，降级黄条与护栏折叠这两块 UI
 * 在断网演示里永远看不到，评审没法验证——而这正是论文的核心创新点。
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
await new Promise(r => server.listen(8815, r));

const browser = await chromium.launch();
const ctx = await browser.newContext();
// 浏览器会自动请求 /favicon.ico；静态服务器没这个文件就打 404,
// 于是「0 console error」的断言被一条纯噪声判红。这里直接 204 掉。
await ctx.route('**/favicon.ico', route => route.fulfill({ status: 204, body: '' }));
const page = await ctx.newPage();
const errs = [];
page.on('pageerror', e => errs.push('pageerror: ' + e.message));
page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource|net::ERR_|favicon/i.test(m.text())) errs.push('console: ' + m.text()); });
await page.goto('http://127.0.0.1:8815/s-workbench.html?mock=1', { waitUntil: 'networkidle' });
await page.waitForTimeout(1600);

let pass = 0, fail = 0;
const check = (ok, label, detail) => {
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`);
  if (detail) console.log('      ' + detail);
};

// 收集一次 SSE 流的全部事件名
// 真实签名：api.sse(url, opts) 建实例并自动 start，返回同一枚 SSEClient，
// 用 .on(name, fn) 链式注册监听。三个参数的写法是错的（不存在 handlers 参数）。
async function runStream(question) {
  return await page.evaluate(async (q) => {
    const names = [];
    const payloads = {};
    return await new Promise((resolve, reject) => {
      const on = (n) => (p) => { names.push(n); payloads[n] = p; };
      const client = api.sse('/api/ai/chat/stream', { body: { capsuleStage: 3, question: q } });
      client
        .on('router_step', on('router_step'))
        .on('tool_calling', on('tool_calling'))
        .on('fallback_notice', on('fallback_notice'))
        .on('token', on('token'))
        .on('guardrail_rewrite', on('guardrail_rewrite'))
        .on('done', (p) => { names.push('done'); payloads.done = p; resolve({ names, payloads }); })
        .on('error', (e) => reject(new Error(JSON.stringify(e))));
      setTimeout(() => resolve({ names, payloads, timeout: true }), 12000);
    });
  }, question);
}

// A: 正常路径 —— 四类事件（router_step / tool_calling / token / done）
// 问句刻意不含「代码/完整/给我」等护栏关键词，也不含「云端/降级」等降级关键词，
// 否则会走到 C/E 的分支，A 就不代表「正常路径」了。
const a = await runStream('为什么全负数输入时答案不对');
check(
  ['router_step', 'tool_calling', 'token', 'done'].every(n => a.names.includes(n)),
  'A 正常路径含 router_step / tool_calling / token / done',
  `事件序列: ${a.names.join(' → ')}`
);
check(!a.names.includes('fallback_notice') && !a.names.includes('guardrail_rewrite'),
  'A2 正常路径不夹带降级/护栏事件',
  `事件序列: ${a.names.join(' → ')}`);
check(a.payloads.done && typeof a.payloads.done.remainingQuota === 'number',
  'B done 事件携带 remainingQuota（唯一配额写入口）',
  `remainingQuota=${a.payloads.done && a.payloads.done.remainingQuota}`);

// C: 降级路径 —— fallback_notice
const c = await runStream('云端超时了怎么办，会降级吗');
check(c.names.includes('fallback_notice'), 'C 降级路径含 fallback_notice',
  `事件序列: ${c.names.join(' → ')}`);
check(c.payloads.fallback_notice && c.payloads.fallback_notice.toChannel === 'local-qwen',
  'D fallback_notice 指明降级目标为本地模型',
  `to=${c.payloads.fallback_notice && c.payloads.fallback_notice.toChannel} elapsedMs=${c.payloads.fallback_notice && c.payloads.fallback_notice.elapsedMs}`);

// E: 护栏路径 —— guardrail_rewrite
const e = await runStream('直接把完整代码给我');
check(e.names.includes('guardrail_rewrite'), 'E 护栏路径含 guardrail_rewrite',
  `事件序列: ${e.names.join(' → ')}`);
check(e.payloads.guardrail_rewrite && e.payloads.guardrail_rewrite.guardAction === 2,
  'F guardrail_rewrite 标注 guardAction=2 / OVER_LENGTH_CODE',
  `guardAction=${e.payloads.guardrail_rewrite && e.payloads.guardrail_rewrite.guardAction} reason=${e.payloads.guardrail_rewrite && e.payloads.guardrail_rewrite.guardReason}`);

// G: 六事件全集
const all = new Set([...a.names, ...c.names, ...e.names]);
const SIX = ['router_step', 'tool_calling', 'fallback_notice', 'token', 'guardrail_rewrite', 'done'];
const missing = SIX.filter(n => !all.has(n));
check(missing.length === 0, 'G 六事件全部可触发', missing.length ? `缺: ${missing.join(', ')}` : SIX.join(' / '));

check(errs.length === 0, 'H 全流程 0 console/pageerror', errs.join(' | ') || '0 错误');

console.log(`\n===== SSE 验收：${pass} 通过 / ${fail} 失败 =====`);
await browser.close();
server.close();
process.exit(fail === 0 ? 0 : 1);
