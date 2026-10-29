(function (g) {
  'use strict';
  var A = g.adapter;
  A.teachingAssignment = function (d, teacher) {
    var a = A.adaptAssignment(d);
    a.raw = d;
    a.idStr = d.displayNo || String(d.id);
    a.pidCount = d.problemCount || 0;
    a.done = teacher ? (d.submittedCount || 0) : (d.doneCount || 0);
    a.full = d.fullScore == null ? a.pidCount * 100 : d.fullScore;
    a.score = d.myScore == null ? 0 : d.myScore;
    a.avg = d.avgScore;
    a.totalStudents = d.studentCount || 0;
    a.ai = d.aiStrategyDesc || '—';
    a.status = teacher ? ({ 0: 'todo', 1: 'todo', 2: 'doing', 3: 'done' }[d.status] || 'todo')
      : (d.mySubmissionStatus === 'SUBMITTED' ? 'done' : ({ 0: 'todo', 1: 'todo', 2: 'doing', 3: 'over' }[d.status] || 'todo'));
    return a;
  };
  A.teachingStudent = function (d, total) {
    return { userId: d.userId, name: d.name || '—', sid: d.stuNo || '—', sub: !!d.submitted,
      prog: d.doneCount == null ? Math.round((d.progressPercent || 0) * total / 100) : d.doneCount,
      total: total, score: d.score, ai: d.aiUsed || 0, risk: d.riskLevel === 'none' ? 'ok' : d.riskLevel || 'ok' };
  };
  A.examQuestion = function (d) {
    return { id: d.problemId, title: d.title || '', lvl: d.difficulty || 1, tags: d.tags || [],
      score: d.score, time: (d.timeLimitMs || 1000) + 'ms',
      desc: [d.statement, d.inputSpec, d.outputSpec].filter(Boolean).map(function (s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br>');
      }), examples: (d.samples || []).map(function (s) { return { in: s.input, out: s.output }; }),
      language: d.myDraftLanguage || 'cpp',
      code: (d.myDraftCode || (d.codeTemplates && d.codeTemplates.cpp) || '').split('\n'),
      state: d.handedIn ? 'done' : 'todo', handedIn: !!d.handedIn, tries: d.handedIn ? 1 : 0, cases: [], raw: d };
  };
  A.monitorStudent = function (s) {
    return { userId: s.userId, name: s.name || '—', sid: s.stuNo || '—', done: s.doneCount || 0,
      total: s.problemCount || 0, switch: s.switchCount || 0, ai: s.aiUsed || 0,
      status: s.redFlag && s.status === 'doing' ? 'abnormal' : s.status,
      enter: A.fmtDateTime(s.enterAt), last: A.fmtDateTime(s.lastActiveAt),
      timeline: (s.timeline || []).map(function (t) { return { t: A.fmtDateTime(t.at), s: t.text, tone: t.tone || '' }; }),
      problems: (s.problems || []).map(function (p) { return { pid: p.problemId, title: p.title || '',
        status: p.status == null ? 'none' : A.statusCodeOf({ status: p.status }), cost: A.fmtDuration(p.costMs) }; }) };
  };
  A.plagiarismPair = function (p) {
    function user(u) { u = u || {}; return { name: u.name || '—', sid: u.stuNo || '—',
      lang: u.languageLabel || A.languageLabel(u.language), at: A.fmtDateTime(u.submittedAt), code: [] }; }
    return { id: p.reportId, no: p.displayNo, pid: p.problemId, title: p.problemTitle || '',
      struct: p.structSim, sem: p.semSim, overallSim: p.overallSim, level: p.level,
      diagnosis: p.aiDiagnosis, model: p.aiModelUsed, a: user(p.userA), b: user(p.userB),
      review: p.auditStatus === 1 ? 'confirmed' : p.auditStatus === 2 ? 'independent' : null,
      segs: {}, segments: [], rows: [] };
  };
})(window);
