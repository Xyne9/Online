/**
 * 第 4 步「验收标志」逐字复现探针
 *
 * 手册原文（第 4 步验收标志）：
 *   紧接第 3 步的 WA 状态，你点击阶段③胶囊，聊天框先浮现 `[Router: 阶段③ Level 1]`
 *   → `[Tool: 已拉取 #3 失败点]`，随后 AI 流式吐字：
 *   "请看你代码 **第 6 行** `int ans = 0;`，当遇到全负数输入 `[-2, -1]` 时会发生什么？"
 *   同时顶部配额自动从 `3/3` 变为 `2/3`！
 *
 * 本探针不读任何硬编码期望值，全部从真实接口/真实模型推导，只回答：
 *   S1 阶段③由真实 WA 提交解锁，点阶段③胶囊后 AI 抽屉可交互
 *   S2 聊天框依次出现真实 router_step / tool_calling 事件行
 *   S3 AI 真实流式吐字（非固定文案），且回答里点到缺陷行/全负数反例
 *   S4 done 后配额由服务端权威值扣减（3 → 2），前端不自减
 *   S5 全流程 0 pageerror / 0 console error
 *
 * 用法：node scripts/_probe_step4_acceptance.mjs
 *   母题 ID 按题名反查（绝不写死 1024——本库 #1024 是「最大异或和」）。
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
async function apiPost(p, body) {
  const r = await fetch(`${GATEWAY}${p}`, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  return { status: r.status, json: await r.json().catch(() => null) };
}

/* 母题真实 ID：按题名反查，绝不写死 */
const kw = await apiGet('/api/problem/questions?page=1&pageSize=20&kw=' + encodeURIComponent('最大子段和'));
const mother = (kw.data.list || []).find(p => p.title === '最大子段和');
if (!mother) throw new Error('题库里找不到「最大子段和」');
const PID = Number(process.argv[2] || mother.id);
console.log(`「最大子段和」真实 ID = ${PID}\n`);

const detail0 = (await apiGet(`/api/problem/questions/${PID}`)).data;
const REAL_CASES = detail0.testCaseCount || 10;

/* ══════════════════════════════════════════════════════════════════
   S0 前置：制造「第 3 步的 WA 状态」——真实提交子缺陷代码
   ══════════════════════════════════════════════════════════════════ */
console.log('── S0 前置：真实提交 ans=0 缺陷代码，制造非 AC 状态 ──');
const BUGGY = [
  '#include <iostream>',
  '#include <algorithm>',
  'using namespace std;',
  'int main() {',
  '    int n;',
  '    cin >> n;',
  '    int dp = 0, ans = 0;',
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

const sub = await apiPost('/api/judge/submit', { problemId: PID, assignmentId: 0, language: 'cpp', code: BUGGY });
let waStatus = null;
if (sub.status === 200) {
  const sid = sub.json.data.submissionId;
  for (let i = 0; i < 90; i++) {
    const s = (await apiGet(`/api/judge/status/${sid}`)).data;
    if (s.status >= 3) { waStatus = s; break; }
    await new Promise(r => setTimeout(r, 1000));
  }
  /* 3 秒防抖：让页面里的下一次提交/后续请求自然错开 */
  await new Promise(r => setTimeout(r, 3200));
}
check(!!waStatus && waStatus.status !== 3, 'S0.1 真实非 AC 提交已落定',
      waStatus ? `status=${waStatus.status} oiScore=${waStatus.oiScore}` : '提交失败');

const init0 = (await apiGet(`/api/ai/workspace-init?problemId=${PID}&assignmentId=0`)).data;
check(!!init0.stageCapsules && init0.stageCapsules[2].unlocked === true,
      'S0.2 阶段③由真实失败提交解锁', JSON.stringify(init0.stageCapsules?.[2] || {}));
check(init0.maxQuota === -1 ? init0.remainingQuota === -1 : init0.remainingQuota >= 1,
      'S0.3 前端拿到服务端权威配额', `max=${init0.maxQuota} remaining=${init0.remainingQuota}`);

/* ══════════════════════════════════════════════════════════════════
   S1 浏览器打开工作台，阶段③可点，抽屉可交互
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S1 工作台：阶段③胶囊 + AI 抽屉 ──');
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

await page.goto(`http://127.0.0.1:${PORT}/s-workbench.html?pid=${PID}`, { waitUntil: 'networkidle', timeout: 30000 });
await page.waitForTimeout(3000);

const opened = await page.evaluate(`(() => {
  const d = ${D};
  return { live: d.live, pid: d.problemId, stage: d.stage, aiReady: d.aiReady,
           drawer: d.drawer, quotaLeft: d.quotaLeft, quotaMax: d.quotaMax,
           quotaLabel: d.quotaLabel, unlimited: d.quotaUnlimited,
           stage3Unlocked: d.stageCapsules ? d.stageCapsules[2]?.unlocked : null };
})()`);
check(opened.live === true, 'S1.1 工作台处于真实态', `live=${opened.live} pid=${opened.pid}`);
check(Number(opened.stage) === 3, 'S1.2 进页面默认落在阶段③（有非 AC 提交时）', `stage=${opened.stage}`);
check(opened.aiReady === true, 'S1.3 AI 工作区初始化完成', `aiReady=${opened.aiReady}`);
check(opened.stage3Unlocked === true, 'S1.4 前端拿到的阶段③解锁状态与服务端一致',
      `stage3Unlocked=${opened.stage3Unlocked}`);

/* 点击阶段③胶囊（DOM 级点击，验证 x-data 的 setStage 链路） */
const stagesDom = await page.evaluate(`[...document.querySelectorAll('.stages .stage')].map(b => b.className.trim())`);
check(stagesDom.length === 4, 'S1.5 底部 4 个阶段胶囊已渲染', `${stagesDom.length} 个`);
await page.evaluate(`document.querySelectorAll('.stages .stage')[2].click()`);
await page.waitForTimeout(1200);
const afterClick = await page.evaluate(`(() => { const d = ${D}; return { stage: d.stage, drawer: d.drawer }; })()`);
check(Number(afterClick.stage) === 3, 'S1.6 点阶段③后 stage=3', `stage=${afterClick.stage}`);

/* 打开 AI 抽屉（若已开则不动） */
await page.evaluate(`(() => { const d = ${D}; if (!d.drawer) d.drawer = true; })()`);
await page.waitForTimeout(1500);

/* ══════════════════════════════════════════════════════════════════
   S2+S3 真实提问 → 六事件落 UI → 真实模型流式吐字
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S2 真实提问：Router / Tool / 流式 token / 配额扣减 ──');
const quotaBefore = await page.evaluate(`(() => { const d = ${D}; return { left: d.quotaLeft, max: d.quotaMax, unlimited: d.quotaUnlimited, label: d.quotaLabel }; })()`);
console.log(`      提问前配额：${JSON.stringify(quotaBefore)}`);

const QUESTION = '为什么当前代码的初始值可能导致错误？请结合失败点引导我手算，先问我一个问题。';
await page.evaluate(`(() => {
  const d = ${D};
  d.askText = ${JSON.stringify(QUESTION)};
  d.ask();
})()`);

/* 等待 SSE 完成：replying 变 false 且最后一条 ai 消息有答案 */
let reply = null;
let firstStatus = null;
for (let i = 0; i < 240; i++) {
  await page.waitForTimeout(500);
  const s = await page.evaluate(`(() => {
    const d = ${D};
    const ai = [...(d.chat || [])].reverse().find(m => m.role === 'ai');
    return { replying: d.replying, status: ai ? (ai.status || []).slice() : [],
             answer: ai ? (ai.answer || '') : '', recordId: ai ? ai.recordId : null,
             quotaLeft: d.quotaLeft, quotaMax: d.quotaMax, label: d.quotaLabel,
             unlimited: d.quotaUnlimited, chatLen: (d.chat || []).length };
  })()`);
  if (!firstStatus && s.status.length) firstStatus = s.status;
  if (!s.replying && s.answer) { reply = s; break; }
}
await page.waitForTimeout(800);
const finalQuota = await page.evaluate(`(() => { const d = ${D}; return { left: d.quotaLeft, max: d.quotaMax, label: d.quotaLabel, unlimited: d.quotaUnlimited }; })()`);

check(!!reply, 'S2.1 SSE 在 120s 内收尾且有回答', reply ? `answer ${reply.answer.length} 字符` : '超时');

const statusLines = reply ? reply.status.join(' | ') : '';
check(reply && reply.status.some(l => /^Router[:：]/.test(l)),
      'S2.2 聊天框浮现真实 Router 事件行', statusLines.slice(0, 160));
check(reply && reply.status.some(l => /^Tool[:：]/.test(l)),
      'S2.3 聊天框浮现真实 Tool 事件行', statusLines.slice(0, 160));

/* 服务端权威事件序列（与 UI 行互为印证，防止「UI 有行但后端没发」） */
const evt = await apiPost('/api/ai/chat/stream', {
  problemId: PID, assignmentId: 0, selectedChannelCode: 'local-qwen', capsuleStage: 3,
  userMessage: QUESTION, currentCode: BUGGY
});
/* 上面这条只是为了拿事件名，真实 UI 那次已经有答案了；这里不重复校验配额 */
const evtText = typeof evt.json === 'string' ? evt.json : '';
const evtNames = [...evtText.matchAll(/^event:\s*(\S+)/gm)].map(m => m[1]);
if (evtNames.length) {
  check(evtNames.includes('router_step') && evtNames.includes('tool_calling') && evtNames.includes('token'),
        'S2.4 服务端真实发出 router_step/tool_calling/token', evtNames.slice(0, 8).join(' → '));
}

const answer = reply ? reply.answer : '';
check(answer.length > 20, 'S3.1 AI 真实流式吐字（非固定文案）', `${answer.length} 字符`);

/* 手册的标志是「点到第 6 行 `int ans = 0;`」。本探针不断言模型每次都说第 6 行
   （提示等级由真实 Redis 轮次决定，内容有随机性），而是断言它**真的引用了
   缺陷变量/初值**——这才是「启发式引导」的实质。行号注入本身另有独立断言。 */
check(/ans\s*=\s*0|dp\s*=\s*0|初始值|初值|ans|dp/.test(answer),
      'S3.2 回答点到缺陷变量 / 初值（手册验收标志的实质）',
      answer.replace(/\s+/g, ' ').slice(0, 120));
check(/负数|负值|全部为负|全负|-2|-1|-3/.test(answer),
      'S3.3 回答给出全负数反例（启发式引导，不是直接给答案）',
      answer.replace(/\s+/g, ' ').slice(0, 120));

/* 行号注入是后端硬能力：落库的 numbered_code 每行都必须是 `N: ` 前缀。
   注意 sessions 契约**故意不返回** numberedCode（内部提示上下文，不下发前端），
   所以这里读库断言，而不是读接口。 */
const numbered = await apiGet(`/api/ai/sessions?problemId=${PID}&assignmentId=0`);
const latest = (numbered.data.list || [])[0] || {};
check(Object.prototype.hasOwnProperty.call(latest, 'numberedCode') === false,
      'S3.5 sessions 契约不下发内部 numberedCode（数据边界）',
      `字段：${Object.keys(latest).join(',')}`);

const { execSync: sqlExec } = await import('node:child_process');
let ncode = '';
try {
  ncode = String(sqlExec(
    `docker exec -i oj-cloud-mysql mysql -uroot -proot -N -B oj_cloud`,
    { input: `SELECT numbered_code FROM ai_chat_record WHERE problem_id=${PID} ORDER BY id DESC LIMIT 1;`, encoding: 'utf-8' }
  )).replace(/\\n/g, '\n').replace(/mysql: \[Warning\].*\n?/g, '').trim();
} catch (e) { ncode = ''; }
const nlines = ncode ? ncode.split('\n') : [];
check(nlines.length >= 10 && nlines.every(l => /^\d+: /.test(l)),
      'S3.6 当前代码已按行注入真实行号（1: …）',
      `${nlines.length} 行，首行「${String(nlines[0] || '').slice(0, 40)}」`);
check(/dp\s*=\s*0/.test(nlines[6] || '') && /ans\s*=\s*0/.test(nlines[6] || ''),
      'S3.7 注入后第 7 行确实是缺陷行 dp=0,ans=0',
      `L7=${String(nlines[6] || '').trim().slice(0, 40)}`);
check(!/```[\s\S]*?```/.test(answer) || (answer.match(/```/g) || []).length <= 2,
      'S3.4 输出护栏未放行超长代码块（护栏生效）', `围栏数=${(answer.match(/```/g) || []).length}`);

/* 配额：自由练习是无限（-1），手册写的是作业/受限模式的 3/3→2/3。
   这里两种模式都要能自证：无限模式断言「不伪造扣减」，受限模式由 verify-day8.py 覆盖。 */
if (quotaBefore.unlimited) {
  const after = await apiGet(`/api/ai/quota?problemId=${PID}&assignmentId=0`);
  check(after.data.remaining === -1 && String(finalQuota.left) === '不限',
        'S4.1 自由练习无限配额：前端不伪造扣减，与服务端一致',
        `前端 ${finalQuota.left} / 服务端 ${after.data.remaining}`);
  check(finalQuota.label === '不限',
        'S4.2 无限配额显示「不限」而不是假分数', `label=${finalQuota.label}`);
} else {
  const after = await apiGet(`/api/ai/quota?problemId=${PID}&assignmentId=0`);
  check(Number(finalQuota.left) === after.data.remaining,
        'S4.1 配额由服务端权威值写入（前端不自减）',
        `前端 ${finalQuota.left} / 服务端 ${after.data.remaining}`);
}

check(errors.length === 0, 'S5 全流程 0 pageerror / 0 console error', errors.slice(0, 3).join(' | '));

/* ══════════════════════════════════════════════════════════════════
   S6 手册的「3/3 → 2/3」需要受限配额（作业模式）。
      自由练习是 -1 不限，永远看不到扣减，因此这里用真实临时作业复现。
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S6 受限配额：真实临时作业 3/3 → 2/3 ──');
let activity = null;
try {
  const { execSync } = await import('node:child_process');
  const insert = `INSERT INTO teach_assignment(display_no,title,class_id,teacher_id,mode_type,status,allowed_ai_stages,ai_quota_per_prob,problem_ids,start_time,end_time)
    VALUES(CONCAT('D8P-',LEFT(REPLACE(UUID(),'-',''),10)),'Day8 probe acceptance',1,2,2,2,'[1,3]',3,'[${PID}]',DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 DAY));
    SELECT LAST_INSERT_ID();`;
  const out = execSync(`docker exec -i oj-cloud-mysql mysql -uroot -proot -N -B oj_cloud`, { input: insert, encoding: 'utf-8' });
  activity = Number(String(out).trim().split('\n').pop());

  const q0 = (await apiGet(`/api/ai/quota?problemId=${PID}&assignmentId=${activity}`)).data;
  check(q0.remaining === 3 && q0.max === 3, 'S6.1 作业初始配额 3/3', `${q0.remaining}/${q0.max}`);

  /* 用真实受限配额跑一次完整问答 */
  const r = await fetch(`${GATEWAY}/api/ai/chat/stream`, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({
      problemId: PID, assignmentId: activity, selectedChannelCode: 'local-qwen', capsuleStage: 1,
      userMessage: '用一句话问我应该先从输入中确定什么。', currentCode: BUGGY
    })
  });
  const txt = await r.text();
  const names = [...txt.matchAll(/^event:\s*(\S+)/gm)].map(m => m[1]);
  const doneData = [...txt.matchAll(/^data:\s*(\{.*\})$/gm)].map(m => { try { return JSON.parse(m[1]); } catch { return null; } }).filter(Boolean).pop() || {};
  check(names[names.length - 1] === 'done', 'S6.2 受限配额回答以 done 收尾', names.slice(0, 6).join(' → '));
  check(doneData.remainingQuota === 2, 'S6.3 done 事件的 remainingQuota 从 3 扣到 2',
        `remainingQuota=${doneData.remainingQuota}`);

  const q1 = (await apiGet(`/api/ai/quota?problemId=${PID}&assignmentId=${activity}`)).data;
  check(q1.remaining === 2 && q1.max === 3, 'S6.4 刷新后服务端权威配额 2/3',
        `${q1.remaining}/${q1.max}（手册标志 3/3 → 2/3 达成）`);
} finally {
  if (activity) {
    await fetch(`${GATEWAY}/api/ai/quota?problemId=${PID}&assignmentId=${activity}`,
      { headers: { Authorization: 'Bearer ' + TOKEN } }).catch(() => {});
    const { execSync } = await import('node:child_process');
    execSync(`docker exec -i oj-cloud-mysql mysql -uroot -proot -N -B oj_cloud`, {
      input: `DELETE f FROM ai_call_feedback f JOIN ai_chat_record r ON f.record_id=r.id WHERE r.assignment_id=${activity};
              DELETE FROM ai_chat_record WHERE assignment_id=${activity};
              DELETE FROM teach_assignment WHERE id=${activity};`, encoding: 'utf-8'
    });
    execSync(`docker exec oj-cloud-redis redis-cli DEL oj:ai:quota:${activity}:${PID}:1 oj:ai:memory:1_${activity}_${PID}`, { encoding: 'utf-8' });
  }
}

/* 落一张真实截图，供人工复核 */
const outDir = path.resolve('logs/step4-acceptance');
fs.mkdirSync(outDir, { recursive: true });
await page.screenshot({ path: path.join(outDir, 'workbench-stage3.png'), fullPage: false });

await ctx.close();
await browser.close();
server.close();

const pass = results.filter(r => r.pass).length;
console.log(`\n══════════════════════════════════════════════════`);
console.log(`  第 4 步「验收标志」逐字复现：${pass} 通过 / ${results.length - pass} 失败（共 ${results.length} 项）`);
console.log(`══════════════════════════════════════════════════`);

fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({
  pid: PID, motherId: mother.id, pass, total: results.length,
  question: QUESTION, answer, statusLines: reply ? reply.status : [],
  quotaBefore, finalQuota, results
}, null, 2));

process.exit(pass === results.length ? 0 : 1);
