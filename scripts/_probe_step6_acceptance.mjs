/**
 * 第 6 步「验收标志」逐字复现探针 —— 真实浏览器 + 真实网关，不读任何硬编码期望值。
 *
 * 手册第 6 步（《从 0 到 1 的 6 步标准开发顺序与操作手册》第 117-126 行）只给了目标
 * （「学情看板 + 运维通道与沙箱治理」），没给验收标志。按任务书切成四段复现：
 *
 *   S1 学生学情真实（s-analytics.html）
 *      · 知识点掌握度与后端 #57 逐项一致（且不是 82/64/41/73/55 这组硬编码）
 *      · 题目掌握表里的题目 ID 在后端真实存在（#7 逐题回查 200）
 *   S2 教师看板真实（t-dashboard.html + t-analytics.html）
 *      · 周趋势柱子数量 == #53 的 days 条数，通过率数字 == #53 的 weekPassRate
 *      · 风险学生姓名来自真实 sys_user（直接查库比对，且不含原型里的假名字）
 *      · #58 学生表的分页与 total 对得上（条数 / 总人数 / 页数三者自洽）
 *   S3 管理端探活真实（a-models.html）
 *      · 点「测试」→ 真实发出 POST /channels/{id}/probe，回显 latencyMs>0 与 httpStatus
 *      · local-qwen（Ollama 在跑）探活后为 ON
 *   S4 审计页真数据（a-audit.html）
 *      · 页面记录条数 == #81 的 total
 *      · 点导出拿到真实 CSV（Content-Disposition 含 attachment，12 列表头）
 *
 * 约束（违反即返工）：
 *   · chromium 只从 scripts/browser-runtime.mjs 来，不许直连 'playwright'
 *   · 报告落 logs/day17-frontend/report.json（结构化 JSON），不靠中文 stdout 正则
 *   · 每条断言独立 try-catch，一条失败不中断整轮
 *   · 过滤 favicon / 静态资源 404 的 console noise
 *
 * 用法：node scripts/_probe_step6_acceptance.mjs
 *   ⚠️ 必须与 verify-day17.py **串行**跑（agent.md §0.1 事实 #7：同账号登录互顶 + 登录限流）
 */
import { chromium } from './browser-runtime.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const ROOT = path.resolve('.');
const APP = path.join(ROOT, 'prototype/app');
const GATEWAY = 'http://127.0.0.1:8080';
const OUT = path.join(ROOT, 'logs/day17-frontend');
fs.mkdirSync(OUT, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.json': 'application/json; charset=utf-8', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ico': 'image/x-icon'
};

/* ── 静态服务器：和 step5 探针同款，端口随机，BASE 由 api/http.js 自动指向 :8080 ── */
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
const SITE = `http://127.0.0.1:${PORT}`;

/* ── 断言框架：每条独立 try-catch，绝不中断整轮 ────────────────────── */
const checks = [];
const pageErrors = [];
let seq = 0;
function record(id, name, pass, detail = '') {
  checks.push({ id, name, pass: !!pass, detail: String(detail ?? '') });
  console.log(`${pass ? 'PASS' : 'FAIL'}  [${id}] ${name}${detail ? '\n       ' + String(detail).replace(/\n/g, '\n       ') : ''}`);
}
async function step(id, name, fn) {
  try {
    const r = await fn();
    if (r === undefined) return;                      // 断言内部已 record
    const { pass, detail } = (typeof r === 'object' && r !== null) ? r : { pass: !!r, detail: '' };
    record(id, name, pass, detail);
  } catch (e) {
    record(id, name, false, '异常: ' + (e && e.message ? e.message : String(e)));
  }
}
const brief = (v, n = 260) => {
  let s;
  try { s = typeof v === 'string' ? v : JSON.stringify(v); } catch { s = String(v); }
  if (s === undefined || s === null) s = String(v);          // JSON.stringify(undefined) === undefined
  return s.length > n ? s.slice(0, n) + '…' : s;
};

/* ── 真实登录 + 真实取数（探针自己打网关，期望值全部来自后端） ────── */
async function apiLogin(username) {
  const r = await fetch(`${GATEWAY}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: '123456' })
  });
  const env = await r.json();
  if (!env.data || !env.data.accessToken) throw new Error(`${username} 登录失败: ${brief(env)}`);
  return env.data;
}
async function apiGet(p, token) {
  const r = await fetch(`${GATEWAY}${p}`, { headers: { Authorization: 'Bearer ' + token } });
  const env = await r.json().catch(() => null);
  return { status: r.status, env };
}
const dataOf = async (p, token) => (await apiGet(p, token)).env?.data;

const auth = {};
const EXPECT = {};
await step('S0.1', '三个角色真实登录成功（student/teacher/admin）', async () => {
  auth.student = await apiLogin('20220101');
  auth.teacher = await apiLogin('T001');
  auth.admin = await apiLogin('admin');
  return { pass: !!auth.student.accessToken && !!auth.teacher.accessToken && !!auth.admin.accessToken,
           detail: `student=${auth.student.user.role} teacher=${auth.teacher.user.role} admin=${auth.admin.user.role}` };
});

const CLASS_ID = auth.student?.user?.classId || 1;
const STUDENT_ID = auth.student?.user?.id;
const T = t => t.accessToken;
const tokS = T(auth.student), tokT = T(auth.teacher), tokA = T(auth.admin);

/* 期望值：全部从后端现取，绝不在前端脚本里写死 */
await step('S0.2', '从后端取到四类期望值（#57 / #53 / #58 / #59 / #81 / #72）', async () => {
  const [knowledge, trend, students, risk, calls, channels] = await Promise.all([
    dataOf(`/api/teaching/analytics/class/${CLASS_ID}/knowledge?range=month`, tokT),
    dataOf(`/api/teaching/week-trend?classId=${CLASS_ID}&weeks=1`, tokT),
    dataOf(`/api/teaching/analytics/class/${CLASS_ID}/students?page=1&pageSize=10&sortBy=score&sortOrder=desc`, tokT),
    dataOf(`/api/teaching/analytics/class/${CLASS_ID}/risk`, tokT),
    dataOf('/api/admin/audit/calls?page=1&pageSize=100&sortBy=at&sortOrder=desc', tokA),
    dataOf('/api/admin/channels?page=1&pageSize=50', tokA)
  ]);
  EXPECT.knowledge = knowledge?.list || [];
  EXPECT.trend = trend || null;
  EXPECT.students = students || null;
  EXPECT.risk = risk || null;
  EXPECT.calls = calls || null;
  EXPECT.channels = channels || null;
  const ok = EXPECT.knowledge.length > 0 && !!EXPECT.trend && !!EXPECT.students && !!EXPECT.calls;
  return { pass: ok, detail: `knowledge=${EXPECT.knowledge.length} days=${EXPECT.trend?.days?.length} students=${EXPECT.students?.total} calls=${EXPECT.calls?.total}` };
});

/* 真实姓名基线：直接查库，独立于接口（防「接口和页面一起造假」） */
await step('S0.3', '从 sys_user 直接查真实姓名基线', async () => {
  const out = execSync(
    `docker exec oj-cloud-mysql mysql -uroot -proot --default-character-set=utf8mb4 -N -B oj_cloud ` +
    `-e "SELECT real_name FROM sys_user WHERE status=1;"`,
    { encoding: 'utf-8' });
  EXPECT.realNames = new Set(String(out).split('\n').map(s => s.trim()).filter(Boolean));
  return { pass: EXPECT.realNames.size > 0, detail: `${EXPECT.realNames.size} 个启用用户真实姓名` };
});

/* 原型里那批硬编码假名字——出现任何一个就说明页面还在用旧 fixtures */
const FAKE_NAMES = ['王嘉言', '李若初', '张景行', '刘清越', '杨时雨', '黄文轩', '赵子墨', '吴思远',
  '徐云舒', '孙以宁', '马知微', '朱怀瑾', '胡观澜', '郭令仪', '何承宇', '高见山', '罗星野', '郑亦舟',
  '梁问渠', '谢明夷', '宋清让', '唐稚川', '韩束之', '冯长歌', '邓无咎', '曹砚白', '彭子期', '曾不器',
  '萧南枝', '田雨眠', '董云起', '袁正则', '潘安之', '蒋怀生', '蔡嘉禾', '余望舒', '邹星河', '屈静姝'];
/* 原型的硬编码掌握度（t-analytics KP_BASE / s-analytics TAGS.pct） */
const FAKE_MASTERY = [82, 64, 41, 73, 55];
/* agent.md §2.6 红线：任何 API Key / 密码 / Token 都不得出现在响应或页面里。
   三种形态都要拦住：明文 sk-xxx / 未解析的 ${VAR} 占位符 / env:VAR 写法。 */
const SECRET_RE = /(sk-[A-Za-z0-9_-]{20,})|(\$\{[A-Za-z_][A-Za-z0-9_]*\})|(env:[A-Z_][A-Z0-9_]{3,})/;
function findSecrets(node, pathStr = '', out = []) {
  if (node === null || node === undefined) return out;
  if (Array.isArray(node)) {
    node.forEach((v, i) => findSecrets(v, `${pathStr}[${i}]`, out));
  } else if (typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) findSecrets(v, pathStr ? `${pathStr}.${k}` : k, out);
  } else if (typeof node === 'string') {
    const m = SECRET_RE.exec(node);
    if (m) out.push({ field: pathStr, match: m[0].slice(0, 40) });
  }
  return out;
}

/* ══════════════════════════════════════════════════════════════════
   浏览器启动 + 三个登录态上下文
   ══════════════════════════════════════════════════════════════════ */
const browser = await chromium.launch();

let screenshotSeq = 0;
async function newPage(authData, label) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  await ctx.addInitScript(a => {
    localStorage.setItem('OJ_TOKEN', a.accessToken);
    localStorage.setItem('OJ_ROLE', String(a.user.role).toLowerCase());
    localStorage.setItem('OJ_USER', JSON.stringify(a.user));
    sessionStorage.removeItem('OJ_USE_MOCK');
    localStorage.removeItem('OJ_USE_MOCK');
  }, authData);
  // favicon 在静态服务器必然 404，纯环境噪声 → 直接 204 兜掉
  await ctx.route('**/favicon.ico', route => route.fulfill({ status: 204, body: '' }));
  const page = await ctx.newPage();
  /* 记录该页真实发出的网关请求：用来区分「页面真接了接口」与「页面还在 setTimeout 造假」 */
  const net = [];
  page.on('request', r => {
    const u = r.url();
    if (u.startsWith(GATEWAY)) net.push(`${r.method()} ${u.replace(GATEWAY, '')}`);
  });
  page.on('pageerror', e => pageErrors.push(`[${label}] pageerror: ${e.message}`));
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const t = m.text();
    if (/Failed to load resource|net::ERR_|favicon/i.test(t)) return;   // 静态 404 噪声
    pageErrors.push(`[${label}] console: ${t}`);
  });
  page.__label = label;
  page.__net = net;
  return { ctx, page };
}
async function shot(page, name) {
  screenshotSeq += 1;
  const file = path.join(OUT, `${String(screenshotSeq).padStart(2, '0')}-${name}.png`);
  try { await page.screenshot({ path: file, fullPage: true }); } catch { /* 截图失败不影响断言 */ }
  return path.relative(ROOT, file);
}
/* Alpine 组件状态（页面读数据的唯一入口，DOM 红线决定不能加节点） */
const pageData = p => p.evaluate(() => {
  const root = document.getElementById('page-content');
  if (!window.Alpine || !root) return null;
  try { return Alpine.$data(root); } catch { return null; }
});
const stateOf = (p, fnSrc, arg) => p.evaluate(({ fnSrc, arg }) =>
  new Function('d', 'arg', `return (${fnSrc})(d, arg)`)(Alpine.$data(document.getElementById('page-content')), arg),
{ fnSrc: fnSrc.toString(), arg });

const student = await newPage(auth.student, 'student');
const teacher = await newPage(auth.teacher, 'teacher');
const admin = await newPage(auth.admin, 'admin');
const pS = student.page, pT = teacher.page, pA = admin.page;

const evidence = {};

/* ══════════════════════════════════════════════════════════════════
   S1 学生学情真实（s-analytics.html）
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S1 学生学情真实（s-analytics.html） ──');
await step('S1.0', '打开学生学情页且无致命脚本错误', async () => {
  await pS.goto(`${SITE}/s-analytics.html`, { waitUntil: 'networkidle', timeout: 45000 });
  await pS.waitForFunction(() => window.Alpine && Alpine.$data(document.getElementById('page-content')), { timeout: 20000 });
  await pS.waitForTimeout(1200);
  const mounted = !!(await pageData(pS));
  evidence.sAnalyticsShot = await shot(pS, 's-analytics');
  return { pass: mounted, detail: `Alpine 组件 ${mounted ? '已挂载' : '未挂载'}，url=${pS.url()}` };
});

await step('S1.1', '知识点掌握度与后端 #57 逐项一致（不是硬编码 82/64/41/73/55）', async () => {
  // ⚠️ 语义要点：s-analytics 的 .cap 行有**两个**掌握度，别搞混：
  //   .cap-v  = t.pct = **我的**掌握度（来自 #62 的 weakKps，契约只回薄弱项）
  //   .cap-c  = t.cls = **班级**掌握度（来自 #57 的 masteryPercent）
  // 所以「页面对上 #57」= 比对 `.cap-c`（班级列），不是 `.cap-v`（我的列）。
  // 第一版我拿 .cap-v 去比 #57，得到「动态规划 页面0 vs 后端100」的假红：
  // 学生自己在 #62 里根本没有「动态规划」条目（它不是薄弱项），所以 .cap-v 显示 0。
  const rows = await pS.evaluate(() => [...document.querySelectorAll('.cap-list .cap')].map(el => {
    const num = s => Number(String(s || '').replace(/[^\d.-]/g, ''));
    return {
      name: (el.querySelector('.cap-n')?.textContent || '').trim(),
      mine: num((el.querySelector('.cap-v')?.textContent || '')),
      cls: num((el.querySelector('.cap-c')?.textContent || '')),
      clsText: (el.querySelector('.cap-c')?.textContent || '').trim()
    };
  }));
  const backend = EXPECT.knowledge.map(k => ({ name: k.kpName, pct: k.masteryPercent }));
  evidence.knowledgeDom = rows;
  evidence.knowledgeBackend = backend;
  if (!rows.length) return { pass: false, detail: '页面上没有 .cap 行（知识点清单为空）' };
  if (!backend.length) return { pass: false, detail: '#57 返回空列表，无法比对' };
  const missing = backend.filter(b => !rows.some(r => r.name === b.name));
  const mismatched = [];
  for (const b of backend) {
    const hit = rows.find(r => r.name === b.name);
    // 只比班级列（.cap-c）。后端 0% 时模板渲染「班级 0%」，也能解析出 0。
    if (hit && Math.abs(Number(hit.cls) - Number(b.pct)) > 0.5) {
      mismatched.push({ name: b.name, pageCls: hit.cls, backend: b.pct });
    }
  }
  const hardcoded = rows.length === FAKE_MASTERY.length
    && [...FAKE_MASTERY].sort().join(',') === rows.map(r => r.cls).sort().join(',');
  const pass = missing.length === 0 && mismatched.length === 0 && !hardcoded
    && rows.every(r => Number.isFinite(r.cls) && r.cls >= 0 && r.cls <= 100);
  return {
    pass,
    detail: missing.length ? `页面缺少知识点: ${brief(missing)}`
      : mismatched.length ? `班级列(.cap-c)与 #57 不一致: ${brief(mismatched)}`
        : hardcoded ? `仍是原型硬编码 ${FAKE_MASTERY.join('/')}`
          : `${rows.length} 个知识点的班级掌握度逐个对齐 #57`
  };
});

await step('S1.2', '班级掌握度列与后端 #57 同源（#57 的 masteryPercent 就是班级列）', async () => {
  const shown = await pS.evaluate(() => [...document.querySelectorAll('.cap-list .cap')].map(el => ({
    name: (el.querySelector('.cap-n')?.textContent || '').trim(),
    cls: Number(String(el.querySelector('.cap-c')?.textContent || '').replace(/[^\d.-]/g, ''))
  })));
  const weekKnowledge = await dataOf(`/api/teaching/analytics/class/${CLASS_ID}/knowledge?range=week`, tokT);
  const monthKnowledge = await dataOf(`/api/teaching/analytics/class/${CLASS_ID}/knowledge?range=month`, tokT);
  const w = (weekKnowledge?.list || []).map(k => ({ name: k.kpName, pct: k.masteryPercent }));
  const m = (monthKnowledge?.list || []).map(k => ({ name: k.kpName, pct: k.masteryPercent }));
  evidence.knowledgeRangeBackend = { week: w, month: m, shown };
  if (!shown.length) return { pass: false, detail: '页面没有知识点行，无法判断同源' };
  if (!w.length && !m.length) return { pass: false, detail: '#57 在两个 range 下都返回空列表 —— 页面数值不可能来自后端' };

  const matches = (page, backend) => backend.length > 0 && backend.every(b => {
    const hit = page.find(r => r.name === b.name);
    return hit && Math.abs(Number(hit.cls) - Number(b.pct)) <= 0.5;
  });
  /* 页面当前处于哪个 range 未知，所以「对上任一个 range 的真实返回」即视为同源 */
  const hitWeek = matches(shown, w);
  const hitMonth = matches(shown, m);
  return {
    pass: hitWeek || hitMonth,
    detail: `页面班级列=${brief(shown.map(r => r.name + '=' + r.cls + '%'))}；` +
      `后端 week=${hitWeek ? '一致' : '不一致'} month=${hitMonth ? '一致' : '不一致'}`
  };
});

await step('S1.3', '题目掌握表里的题目 ID 在后端真实存在（#7 逐题回查）', async () => {
  const rows = await pS.evaluate(() => [...document.querySelectorAll('.tbl tbody tr')].map(tr => {
    const idCell = tr.querySelector('.q-id');
    const tCell = tr.querySelector('.q-t');
    return {
      id: Number(String(idCell?.textContent || '').replace(/[^\d]/g, '')),
      title: (tCell?.textContent || '').trim()
    };
  }).filter(r => Number.isFinite(r.id) && r.id > 0));
  evidence.studentProblemRows = rows.slice(0, 8);
  if (!rows.length) return { pass: false, detail: '题目掌握表没有可解析的题目 ID 行' };
  const sample = rows.slice(0, 5);
  const bad = [];
  for (const r of sample) {
    const { status, env } = await apiGet(`/api/problem/questions/${r.id}`, tokS);
    if (status !== 200 || !env?.data?.title) bad.push({ id: r.id, status, title: env?.data?.title });
    else if (r.title && env.data.title !== r.title) bad.push({ id: r.id, pageTitle: r.title, backendTitle: env.data.title });
  }
  return {
    pass: bad.length === 0,
    detail: bad.length ? `有 ${bad.length} 个 ID 后端查不到或题名不符: ${brief(bad)}`
      : `${sample.length} 个题目 ID 全部真实存在且题名一致: ${sample.map(r => r.id).join(', ')}`
  };
});

await step('S1.4', '学生学情页真实打到了网关（不是纯前端 setTimeout 造假）', async () => {
  const hits = pS.__net.filter(u => /\/api\/teaching\/analytics\/|\/api\/problem\/|week-trend/.test(u));
  evidence.studentNet = pS.__net;
  return {
    pass: hits.length > 0,
    detail: hits.length ? `${hits.length} 个学情/题库请求: ${brief(hits.slice(0, 6), 240)}`
      : `0 个学情请求；整页只发了 ${pS.__net.length} 个请求: ${brief(pS.__net.slice(0, 6), 200)}`
  };
});

await step('S1.5', '学生学情页 0 pageerror / 0 未捕获 console error', async () => {
  const mine = pageErrors.filter(e => e.startsWith('[student]'));
  return { pass: mine.length === 0, detail: mine.slice(0, 3).join(' | ') || '干净' };
});

/* ══════════════════════════════════════════════════════════════════
   S2 教师看板真实（t-dashboard.html + t-analytics.html）
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S2 教师看板真实（t-dashboard.html + t-analytics.html） ──');
await step('S2.0', '打开教师工作台且无致命脚本错误', async () => {
  await pT.goto(`${SITE}/t-dashboard.html`, { waitUntil: 'networkidle', timeout: 45000 });
  await pT.waitForFunction(() => window.Alpine && Alpine.$data(document.getElementById('page-content')), { timeout: 20000 });
  await pT.waitForTimeout(1500);
  const mounted = !!(await pageData(pT));
  evidence.dashboardShot = await shot(pT, 't-dashboard');
  return { pass: mounted, detail: `Alpine 组件 ${mounted ? '已挂载' : '未挂载'}` };
});

await step('S2.1', '周趋势柱子数量与 #53 的 days 条数一致', async () => {
  const bars = await pT.locator('.hero-wk button').count();
  const axis = await pT.locator('.wkaxis span').count();
  const backendDays = EXPECT.trend?.days?.length ?? -1;
  evidence.weekBars = { bars, axis, backendDays };
  return {
    pass: bars === backendDays && axis === backendDays && backendDays > 0,
    detail: `页面柱子 ${bars} 根（轴 ${axis} 个）/ 后端 #53 days=${backendDays}`
  };
});

await step('S2.2', '本周通过率数字 == #53 的 weekPassRate（不是写死的 62）', async () => {
  const shown = await pT.evaluate(() => {
    const el = document.querySelector('.wkv');
    if (!el) return null;
    // .wkv 里是「33%」这种整数显示；取第一个数字
    const m = /(\d+(?:\.\d+)?)/.exec(String(el.textContent || ''));
    return m ? Number(m[1]) : null;
  });
  const backendRaw = Number(EXPECT.trend?.weekPassRate);
  const sub = EXPECT.trend?.weekSubmit;
  const ac = EXPECT.trend?.weekAc;
  evidence.weekPassRate = { shown, backendRaw, sub, ac };
  // ⚠️ 后端下发的是**未取整**的百分比（33.06），页面按原型模板显示为整数（33）。
  // 直接 `shown === backend` 会假红。正确判据：页面整数 == 后端四舍五入后的整数，
  // 或在 ±1 的取整容差内（后端 33.06 → 页面 33；若后端做了 trunc 也会是 33）。
  const backendRounded = Math.round(backendRaw);
  const ok = Number.isFinite(shown) && Number.isFinite(backendRaw)
    && (shown === backendRounded || Math.abs(shown - backendRaw) < 1);
  return {
    pass: ok,
    detail: `页面 ${shown}% / 后端 weekPassRate=${backendRaw}（四舍五入 ${backendRounded}）；`
      + `weekSubmit=${sub} weekAc=${ac}`
  };
});

await step('S2.3', '周趋势逐日数值与 #53 一致（抽查柱子的 aria-label 真值）', async () => {
  const labels = await pT.evaluate(() => [...document.querySelectorAll('.hero-wk button')]
    .map(b => b.getAttribute('aria-label') || ''));
  const days = EXPECT.trend?.days || [];
  if (!labels.length || !days.length) return { pass: false, detail: `页面 ${labels.length} 条 / 后端 ${days.length} 条` };
  const mismatch = [];
  days.forEach((d, i) => {
    const label = labels[i] || '';
    const nums = (label.match(/\d+/g) || []).map(Number);
    // aria-label 形如 "10-18 周日 提交 38 次、通过 24 次" —— 末尾两个数字是 sub / ac
    const tail = nums.slice(-2);
    if (!(tail.includes(Number(d.submitCount)) && tail.includes(Number(d.acCount)))) {
      mismatch.push({ i, label, expect: [d.submitCount, d.acCount] });
    }
  });
  evidence.weekLabels = labels;
  return {
    pass: mismatch.length === 0,
    detail: mismatch.length ? `逐日不一致: ${brief(mismatch.slice(0, 3))}`
      : `7 天逐一对应：${days.map(d => `${d.date} ${d.submitCount}/${d.acCount}`).join(' | ')}`
  };
});

await step('S2.4', '打开班级学情页（t-analytics.html）且无致命脚本错误', async () => {
  await pT.goto(`${SITE}/t-analytics.html`, { waitUntil: 'networkidle', timeout: 45000 });
  await pT.waitForFunction(() => window.Alpine && Alpine.$data(document.getElementById('page-content')), { timeout: 20000 });
  await pT.waitForTimeout(1800);
  const mounted = !!(await pageData(pT));
  evidence.analyticsShot = await shot(pT, 't-analytics');
  return { pass: mounted, detail: `Alpine 组件 ${mounted ? '已挂载' : '未挂载'}` };
});

await step('S2.5', '风险学生姓名来自真实 sys_user（且不含原型假名字）', async () => {
  const names = await pT.evaluate(() => [...document.querySelectorAll('.risk .rm b')]
    .map(el => String(el.textContent || '').trim()).filter(Boolean));
  evidence.riskNames = names;
  if (!names.length) return { pass: false, detail: '风险学生面板没有姓名（可能仍走空态或未接接口）' };
  const fake = names.filter(n => FAKE_NAMES.includes(n));
  const notReal = names.filter(n => !EXPECT.realNames.has(n));
  const backendRisk = (EXPECT.risk?.list || []).map(r => r.name);
  evidence.riskBackendNames = backendRisk;
  return {
    pass: fake.length === 0 && notReal.length === 0,
    detail: fake.length ? `仍是原型假名字: ${brief(fake)}`
      : notReal.length ? `sys_user 里没有这些姓名: ${brief(notReal)}（#59 返回 ${brief(backendRisk)}）`
        : `${names.length} 个姓名全部在 sys_user 中真实存在: ${brief(names, 200)}`
  };
});

await step('S2.6', '#58 学生表分页与 total 对得上（条数 / 总人数 / 页数三者自洽）', async () => {
  const rows = await pT.locator('tbody tr.rowclick').count();
  const pagerText = await pT.evaluate(() => {
    const el = document.querySelector('.pager');
    return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : '';
  });
  const total = EXPECT.students?.total;
  const backendPageSize = EXPECT.students?.pageSize;
  const nums = (pagerText.match(/\d+/g) || []).map(Number);
  const shownTotal = nums[0];                       // 「共 N 人 · 第 p / q 页」
  const shownPages = nums[nums.length - 1];
  const expectPages = total > 0 ? Math.ceil(total / (rows || 1)) : 0;
  evidence.studentPager = { rows, pagerText, total, backendPageSize, shownTotal, shownPages, expectPages };
  const problems = [];
  if (shownTotal !== total) problems.push(`页脚总人数 ${shownTotal} ≠ 后端 total ${total}`);
  if (rows > (backendPageSize || 100)) problems.push(`本页 ${rows} 行 > 后端 pageSize ${backendPageSize}`);
  if (total > rows && shownPages !== expectPages) problems.push(`页数 ${shownPages} ≠ ceil(${total}/${rows})=${expectPages}`);
  if (total <= rows && shownPages !== 1) problems.push(`一页装得下（${total}≤${rows}）但页数是 ${shownPages}`);
  return { pass: problems.length === 0 && rows > 0, detail: problems.join('；') || brief(pagerText, 160) };
});

await step('S2.7', '班级学情页知识点掌握度 == 后端 #57', async () => {
  const rows = await pT.evaluate(() => [...document.querySelectorAll('.aihero-side .kp')].map(el => ({
    name: (el.querySelector('.kp-n')?.textContent || '').trim(),
    pct: Number(String(el.querySelector('.kp-v span')?.textContent || '').replace(/[^\d.-]/g, ''))
  })));
  const backend = EXPECT.knowledge.map(k => ({ name: k.kpName, pct: k.masteryPercent }));
  evidence.teacherKp = { rows, backend };
  if (!rows.length) return { pass: false, detail: '知识点清单为空（.aihero-side .kp 为 0）' };
  const bad = backend.filter(b => {
    const hit = rows.find(r => r.name === b.name);
    return !hit || Math.abs(Number(hit.pct) - Number(b.pct)) > 0.5;
  });
  const hardcoded = rows.length === FAKE_MASTERY.length
    && [...FAKE_MASTERY].sort().join(',') === rows.map(r => r.pct).sort().join(',');
  return {
    pass: bad.length === 0 && !hardcoded,
    detail: hardcoded ? '仍是原型 KP_BASE 硬编码 82/64/41/73/55'
      : bad.length ? `不一致: ${brief(bad)}` : `一致: ${rows.map(r => `${r.name}=${r.pct}%`).join(' / ')}`
  };
});

await step('S2.8', '教师两页真实打到了网关（不是纯前端造假）', async () => {
  const hits = pT.__net.filter(u => /\/api\/teaching\/analytics\/|\/api\/teaching\/week-trend|\/api\/teaching\/todos/.test(u));
  evidence.teacherNet = pT.__net;
  return {
    pass: hits.length > 0,
    detail: hits.length ? `${hits.length} 个学情请求: ${brief(hits.slice(0, 8), 260)}`
      : `0 个学情请求；整页请求: ${brief(pT.__net.slice(0, 8), 220)}`
  };
});

await step('S2.9', '教师两页 0 pageerror / 0 未捕获 console error', async () => {
  const mine = pageErrors.filter(e => e.startsWith('[teacher]'));
  return { pass: mine.length === 0, detail: mine.slice(0, 3).join(' | ') || '干净' };
});

/* ══════════════════════════════════════════════════════════════════
   S3 管理端探活真实（a-models.html）
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S3 管理端探活真实（a-models.html） ──');
await step('S3.0', '打开模型通道页且无致命脚本错误', async () => {
  await pA.goto(`${SITE}/a-models.html`, { waitUntil: 'networkidle', timeout: 45000 });
  await pA.waitForFunction(() => window.Alpine && Alpine.$data(document.getElementById('page-content')), { timeout: 20000 });
  await pA.waitForTimeout(1500);
  const mounted = !!(await pageData(pA));
  return { pass: mounted, detail: `Alpine 组件 ${mounted ? '已挂载' : '未挂载'}` };
});

await step('S3.1', '通道列表来自后端 #72（条数与 summary 对得上）', async () => {
  const rows = await pA.locator('.ch-tbl tbody tr.rowclick').count();
  const cards = await pA.locator('.hgrid .hblk').count();
  const backendTotal = EXPECT.channels?.total;
  evidence.channelRows = { rows, cards, backendTotal };
  return {
    pass: rows === backendTotal && rows > 0,
    detail: `表格 ${rows} 行 / 健康卡 ${cards} 块 / 后端 #72 total=${backendTotal}`
  };
});

/* 真实点击「测试」→ 捕获真实 /probe 响应。目标优先 local-qwen。 */
let probeEvidence = null;
await step('S3.2', '点「测试」真实发出 #78 探活请求（捕获响应）', async () => {
  const state = await pageData(pA);
  const channels = state?.channels || [];
  evidence.channelsState = channels.map(c => {
    const flat = {};
    for (const [k, v] of Object.entries(c || {})) {
      if (v === null || ['string', 'number', 'boolean'].includes(typeof v)) flat[k] = v;
    }
    return flat;
  });
  // 用「值里有 local-qwen」的方式定位，不依赖字段名（前后端字段可能叫 id / code / channelCode）
  const isLocal = c => Object.values(c || {}).some(v => typeof v === 'string' && v.trim() === 'local-qwen');
  const localIdx = channels.findIndex(isLocal);
  const targetIdx = localIdx >= 0 ? localIdx : 0;
  if (!channels.length) return { pass: false, detail: '页面 channels 为空，无法定位通道' };

  const row = pA.locator('.ch-tbl tbody tr.rowclick').nth(targetIdx);
  const testBtn = row.locator('button.test').first();
  const btnCount = await testBtn.count();
  if (!btnCount) return { pass: false, detail: '.ch-tbl 行内没有「测试」按钮（button.test）' };

  // 探活有 10s 防抖锁：429 时等 12s 再点一次
  let captured = null;
  for (let attempt = 1; attempt <= 2 && !captured; attempt++) {
    const waitResp = pA.waitForResponse(
      r => /\/api\/admin\/channels\/[^/]+\/probe/.test(r.url()) && r.request().method() === 'POST',
      { timeout: 30000 }).catch(() => null);
    await testBtn.click({ force: true });
    const resp = await waitResp;
    if (!resp) continue;
    let env = null;
    try { env = await resp.json(); } catch { env = null; }
    if (resp.status() === 429) {
      console.log('       [429] 探活防抖，等 12s 重试');
      await pA.waitForTimeout(12000);
      continue;
    }
    captured = { status: resp.status(), url: resp.url(), env };
  }
  if (!captured) return { pass: false, detail: '没有捕获到 /probe 响应（按钮可能仍是 setTimeout 假动作）' };
  probeEvidence = captured;
  evidence.probe = { status: captured.status, url: captured.url, data: captured.env?.data, code: captured.env?.code };
  const d = captured.env?.data || {};
  return {
    pass: captured.status === 200 && captured.env?.code === 200,
    detail: `HTTP ${captured.status} code=${captured.env?.code} url=${captured.url} data=${brief(d)}`
  };
});

await step('S3.3', '探活真实回显 latencyMs（数字 > 0）与 httpStatus', async () => {
  if (!probeEvidence) return { pass: false, detail: 'S3.2 没能捕获到 /probe 响应，本条无法判断' };
  const d = probeEvidence.env?.data || {};
  const latency = Number(d.latencyMs);
  const httpStatus = d.httpStatus;
  evidence.probeLatency = { latencyMs: d.latencyMs, httpStatus, modelEcho: d.modelEcho, healthStatus: d.healthStatus };
  const problems = [];
  if (!Number.isFinite(latency) || latency <= 0) problems.push(`latencyMs=${brief(d.latencyMs)}（必须 >0 的数字）`);
  if (!Number.isFinite(Number(httpStatus))) problems.push(`httpStatus=${brief(httpStatus)}（必须是数字）`);
  return {
    pass: problems.length === 0,
    detail: problems.join('；') || `latencyMs=${latency}ms httpStatus=${httpStatus} modelEcho=${brief(d.modelEcho, 80)} healthStatus=${d.healthStatus}`
  };
});

await step('S3.4', '探活结果回显到页面（不是只拿到响应不渲染）', async () => {
  const state = await pageData(pA);
  const channels = state?.channels || [];
  const isLocal = c => Object.values(c || {}).some(v => typeof v === 'string' && v.trim() === 'local-qwen');
  const target = channels.find(isLocal) || channels[0] || {};
  // 页面可能把延迟写进 ttft / latency / note 等既有字段；扫一遍有没有「数字 > 0」的新值 + 文本里有 ms
  const flat = {};
  for (const [k, v] of Object.entries(target)) {
    if (v === null || ['string', 'number', 'boolean'].includes(typeof v)) flat[k] = v;
  }
  const latencyLike = Object.entries(flat).filter(([k, v]) =>
    /ttft|latency|ms/i.test(k) && Number.isFinite(Number(v)) && Number(v) > 0);
  const pageText = await pA.evaluate(() => document.body.innerText || '');
  const msShown = /\b\d+(\.\d+)?\s*(ms|s)\b/.test(pageText);
  evidence.probeRendered = { stateFlat: flat, latencyLike, msShown };
  const backendLatency = probeEvidence?.env?.data?.latencyMs;
  const echoed = pageText.includes(String(backendLatency)) || latencyLike.length > 0;
  return {
    pass: msShown && echoed,
    detail: `页面含 ms 量纲=${msShown}；状态里延迟类字段=${brief(latencyLike)}；后端 latencyMs=${backendLatency}`
  };
});

await step('S3.5', 'local-qwen（Ollama 在跑）探活后为 ON', async () => {
  const r = await fetch(`${GATEWAY}/api/admin/channels?page=1&pageSize=50`, {
    headers: { Authorization: 'Bearer ' + tokA }
  });
  const env = await r.json().catch(() => null);
  const local = (env?.data?.list || []).find(c => c.channelCode === 'local-qwen');
  const state = await pageData(pA);
  const isLocal = c => Object.values(c || {}).some(v => typeof v === 'string' && v.trim() === 'local-qwen');
  const pageLocal = (state?.channels || []).find(isLocal) || {};
  const pageText = await pA.evaluate(() => document.body.innerText || '');
  evidence.localQwen = { backend: local ? { healthStatus: local.healthStatus, isEnabled: local.isEnabled, avgTtftMs: local.avgTtftMs } : null,
    pageState: brief(pageLocal, 300) };
  const backendOn = String(local?.healthStatus || '').toUpperCase() === 'ON';
  // 页面上该通道块的状态文案应为「在线」
  const pageShowsOnline = /本地\s*Qwen|local-qwen/i.test(pageText) && /在线/.test(pageText);
  return {
    pass: backendOn && pageShowsOnline,
    detail: `后端 healthStatus=${local?.healthStatus}；页面出现「在线」=${pageShowsOnline}`
  };
});

await step('S3.6', '模型通道页真实打到了网关（不是纯前端造假）', async () => {
  const hits = pA.__net.filter(u => /\/api\/admin\/channels|\/api\/admin\/health|\/api\/admin\/stats/.test(u));
  evidence.adminModelsNet = pA.__net;
  return {
    pass: hits.length > 0,
    detail: hits.length ? `${hits.length} 个管理请求: ${brief(hits.slice(0, 8), 260)}`
      : `0 个管理请求；整页请求: ${brief(pA.__net.slice(0, 8), 220)}`
  };
});

await step('S3.7', '模型通道页 0 pageerror / 0 未捕获 console error', async () => {
  const mine = pageErrors.filter(e => e.startsWith('[admin]'));
  return { pass: mine.length === 0, detail: mine.slice(0, 3).join(' | ') || '干净' };
});

/* ══════════════════════════════════════════════════════════════════
   S4 审计页真数据（a-audit.html）
   ══════════════════════════════════════════════════════════════════ */
console.log('\n── S4 审计页真数据（a-audit.html） ──');
await step('S4.0', '打开调用审计页且无致命脚本错误', async () => {
  await pA.goto(`${SITE}/a-audit.html`, { waitUntil: 'networkidle', timeout: 45000 });
  await pA.waitForFunction(() => window.Alpine && Alpine.$data(document.getElementById('page-content')), { timeout: 20000 });
  await pA.waitForTimeout(1800);
  const mounted = !!(await pageData(pA));
  evidence.auditShotPending = true;
  return { pass: mounted, detail: `Alpine 组件 ${mounted ? '已挂载' : '未挂载'}` };
});

await step('S4.1', '调用记录条数与 #81 的 total 一致', async () => {
  const pagerText = await pA.evaluate(() => {
    const el = document.querySelector('.aud-tab .pager');
    return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : '';
  });
  const tabCount = await pA.evaluate(() => {
    const el = document.querySelector('.tabs .seg-item .n');
    return el ? Number(String(el.textContent || '').replace(/[^\d]/g, '')) : null;
  });
  const backendTotal = EXPECT.calls?.total;
  // ⚠️ 不能用「所有数字的第一个」：分页文案是「共 67 条记录 · 第 1 / 9 页」，
  //    而模板里第 3 个数字可能是时间戳片段（实测出现过 123456789）。
  //    只认「共 N 条」这个锚点。
  const shownMatch = /共\s*(\d+)\s*条/.exec(pagerText);
  const shown = shownMatch ? Number(shownMatch[1]) : null;
  evidence.auditCounts = { pagerText, tabCount, backendTotal, shown };
  // 页签徽标是「当前已加载到内存的条数」（分页加载时可能小于 total），
  // 只有一次拉完时才等于 total —— 所以徽标用「≤ total 且 > 0」判，
  // 精确相等靠页脚「共 N 条」那条。
  const tabOk = Number.isFinite(tabCount) && tabCount > 0 && tabCount <= Number(backendTotal);
  return {
    pass: shown === Number(backendTotal) && tabOk,
    detail: `页脚「${brief(pagerText, 100)}」→ 共 ${shown} 条；后端 #81 total=${backendTotal}；`
      + `页签徽标（已加载）${tabCount}`
  };
});

await step('S4.2', '审计表格行数 == 页面当前页应显示的条数（不是空表假数字）', async () => {
  // 排除骨架行（tr.sk-row）：加载态骨架与数据是两套 tr，混在一起数会得到假行数
  const rows = await pA.locator('.au-tbl tbody tr:not(.sk-row)').count();
  const state = await pageData(pA);
  const callsLen = (state?.calls || []).length;
  const total = Number(EXPECT.calls?.total ?? 0);
  const pageSize = Number(state?.pageSize ?? 0);
  // 页面持有「当前页」的 calls（实测 8 条 = 它的 pageSize），不是全量 total。
  // 正确判据：calls 长度 ≤ total，且恰好等于 min(pageSize, total)。
  const expected = pageSize > 0 ? Math.min(pageSize, total) : total;
  evidence.auditRows = { rows, callsLen, total, pageSize, expected };
  return {
    pass: rows > 0 && rows === callsLen && callsLen === expected,
    detail: `渲染 ${rows} 行（排除骨架）/ 状态 calls=${callsLen} / 期望 min(pageSize ${pageSize}, total ${total})=${expected}`
  };
});

await step('S4.3', '点「导出 CSV」拿到真实响应（Content-Disposition 含 attachment）', async () => {
  const btn = pA.locator('.ph-act button:has-text("导出")').first();
  if (!(await btn.count())) return { pass: false, detail: '找不到「导出 CSV」按钮' };
  let resp = null;
  for (let attempt = 1; attempt <= 2 && !resp; attempt++) {
    const waiter = pA.waitForResponse(
      r => /\/api\/admin\/audit\/calls\/export/.test(r.url()),
      { timeout: 45000 }).catch(() => null);
    const download = pA.waitForEvent('download', { timeout: 15000 }).catch(() => null);
    await btn.click({ force: true });
    const r = await waiter;
    const dl = await download;
    if (!r) continue;
    if (r.status() === 429) {
      console.log('       [429] 导出限流（1 次/分钟），等 61s 重试');
      await pA.waitForTimeout(61000);
      continue;
    }
    // 不取 r.text()：blob 下载会把响应体消费掉，正文改从 download 落盘文件读（见下方）
    resp = { status: r.status(), headers: r.headers(), download: dl };
  }
  if (!resp) return { pass: false, detail: '没有捕获到 /audit/calls/export 响应（按钮可能仍是 setTimeout 假动作）' };
  const disposition = String(resp.headers['content-disposition'] || '');
  const contentType = String(resp.headers['content-type'] || '');
  /* ⚠️ 不能用 r.text() 取正文：页面是 blob 下载，响应体已被下载流消费，text() 拿到空串。
     正确做法是从 Playwright 的 download 事件里拿到真实落盘文件，再读它。
     这样断言的才是「用户点导出后真正拿到的那个文件」。 */
  let csvBody = '';
  let downloadPath = null;
  if (resp.download) {
    try {
      const savePath = path.join(OUT, 'audit-export.csv');
      await resp.download.saveAs(savePath);
      downloadPath = savePath;
      csvBody = fs.readFileSync(savePath, 'utf-8');
    } catch (e) {
      csvBody = '';
    }
  }
  evidence.export = {
    status: resp.status, contentType, disposition,
    downloaded: !!resp.download,
    suggestedFilename: resp.download ? resp.download.suggestedFilename() : null,
    savedTo: downloadPath ? path.relative(ROOT, downloadPath) : null,
    bytes: csvBody.length,
    head: csvBody.slice(0, 300)
  };
  const ok = resp.status === 200 && /attachment/i.test(disposition) && /text\/csv/i.test(contentType);
  return {
    pass: ok,
    detail: `HTTP ${resp.status} content-type=${contentType} disposition=${brief(disposition, 120)} ` +
      `download=${!!resp.download}${resp.download ? '(' + resp.download.suggestedFilename() + ')' : ''} ` +
      `落盘=${csvBody.length} 字节`
  };
});

await step('S4.4', '导出的 CSV 表头恰好 12 列', async () => {
  const body = evidence.export?.body || evidence.export?.head || '';
  const firstLine = String(body || '').replace(/\r\n/g, '\n').split('\n').find(l => l.trim()) || '';
  const columns = firstLine ? firstLine.split(',') : [];
  evidence.csvColumns = columns;
  return {
    pass: columns.length === 12,
    detail: `列数=${columns.length}，表头=${brief(firstLine, 240)}`
  };
});

await step('S4.5', '导出内容是真实 CSV（不是 JSON 错误体冒充）', async () => {
  const body = String(evidence.export?.head || '');
  const firstLine = body.replace(/\r\n/g, '\n').split('\n').find(l => l.trim()) || '';
  /* 关键：404/400 的错误信封也是「一行文本」，只看行数会把 JSON 报错当成 CSV 通过。
     真 CSV 的特征是「表头里有逗号且不以 { 开头」。 */
  const looksJson = firstLine.trim().startsWith('{') || /"timestamp"\s*:/.test(firstLine);
  const hasComma = firstLine.includes(',');
  const rows = body.replace(/\r\n/g, '\n').split('\n').filter(l => l.trim()).length;
  evidence.exportRowCount = rows;
  return {
    pass: !looksJson && hasComma && rows >= 1,
    detail: looksJson ? `导出返回的是 JSON 错误体，不是 CSV: ${brief(firstLine, 160)}`
      : hasComma ? `CSV 首行含逗号分隔，共 ${rows} 行（含表头）`
        : `首行没有逗号，不像 CSV: ${brief(firstLine, 160)}`
  };
});

await step('S4.6', '审计页真实打到了网关（不是纯前端造假）', async () => {
  const hits = pA.__net.filter(u => /\/api\/admin\/audit\/calls|\/api\/admin\/sandbox/.test(u));
  evidence.adminAuditNet = pA.__net;
  return {
    pass: hits.length > 0,
    detail: hits.length ? `${hits.length} 个审计请求: ${brief(hits.slice(0, 8), 260)}`
      : `0 个审计请求；整页请求: ${brief(pA.__net.slice(0, 8), 220)}`
  };
});

/* ── 密钥红线（agent.md §2.6）：响应 + 页面两端都要干净 ──────────────
   在审计详情这条最重的链路上守：它同时带 prompt / reply / 通道信息，
   一旦把 apiKey 或未解析的 ${VAR} 原样吐出来，答辩现场就是事故。 */
await step('S4.7', '审计详情 / 通道详情响应不含明文密钥（sk-xxx / ${VAR} / env:VAR）', async () => {
  const account = await dataOf('/api/auth/me', tokA);
  const calls = await dataOf('/api/admin/audit/calls?page=1&pageSize=5', tokA);
  const channels = await dataOf('/api/admin/channels?page=1&pageSize=50', tokA);
  const recordId = calls?.list?.[0]?.recordId;
  const record = recordId
    ? await dataOf(`/api/admin/audit/calls/${recordId}`, tokA)
    : null;
  const channelId = channels?.list?.[0]?.id;
  const channel = channelId
    ? await dataOf(`/api/admin/channels/${channelId}`, tokA)
    : null;

  const scanned = { me: account, calls, channels, record, channel };
  const hits = [];
  for (const [key, payload] of Object.entries(scanned)) {
    if (!payload) continue;
    for (const h of findSecrets(payload, key)) hits.push(h);
  }
  evidence.secretScan = { scannedKeys: Object.keys(scanned), hits };
  /* apiKeyMasked 必须是脱敏形态：含 * 或为空，且长度可控 */
  const masked = channel?.apiKeyMasked;
  const maskedOk = masked === undefined || masked === null || masked === ''
    || (typeof masked === 'string' && (masked.includes('*') || masked.includes('·')) && masked.length <= 24);
  return {
    pass: hits.length === 0 && maskedOk,
    detail: hits.length ? `命中明文密钥: ${brief(hits.slice(0, 5))}`
      : maskedOk ? `4 个响应全部干净；apiKeyMasked=${brief(masked, 60)}`
        : `apiKeyMasked 不是脱敏形态: ${brief(masked, 80)}`
  };
});

await step('S4.8', '页面 DOM 文本不含明文密钥（前端没把 key 渲染出来）', async () => {
  // 扫全部已访问页面：任何一处显式渲染 key 都算破红线
  const pages = [pS, pT, pA];
  const hits = [];
  for (const p of pages) {
    let text = '';
    try { text = await p.evaluate(() => document.body.innerText || ''); } catch { continue; }
    const m = SECRET_RE.exec(text);
    if (m) hits.push({ page: p.__label, match: m[0].slice(0, 40) });
  }
  evidence.domSecretScan = hits;
  return {
    pass: hits.length === 0,
    detail: hits.length ? `页面文本命中: ${brief(hits)}` : '三个页面 DOM 文本均无密钥形态'
  };
});

await step('S4.9', '审计页截图 + 0 pageerror / 0 未捕获 console error', async () => {
  evidence.auditShot = await shot(pA, 'a-audit');
  const mine = pageErrors.filter(e => e.startsWith('[admin]'));
  return { pass: mine.length === 0, detail: mine.slice(0, 3).join(' | ') || '干净' };
});

/* 补一张探活后的 a-models 截图（S3 之后 a-models 已被 S4 覆盖，单独再拍一次） */
try {
  await pA.goto(`${SITE}/a-models.html`, { waitUntil: 'networkidle', timeout: 45000 });
  await pA.waitForTimeout(1500);
  evidence.modelsShot = await shot(pA, 'a-models');
} catch (e) {
  evidence.modelsShotError = String(e && e.message ? e.message : e);
}

/* ══════════════════════════════════════════════════════════════════
   收尾：报告落盘（结构化 JSON，agent.md §11.3 第 10 条）
   ══════════════════════════════════════════════════════════════════ */
await student.ctx.close(); await teacher.ctx.close(); await admin.ctx.close();
await browser.close();
server.close();

const passed = checks.filter(c => c.pass).length;
const total = checks.length;
const report = {
  suite: 'step6-acceptance',
  generatedAt: new Date().toISOString(),
  gateway: GATEWAY,
  site: { root: 'prototype/app', port: PORT },
  accounts: {
    student: { username: '20220101', id: STUDENT_ID, classId: CLASS_ID },
    teacher: { username: 'T001' },
    admin: { username: 'admin' }
  },
  passed, total, failed: total - passed,
  checks,
  pageErrors,
  evidence
};
fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(report, null, 2), 'utf-8');

console.log(`\n══════════════════════════════════════════════════════════`);
console.log(`  第 6 步「验收标志」逐字复现：${passed} 通过 / ${total - passed} 失败（共 ${total} 项）`);
console.log(`  报告：${path.relative(ROOT, path.join(OUT, 'report.json'))}   截图：${path.relative(ROOT, OUT)}/`);
console.log(`══════════════════════════════════════════════════════════`);
if (passed !== total) {
  console.log('\n未通过项：');
  for (const c of checks.filter(x => !x.pass)) console.log(`  FAIL [${c.id}] ${c.name}\n        ${c.detail}`);
}
process.exitCode = passed === total ? 0 : 1;
