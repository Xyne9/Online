/* adapter/admin.js —— 管理端字段映射（02 契约 §7.1~§7.6，#72~#87）
   ─────────────────────────────────────────────────────────────────────
   三约定同 adapter/index.js：只改名与换算、键名与改造前常量一致、缺值给 null/'—'。

   坑位记录：
   ① 沙箱 state 契约是 QUEUED/RUNNING/EXITED/KILLED，原型 a-audit 的模板与 class
      绑定认的是小写 run/idle/exited —— 这里统一压成 running/queued/exited
      （KILLED 也归 exited：它已经是终态，模板对 exited 的处理就是「已释放」）。
   ② 通道 healthStatus 是 ON/DEGRADE/OFF/PROBING 四态，原型只有 on/degrade/off 三档。
      PROBING 单独给 'probing'，由页面自己决定怎么显示，绝不当成 ON。
   ③ 审计 / 沙箱两处**页码必须分开**（已知 bug）：本文件不碰分页，由页面各自持有。
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
  function hostOf(url) {
    if (!url) return '—';
    return String(url).replace(/^https?:\/\//, '').replace(/\/+$/, '');
  }
  /* ⚠️ adapter/language.js 的 languageLabel(code) 在 code 缺失时会返回字符串
     "undefined"（`LABEL[String(code).toLowerCase()] || String(code)`）。
     真实态的 x-text 渲染出 "undefined" 比空串更糟——看着像接口坏了。
     凡是有可能缺语言的字段，一律走这个包一层。 */
  function langLabel(code, label) {
    if (label) return label;
    if (code === null || code === undefined || code === '') return '—';
    var v = A.languageLabel(code);
    return v === 'undefined' || v === 'null' ? '—' : v;
  }
  function p2(n) { return n < 10 ? '0' + n : String(n); }
  function hms(ms) {
    if (ms === null || ms === undefined) return '—';
    var d = new Date(ms);
    if (isNaN(d.getTime())) return '—';
    return p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds());
  }
  function clock(ms) {
    if (ms === null || ms === undefined) return '—';
    var d = new Date(ms);
    if (isNaN(d.getTime())) return '—';
    return p2(d.getHours()) + ':' + p2(d.getMinutes());
  }

  var STAGE_NO = { 1: '①', 2: '②', 3: '③', 4: '④' };
  var STAGE_NAME = { 1: '读题拆解', 2: '编码引导', 3: '排错诊断', 4: 'AC 复盘' };
  var HIT_NAME = { leak: '泄漏率', jailbreak: '越狱特征', sensitive: '敏感词',
                   LEAK_RATE: '泄漏率', JAILBREAK: '越狱特征', SENSITIVE_WORD: '敏感词' };

  function stageLabel(stage, stageName) {
    var no = num(stage);
    var name = def(stageName, STAGE_NAME[no] || '—');
    if (/^[①②③④]/.test(name)) return name;
    return (STAGE_NO[no] || '') + (STAGE_NO[no] ? ' ' : '') + name;
  }

  /* ── §7.2 通道（#72 列表 / #73 详情）─────────────────────────── */
  var STATE_OF = { ON: 'on', DEGRADE: 'degrade', OFF: 'off', PROBING: 'probing' };

  A.admChannel = function (c) {
    c = c || {};
    var state = STATE_OF[String(def(c.healthStatus, 'OFF')).toUpperCase()] || 'off';
    var tin = num(c.tokensIn), tout = num(c.tokensOut);
    return {
      id: def(c.id, c.channelCode),
      code: def(c.channelCode, '—'),
      name: def(c.channelName, def(c.channelCode, '—')),
      vendor: def(c.vendor, '—'),
      model: def(c.modelName, '—'),
      match: def(c.modelName, ''),
      base: def(c.baseUrl, '—'),
      host: hostOf(c.baseUrl),
      roles: (c.roleBindings || []).slice(),
      state: state,
      enabled: !!c.isEnabled,
      note: def(c.healthNote, ''),
      today: num(c.todayCalls) === null ? 0 : num(c.todayCalls),
      ttftMs: num(c.avgTtftMs),
      ttft: A.ttftText(num(c.avgTtftMs)),
      tokens: A.fmtNum((tin === null ? 0 : tin) + (tout === null ? 0 : tout)),
      tokensIn: tin, tokensOut: tout,
      costCents: num(c.costCents),
      cost: A.centsText(num(c.costCents)),
      lastProbeAt: num(c.lastProbeAt),
      cfg: {
        model: def(c.modelName, '—'),
        timeout: num(c.timeoutMs) === null ? 0 : num(c.timeoutMs),
        max_tokens: num(c.maxTokens) === null ? 0 : num(c.maxTokens),
        temp: num(c.temperature) === null ? '—' : num(c.temperature),
        conc: num(c.maxConcurrency) === null ? 0 : num(c.maxConcurrency)
      },
      trend: [], recent: [], degradeEvents: [], detailLoaded: false,
      testing: false,
      raw: c
    };
  };

  /* #73 详情：24h 趋势 / 近期调用 / 降级事件 */
  A.admChannelDetail = function (c) {
    c = c || {};
    var trend = (c.trend24h || []).map(function (t) { return num(t.ttftMs) === null ? 0 : num(t.ttftMs); });
    return {
      trend: trend,
      hours: (c.trend24h || []).map(function (t) { return def(t.hour, null); }),
      recent: (c.recentCalls || []).map(function (r) {
        return {
          recordId: def(r.recordId, null),
          at: clock(r.at),
          atMs: num(r.at),
          who: def(r.userName, '—'),
          stage: stageLabel(r.stage, r.stageName),
          ttft: num(r.ttftMs) === null ? 0 : num(r.ttftMs),
          tok: num(r.tokens) === null ? (num(r.tokensIn) || 0) + (num(r.tokensOut) || 0) : num(r.tokens),
          guard: def(r.guardCode, 'pass')
        };
      }),
      degradeEvents: (c.degradeEvents || []).map(function (e) {
        return { t: hms(e.at), n: def(e.from, '—') + ' → ' + def(e.to, '—'), d: def(e.reason, '—') };
      }),
      apiKeyMasked: def(c.apiKeyMasked, '—'),
      detailLoaded: true
    };
  };

  /* ── §7.4 审计（#81 列表 / #82 详情）──────────────────────────── */
  A.admCall = function (c) {
    c = c || {};
    var gin = num(c.tokensIn), gout = num(c.tokensOut);
    return {
      recordId: def(c.recordId, null),
      atMs: num(c.at),
      at: A.fmtDateTime(num(c.at)),
      time: hms(num(c.at)),
      who: def(c.userName, '—'),
      userId: def(c.userId, null),
      stage: stageLabel(c.stage, c.stageName),
      stageNo: num(c.stage),
      model: def(c.modelName, '—'),
      channelCode: def(c.channelCode, '—'),
      route: def(c.route, 'local'),
      ttft: num(c.ttftMs) === null ? 0 : num(c.ttftMs),
      tok: (gin === null ? 0 : gin) + (gout === null ? 0 : gout),
      guard: def(c.guardCode, 'pass'),
      guardAction: num(c.guardAction),
      guardReason: def(c.guardReason, 'NONE'),
      note: def(c.note, '—'),
      submissionId: def(c.submissionId, null),
      traceId: def(c.traceId, ''),
      detailLoaded: false,
      detail: null
    };
  };

  A.admCallDetail = function (d) {
    d = d || {};
    var u = d.user || {};
    return {
      userName: def(u.name, null),
      stuNo: def(u.stuNo, null),
      promptText: def(d.promptText, '（本次调用没有留下 prompt 摘要）'),
      replyText: def(d.replyText, '（本次调用没有留下回复摘要）'),
      guardReplacementText: def(d.guardReplacementText, null),
      guardReason: def(d.guardReason, 'NONE'),
      isFallback: !!d.isFallback,
      costCents: num(d.costCents),
      chain: (d.chain || []).map(function (c) {
        return { seqNo: num(c.seqNo), kind: def(c.kind, '—'), status: A.statusCodeOf(c) };
      }),
      sandboxInstanceId: def(d.sandboxInstanceId, null)
    };
  };

  /* 命中分布：code → 中文名（契约 hitDist 的 leak/jailbreak/sensitive） */
  A.admHitDist = function (list) {
    return (list || []).map(function (h) {
      var code = def(h.code, '');
      return { k: HIT_NAME[code] || code || '—', v: num(h.count) === null ? 0 : num(h.count), code: code };
    });
  };

  /* ── §7.5 沙箱（#84 列表 / #85 日志）────────────────────────── */
  A.admSandbox = function (b) {
    b = b || {};
    var st = String(def(b.state, 'EXITED')).toUpperCase();
    var state = st === 'RUNNING' ? 'running' : st === 'QUEUED' ? 'queued' : 'exited';
    var ageMs = num(b.ageMs);
    return {
      id: def(b.sandboxInstanceId, '—'),
      sandboxInstanceId: def(b.sandboxInstanceId, null),
      state: state,
      stateRaw: st,
      lang: langLabel(b.language, b.languageLabel),
      langKey: def(b.language, ''),
      user: def(b.userName, '—'),
      userId: def(b.userId, null),
      problemId: def(b.problemId, null),
      submissionId: def(b.submissionId, null),
      cpu: num(b.cpuPercent) === null ? 0 : num(b.cpuPercent),
      mem: num(b.memKb) === null ? 0 : num(b.memKb),
      ageMs: ageMs,
      age: ageMs === null ? '—' : A.fmtDuration(ageMs),
      exitCode: num(b.exitCode),
      startedAt: num(b.startedAt),
      killing: false
    };
  };

  A.admLogLine = function (l) {
    l = l || {};
    var ts = l.ts;
    if (typeof ts === 'number') ts = hms(ts);
    var msg = def(l.msg, '');
    var hl = !!l.highlight || /\b(WA|RE|TLE|MLE|CE|ERROR|error|failed|fail)\b/.test(msg);
    return { ts: def(ts, '—'), msg: msg, cls: hl ? 'hl' : '' };
  };

  /* ── §7.6 全站统计（#87）────────────────────────────────────── */
  A.admStats = function (s) {
    s = s || {};
    var ai = s.ai || {}, sub = s.submissions || {}, stor = s.storage || {}, teach = s.teaching || {};
    return {
      aiCallsToday: num(ai.callsToday) === null ? 0 : num(ai.callsToday),
      tokensToday: num(ai.tokensToday) === null ? 0 : num(ai.tokensToday),
      costCentsToday: num(ai.costCentsToday),
      fallbackRate: num(ai.fallbackRate),
      submissionsToday: num(sub.today) === null ? 0 : num(sub.today),
      acRate: num(sub.acRate),
      classes: num(teach.classes),
      assignments: num(teach.assignments),
      exams: num(teach.exams),
      pendingPlagiarism: num(teach.pendingPlagiarism),
      storageBytes: num(stor.testCaseBytes),
      trend: (s.trend || []).map(function (t) {
        return { date: def(t.date, '—'), submissions: num(t.submissions) || 0, aiCalls: num(t.aiCalls) || 0 };
      })
    };
  };
})(window);
