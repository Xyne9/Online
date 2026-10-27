/** Day8 前端契约与异步竞态探针：Node VM 驱动实际页面/SSE 源码，无模型依赖。 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = p => fs.readFileSync(path.join(ROOT, 'prototype/app', p), 'utf8');
const results = [];
function check(name, fn) {
  try { fn(); results.push({ name, pass: true }); console.log('PASS ' + name); }
  catch (e) { results.push({ name, pass: false, error: e.message }); console.error('FAIL ' + name + ': ' + e.message); }
}
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const capsules = () => [1, 2, 3, 4].map(stage => ({ stage, stageName: '阶段' + stage, unlocked: stage < 4, lockReason: '先 AC' }));
const channels = [{ channelCode: 'local-qwen', healthStatus: 'ON' }, { channelCode: 'cloud-ds', healthStatus: 'DEGRADE' }];
function harness(search = '?pid=1006&assignmentId=412') {
  const timers = new Map(), watchers = {}, calls = [], streams = [];
  let seq = 0;
  const sandbox = {
    console, URLSearchParams, AbortController, TextDecoder, TextEncoder, ReadableStream,
    performance: { now: () => 0 }, location: { search, replace() {} },
    document: { querySelector: () => null },
    setTimeout(fn, ms) { const id = ++seq; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); }, requestAnimationFrame() {}, addEventListener() {},
    api: { http: {
      BASE: 'http://127.0.0.1:8080', useMock: () => search.includes('mock=1'),
      token: { get: () => 'test-jwt', set() {} }, describe: e => e.message,
      get: async (url, opts) => {
        calls.push({ method: 'GET', url, opts });
        if (url.includes('/questions/')) return { codeTemplates: { cpp: 'int main(){}' } };
        if (url.endsWith('/workspace-init')) return { aiEnabled: true, remainingQuota: 2, maxQuota: 3, stageCapsules: capsules(), availableChannels: channels, chatHistory: [] };
        if (url.endsWith('/channels')) return { list: channels };
        if (url.endsWith('/quota')) return { remaining: 1, max: 3, stages: capsules() };
        if (url.endsWith('/sessions')) return { list: [{ recordId: 2, userMessage: 'new', aiResponse: 'answer2' }, { recordId: 1, userMessage: 'old', aiResponse: 'answer1' }] };
        return {};
      },
      post: async (url, body) => { calls.push({ method: 'POST', url, body }); return { submissionId: 7, displayNo: 'S-7' }; }
    } }
  };
  sandbox.window = sandbox;
  sandbox.api.sse = (url, opts) => {
    const client = { url, opts, closed: false, handlers: {}, on(n, fn) { this.handlers[n] = fn; return this; }, close() { this.closed = true; }, emit(n, p = {}) { this.handlers[n]?.(p); } };
    streams.push(client); return client;
  };
  const ctx = vm.createContext(sandbox);
  vm.runInContext(source('s-workbench.js'), ctx);
  const d = ctx.workbench();
  d.$watch = (n, fn) => { watchers[n] = fn; };
  d.$nextTick = fn => fn();
  return { ctx, d, calls, streams, watchers, timers };
}
async function ready() { const h = harness(); h.d.init(); await flush(); return h; }

{
  const h = await ready();
  check('URL assignmentId 与 workspace-init 查询同源', () => {
    assert.equal(h.d.assignmentId, 412); assert.equal(h.d.problemId, 1006);
    assert.equal(h.calls.find(c => c.url.endsWith('/workspace-init')).opts.query.assignmentId, 412);
  });
  check('服务端配额、阶段标题与阶段4锁生效', () => {
    assert.equal(h.d.quotaLeft, 2); assert.equal(h.d.stageLabel, '③ 阶段3');
    h.d.result = 'ac'; assert.equal(h.d.stage4Unlocked, false);
    h.d.setStage(4); assert.equal(h.d.quotaEmpty, true); assert.equal(h.d.askPlaceholder, '先 AC');
  });
  check('workspace-init 的 role/content 历史映射到安全文本', () => {
    const history = h.d.historyMessages([{ role: 'user', content: 'question' }, { role: 'assistant', content: '<b>answer</b>', recordId: 80 }]);
    assert.equal(history[0].role, 'me'); assert.equal(history[1].text, '<b>answer</b>'); assert.equal(history[1].recordId, 80);
  });
  h.d.setStage(2); h.d.model = 'cloud'; h.d.fixed = true; h.d.codeFixed = 'fixed-source'; h.d.ask('<b>用户输入</b>');
  const s = h.streams[0];
  check('聊天 POST 六个契约字段与真实源码', () => {
    assert.equal(s.opts.body.assignmentId, 412); assert.equal(s.opts.body.selectedChannelCode, 'cloud-ds');
    assert.equal(s.opts.body.capsuleStage, 2); assert.equal(s.opts.body.currentCode, 'fixed-source');
    assert.equal(s.opts.body.userMessage, '<b>用户输入</b>'); assert.equal(s.opts.reconnect, false);
    assert.equal(h.d.quotaLeft, 2);
  });
  s.emit('router_step', { stage: 3, stageName: '错因诊断', routerModel: 'router', levelDesc: '方向提示' });
  s.emit('tool_calling', { toolName: 'feature', summary: '真实特征' });
  s.emit('fallback_notice', { fromChannel: 'cloud-ds', toChannel: 'local-qwen', reason: '云端超时' });
  s.emit('token', { delta: 'LEAKED-CODE' });
  s.emit('guardrail_rewrite', { replacementText: '<b>安全提示</b>', reason: '代码超长' });
  check('六事件安全文本与护栏整段替换', () => {
    const text = h.d.chat.at(-1).text;
    for (const part of ['Router', 'Tool', '降级', '护栏', '<b>安全提示</b>']) assert.ok(text.includes(part));
    assert.ok(!text.includes('LEAKED-CODE')); assert.equal(h.d.stage, 2); assert.equal(h.d.model, 'cloud');
    assert.ok(source('s-workbench.html').includes('<p x-text="m.text"></p>'));
  });
  s.emit('done', { recordId: 81, remainingQuota: 1, actualModelUsed: 'local-qwen' });
  check('done 是配额写入口并保存 recordId', () => {
    assert.equal(h.d.quotaLeft, 1); assert.equal(h.d.replying, false); assert.equal(h.d.chat.at(-1).recordId, 81); assert.ok(s.closed);
  });
  await h.d.feedback(81, false, 'TOO_VAGUE', '需更具体');
  check('反馈走 api.http 且保留服务端记录 ID', () => assert.equal(h.calls.at(-1).body.recordId, 81));
}
{
  const h = await ready(); h.d.ask('first'); const old = h.streams[0];
  h.watchers.drawer(false); const before = h.d.chat.at(-1).text;
  old.emit('token', { delta: 'STALE' }); old.emit('done', { remainingQuota: 0 });
  check('抽屉关闭取消旧流，迟到事件不写聊天或配额', () => {
    assert.ok(old.closed); assert.equal(h.d.replying, false); assert.equal(h.d.chat.at(-1).text, before); assert.equal(h.d.quotaLeft, 2);
  });
  h.d.ask('second'); h.d.submitLive = () => {}; h.d.submit();
  check('提交取消 AI 且不重置配额', () => { assert.ok(h.streams[1].closed); assert.equal(h.d.chat.length, 0); assert.equal(h.d.quotaLeft, 2); });
  h.d.result = 'wa'; h.d.ask('third'); h.d.destroy(); h.streams[2].emit('token', { delta: 'STALE' });
  check('卸载取消 SSE 并禁止迟到回写', () => { assert.ok(h.streams[2].closed); assert.equal(h.d.replying, false); assert.ok(!h.d.chat.at(-1).text.includes('STALE')); });
}
for (const code of [429, 403, 502]) {
  const h = await ready(); h.d.ask('failure'); h.streams[0].emit('error', { code, message: '真实错误' });
  check('error ' + code + ' 结束输入状态并保留原因', () => {
    assert.equal(h.d.replying, false); assert.ok(h.d.chat.at(-1).text.includes('真实错误'));
    assert.equal(h.d.quotaLeft, 2); assert.equal(h.d.aiEnabled, true);
    assert.equal(h.streams.length, 1);
  });
  await flush();
  check('error ' + code + ' 收尾后刷新权威 quota', () => {
    assert.equal(h.d.quotaLeft, 1); assert.equal(h.d.aiEnabled, true);
    assert.ok(h.calls.some(c => c.url.endsWith('/quota'))); assert.equal(h.streams.length, 1);
  });
}
{
  const h = await ready(); const pending = deferred();
  h.ctx.api.http.get = () => pending.promise;
  h.d.applyQuota({ remaining: -1, max: -1, unlimited: true, aiEnabled: true });
  h.d.ask('practice'); h.streams[0].emit('error', { code: 429, message: '并发繁忙' });
  check('429 并发错误不把无限练习改成零配额', () => {
    assert.equal(h.d.quotaUnlimited, true); assert.equal(h.d.quotaLeft, '不限'); assert.equal(h.d.quotaEmpty, false);
  });
  pending.resolve({ remaining: -1, max: -1, unlimited: true, aiEnabled: true, stages: capsules() }); await flush();
  check('429 后权威无限配额仍可继续提问', () => {
    h.d.ask('retry'); assert.equal(h.streams.length, 2); assert.equal(h.d.aiEnabled, true);
    assert.ok(h.d.chat.some(m => m.text.includes('并发繁忙')));
  });
}
{
  const h = await ready();
  h.ctx.api.http.get = async () => ({ remaining: 0, max: 3, aiEnabled: true, stages: capsules() });
  h.d.ask('last'); h.streams[0].emit('error', { code: 429, message: '配额耗尽' }); await flush();
  check('真正耗尽由权威 quota=0 禁用输入', () => {
    assert.equal(h.d.quotaLeft, 0); assert.equal(h.d.quotaEmpty, true); assert.equal(h.d.aiEnabled, true);
  });
}
{
  const h = await ready();
  h.ctx.api.http.get = async () => ({ remaining: 2, max: 3, aiEnabled: true, stages: capsules() });
  h.d.ask('blocked input'); h.streams[0].emit('error', { code: 403, message: '输入护栏拦截' }); await flush();
  check('403 输入护栏只拒绝本次输入，正常提问仍可发送', () => {
    assert.equal(h.d.aiEnabled, true); assert.equal(h.d.quotaEmpty, false);
    h.d.ask('normal input'); assert.equal(h.streams.length, 2);
  });
}
{
  const h = await ready(); h.d.setStage(2);
  h.ctx.api.http.get = async () => ({ remaining: 2, max: 3, aiEnabled: true,
    stages: capsules().map(c => ({ ...c, unlocked: c.stage === 1 })) });
  h.d.ask('stage'); h.streams[0].emit('error', { code: 403, message: '当前阶段未开放' }); await flush();
  check('403 阶段拒绝只锁该阶段，不关闭整个 AI', () => {
    assert.equal(h.d.aiEnabled, true); assert.equal(h.d.quotaEmpty, true);
    h.d.setStage(1); assert.equal(h.d.quotaEmpty, false); h.d.ask('allowed stage'); assert.equal(h.streams.length, 2);
  });
}
{
  const h = await ready();
  h.ctx.api.http.get = async () => ({ remaining: 0, max: 0, aiEnabled: false, stages: capsules() });
  h.d.ask('exam'); h.streams[0].emit('error', { code: 403, message: '考试禁用 AI' }); await flush();
  check('考试全局禁用只接受 quota.aiEnabled=false 的权威结果', () => {
    assert.equal(h.d.aiEnabled, false); assert.equal(h.d.quotaEmpty, true);
  });
}
{
  const h = await ready(); h.d.ask('disconnect'); h.streams[0].emit('end');
  check('缺少 done 的 EOF 明确标注回复未完成', () => { assert.equal(h.d.replying, false); assert.ok(h.d.chat.at(-1).text.includes('未完成')); });
}
{
  const h = await ready(); await h.d.loadSessions();
  check('服务端 sessions 倒序转为聊天正序', () => assert.equal(h.d.chat.map(m => m.text).join('|'), 'old|answer1|new|answer2'));
  const pending = deferred(); h.ctx.api.http.get = () => pending.promise;
  const load = h.d.loadSessions(); h.d.ask('newest'); pending.resolve({ list: [{ aiResponse: 'STALE-HISTORY' }] }); await load;
  check('迟到历史不覆盖新提问', () => assert.ok(!h.d.chat.some(m => m.text.includes('STALE-HISTORY'))));
}
{
  const h = await ready(); const pending = deferred(); h.ctx.api.http.get = () => pending.promise;
  const quota = h.d.refreshQuota(); h.d.ask('new quota'); h.streams[0].emit('done', { remainingQuota: 0 });
  pending.resolve({ remaining: 3, max: 3 }); await quota;
  check('迟到 quota GET 不覆盖 SSE done', () => assert.equal(h.d.quotaLeft, 0));
}
{
  const h = harness(); const pending = deferred(); h.ctx.api.http.get = () => pending.promise;
  h.d.live = true; h.d.aiReady = true; h.d.aiEnabled = true; h.d.channels = channels; h.d.stageCapsules = capsules();
  const mine = h.d.loadMine(); h.d.submitLive = () => {}; h.d.submit(); pending.resolve({ myRecord: { submitted: true, lastStatus: 3, bestScore: 100 } }); await mine;
  check('初始 loadMine 不覆盖较新的判题状态', () => assert.equal(h.d.result, 'judging'));
}
{
  const h = await ready(); h.d.startPoll = () => {}; h.d.startLitAnimation = () => {}; await h.d.submitLive();
  check('判题保留 URL assignmentId', () => assert.equal(h.calls.find(c => c.url.endsWith('/submit')).body.assignmentId, 412));
  h.d.applyQuota({ remaining: -1, max: -1, unlimited: true });
  check('无限配额显示不限且不禁用输入', () => { assert.equal(h.d.quotaLeft, '不限'); assert.equal(h.d.quotaMax, '不限'); assert.equal(h.d.quotaEmpty, false); });
}
{
  const h = harness('?mock=1'); h.d.init();
  check('mock 首屏状态保留 2/3、阶段3、空聊天', () => { assert.equal(h.d.quotaLeft, 2); assert.equal(h.d.quotaMax, 3); assert.equal(h.d.stage, 3); assert.equal(h.d.chat.length, 0); assert.equal(h.calls.length, 0); });
}
{
  const h = harness(); const get = h.ctx.api.http.get;
  h.ctx.api.http.get = async (url, opts) => url.endsWith('/workspace-init')
    ? { aiEnabled: true, remainingQuota: 2, maxQuota: 3, stageCapsules: capsules().map(c => ({ ...c, unlocked: c.stage === 2 })), availableChannels: channels, chatHistory: [] }
    : get(url, opts);
  h.d.init(); await flush();
  check('默认阶段3未解锁时只在初始化选择第一个开放阶段', () => assert.equal(h.d.stage, 2));
  h.d.setStage(4); await h.d.loadWorkspace(); await h.d.refreshQuota();
  check('后续 workspace/quota 刷新不覆盖用户阶段选择', () => assert.equal(h.d.stage, 4));
}
{
  const h = harness(); const pending = deferred(), get = h.ctx.api.http.get;
  h.ctx.api.http.get = (url, opts) => url.endsWith('/workspace-init') ? pending.promise : get(url, opts);
  h.d.init(); h.d.setStage(4); h.d.model = 'cloud';
  pending.resolve({ aiEnabled: true, remainingQuota: 2, maxQuota: 3, stageCapsules: capsules(), availableChannels: channels, chatHistory: [] }); await flush();
  check('初始化期间的用户阶段/模型选择不被迟到响应覆盖', () => { assert.equal(h.d.stage, 4); assert.equal(h.d.model, 'cloud'); });
}
{
  const h = await ready();
  check('真实无判题记录不虚构第3测试点或80分', () => {
    assert.equal(h.d.firstFailSeq, 0); assert.equal(h.d.diagnosisContext, '暂无判题结果');
    assert.equal(h.d.judgeSummary, '暂无判题结果'); assert.equal(h.d.acceptedContext, '暂无判题结果');
  });
  h.d.judgeStatus = 5; h.d.cases = [{ seqNo: 1, score: 10, gotScore: 10 }, { seqNo: 7, score: 10, gotScore: 0 }];
  check('诊断与复盘统计读取真实失败序号、状态及通过数', () => {
    assert.equal(h.d.diagnosisContext, '第 7 号测试点'); assert.equal(h.d.judgeStatusLabel, 'WA'); assert.equal(h.d.acceptedContext, '1 / 2 个测试点');
  });
  h.d.channels = [{ channelCode: 'local-qwen', channelName: '边缘教学', modelName: 'Tutor-Local-v2', healthStatus: 'ON' },
    { channelCode: 'cloud-ds', channelName: '远程教学', modelName: 'Tutor-Cloud-v3', healthStatus: 'DEGRADE' }];
  check('模型菜单使用服务端通道名、模型名与状态', () => {
    assert.equal(h.d.modelLabel, '边缘教学'); assert.equal(h.d.channelName('cloud'), '远程教学');
    assert.equal(h.d.channelSubtitle('cloud'), 'Tutor-Cloud-v3 · 降级');
  });
  h.d.applyQuota({ remaining: -1, max: -1, unlimited: true });
  check('无限配额只显示不限且输入/发送提示不声称扣次数', () => {
    assert.equal(h.d.quotaLabel, '不限'); assert.ok(!h.d.askInputTitle.includes('消耗')); assert.ok(!h.d.sendTitle.includes('消耗'));
  });
  h.d.setStage(4);
  check('锁定阶段输入同时说明真实锁定原因', () => { assert.equal(h.d.askPlaceholder, '先 AC'); assert.equal(h.d.sendTitle, '先 AC'); });
}
{
  const html = source('s-workbench.html');
  check('四阶段固定AI卡片与思考示例均由live显隐隔离', () => {
    for (const name of ['ai-body', 'ai-pin', 'ai-next', 'think']) {
      assert.equal((html.match(new RegExp('class="' + name + '" x-show="!live"', 'g')) || []).length, 4);
    }
    assert.ok(html.includes('askText.trim() && !replying && !quotaUnlimited'));
    assert.ok(html.includes('x-text="quotaLabel"'));
  });
}

// 直接驱动实际 SSE 客户端，验证请求地址、UTF-8 分块、CRLF、EOF 与不重放。
function sseHarness(fetcher) {
  const h = harness(); h.ctx.fetch = fetcher; vm.runInContext(source('api/sse.js'), h.ctx); return h;
}
const wire = (name, data) => 'event: ' + name + '\r\ndata: ' + JSON.stringify(data) + '\r\n\r\n';
{
  const requests = [], events = [];
  const bytes = new TextEncoder().encode(': ping\r\n\r\n' + wire('token', { delta: '中文' }) + wire('done', { remainingQuota: 1 }));
  const h = sseHarness(async (url, opts) => {
    requests.push({ url, opts });
    return { ok: true, status: 200, body: new ReadableStream({ start(c) { for (let i = 0; i < bytes.length; i++) c.enqueue(bytes.slice(i, i + 1)); c.close(); } }) };
  });
  const c = new h.ctx.api.SSEClient('/api/ai/chat/stream', { body: { userMessage: 'x' } });
  c.on('token', p => events.push(p.delta)).on('done', () => events.push('done')).on('end', () => events.push('end'));
  await c.start();
  check('SSE 使用 HTTP BASE 与 Bearer', () => { assert.equal(requests[0].url, 'http://127.0.0.1:8080/api/ai/chat/stream'); assert.equal(requests[0].opts.headers.Authorization, 'Bearer test-jwt'); assert.equal(requests[0].opts.credentials, 'omit'); });
  check('SSE 跨字节中文/CRLF/心跳解析正确且只结束一次', () => assert.deepEqual(events, ['中文', 'done', 'end']));
}
{
  let count = 0; const errors = [];
  const h = sseHarness(async () => { count++; throw new Error('offline'); });
  const c = new h.ctx.api.SSEClient('/api/ai/chat/stream', { reconnect: true, maxRetries: 2 });
  c.on('error', p => errors.push(p)); await c.start(); await c.start();
  check('网络断线和重复 start 都不会重放 POST', () => { assert.equal(count, 1); assert.equal(errors.length, 1); assert.ok(c.closed); });
}
{
  const h = sseHarness(async () => ({ ok: false, status: 429, text: async () => JSON.stringify({ code: 429, message: '配额耗尽' }) }));
  const events = []; const c = new h.ctx.api.SSEClient('/api/ai/chat/stream');
  c.on('error', p => events.push(p.code)).on('end', () => events.push('end')); await c.start();
  check('HTTP 429 与 end 可由页面明确接收', () => assert.deepEqual(events, [429, 'end']));
}

const report = { pass: results.filter(r => r.pass).length, fail: results.filter(r => !r.pass).length, results };
const reportDir = path.join(ROOT, 'logs/day8-frontend');
fs.mkdirSync(reportDir, { recursive: true });
fs.writeFileSync(path.join(reportDir, 'report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report));
process.exitCode = report.fail ? 1 : 0;
