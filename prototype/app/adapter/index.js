/* adapter/index.js —— 收口：把七个小子文件聚合成一个对象
   ─────────────────────────────────────────────────────────────────────
   必须排在 7 个子文件之后加载；子文件各自挂 window.adapter.X。
   mock.js 与 16 页都只认 window.adapter，不直接认子命名空间。

   三约定（03 指南 §3.2 末）：
     ① 每个 adaptX 只做字段改名与单位换算，不做筛选、不做排序、不发请求；
     ② 输出对象的键名必须与改造前的常量字段名一字不差
        （lang/pass/time/mem/at/first/left/due/ai/pidCount/done/score/full/
          lvl/tags/ac/tried 全部保留），这样模板与 getter 零改动；
     ③ 缺值一律给 null 或 '—'，绝不给 undefined——undefined 在 Alpine 的
        x-text 里会渲染出空字符串，看起来像加载失败。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  var A = global.adapter;
  if (!A.status || !A.time) { console.error('[adapter] 子文件加载顺序不对'); return; }

  /* ── 扁平日志：模板与页面只调这些名字，不直接碰子命名空间 ── */
  A.statusCodeOf = A.status.statusCodeOf;
  A.statusLabelOf = A.status.statusLabelOf;
  A.isFinal       = A.status.isFinal;
  A.lvlName       = A.difficulty.lvlName;
  A.lvl           = A.difficulty.lvl;
  A.languageLabel = A.language.languageLabel;
  A.languageKey   = A.language.languageKey;
  A.timeLimitFor  = A.language.timeLimitFor;
  A.fmtDateTime   = A.time.fmtDateTime;
  A.leftText      = A.time.leftText;
  A.fmtDuration   = A.time.fmtDuration;
  A.kbText        = A.unit.kbText;
  A.mbText        = A.unit.mbText;
  A.bytesToKb     = A.unit.bytesToKb;
  A.centsText     = A.unit.centsText;
  A.pctText       = A.unit.pctText;
  A.ttftText      = A.unit.ttftText;
  A.displayNo     = A.id.displayNo;
  A.problemNo     = A.id.problemNo;
  A.paginate      = A.page.paginate;
  A.pagerOf       = A.page.pagerOf;

  /* ── 行级适配器：一个实体一次换算，页面不再看后端字段名 ── */

  /* 题目：p.myStatus / p.myScore 为 null 表示"未提交"，不是 status 0 */
  A.adaptProblem = function (p) {
    var none = p.myStatus === null || p.myStatus === undefined;
    return {
      id: p.id, no: A.displayNo(p) || A.problemNo(p), title: p.title,
      difficulty: p.difficulty, lvlName: A.lvlName(p.difficulty), lvl: A.lvl(p.difficulty),
      tags: p.tags || [],
      status: none ? 'none' : A.statusCodeOf({ status: p.myStatus }),
      statusCode: none ? 'none' : A.statusCodeOf({ status: p.myStatus }),
      score: p.myScore === null || p.myScore === undefined ? null : p.myScore,
      ac: p.acCount, tried: p.tryCount, isFeatured: !!p.isFeatured
    };
  };

  A.adaptSubmission = function (s) {
    return {
      id: s.id, no: A.displayNo(s), pid: s.problemId, title: s.problemTitle,
      lang: A.languageLabel(s.language),
      status: A.statusCodeOf(s), statusCode: A.statusCodeOf(s), statusLabel: A.statusLabelOf(s),
      score: s.oiScore,
      pass: (s.passCaseCount === null || s.passCaseCount === undefined ? 0 : s.passCaseCount) + '/' +
            (s.totalCaseCount === null || s.totalCaseCount === undefined ? 0 : s.totalCaseCount),
      time: A.fmtDuration(s.timeUsedMs), mem: A.kbText(s.memoryUsedKb),
      at: A.fmtDateTime(s.createdAt), createdAt: s.createdAt,
      first: s.firstFailedCaseId, cases: (s.caseDetails || []).map(A.adaptCase)
    };
  };

  A.adaptCase = function (c) {
    return { caseId: c.caseId, status: A.statusCodeOf(c), statusCode: A.statusCodeOf(c),
             timeMs: c.timeMs, memKb: c.memoryKb !== undefined ? c.memoryKb : c.memKb,
             input: c.input, expected: c.expected, actual: c.actual };
  };

  A.adaptAssignment = function (a) {
    /* 后端给 0-3 整型；mock 夹具沿用了老字符串 'todo'/'doing'/'done'，
       两种都收，避免 MOCK 与真实走两条分支。 */
    var stName = typeof a.status === 'number'
      ? (({ 0: 'draft', 1: 'todo', 2: 'doing', 3: 'done' })[a.status] || 'todo')
      : (a.status || 'todo');
    return {
      id: a.id, no: A.displayNo(a), title: a.title,
      type: a.modeType, typeName: ({ 1: '练习', 2: '作业', 3: '考试' })[a.modeType] || '作业',
      status: stName,
      pidCount: a.problemCount, done: a.doneCount, score: a.myScore, full: a.fullScore,
      dueAt: a.dueAt, due: A.fmtDateTime(a.dueAt), left: A.leftText(a.dueAt),
      ai: a.aiStrategyDesc
    };
  };

  A.adaptModel = function (m) {
    return {
      id: m.id, code: m.channelCode, name: m.channelName, vendor: m.vendor, role: m.roleDesc,
      base: m.baseUrl, state: String(m.healthStatus || 'OFF').toLowerCase(),
      ttft: m.ttftMs === null || m.ttftMs === undefined ? '—' : A.unit.ttftText(m.ttftMs),
      calls: m.callCount, tokens: OJ.fmt(m.totalTokens || 0), cost: A.centsText(m.costCents)
    };
  };
})(window);
