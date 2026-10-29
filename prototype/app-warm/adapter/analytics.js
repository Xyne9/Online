/* adapter/analytics.js —— 学情字段映射（02 契约 §6.5 + §6.6）
   ─────────────────────────────────────────────────────────────────────
   三约定（adapter/index.js 文件头）：① 只改名与换算，不筛选/不排序/不发请求；
   ② 输出键名与改造前页面的常量一字不差；③ 缺值给 null 或 '—'，绝不给 undefined。

   ⚠️ masteryPercent 越高越强（掌握度），sys_user.weak_tags 越高越弱（薄弱名单）。
      两者语义反向，本文件只认 masteryPercent，不借用 weakTags（02 §6.6 末）。
   ───────────────────────────────────────────────────────────────────── */
(function (g) {
  'use strict';
  var A = g.adapter;

  function def(v, d) { return v === null || v === undefined || v === '' ? d : v; }
  function num(v) {
    if (v === null || v === undefined || v === '') return null;
    var n = Number(v);
    return isNaN(n) ? null : n;
  }
  /* 展示用日期：'2026-10-18' / '10-18' / epoch ms → '10-18' */
  function md(v) {
    if (v === null || v === undefined || v === '') return '—';
    if (typeof v === 'number') return A.fmtDateTime(v).slice(0, 5);
    var s = String(v);
    var m = /(\d{2})-(\d{2})/.exec(s.slice(-5)) || /(\d{4})-(\d{2})-(\d{2})/.exec(s);
    if (!m) return s.length > 5 ? s.slice(0, 5) : s;
    return m.length === 3 && m[0].length === 10 ? m[2] + '-' + m[3] : m[1] + '-' + m[2];
  }
  var WEEKDAY = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  function weekdayOf(v, ref) {
    if (v) return String(v);
    if (ref === null || ref === undefined) return '—';
    var d = typeof ref === 'number' ? new Date(ref) : new Date(String(ref).replace(/-/g, '/'));
    return isNaN(d.getTime()) ? '—' : WEEKDAY[d.getDay()];
  }

  var AI_STAGE_NO = { 1: '①', 2: '②', 3: '③', 4: '④' };
  var AI_STAGE_NAME = { 1: '读题拆解', 2: '编码引导', 3: '排错诊断', 4: 'AC 复盘' };

  /* ── 周日历趋势（#53 week-trend）─────────────────────────────────
     原型 bars 需要 {date,label,ac,wa,total,hAc,hWa}，其中 hAc/hWa 是柱高像素。
     这里只负责「接口天 → 柱」，柱高换算交给页面（要按当页 max 归一）。 */
  A.anTrendDay = function (d) {
    d = d || {};
    var sub = num(d.submitCount), ac = num(d.acCount);
    sub = sub === null ? 0 : sub;
    ac = ac === null ? 0 : ac;
    var date = md(d.date);
    return {
      date: date,
      label: date.length > 2 ? date.slice(3) : date,
      week: weekdayOf(d.weekday, d.date),
      sub: sub, ac: ac, wa: Math.max(0, sub - ac), total: sub,
      passRate: num(d.passRate)
    };
  };

  /* ── 知识点掌握度（#57 knowledge）───────────────────────────────
     pct 是「我的掌握度」，cls 是「班级掌握度」。契约里 #57 只给班级口径，
     学生自己的值来自 #62 的 weakKps —— 页面把两者拼起来再交给这里。 */
  A.anKp = function (kp, mine) {
    kp = kp || {};
    var cls = num(kp.masteryPercent);
    var pct = num(mine);
    var level = kp.level || (pct === null ? '' : pct >= 70 ? 'strong' : pct >= 50 ? 'normal' : 'weak');
    var recs = (kp.recommendProblems || []).map(function (p) {
      return {
        id: def(p.problemId, null), title: def(p.title, '—'), lvl: def(p.difficulty, 1),
        tag: def(kp.kpName, '—'),
        why: level === 'weak' ? '重点补强' : level === 'normal' ? '巩固练习' : '保持手感'
      };
    });
    return {
      k: String(def(kp.kpId, def(kp.kpName, ''))),
      name: def(kp.kpName, '—'),
      pct: pct, cls: cls, level: level,
      n: num(kp.triedCount), done: num(kp.acCount), subs: num(kp.triedCount),
      ac: num(kp.acCount), tried: num(kp.triedCount),
      delta: 0,                                  /* 契约未给环比，0 = 不画涨跌箭头，不臆造 */
      symptom: def(kp.symptom, '暂无归因数据'),
      cause: def(kp.cause, '—'),
      advice: def(kp.advice, '—'),
      note: pct === null
        ? '该知识点的个人掌握度暂无统计，先按班级掌握度决定是否补强。'
        : ('个人掌握度 ' + pct + '%，' + (level === 'weak' ? '低于达标线 70%，建议优先补强。'
          : level === 'normal' ? '接近达标线，做两道巩固题即可。' : '已达标，保持手感。')),
      recs: recs,
      pids: recs.map(function (r) { return r.id; }).filter(function (x) { return x !== null; }),
      hint: def(kp.symptom, '暂无归因数据'),
      sub: recs.length ? recs.length + ' 道推荐' : '暂无推荐题目'
    };
  };

  /* ── 逐生学情（#58 students / #62 student）───────────────────────
     页面排序/筛选一律用 sid（字符串），数字主键另放 userId（3.2.4）。 */
  A.anRecentSub = function (r) {
    r = r || {};
    return {
      id: def(r.submissionId, null),
      idStr: def(r.displayNo, r.submissionId === null || r.submissionId === undefined ? '—' : 'S-' + r.submissionId),
      pid: def(r.problemId, null),
      title: def(r.problemTitle, '—'),
      status: A.statusCodeOf(r),
      score: num(r.score),
      pass: num(r.passCaseCount),
      at: A.fmtDateTime(r.at !== undefined ? r.at : r.submittedAt)
    };
  };

  A.anStudent = function (s) {
    s = s || {};
    var tags = (s.knowledgeTags || []).map(function (t) {
      return { name: def(t.name, '—'), pct: num(t.percent) };
    });
    var solved = num(s.solved), total = num(s.total);
    return {
      userId: def(s.userId, null),
      name: def(s.name, '—'),
      sid: def(s.stuNo, '—'),
      solved: solved === null ? 0 : solved,
      total: total === null ? 0 : total,
      score: num(s.score),
      ai: num(s.aiUsed) === null ? 0 : num(s.aiUsed),
      rate: num(s.acRate),
      spark: (s.spark || []).map(function (v) { var n = num(v); return n === null ? 0 : Math.round(n); }),
      risk: def(s.riskLevel, 'ok'),
      reason: def(s.riskReason, '进度、得分与 AI 使用都在班级合理区间'),
      suggestion: def(s.suggestion, null),
      tags: tags,
      recent: (s.recent || []).map(A.anRecentSub),
      no: def(s.displayNo, null),
      rank: num(s.rank)
    };
  };

  A.anRisk = function (r) {
    r = r || {};
    return {
      userId: def(r.userId, null),
      name: def(r.name, '—'),
      sid: def(r.stuNo, '—'),
      risk: def(r.riskLevel, 'low'),
      reason: def(r.riskReason, '—'),
      score: num(r.score),
      ai: num(r.aiUsed) === null ? 0 : num(r.aiUsed),
      lastAt: A.fmtDateTime(r.lastActiveAt),
      suggestion: def(r.suggestion, null),
      solved: 0, total: 0, rate: null, spark: [], tags: [], recent: []
    };
  };

  /* ── 热点测试点（#60 heatmap）──────────────────────────────────
     status 契约取 ac/wa/none，映射到原型三色格：ac / wa / 空（多数没跑到）。 */
  A.anHeatRow = function (r) {
    r = r || {};
    var cells = (r.cells || []).map(function (c) {
      var st = String(c.status || '').toLowerCase();
      return st === 'ac' ? 'ac' : st === 'wa' ? 'wa' : '';
    });
    var rates = (r.cells || []).map(function (c) { return num(c.passRate); })
      .filter(function (v) { return v !== null; });
    var pass = rates.length ? Math.round(rates.reduce(function (a, b) { return a + b; }, 0) / rates.length) : null;
    return {
      pid: def(r.problemId, null),
      title: def(r.title, '—'),
      pass: pass,
      cells: cells,
      okN: cells.filter(function (c) { return c === 'ac'; }).length
    };
  };

  /* ── AI 用量（#61 ai-usage）────────────────────────────────────
     stageDist 同时喂 s-analytics 的四阶段小卡与 t-dashboard 的四阶段条。 */
  A.anAiStage = function (s) {
    s = s || {};
    var no = num(s.stage);
    return {
      k: 's' + def(no, def(s.stageName, 'x')),
      no: AI_STAGE_NO[no] || '',
      label: def(s.stageName, (AI_STAGE_NO[no] || '') + ' ' + (AI_STAGE_NAME[no] || '—')),
      n: num(s.count) === null ? 0 : num(s.count),
      percent: num(s.percent)
    };
  };

  /* ── 待办（#52 todos）──────────────────────────────────────────
     severity(info/warn/danger) 决定红点；act 决定行内动作形态：
       review = 跳页复核（模板里的 <a>）
       other  = 按钮（真实态点它就是跳 href，不做本地假发布） */
  var TODO_ICON = { publish: 'task', plagiarism: 'scan', plag: 'scan', review: 'scan',
                    channel: 'plug', model: 'plug', urge: 'send', submit: 'send' };
  A.anTodo = function (t) {
    t = t || {};
    var key = def(t.key, '');
    var act = /plag|review/i.test(key) ? 'review' : 'link';
    return {
      k: key || def(t.label, 'todo'),
      label: def(t.label, '—'),
      sub: def(t.subtitle, ''),
      count: num(t.count) === null ? 0 : num(t.count),
      icon: TODO_ICON[key] || 'task',
      href: def(t.href, 't-dashboard.html'),
      act: act,
      actTitle: def(t.actionText, '去处理'),
      warn: !!(t.warn || t.severity === 'warn' || t.severity === 'danger'),
      severity: def(t.severity, 'info'),
      busy: false, struck: false, gone: false
    };
  };

  /* ── 最易错题（#54 hard-problems）──────────────────────────────
     原型字段：{pid,title,lvl,tags,tried,ac,pass,avg,first,students}
     契约没有「平均尝试次数」与「通过人数」，前者给 '—'（不臆造），
     后者按 triedCount × acRate 换算（同一份数据的派生，不是新数据）。 */
  A.anHardProblem = function (p) {
    p = p || {};
    var tried = num(p.triedCount), rate = num(p.acRate);
    return {
      pid: def(p.problemId, null),
      idStr: def(p.displayNo, p.problemId === null || p.problemId === undefined ? '—' : '#' + p.problemId),
      title: def(p.title, '—'),
      lvl: def(p.difficulty, 1),
      tags: [],
      tried: tried === null ? 0 : tried,
      ac: rate === null ? null : Math.round(rate),
      pass: tried === null || rate === null ? null : Math.round(tried * rate / 100),
      avg: '—',
      avgScore: num(p.avgScore),
      first: p.firstFailSeq === null || p.firstFailSeq === undefined ? '—' : '第 ' + p.firstFailSeq + ' 个测试点',
      students: (p.topStudents || []).map(function (s) {
        return {
          name: def(s.name, '—'), sid: def(s.stuNo, '—'),
          n: num(s.failCount) === null ? 0 : num(s.failCount),
          at: A.fmtDateTime(s.lastAt)
        };
      })
    };
  };

  /* ── 班级（#51 classes）────────────────────────────────────────── */
  A.anClass = function (c) {
    c = c || {};
    var id = def(c.classId, null);
    return {
      k: String(id),
      id: id,
      label: def(c.className, '—') + (num(c.studentCount) === null ? '' : '（' + c.studentCount + ' 人）'),
      className: def(c.className, '—'),
      teacherName: def(c.teacherName, '—'),
      studentCount: num(c.studentCount),
      activeCount: num(c.activeCount),
      avgScore: num(c.avgScore),
      acRate: num(c.acRate)
    };
  };

  /* ── 班级成绩分布（#56 dist）────────────────────────────────────
     契约字段是 partial，页面用 part —— 只在适配器里改名。 */
  A.anDist = function (d) {
    d = d || {};
    return {
      ac: num(d.ac) === null ? 0 : num(d.ac),
      part: num(d.partial) === null ? (num(d.part) === null ? 0 : num(d.part)) : num(d.partial),
      fail: num(d.fail) === null ? 0 : num(d.fail),
      max: num(d.max), min: num(d.min), median: num(d.median),
      sd: d.sd === null || d.sd === undefined ? '—' : String(d.sd)
    };
  };

  /* ── 进行中的教学活动（t-dashboard 复用第 5 步的作业列表）──────── */
  A.anActivity = function (a) {
    var x = A.teachingAssignment ? A.teachingAssignment(a, true) : {};
    var done = num(a.submittedCount) === null ? 0 : num(a.submittedCount);
    var total = num(a.studentCount) === null ? 0 : num(a.studentCount);
    return {
      id: a.id, idStr: def(x.idStr, String(a.id)),
      title: def(a.title, '—'),
      type: def(x.type, 'homework'),
      typeName: def(x.typeName, '作业'),
      pidCount: num(a.problemCount) === null ? 0 : num(a.problemCount),
      done: done, total: total,
      due: A.fmtDateTime(a.dueAt),
      left: A.leftText(a.dueAt),
      status: def(x.status, 'doing'),
      raw: a
    };
  };
})(window);
