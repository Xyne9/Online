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
  A.lvl           = A.difficulty.lvlName;   /* 只给名字；刻度 HTML 用 A.lvlHtml */
  A.lvlHtml       = A.difficulty.lvlHtml;
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

  /* ⚠️ 全部适配器都**不得依赖 window.OJ**。
     s-workbench.html 不引 shell.js，那页没有 OJ（详见 difficulty.js 头注释）。
     下面用自带的 fmtNum 替代 OJ.fmt，口径与 shell.js 逐字一致。 */
  function fmtNum(n) {
    n = Number(n) || 0;
    if (n >= 1e9) return (n / 1e9).toFixed(1).replace(/\.0$/, '') + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
    return String(n);
  }
  A.fmtNum = fmtNum;

  /* 题目：p.myStatus / p.myScore 为 null 表示"未提交"，不是 status 0。
     lvl 必须是**整数 1-5**——模板里是 OJ.lvl(p.lvl) 现画刻度；
     原实现把渲染好的 HTML 串塞进 lvl，模板会再包一层，5 格全灭。

     Day 4 真连后字段名以契约为准（02 §3.2）：
       acRate / triedCount / myStatus / myScore / difficulty
     而 mock 夹具用的是短名 ac / tried / status / score。
     两者都要认——mock 与真实走同一个 adapter 是 Stage 0 的核心承诺，
     分叉成两个函数就会「演示好看、真实炸」。
     myStatus 可能是：
       null（未提交）｜ 数字 0-9（旧口径）｜ 字符串 'none'/'ac'/'fail'/'todo'（契约口径） */
  A.adaptProblem = function (p) {
    var none = p.myStatus === null || p.myStatus === undefined ||
               p.myStatus === 'none' || p.myStatus === 'todo';
    var code;
    if (typeof p.myStatus === 'number') {
      code = A.statusCodeOf({ status: p.myStatus });
    } else if (typeof p.myStatus === 'string' && p.myStatus) {
      /* 契约给的是 'ac'/'fail' 这类语义串，不是 int。'fail' 不是 CSS class，
         统一收敛成 'wa'（判分链/标签只需要一个「未通过」的视觉态）。 */
      code = p.myStatus === 'fail' ? 'wa' : p.myStatus.toLowerCase();
    } else {
      code = A.statusCodeOf(p);
    }
    var score = p.myScore !== undefined ? p.myScore : p.score;
    return {
      id: p.id, no: A.displayNo(p) || A.problemNo(p), title: p.title,
      difficulty: p.difficulty, lvlName: A.lvlName(p.difficulty),
      lvl: A.difficulty.clamp(p.difficulty),
      lvlHtml: A.lvlHtml(p.difficulty),
      tags: p.tags || [],
      status: none ? 'none' : code,
      statusCode: none ? 'none' : code,
      score: (score === null || score === undefined) ? null : score,
      /* acRate 是 0-100 两位小数（契约），夹具的 ac 是整数百分比；模板只做展示与排序 */
      ac: p.acRate !== undefined ? Math.round(p.acRate) : (p.ac !== undefined ? p.ac : 0),
      tried: p.triedCount !== undefined ? p.triedCount : (p.tried !== undefined ? p.tried : 0),
      isFeatured: !!p.isFeatured,
      /* 详情抽屉与工作台跳转要用的额外字段（模板不用则不显示，无害） */
      timeLimitMs: p.timeLimitMs, memoryLimitMb: p.memoryLimitMb,
      testCaseCount: p.testCaseCount, publicCaseCount: p.publicCaseCount,
      state: p.state
    };
  };

  /* 提交记录：字段名以契约 02 §4.2 为准（problemTitle / oiScore / passCaseCount /
     timeUsedMs / memoryUsedKb / submittedAt），mock 夹具用短名（title / score /
     pass / time / mem / at）。两者都认。

     displayNo 契约给的是 "S-20481"（字符串），mock 也是字符串；但适配器统一走
     A.displayNo 兜底，避免后端某天只给数字 id 时页面显示空白。 */
  A.adaptSubmission = function (s) {
    var passN = s.passCaseCount !== undefined ? s.passCaseCount : s.pass;
    var totalN = s.totalCaseCount !== undefined ? s.totalCaseCount : s.total;
    /* mock 的 pass 是 "8/10" 字符串，真实是两个整数；统一成 "8/10" 展示串 */
    var passText;
    if (typeof passN === 'string' && passN.indexOf('/') !== -1) {
      passText = passN;
    } else {
      passText = (passN === null || passN === undefined ? 0 : passN) + '/' +
                 (totalN === null || totalN === undefined ? 0 : totalN);
    }
    var atMs = s.submittedAt !== undefined ? s.submittedAt : s.createdAt;
    return {
      id: s.id, no: A.displayNo(s), pid: s.problemId !== undefined ? s.problemId : s.pid,
      title: s.problemTitle !== undefined ? s.problemTitle : s.title,
      lang: A.languageLabel(s.language || s.lang),
      status: A.statusCodeOf(s), statusCode: A.statusCodeOf(s), statusLabel: A.statusLabelOf(s),
      score: s.oiScore !== undefined ? s.oiScore : s.score,
      pass: passText,
      time: A.fmtDuration(s.timeUsedMs !== undefined ? s.timeUsedMs : s.time),
      mem: A.kbText(s.memoryUsedKb !== undefined ? s.memoryUsedKb : s.mem),
      at: atMs !== undefined && typeof atMs === 'number' ? A.fmtDateTime(atMs) : (s.at || '—'),
      createdAt: atMs,
      first: s.firstFailedCaseSeq !== undefined ? s.firstFailedCaseSeq : s.first,
      cases: (s.caseDetails || []).map(A.adaptCase)
    };
  };

  A.adaptCase = function (c) {
    return { caseId: c.caseId, seqNo: c.seqNo, kind: c.kind,
             status: A.statusCodeOf(c), statusCode: A.statusCodeOf(c),
             statusLabel: A.statusLabelOf(c),
             score: c.score, gotScore: c.gotScore,
             timeMs: c.timeMs, memKb: c.memKb !== undefined ? c.memKb : c.memoryKb,
             input: c.input, expected: c.expected, actual: c.actual,
             /* 详情抽屉用（02 §4.3）：三个预览字段，隐藏点的 expected 为 null */
             inputPreview: c.inputPreview, expectedPreview: c.expectedPreview,
             actualPreview: c.actualPreview };
  };

  A.adaptAssignment = function (a) {
    /* 后端给 0-3 整型；mock 夹具沿用了老字符串 'todo'/'doing'/'done'，
       两种都收，避免 MOCK 与真实走两条分支。 */
    var stName = typeof a.status === 'number'
      ? (({ 0: 'draft', 1: 'todo', 2: 'doing', 3: 'done' })[a.status] || 'todo')
      : (a.status || 'todo');
    /* type 必须保持改造前的字符串（'practice'/'homework'/'exam'），
       因为 s-assignments.html 的 typeCls(t) 是按字符串查 class 的；
       原来这里给的是 modeType 数字，typeCls 返回空串，作业类型标签丢色。
       数字另放 modeType 供需要的地方用。 */
    var TYPE_STR = { 1: 'practice', 2: 'homework', 3: 'exam' };
    var TYPE_NAME = { 1: '练习', 2: '作业', 3: '考试' };
    return {
      id: a.id, no: A.displayNo(a), title: a.title,
      type: TYPE_STR[a.modeType] || (typeof a.type === 'string' ? a.type : 'homework'),
      modeType: a.modeType,
      typeName: TYPE_NAME[a.modeType] || '作业',
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
      calls: m.callCount, tokens: fmtNum(m.totalTokens || 0), cost: A.centsText(m.costCents)
    };
  };
})(window);
