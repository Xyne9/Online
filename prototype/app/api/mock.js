/* api/mock.js —— http.js 在 useMock() 为真时短路到这里
   ─────────────────────────────────────────────────────────────────────
   Day2 Stage 0 地基②：没有后端也能演示的假数据源。
   做法：把 shell.js:320-454 的 8 组常量整块搬进本文件（字面量一个不改），
        再按 03 指南 §2.4 的搬迁表补上"契约字段"——原字段与契约字段并存，
        这样 14 个页面现有 `OJ.xxx` 读取不会崩，mock 路由又能吐契约形状。
   同一文件同时是：fixtures 唯一数据源 + 判题进度模拟器 + 路由表。
   风格：纯 ES5。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  var A = global.adapter;

  /* ══════════════════════════════════════════════════════════════════
     1. 数据源（原样搬自 shell.js:320-454）
     ────────────────────────────────────────────────────────────────── */

  var PROBLEMS = [
    { id: 1024, title: '最大子段和', lvl: 3, tags: ['动态规划', '前缀和'], ac: 62, tried: 1841,
      status: 'wa', score: 80 },
    { id: 1001, title: '两数之和', lvl: 1, tags: ['哈希表', '数组'], ac: 88, tried: 3210,
      status: 'ac', score: 100 },
    { id: 1017, title: '二叉树层序遍历', lvl: 2, tags: ['树', '广度优先'], ac: 74, tried: 1502,
      status: 'ac', score: 100 },
    { id: 1032, title: '最短路径（Dijkstra）', lvl: 3, tags: ['图', '最短路', '堆'], ac: 41, tried: 986,
      status: 'none', score: 0 },
    { id: 1045, title: '区间合并', lvl: 2, tags: ['排序', '贪心'], ac: 67, tried: 1233,
      status: 'none', score: 0 },
    { id: 1058, title: '背包问题（01）', lvl: 3, tags: ['动态规划'], ac: 55, tried: 1740,
      status: 'tle', score: 60 },
    { id: 1063, title: '并查集 · 朋友圈', lvl: 3, tags: ['并查集', '图'], ac: 48, tried: 812,
      status: 'none', score: 0 },
    { id: 1071, title: '滑动窗口最大值', lvl: 3, tags: ['单调队列', '滑动窗口'], ac: 39, tried: 640,
      status: 'none', score: 0 },
    { id: 1088, title: '字符串匹配（KMP）', lvl: 4, tags: ['字符串', 'KMP'], ac: 27, tried: 508,
      status: 'none', score: 0 },
    { id: 1092, title: '拓扑排序 · 课程表', lvl: 3, tags: ['图', '拓扑排序'], ac: 52, tried: 733,
      status: 'none', score: 0 },
    { id: 1105, title: '最长递增子序列', lvl: 3, tags: ['动态规划', '二分'], ac: 44, tried: 901,
      status: 're', score: 0 },
    { id: 1118, title: '回文串分割', lvl: 4, tags: ['动态规划', '字符串'], ac: 31, tried: 412,
      status: 'none', score: 0 }
  ];

  var SUBMISSIONS = [
    { id: 'S-20481', pid: 1024, title: '最大子段和', lang: 'C++ 17', status: 'wa', score: 80,
      pass: '8/10', time: '12ms', mem: '4096KB', at: '10-24 23:41', first: 3 },
    { id: 'S-20477', pid: 1024, title: '最大子段和', lang: 'C++ 17', status: 'wa', score: 70,
      pass: '7/10', time: '9ms', mem: '3902KB', at: '10-24 23:18', first: 3 },
    { id: 'S-20466', pid: 1024, title: '最大子段和', lang: 'C++ 17', status: 'tle', score: 40,
      pass: '4/10', time: '1024ms', mem: '4108KB', at: '10-24 22:52', first: 7 },
    { id: 'S-20440', pid: 1001, title: '两数之和', lang: 'C++ 17', status: 'ac', score: 100,
      pass: '10/10', time: '6ms', mem: '3204KB', at: '10-24 21:03', first: null },
    { id: 'S-20412', pid: 1017, title: '二叉树层序遍历', lang: 'C++ 17', status: 'ac', score: 100,
      pass: '10/10', time: '8ms', mem: '3512KB', at: '10-24 20:11', first: null },
    { id: 'S-20398', pid: 1058, title: '背包问题（01）', lang: 'C++ 17', status: 'tle', score: 60,
      pass: '6/10', time: '2048ms', mem: '512000KB', at: '10-23 19:42', first: 8 },
    { id: 'S-20371', pid: 1105, title: '最长递增子序列', lang: 'Python 3', status: 're', score: 0,
      pass: '0/10', time: '—', mem: '—', at: '10-23 17:20', first: 1 },
    { id: 'S-20355', pid: 1001, title: '两数之和', lang: 'Java 17', status: 'ce', score: 0,
      pass: '0/10', time: '—', mem: '—', at: '10-23 16:05', first: null },
    { id: 'S-20341', pid: 1017, title: '二叉树层序遍历', lang: 'C++ 17', status: 'ac', score: 100,
      pass: '10/10', time: '7ms', mem: '3408KB', at: '10-23 15:33', first: null },
    { id: 'S-20318', pid: 1045, title: '区间合并', lang: 'C++ 17', status: 'wa', score: 30,
      pass: '3/10', time: '11ms', mem: '3711KB', at: '10-23 14:02', first: 4 }
  ];

  var ASSIGNMENTS = [
    { id: 'A-0412', title: '第五章 动态规划 · 课后作业', type: 'homework', typeName: '作业',
      pidCount: 6, done: 3, score: 82, full: 100, due: '10-26 23:59', left: '2 天',
      ai: '仅阶段 ①③ · 上限 3 次', status: 'doing' },
    { id: 'A-0415', title: '树与堆 · 课堂练习', type: 'practice', typeName: '练习',
      pidCount: 4, done: 1, score: 25, full: 100, due: '10-27 23:59', left: '3 天',
      ai: '全阶段开放 · 不限次', status: 'doing' },
    { id: 'A-0419', title: '字符串算法 · 课后作业', type: 'homework', typeName: '作业',
      pidCount: 5, done: 0, score: 0, full: 100, due: '10-29 23:59', left: '5 天',
      ai: '仅阶段 ①③ · 上限 3 次', status: 'todo' },
    { id: 'A-0408', title: '数据结构期中测验', type: 'exam', typeName: '考试',
      pidCount: 4, done: 4, score: 96, full: 100, due: '10-22 16:00', left: '已结束',
      ai: '全部关闭', status: 'done' },
    { id: 'A-0403', title: '图论专题 · 课堂练习', type: 'practice', typeName: '练习',
      pidCount: 5, done: 5, score: 100, full: 100, due: '10-20 23:59', left: '已结束',
      ai: '全阶段开放 · 不限次', status: 'done' }
  ];

  var STUDENTS = [
    { name: '林昭', sid: '20220101', done: 8, total: 10, score: 82, ai: 6, last: '10-24 23:41', risk: 'ok' },
    { name: '陈砚', sid: '20220102', done: 10, total: 10, score: 96, ai: 3, last: '10-24 22:15', risk: 'ok' },
    { name: '苏晚', sid: '20220103', done: 7, total: 10, score: 74, ai: 11, last: '10-24 21:50', risk: 'ai' },
    { name: '周叙', sid: '20220104', done: 4, total: 10, score: 42, ai: 1, last: '10-23 20:02', risk: 'low' },
    { name: '何知遥', sid: '20220105', done: 9, total: 10, score: 91, ai: 4, last: '10-24 20:33', risk: 'ok' },
    { name: '沈亦', sid: '20220106', done: 3, total: 10, score: 31, ai: 0, last: '10-22 19:11', risk: 'low' },
    { name: '顾南', sid: '20220107', done: 8, total: 10, score: 85, ai: 5, last: '10-24 19:47', risk: 'ok' },
    { name: '许清和', sid: '20220108', done: 6, total: 10, score: 63, ai: 8, last: '10-24 18:20', risk: 'ai' },
    { name: '温言', sid: '20220109', done: 10, total: 10, score: 98, ai: 2, last: '10-24 17:55', risk: 'ok' },
    { name: '裴照', sid: '20220110', done: 5, total: 10, score: 55, ai: 7, last: '10-24 16:40', risk: 'ai' }
  ];

  var MODELS = [
    { id: 'local-qwen', name: 'Qwen2.5-Coder 7B', vendor: 'Ollama · 本地', role: 'Router / Guardrail',
      base: 'http://127.0.0.1:11434', state: 'on', ttft: '0.42s', calls: 12840, tokens: '3.1M', cost: '¥0' },
    { id: 'cloud-ds', name: 'DeepSeek-V3', vendor: 'DeepSeek · 云端', role: 'Tutor 主通道',
      base: 'https://api.deepseek.com', state: 'on', ttft: '1.12s', calls: 5420, tokens: '8.6M', cost: '¥214.80' },
    { id: 'cloud-qwen', name: '通义千问 Max', vendor: '阿里云 · 云端', role: 'Tutor 备用通道',
      base: 'https://dashscope.aliyuncs.com', state: 'off', ttft: '1.48s', calls: 218, tokens: '0.4M', cost: '¥12.40' },
    { id: 'cloud-zhipu', name: 'GLM-4-Plus', vendor: '智谱 · 云端', role: 'Tutor 备用通道',
      base: 'https://open.bigmodel.cn', state: 'off', ttft: '1.35s', calls: 96, tokens: '0.2M', cost: '¥6.20' }
  ];

  var AI_CALLS = [
    { at: '23:41:08', who: '林昭', stage: '③ 排错诊断', model: 'DeepSeek-V3', route: 'cloud', ttft: 1180,
      tok: 1240, guard: 'pass', note: '取回 #3 测试点特征' },
    { at: '23:38:52', who: '陈砚', stage: '① 读题拆解', model: 'Qwen2.5-Coder', route: 'local', ttft: 396,
      tok: 640, guard: 'pass', note: '—' },
    { at: '23:36:19', who: '苏晚', stage: '② 编码引导', model: 'DeepSeek-V3', route: 'cloud', ttft: 1042,
      tok: 2180, guard: 'rewrite', note: '检出 7 行完整源码，已改写为苏格拉底式提问' },
    { at: '23:34:03', who: '周叙', stage: '③ 排错诊断', model: 'Qwen2.5-Coder', route: 'local', ttft: 421,
      tok: 880, guard: 'pass', note: '云端超时 8.1s，自动降级本地' },
    { at: '23:31:44', who: '何知遥', stage: '④ AC 复盘', model: 'DeepSeek-V3', route: 'cloud', ttft: 1266,
      tok: 1560, guard: 'pass', note: '推荐 2 道同标签题目' },
    { at: '23:29:10', who: '许清和', stage: '② 编码引导', model: 'DeepSeek-V3', route: 'cloud', ttft: 980,
      tok: 3120, guard: 'block', note: '泄漏率拦截：整段题解源码' },
    { at: '23:26:35', who: '温言', stage: '① 读题拆解', model: 'Qwen2.5-Coder', route: 'local', ttft: 388,
      tok: 520, guard: 'pass', note: '—' },
    { at: '23:24:12', who: '裴照', stage: '③ 排错诊断', model: 'Qwen2.5-Coder', route: 'local', ttft: 452,
      tok: 990, guard: 'pass', note: '—' },
    { at: '23:21:50', who: '顾南', stage: '④ AC 复盘', model: 'DeepSeek-V3', route: 'cloud', ttft: 1310,
      tok: 1420, guard: 'pass', note: '—' },
    { at: '23:19:27', who: '沈亦', stage: '① 读题拆解', model: 'Qwen2.5-Coder', route: 'local', ttft: 402,
      tok: 610, guard: 'pass', note: '—' }
  ];

  var SANDBOX = [
    { id: 'sbx-8841', lang: 'C++ 17', user: '林昭', state: 'run', cpu: 12, mem: 4096, age: '0.4s' },
    { id: 'sbx-8840', lang: 'Python 3', user: '苏晚', state: 'run', cpu: 88, mem: 38200, age: '1.1s' },
    { id: 'sbx-8839', lang: 'Java 17', user: '陈砚', state: 'idle', cpu: 0, mem: 0, age: '3.2s' },
    { id: 'sbx-8838', lang: 'C++ 17', user: '周叙', state: 'run', cpu: 96, mem: 51200, age: '2.4s' },
    { id: 'sbx-8837', lang: 'C++ 17', user: '何知遥', state: 'idle', cpu: 0, mem: 0, age: '5.0s' }
  ];

  var PLAGIARISM = [
    { id: 'P-0088', a: '林昭', b: '许清和', pid: 1024, title: '最大子段和', sim: 96.4, stage: 2,
      state: 'flagged', tokens: 214, level: 'high', verdict: 'pending' },
    { id: 'P-0087', a: '沈亦', b: '裴照', pid: 1058, title: '背包问题（01）', sim: 88.1, stage: 2,
      state: 'flagged', tokens: 176, level: 'high', verdict: 'pending' },
    { id: 'P-0086', a: '苏晚', b: '顾南', pid: 1017, title: '二叉树层序遍历', sim: 71.2, stage: 2,
      state: 'review', tokens: 92, level: 'mid', verdict: 'pending' },
    { id: 'P-0085', a: '周叙', b: '温言', pid: 1045, title: '区间合并', sim: 42.6, stage: 1,
      state: 'pass', tokens: 12, level: 'low', verdict: 'clean' },
    { id: 'P-0084', a: '陈砚', b: '何知遥', pid: 1001, title: '两数之和', sim: 33.8, stage: 1,
      state: 'pass', tokens: 5, level: 'low', verdict: 'clean' }
  ];

  /* ══════════════════════════════════════════════════════════════════
     2. 口径增量（搬迁表"必须同时做的口径修正"一列）
        —— 原字段全部保留，契约字段并排新增，页面两头都能读
     ────────────────────────────────────────────────────────────────── */

  var DAY = 86400000;
  /* 夹具里的 '10-24 23:41' 只有月日时分，没有年份。
     补年份时用「今年」而不是写死某一年——否则跨年后 fmtDateTime 会按
     跨年规则输出 '2025-10-24 23:41'，和改造前的 '10-24 23:41' 对不上。 */
  var THIS_YEAR = new Date().getFullYear();
  function stamp(m, d, h, mi) { return new Date(THIS_YEAR, m - 1, d, h, mi, 0).getTime(); }
  /* AI 调用日志只有时分秒，按"今天"补成时间戳 */
  var TODAY0 = (function () { var t = new Date(); t.setHours(0, 0, 0, 0); return t.getTime(); })();
  function clockTs(hms) {
    var p = String(hms).split(':');
    return TODAY0 + Number(p[0]) * 3600000 + Number(p[1]) * 60000 + Number(p[2] || 0) * 1000;
  }
  /* '8.6M' / '3100' / '0.2M' → 个数 */
  function tokenCount(s) {
    var t = String(s || '0').replace(/[, ]/g, '');
    if (/[Mm]$/.test(t)) return Math.round(parseFloat(t) * 1000000);
    if (/[Kk]$/.test(t)) return Math.round(parseFloat(t) * 1000);
    return Math.round(parseFloat(t) || 0);
  }
  /* '0.42s' → 420 (ms) */
  function ttftMs(s) { return Math.round(parseFloat(String(s || '0').replace('s', '')) * 1000) || 0; }
  /* '¥214.80' → 21480 (分) */
  function centsOf(s) {
    var n = parseFloat(String(s || '0').replace(/[¥,\s]/g, ''));
    return isNaN(n) ? 0 : Math.round(n * 100);
  }
  /* 'C++ 17' → cpp；'Python 3' → python；'Java 17' → java */
  function langKey(s) { return A.languageKey(s); }
  /* 老状态字符串 → 契约整型（0-9），见 03 §3.2 ① */
  var STATUS_NUM = { ac: 3, pac: 4, wa: 5, tle: 6, mle: 7, re: 8, ce: 9 };

  PROBLEMS.forEach(function (p) {
    p.difficulty = p.lvl;                                  // lvl 已是 1-5 级，直接进契约口径
    p.myStatus = p.status === 'none' ? null : (STATUS_NUM[p.status] === undefined ? null : STATUS_NUM[p.status]);
    p.myScore = p.status === 'none' ? null : p.score;
    p.acCount = p.ac;
    p.tryCount = p.tried;
    p.displayNo = '#' + p.id;
    p.timeLimitMs = p.difficulty >= 4 ? 2000 : 1000;
    p.memoryLimitMb = p.difficulty >= 4 ? 256 : 128;
  });

  SUBMISSIONS.forEach(function (s) {
    var mPass = /^(\d+)\/(\d+)$/.exec(String(s.pass || ''));
    s.displayNo = s.id;                                    // 单号由服务端下发，前端只透传
    s.problemId = s.pid;
    s.problemTitle = s.title;
    s.language = langKey(s.lang);
    s.oiScore = s.score;
    s.passCaseCount = mPass ? Number(mPass[1]) : 0;
    s.totalCaseCount = mPass ? Number(mPass[2]) : 0;
    s.timeUsedMs = /^\d+ms$/.test(String(s.time)) ? parseInt(s.time, 10) : null;
    s.memoryUsedKb = /^\d+KB$/.test(String(s.mem)) ? parseInt(s.mem, 10) : null;
    s.createdAt = stamp(Number(String(s.at).slice(0, 2)), Number(String(s.at).slice(3, 5)),
                        Number(String(s.at).slice(6, 8)), Number(String(s.at).slice(9, 11)));
    s.status = s.status;                                   /* 老字段：字符串（页面按字符串比对） */
    s.statusCode = A.statusCodeOf({ status: STATUS_NUM[s.status] });
    s.statusLabel = A.statusLabelOf({ status: STATUS_NUM[s.status] });
    s.firstFailedCaseId = s.first;
  });

  ASSIGNMENTS.forEach(function (a, i) {
    a.displayNo = a.id;
    a.modeType = { homework: 2, practice: 1, exam: 3 }[a.type] || 2;
    a.problemCount = a.pidCount;
    a.doneCount = a.done;
    a.myScore = a.score;
    a.fullScore = a.full;
    a.aiStrategyDesc = a.ai;
    /* 契约：dueAt 是 epoch ms。取值要对齐搬迁表里的 left 文案
       （'2 天'/'3 天'/'5 天'/'已结束'），所以落在「今天 00:00 + (N+1) 天 − 1 分钟」：
       这样 leftText 的 Math.floor(gap / 86400000) 恰好是 N，不会因为
       Date.now() 往前走了几毫秒就掉成 N-1（页面上就变成 '1 天' 了）。
       后两条落在过去，leftText 自然给 '已结束'。 */
    a.dueAt = i === 3 ? TODAY0 - 1 * DAY + 16 * 3600000                          /* 昨天 16:00 */
            : i === 4 ? TODAY0 - 3 * DAY + 23 * 3600000 + 59 * 60000              /* 前 3 天 23:59 */
            : TODAY0 + (i === 0 ? 3 : i === 1 ? 4 : 6) * DAY - 60000;             /* 明天/后天/大后天 23:59 */
    a.status = a.status;                                   /* 老字段：'doing'/'todo'/'done'（页面按字符串比对） */
    /* 契约口径另开一列：适配器 adaptAssignment 吃的是 1/2/3 整型 */
    a.statusNum = { todo: 1, doing: 2, done: 3 }[a.status] || 1;
  });

  STUDENTS.forEach(function (s) { s.lastAt = stamp(10, Number(String(s.last).slice(3, 5)), 0, 0); s.displayNo = s.sid; });

  MODELS.forEach(function (m) {
    m.displayNo = '';
    m.channelCode = m.id;
    m.channelName = m.name;
    m.roleDesc = m.role;
    m.baseUrl = m.base;
    m.healthStatus = m.state === 'on' ? 'ON' : 'OFF';      // 契约：大写枚举
    m.ttftMs = ttftMs(m.ttft);
    m.callCount = m.calls;
    m.totalTokens = tokenCount(m.tokens);
    m.costCents = centsOf(m.cost);
    m.timeoutMs = 8000;
    m.maxTokens = 2048;
  });

  AI_CALLS.forEach(function (c, i) {
    c.createdAt = clockTs(c.at);
    c.ttftMs = c.ttft;
    c.totalTokens = c.tok;
    c.guardResult = c.guard;
    c.operatorName = c.who;
    c.modelCode = c.model;
    /* PRD v2.0 微创修正 #3：a-models.html:512 与 a-audit.html:374 的 x-for
       :key 原本用 c.at（时间戳），同一分钟内多条调用会撞 key，Alpine 复用
       DOM 时会把上一行的 model/guard 渲染到新行上。契约里调用记录有唯一
       主键，故补 recordId，页面 :key 改用它。 */
    c.recordId = 90000 + i + 1;
  });

  SANDBOX.forEach(function (b) {
    /* state 是同一字段名、两套取值，无法并存：保留老值（a-audit.html 已有自己的映射），
       契约值另放 stateCode，两边都不丢。 */
    b.stateCode = b.state === 'run' ? 'RUNNING' : 'EXITED';
    b.memoryLimitKb = b.mem;
  });

  PLAGIARISM.forEach(function (r) {
    r.stageCode = r.stage === 2 ? 'STAGE2' : 'STAGE1';
    r.simPercent = r.sim;
  });

  /* 全站唯一数据源：shell.js 的 8 个 getter 都指到这里 */
  var F = global.OJ_FIXTURES = global.OJ_FIXTURES || {
    problems: PROBLEMS, submissions: SUBMISSIONS, assignments: ASSIGNMENTS,
    students: STUDENTS, models: MODELS, aiCalls: AI_CALLS,
    sandbox: SANDBOX, plagiarism: PLAGIARISM
  };

  /* ══════════════════════════════════════════════════════════════════
     3. 假单号：形状与服务端 display_no 一致，前端永不自拼
     ────────────────────────────────────────────────────────────────── */
  var seq = { submission: 20481, assignment: 412, plagiarism: 88, sandbox: 8841 };
  function pad(n, width) {
    var s = String(n);
    while (s.length < width) s = '0' + s;
    return s;
  }
  function nextNo(prefix, key, width) {
    seq[key] += 1;
    return prefix + '-' + pad(seq[key], width || 4);
  }

  /* ══════════════════════════════════════════════════════════════════
     4. 判题进度模拟：用真实时间推进，让 1s 轮询有东西可看
        每 260ms 点亮一个测试点，10 个点最慢 2.6s 判完。
     ────────────────────────────────────────────────────────────────── */
  var JOBS = {};
  function judgeStatus(id) {
    var j = JOBS[id] || (JOBS[id] = { startAt: Date.now(), pass: 8, total: 10 });
    var stage = Math.min(j.total, Math.floor((Date.now() - j.startAt) / 260));
    var finished = stage >= j.total;
    var i, n, cases = [];
    for (i = 1; i <= j.total; i++) {
      n = i;
      cases.push({
        caseId: n,
        status: n > stage ? 0 : (n === 3 ? 5 : 3),         // 3 号点固定 WA，其余 AC
        timeMs: 2 + (n % 5),
        memoryKb: 1200 + n * 130
      });
    }
    return {
      submissionId: Number(id),
      status: finished ? 5 : (stage < 2 ? 1 : 2),          // 0-9 整型，见 §3.2
      statusCode: finished ? 'wa' : 'pending',             // 给 OJ.statusTag
      statusLabel: finished ? 'WA' : '判题中',
      oiScore: finished ? 80 : null,
      passCaseCount: finished ? j.pass : stage,
      totalCaseCount: j.total,
      timeUsedMs: finished ? 12 : null,
      memoryUsedKb: finished ? 3240 : null,
      firstFailedCaseId: finished ? 3 : null,
      caseDetails: cases,
      /* 判题完成后前端据此解锁 AI 阶段③④——判题接口是唯一写入口 */
      aiStateUpdate: {
        canUnlockStage3: stage >= 1,
        canUnlockStage4: finished,
        guideBtnText: '继续引导'
      }
    };
  }

  /* ══════════════════════════════════════════════════════════════════
     5. 路由表：method + 正则 → 函数(ctx{query,body,m})
     ────────────────────────────────────────────────────────────────── */
  var ROUTES = [
    /* ── 认证 ── */
    ['POST', /^\/api\/auth\/login$/, function (c) {
      var account = String(c.body.account || c.body.username || '').trim();
      var isAdmin = /admin|^a[_-]|运维/i.test(account);
      var isTeacher = /teacher|^t[_-]|工号/i.test(account);
      var role = isAdmin ? 'ADMIN' : (isTeacher ? 'TEACHER' : 'STUDENT');
      var user = isAdmin
        ? { id: 1, username: account, realName: '运维管理员', name: '运维管理员', role: 'ADMIN', roleLabel: '管理员', displayNo: 'A-0001', className: '系统与模型运维', subtitle: '系统与模型运维', avatarText: '运' }
        : isTeacher
          ? { id: 2, username: account, realName: '周明远', name: '周明远', role: 'TEACHER', roleLabel: '教师', displayNo: 'T-0102', className: '数据结构 · 主讲教师', subtitle: '数据结构 · 主讲教师', avatarText: '周' }
          : { id: 1024, username: account, realName: '林昭', name: '林昭', role: 'STUDENT', roleLabel: '学生', displayNo: '20220101', className: '计算机2022级1班', subtitle: '计算机 2022 级 · 1 班', avatarText: '林' };
      return {
        accessToken: 'mock-' + role.toLowerCase() + '-' + Date.now().toString(36),
        refreshToken: 'mock-refresh-' + Date.now().toString(36),
        expiresIn: 86400,
        token: 'mock-' + role.toLowerCase() + '-' + Date.now().toString(36),   // 兼容旧调用
        user: user,
        role: role.toLowerCase(),
        name: user.realName,
        displayNo: user.displayNo,
        homePath: { STUDENT: 's-problems.html', TEACHER: 't-dashboard.html', ADMIN: 'a-models.html' }[role]
      };
    }],
    ['POST', /^\/api\/auth\/logout$/, function () { return { loggedOut: true }; }],
    ['GET', /^\/api\/auth\/me$/, function () {
      var u = (global.OJ_FIXTURES && global.OJ_FIXTURES.students[0]) || {};
      return { id: 1024, username: u.sid || '20220101', realName: u.name || '林昭', role: 'STUDENT',
               roleLabel: '学生', classId: 1, className: '计算机2022级1班', subtitle: '计算机2022级1班',
               weakTags: ['图论', '数学'], aiQuotaDefault: 3, lastLoginAt: Date.now() - 3600000 };
    }],

    /* ── 题库 ── */
    ['GET', /^\/api\/problem\/questions$/, function (c) {
      var list = F.problems.map(A.adaptProblem);
      var kw = String(c.query.kw || '').toLowerCase();
      if (kw) list = list.filter(function (p) { return String(p.title).toLowerCase().indexOf(kw) !== -1; });
      return A.paginate(list, c.query);
    }],
    ['GET', /^\/api\/problem\/questions\/(\d+)$/, function (c) {
      var hit = F.problems.filter(function (p) { return p.id === Number(c.m[1]); })[0] || F.problems[0];
      return A.adaptProblem(hit);
    }],
    ['GET', /^\/api\/problem\/questions\/(\d+)\/test-cases$/, function () {
      return judgeStatus(1).caseDetails;
    }],

    /* ── 判题 ── */
    ['POST', /^\/api\/judge\/submit$/, function () {
      var id = Number(String(Date.now()).slice(-9));
      JOBS[id] = { startAt: Date.now(), pass: 8, total: 10 };
      return { submissionId: id, status: 0, isOverdue: 0 };
    }],
    ['GET', /^\/api\/judge\/status\/(\d+)$/, function (c) { return judgeStatus(c.m[1]); }],
    ['GET', /^\/api\/judge\/submissions$/, function (c) {
      var list = F.submissions.map(A.adaptSubmission);
      var kw = String(c.query.kw || '').toLowerCase();
      if (kw) list = list.filter(function (s) { return String(s.title).toLowerCase().indexOf(kw) !== -1; });
      return A.paginate(list, c.query);
    }],

    /* ── 教学 ── */
    ['GET', /^\/api\/teaching\/assignments$/, function (c) {
      return A.paginate(F.assignments.map(A.adaptAssignment), c.query);
    }],
    ['GET', /^\/api\/teaching\/students$/, function () {
      return { list: F.students.slice(), total: F.students.length };
    }],
    ['POST', /^\/api\/teaching\/exam\/tab-switch$/, function () {
      var n = (global.__tabCount = (global.__tabCount || 0) + 1);
      return {
        tabSwitchCount: n, limit: 3, redFlagTriggered: n >= 3,
        alertMessage: n >= 3 ? '已记录异常：切屏 ' + n + ' 次，超过 3 次将标记为违纪' : ''
      };
    }],

    /* ── 管理 ── */
    ['GET', /^\/api\/admin\/models$/, function () { return F.models.map(A.adaptModel); }],
    ['GET', /^\/api\/admin\/sandbox$/, function () { return F.sandbox.slice(); }],
    ['GET', /^\/api\/admin\/ai-calls$/, function (c) {
      return A.paginate(F.aiCalls.slice(), c.query);
    }],
    ['GET', /^\/api\/admin\/plagiarism$/, function (c) {
      return A.paginate(F.plagiarism.slice(), c.query);
    }],

    /* ── AI 工作台 ── */
    ['GET', /^\/api\/ai\/workspace-init$/, function () {
      var used = (global.__aiUsed = global.__aiUsed || 0);
      return {
        aiEnabled: true,
        modeDesc: '练习模式：四阶段全开',
        remainingQuota: Math.max(0, 3 - used),
        maxQuota: 3,
        availableChannels: [
          { channelCode: 'local-qwen', channelName: 'Qwen2.5-Coder 7B', isDefault: true }
        ],
        stageCapsules: [
          { stage: 1, title: '读题拆解', unlocked: true },
          { stage: 2, title: '编码引导', unlocked: true },
          { stage: 3, title: '排错诊断', unlocked: true, nextHintLevel: 1 },
          { stage: 4, title: 'AC 复盘', unlocked: false, lockReason: 'AC 后解锁' }
        ],
        chatHistory: []
      };
    }],
    ['GET', /^\/api\/ai\/quota$/, function () {
      var used = (global.__aiUsed = global.__aiUsed || 0);
      return { remainingQuota: Math.max(0, 3 - used), maxQuota: 3 };
    }],
    ['POST', /^\/api\/ai\/chat\/stream$/, function (c) {
      global.__aiUsed = (global.__aiUsed || 0) + 1;
      var stage = Number(c.body.capsuleStage) || 2;
      return {
        frames: [
          ['router_step', { stage: stage, stageName: '编码引导', hintLevel: 1, levelDesc: '方向提示', routerModel: 'local-qwen' }],
          ['tool_calling', { toolName: 'similar_problems', summary: '检索到 2 道相似题' }],
          ['token', { delta: '先看第三组数据：' }],
          ['token', { delta: '你把 ans 初始化成 0，' }],
          ['token', { delta: '全负数时会输出 0。' }],
          ['done', { recordId: 90001, remainingQuota: Math.max(0, 3 - global.__aiUsed), actualModelUsed: 'local-qwen' }]
        ]
      };
    }]
  ];

  /* 把 "?a=1&b=2" 解成对象；页面常常把参数直接拼在 path 上
     （api.http.get('/api/problem/questions?kw=树')），所以这里必须认。 */
  function parseQuery(qs) {
    var out = {};
    if (!qs) return out;
    String(qs).replace(/^\?/, '').split('&').forEach(function (kv) {
      if (!kv) return;
      var i = kv.indexOf('=');
      var k = i === -1 ? kv : kv.slice(0, i);
      var v = i === -1 ? '' : kv.slice(i + 1);
      try { out[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' ')); }
      catch (e) { out[k] = v; }
    });
    return out;
  }

  function request(method, path, opts) {
    opts = opts || {};
    var raw = String(path);
    var qi = raw.indexOf('?');
    /* opts.query 优先于 path 上的同名参数：显式传的才是调用方本意 */
    var c = { query: parseQuery(qi === -1 ? '' : raw.slice(qi + 1)), body: opts.body || {}, m: null };
    var k2 = opts.query || {};
    for (var kk in k2) { if (Object.prototype.hasOwnProperty.call(k2, kk)) c.query[kk] = k2[kk]; }
    var clean = qi === -1 ? raw : raw.slice(0, qi);
    for (var i = 0; i < ROUTES.length; i++) {
      if (ROUTES[i][0] !== method) continue;
      var m = clean.match(ROUTES[i][1]);
      if (m) {
        c.m = m;
        try { return Promise.resolve(ROUTES[i][2](c)); }
        catch (e) {
          return Promise.reject(global.api.http.ApiError(500, 'MOCK 路由内部错误：' + (e && e.message), null));
        }
      }
    }
    return Promise.reject(global.api.http.ApiError(404, 'MOCK 未实现：' + method + ' ' + clean));
  }

  /* ══════════════════════════════════════════════════════════════════
     4. MOCK AI 回复（Stage 0 兜底，阶段 2 接真实 SSE 后删除）
     ──────────────────────────────────────────────────────────────────
     03 指南 §5.2 要求删掉 s-workbench.js 的 REPLIES{1..9}——那九条是
     「与输入完全无关」的固定文本，两问一答，评审当场能证伪。
     但 Stage 0 还没接 SSE，完全不回话会让断网演示断在半空。
     折中：按问题关键字 + 当前阶段挑一段，且**每段都带行号或反例**，
     形态贴近真实助教回复；真实 SSE 上线后本函数整体删除即可。
     ────────────────────────────────────────────────────────────────── */
  var AI_KB = [
    { k: ['tle', '超时', '时间'], a: '先估算量级：n 到 10⁵ 时平方级是 10¹⁰ 次运算，一定超时。' +
        '你代码第 9 行的循环里如果再套一层扫描，就是 O(n²)。考虑「以 i 结尾」的单遍转移。' },
    { k: ['wa', '错', '不对', '为什么'], a: '看你代码第 8 行 `int dp = 0, ans = 0;`。' +
        '当输入全是负数（例如 `[-2, -1]`）时，dp 每轮都是负的，而 ans 停在 0 取不到它——先想清楚 ans 的初值该是什么。' },
    { k: ['边界', '全负', '负数', '特殊情况'], a: '把边界列出来手算：全负、只有一个元素、n 取到 10⁵、答案刚好跨过整个数组。' +
        '这四类各自代进你第 12 行的转移式，看哪一类会让 ans 停在 0。' },
    { k: ['思路', '怎么做', '算法', 'dp', '动态规划'], a: '先回答一个问题：如果只允许你扫一遍数组，' +
        '「以第 i 个元素结尾的最大子段和」需要知道前面哪些信息？把它压成一个数之后，转移就只剩两种选择。' },
    { k: ['复杂度', '优化', '空间'], a: 'dp 只依赖上一轮的值，所以不需要数组——两个变量滚动更新就够，空间从 O(n) 降到 O(1)。' +
        '时间下界是 O(n)，至少要把 10⁵ 个数读进来一遍。' },
    { k: ['题解', '答案', '代码', '给我', '直接'], a: '完整题解我不能给——这正是这道题要考的东西。' +
        '但我可以陪你走完：先把第 8 行的初值问题想通，再回来讨论第 12 行的转移。' }
  ];

  function mockAiReply(q, ctx) {
    var s = String(q || '').toLowerCase();
    for (var i = 0; i < AI_KB.length; i++) {
      var item = AI_KB[i];
      for (var j = 0; j < item.k.length; j++) {
        if (s.indexOf(item.k[j].toLowerCase()) !== -1) return item.a;
      }
    }
    /* 没命中关键字：按当前阶段给一句方向性反问，仍与「当前状态」相关，
       不是固定文本。ctx 为 s-workbench 的 Alpine 组件实例。 */
    var stage = (ctx && ctx.stage) || 3;
    var fixed = !!(ctx && ctx.fixed);
    if (stage === 4) {
      return '你已经拿到满分了，这题可以往复杂度上再压一层：现在的空间是 O(1) 吗？' +
             '如果要你同时求「最大子段积」，需要多维护哪个量？';
    }
    if (fixed) {
      return '改完第 8 行的初值后，先自己造三组数据验证：全负、单元素、答案跨过整个数组。' +
             '都过了再提交，比直接提交更能看清自己是否真的想通了。';
    }
    return '先说说你的判断：这段代码在第 3 号测试点（全负输入）上会发生什么？' +
           '把你的预测和实际输出对一下，差异就是下一步该查的地方。';
  }

  global.OJMock = { request: request, JOBS: JOBS, fixtures: F, seq: seq, nextNo: nextNo };
  global.api = global.api || {};
  global.api.mockAiReply = mockAiReply;
})(window);
