/* step6-analytics.js —— 学情三页（s-analytics / t-dashboard / t-analytics）真实态控制器
   ─────────────────────────────────────────────────────────────────────
   范式照抄 step5-assignments.js：**包装器覆盖页面 x-data 工厂函数**。
   · 第一行 `if (H.useMock()) return d;` —— mock 态原样返回，视觉零回归；
   · 只改 x-data 对象内部（新增 / 覆盖方法、getter、data 属性），不碰 DOM；
   · 页尾只允许追加 <script src>（在 alpine.min.js 之前）。

   真实态数据一律来自 api/analytics.js → 网关 → teaching 服务，
   页面不再有 setTimeout 假加载、不再有 Math.random() 造分数（agent.md §3.4）。
   ───────────────────────────────────────────────────────────────────── */
(function (g) {
  'use strict';
  var H = g.api.http, S = g.api.analytics, A = g.adapter;
  if (!H || !S) return;
  var PAGE = document.body.dataset.page;

  /* 原型里 `get xxx(){...}` 是对象字面量的 accessor（enumerable+configurable），
     直接 `d.xxx = fn` 会被 get 挡住（静默失效），必须 defineProperty 覆写。 */
  function prop(d, name, getter) {
    Object.defineProperty(d, name, { configurable: true, enumerable: true, get: getter });
  }
  function method(d, name, fn) { d[name] = fn; }
  function fail(d, e) {
    d.loadErr = true;
    /* 暴露原始异常方便联调定位（loadErr 是布尔，看不到原因）。
       只写 window 上的调试字段，不碰 DOM。 */
    try { g.__oj_step6_err = (e && (e.stack || e.message)) || String(e); } catch (e2) {}
    if (typeof d.say === 'function') d.say(H.describe(e), 'warn');
  }
  /* 缺值兜底：适配器已保证不给 undefined，这里再兜一层，
     避免 `arr[0].xxx` 这类不经判空的取值抛 pageerror（_probe_realmode 会抓）。 */
  function at(list, i) { return (Array.isArray(list) && list.length > (i || 0)) ? list[i || 0] : null; }
  /* 趋势柱高：按当页最大值归一；96px 是原型写死的峰高 */
  function bars(list, h) {
    var max = 1;
    (list || []).forEach(function (x) { if (x.total > max) max = x.total; });
    return (list || []).map(function (x) {
      return {
        date: x.date, label: x.label, week: x.week, ac: x.ac, wa: x.wa, total: x.total,
        hAc: x.total ? Math.max(3, Math.round(x.ac / max * (h || 96))) : 0,
        hWa: x.total ? Math.max(2, Math.round(x.wa / max * (h || 96))) : 0
      };
    });
  }
  /* 一律并行拉、单条失败不拖垮整页（缺的那块走各自的空态） */
  function settle(list) {
    return Promise.all(list.map(function (p) {
      return Promise.resolve(p).catch(function () { return null; });
    }));
  }
  function pctText(v) { return v == null ? '—' : Math.round(v) + '%'; }
  /* 数据就绪 → 通知 step6-bindings.js 改写既有文本节点（不用定时器、不用轮询） */
  function ready(d) {
    try { document.dispatchEvent(new CustomEvent('oj:step6-data')); } catch (e) {}
  }

  /* ══════════════════════════════════════════════════════════════════
     ① s-analytics.html —— 学生学情
        #62 analytics/student/{userId}   我的薄弱点与最近提交
        #62 aiStages / aiUsed            本人四阶段用量
        #57 class/{classId}/knowledge    班级掌握度（作为「vs 班级」的对照）
        #53 week-trend                   提交趋势（range 切换必须重拉）
        #51 classes                      班级 AC 率（统计带的对照基准）
        #6  题库 + #22 我的提交           题目掌握表（第 3 步既有端点）
     ══════════════════════════════════════════════════════════════════ */
  if (PAGE === 's-analytics' && g.analyticsPage) {
    var oldStudent = g.analyticsPage;
    g.analyticsPage = function () {
      var d = oldStudent();
      if (H.useMock()) return d;

      d.tags = [];
      d.probs = [];
      d.recentAc = [];
      d.days = [];
      d.summary = null;
      d.mine = null;
      d.classKp = null;
      d.classAcRate = null;
      d.className = '';
      d.aiTotal = 0;
      d.aiStages = [
        { k: 's1', no: '①', n: 0 }, { k: 's2', no: '②', n: 0 },
        { k: 's3', no: '③', n: 0 }, { k: 's4', no: '④', n: 0 }
      ];
      d.aiNote = '正在加载 AI 用量…';
      d.loading = true;
      d.loadErr = false;
      d.rangeIdx = 1;
      d.rangeKey = 'month';
      d.ranges = [{ d: 7, label: '近 7 天' }, { d: 30, label: '近 30 天' },
                  { d: 126, label: '本学期' }];

      var RANGE_OF = ['week', 'month', 'term'];
      var TREND_RANGE = { week: '7d', month: '30d', term: 'term' };
      var SUB_RANGE = { week: 7, month: 30, term: 126 };

      method(d, 'init', async function () { await this.load(); });
      method(d, 'retry', async function () { this.loadErr = false; await this.load(); });
      /* range 切换 → 服务端重拉（03 指南 §4.13 第 5 条：不许只在前端切片） */
      method(d, 'setRange', async function (i) {
        if (this.rangeIdx === i) return;
        this.rangeIdx = i;
        this.rangeKey = RANGE_OF[i] || 'month';
        await this.load();
      });
      method(d, 'load', async function () {
        this.loading = true; this.loadErr = false;
        try {
          var me = await S.me();
          var uid = me.id != null ? me.id : me.userId;
          var cid = me.classId;
          var range = this.rangeKey;
          var self = this;
          var res = await settle([
            uid != null ? S.student(uid, range) : null,
            cid != null ? S.classKnowledge(cid, range) : null,
            S.weekTrend({ classId: cid, range: TREND_RANGE[range] || '30d' }),
            S.mySubmissions({ range: SUB_RANGE[range] || 30 }),
            S.questions({ status: 'all' }),
            S.classes(true)
          ]);
          var stu = res[0], kp = res[1], trend = res[2], subs = res[3], probs = res[4], clsList = res[5];

          /* 班级 AC 率：#51 withStats 的真实值，作为统计带「班级均值」的对照基准。
             取不到就给 null，页面显示 '—'，不再写死 62%。 */
          var myClass = (clsList || []).filter(function (c) {
            return cid != null && String(c.classId) === String(cid);
          })[0] || at(clsList, 0);
          this.classAcRate = myClass && myClass.acRate != null ? Math.round(myClass.acRate) : null;
          this.className = myClass ? (myClass.className || '') : (me.className || '');

          /* ① 我的掌握度：契约 #62 只回 weakKps（薄弱项），不是全部知识点。
             pct 有值才画；没值的保持 null，页面显示 '—'，绝不拿别人的数顶上。 */
          var mine = {};
          ((stu && stu.weakKps) || []).forEach(function (k) {
            if (k && k.name != null && k.masteryPercent != null) mine[k.name] = k.masteryPercent;
          });
          var kpList = (kp && kp.list) || [];
          this.tags = kpList.map(function (k) { return A.anKp(k, mine[k.kpName]); });
          if (!this.tags.length) {
            this.tags = ((stu && stu.weakKps) || []).map(function (k) {
              return A.anKp({ kpId: k.name, kpName: k.name, masteryPercent: null }, k.masteryPercent);
            });
          }
          this.classKp = kp ? { avg: kp.avgMastery, weak: kp.weakCount } : null;
          this.mine = stu ? {
            name: stu.name || me.realName || '—',
            stuNo: stu.stuNo || me.username || '—',
            solved: stu.solved, total: stu.total,
            score: stu.score, acRate: stu.acRate, aiUsed: stu.aiUsed, rank: stu.rank
          } : null;

          /* ② 题目掌握表：题库（难度/通过率）+ 我的提交按题聚合（尝试/最高分/最近） */
          var byPid = {};
          (subs || []).forEach(function (s) {
            var pid = s.problemId != null ? s.problemId : s.pid;
            if (pid == null) return;
            var cell = byPid[pid] || (byPid[pid] = { tries: 0, best: null, last: null, status: 'none', rows: [] });
            cell.tries += 1;
            var sc = s.oiScore != null ? s.oiScore : s.score;
            sc = (sc == null || sc === '') ? null : Number(sc);
            if (sc != null && !isNaN(sc) && (cell.best == null || sc > cell.best)) cell.best = sc;
            if (cell.last == null) {
              cell.last = s.submittedAt != null ? s.submittedAt : s.createdAt;
              cell.status = A.statusCodeOf(s);
            }
            if (A.statusCodeOf(s) === 'ac') cell.status = 'ac';
            cell.rows.push(s);
          });
          self.probs = (probs || []).map(function (p) {
            var x = A.adaptProblem(p);
            var c = byPid[x.id] || null;
            /* 标签归属：优先按知识点推荐题表匹配（#57 recommendProblems），
               匹配不上再按题目自己的 tags 名称匹配（#6 的真实 tags 数组）。
               只靠推荐表会漏——后端每个知识点最多只推荐 3 道，
               其余属于该知识点但没被推荐的题会整行消失。 */
            var tag0 = self.tags.filter(function (t) {
              return (t.pids || []).indexOf(x.id) >= 0;
            })[0] || self.tags.filter(function (t) {
              return (x.tags || []).indexOf(t.name) >= 0;
            })[0] || null;
            return {
              id: x.id, no: x.no, title: x.title, lvl: x.lvl, tags: x.tags,
              ac: x.ac, rate: x.ac,
              tries: c ? c.tries : 0,
              score: (c && c.best != null) ? c.best : 0,
              at: (c && c.last) ? A.fmtDateTime(c.last) : '—',
              status: c ? (c.best === 100 ? 'ac' : (c.status || 'wa')) : 'none',
              tagName: tag0 ? tag0.name : ((x.tags && x.tags[0]) || '—'),
              tagKey: tag0 ? tag0.k : 'all',
              history: c ? c.rows.slice(0, 10).map(function (s) {
                var y = A.adaptSubmission(s);
                return { id: y.idStr, status: y.status, score: y.score == null ? 0 : y.score,
                         pass: y.pass, at: y.at };
              }) : [],
              note: !c
                ? '还没提交过这道题，先用 ① 读题拆解理清题意，再动手写。'
                : c.best === 100
                  ? '这题已经 AC，可以进入工作台 ④ AC 复盘，让 AI 归纳解法与同类题型。'
                  : c.best > 0
                    ? '还有部分测试点未通过，建议先在工作台 ③ 排错诊断里对比期望输出与实际输出。'
                    : '尚未得分，建议先用 ① 读题拆解理清题意，再动手写。'
            };
          });

          /* #61 是教师专属；学生的个人用量由 #62 下发，不能把 403 当成零调用。 */
          var stages = (stu && stu.aiStages) || [];
          var total = (stu && stu.aiUsed != null) ? stu.aiUsed : 0;
          this.aiTotal = total;
          this.aiStages = [1, 2, 3, 4].map(function (stage) {
            var value = stages.filter(function (s) { return Number(s.stage) === stage; })[0];
            return A.anAiStage(value || { stage: stage, count: 0 });
          }).map(function (s) { return { k: s.k, no: s.no || '—', n: s.n }; });
          var zero = this.aiStages.filter(function (s) { return !s.n; }).map(function (s) { return s.no; });
          this.aiNote = total
            ? ('本周期共 ' + total + ' 次调用' +
               (zero.length ? '；' + zero.join('、') + ' 阶段暂无调用记录。' : '。'))
            : (stu ? '本周期还没有 AI 调用记录。' : 'AI 用量暂时无法加载，请重试。');

          /* ④ 最近 AC（#62 recentSubmissions 里 status=ac 的前三条）
             ⚠️ #62 的 recentSubmissions 契约只给 submissionId/problemTitle/status/score/at，
                **没有 language 与 timeUsedMs**。adapter/language.js 的 languageLabel(undefined)
               会返回字符串 "undefined"——真实态渲染出来比空串更像坏了，所以缺值一律给 '—'。 */
          this.recentAc = ((stu && stu.recentSubmissions) || [])
            .filter(function (s) { return A.statusCodeOf(s) === 'ac'; })
            .slice(0, 3)
            .map(function (s) {
              return {
                pid: s.problemId != null ? s.problemId : '—',
                title: s.problemTitle || '—',
                lang: s.language ? A.languageLabel(s.language) : '—',
                at: A.fmtDateTime(s.at != null ? s.at : s.submittedAt),
                time: s.timeUsedMs != null ? A.fmtDuration(s.timeUsedMs) : '—'
              };
            });

          /* ⑤ 提交趋势（#53 week-trend；weeks 由 range 决定，服务端已切好窗口） */
          this.days = ((trend && trend.days) || []).map(A.anTrendDay);
          this.summary = trend ? {
            weekPassRate: trend.weekPassRate, weekSubmit: trend.weekSubmit,
            weekAc: trend.weekAc, peakDate: trend.peakDate
          } : null;
        } catch (e) {
          fail(this, e);
        } finally {
          this.loading = false;
          ready(this);
        }
      });

      /* ── 覆盖派生 getter（原型全部基于 DAYS / TAGS / PROBS 三份假常量） ── */
      prop(d, 'range', function () {
        var ac = 0, wa = 0, days = 0;
        (this.days || []).forEach(function (x) { ac += x.ac; wa += x.wa; if (x.total) days += 1; });
        var total = ac + wa;
        return { d: (this.days || []).length, ac: ac, wa: wa, total: total,
                 acRate: total ? Math.round(ac / total * 100) : 0, pids: days };
      });
      prop(d, 'rangeLabel', function () {
        return ({ week: '近 7 天', month: '近 30 天', term: '本学期（126 天）' })[this.rangeKey] || '近 30 天';
      });
      prop(d, 'bars', function () { return bars(this.days, 96); });
      prop(d, 'weakTags', function () {
        return (this.tags || []).filter(function (t) { return t.pct != null && t.pct < 70; });
      });
      prop(d, 'weakCount', function () { return this.weakTags.length; });
      prop(d, 'avgTry', function () {
        var n = 0, c = 0;
        (this.probs || []).forEach(function (p) { if (p.tries) { n += p.tries; c += 1; } });
        return c ? (n / c).toFixed(1) : '—';
      });
      prop(d, 'triedCount', function () {
        return (this.probs || []).filter(function (p) { return p.tries > 0; }).length;
      });
      /* 题目掌握只列「我提交过的题」——没做过的题谈不上掌握度（原型也是这个语义） */
      prop(d, 'rows', function () {
        var self = this;
        return (this.probs || []).filter(function (p) {
          if (!p.tries) return false;
          if (self.selTag !== 'all' && p.tagKey !== self.selTag) return false;
          if (self.onlyWeak) {
            var pids = [];
            self.weakTags.forEach(function (t) { (t.pids || []).forEach(function (id) { pids.push(id); }); });
            if (pids.indexOf(p.id) < 0) return false;
          }
          return true;
        });
      });
      prop(d, 'emptyTitle', function () {
        if (this.onlyWeak && this.selTag !== 'all') return this.tagName(this.selTag) + '不在薄弱范围内';
        if (!(this.probs || []).length) return '还没有可以统计的题目';
        return '当前筛选下没有题目';
      });
      prop(d, 'emptyDesc', function () {
        if (this.onlyWeak && this.selTag !== 'all')
          return '你只勾选了「只看薄弱」，而这个标签的掌握度高于 70%。取消勾选或换个标签试试。';
        if (!(this.probs || []).length) return '题库还没有同步过来，稍后重试。';
        return '提交过一次之后，这道题才会出现在掌握表里。换个标签，或点「查看全部题目」。';
      });
      prop(d, 'heatProblems', function () {
        return (this.probs || []).filter(function (p) { return p.tries > 0; }).slice(0, 6);
      });
      /* 判分链：真实态按「最近一次提交的 passCaseCount / totalCaseCount」点格，
         不再用 score/100 反推（03 指南 §4.7 第 6 条明确要求删除该伪造逻辑）。 */
      method(d, 'chain', function (p) {
        var h = p && p.history && p.history[0];
        if (!h) return new Array(10).fill('');
        var seg = String(h.pass == null ? '0/0' : h.pass).split('/');
        var ok = Math.max(0, Math.min(10, Number(seg[0]) || 0));
        var out = [];
        for (var i = 0; i < 10; i++) {
          if (i < ok) out.push('ac');
          else if (i === ok) out.push(h.status === 'ac' ? 'ac' : (h.status || 'wa'));
          else out.push('');
        }
        return out;
      });
      /* 去练习：有推荐题就直接进工作台（真实题号），否则落到题库筛未通过 */
      method(d, 'practiceHref', function (t) {
        var rec = t && t.recs && t.recs[0];
        if (rec && rec.id != null) return 's-workbench.html?pid=' + rec.id;
        return 's-problems.html?status=fail';
      });
      method(d, 'tagName', function (k) {
        var t = (this.tags || []).filter(function (x) { return x.k === k; })[0];
        return t ? t.name : '全部';
      });
      return d;
    };
  }

  /* ══════════════════════════════════════════════════════════════════
     ② t-dashboard.html —— 教师工作台
        #52 todos、#53 week-trend、#56 class/{classId}、#54 hard-problems
        「进行中的教学活动」复用第 5 步的 #38 /api/teaching/assignments。
     ══════════════════════════════════════════════════════════════════ */
  if (PAGE === 't-dashboard' && g.dashboardPage) {
    var oldDash = g.dashboardPage;
    g.dashboardPage = function () {
      var d = oldDash();
      if (H.useMock()) return d;

      d.days = [];
      d.ranks = [];
      d.activities = [];
      d.todos = [];
      d.overview = null;
      d.aiTotal = 0;
      d.aiStages = [
        { k: 's1', label: '① 读题拆解', n: 0 }, { k: 's2', label: '② 编码引导', n: 0 },
        { k: 's3', label: '③ 排错诊断', n: 0 }, { k: 's4', label: '④ AC 复盘', n: 0 }
      ];
      d.healthy = null;
      d.todoTotal = 0;
      d.clsName = '';
      d.classId = null;
      d.peakDate = '—';
      d.weekPassRateS = null;
      d.weekSubmitS = null;
      d.weekAcS = null;
      d.directCount = 0;
      d.fallbackCount = 0;
      d.directPct = 0;
      d.fallbackPct = 0;
      d.loading = true;
      d.loadErr = false;
      d.sel = 0;

      method(d, 'init', async function () {
        var self = this;
        await this.load();
        /* 模板里的「按 AC 率 / 按尝试人数」是 @click="rankMode=0" 直接赋值，
           #54 的 mode 是服务端参数 → 变了必须重拉（不能只重排这 5 条）。 */
        this.$watch('rankMode', function () {
          clearTimeout(self._rankT);
          self._rankT = setTimeout(function () { self.load(); }, 30);
        });
      });
      method(d, 'retry', async function () { this.loadErr = false; await this.load(); });
      method(d, 'load', async function () {
        this.loading = true; this.loadErr = false;
        try {
          var me = await S.me();
          var cid = me.classId;
          this.classId = cid == null ? null : cid;
          this.clsName = me.className || '';
          var mode = this.rankMode === 0 ? 'ac' : 'tried';
          var res = await settle([
            S.todos(10),
            S.weekTrend({ classId: cid, weeks: 1 }),
            cid != null ? S.classOverview(cid, 'week') : null,
            S.hardProblems({ classId: cid, range: 'week', mode: mode, limit: 5 }),
            S.aiUsage({ classId: cid, range: 'week', groupBy: 'stage' }),
            S.assignments(cid != null ? { classId: cid } : {})
          ]);
          var todo = res[0], trend = res[1], ov = res[2], hard = res[3], ai = res[4], acts = res[5];

          this.todos = ((todo && todo.list) || []).map(A.anTodo);
          this.healthy = (todo && todo.healthyCount != null) ? todo.healthyCount : null;
          this.todoTotal = (todo && todo.totalCount != null) ? todo.totalCount : this.todos.length;

          this.days = ((trend && trend.days) || []).map(A.anTrendDay);
          this.weekPassRateS = (trend && trend.weekPassRate != null) ? Math.round(trend.weekPassRate) : null;
          this.weekSubmitS = (trend && trend.weekSubmit != null) ? trend.weekSubmit : null;
          this.weekAcS = (trend && trend.weekAc != null) ? trend.weekAc : null;
          this.peakDate = (trend && trend.peakDate) ? trend.peakDate : '—';
          this.sel = Math.max(0, this.days.length - 1);

          this.overview = ov ? {
            className: ov.className || this.clsName,
            studentCount: ov.studentCount, avgScore: ov.avgScore, acRate: ov.acRate,
            activeCount: ov.activeCount, aiRiskCount: ov.aiRiskCount,
            peakDay: ov.peakDay, peakCount: ov.peakCount,
            weakestKp: ov.weakestKp, avgKpMastery: ov.avgKpMastery
          } : null;

          this.ranks = ((hard && hard.list) || []).map(A.anHardProblem);

          var stages = (ai && ai.stageDist) || [];
          if (stages.length) {
            this.aiStages = stages.map(function (s) {
              var x = A.anAiStage(s);
              return { k: x.k, label: x.label, n: x.n };
            });
          }
          this.aiTotal = (ai && ai.totalCalls != null) ? ai.totalCalls : 0;

          /* ⚠️ 云端/本地路由拆分：唯一的数据源是 #87 /api/admin/stats（ADMIN 专属），
             教师账号拿不到（403）。#56 / #61 都不下发这个拆分。
             所以不画那三段比例——原型的 72%/21%/7% 是假的，真实态如实留空。
             （step6-bindings.js 已把该块的 stackbar/legend x-show="false" 并改文案说明。） */
          this.fallbackCount = null;
          this.directCount = 0;
          this.fallbackPct = 0;
          this.directPct = 0;

          /* 只留「还没结束」的活动；服务端 status 3 是 done */
          this.activities = (acts || []).filter(function (a) { return a.status !== 3; })
            .slice(0, 5).map(A.anActivity);
        } catch (e) {
          fail(this, e);
        } finally {
          this.loading = false;
          ready(this);
        }
      });
      /* 行内「发布」：真实态跳去作业页处理，不再 setTimeout 假发布 + 假撤销。
         （agent.md §3.4：setTimeout 假发布属于必须删的假数据。）
         ⚠️ act==='review' 那一行在模板里本身是 <a :href="t.href" @click="finish(t)">，
            href 已经会跳走；这里再 location.href 就是发两次导航。 */
      method(d, 'finish', function (t) {
        if (!t || t.busy || t.struck) return;
        if (t.act === 'review') { this.collapse(t); return; }
        if (t.href) { g.location.href = t.href; return; }
        this.collapse(t);
      });

      prop(d, 'maxSub', function () {
        var m = 1;
        (this.days || []).forEach(function (x) { if (x.sub > m) m = x.sub; });
        return m;
      });
      prop(d, 'day', function () {
        return at(this.days, this.sel) || at(this.days, 0) ||
          { date: '—', week: '—', sub: 0, ac: 0 };
      });
      prop(d, 'weekSub', function () {
        if (this.weekSubmitS != null) return this.weekSubmitS;
        return (this.days || []).reduce(function (n, x) { return n + x.sub; }, 0);
      });
      prop(d, 'weekAc', function () {
        if (this.weekAcS != null) return this.weekAcS;
        return (this.days || []).reduce(function (n, x) { return n + x.ac; }, 0);
      });
      /* 口径：服务端 pct 优先；缺了才按提交次数加权（与原型注释同一口径） */
      prop(d, 'weekPassRate', function () {
        if (this.weekPassRateS != null) return this.weekPassRateS;
        var s = this.weekSub;
        return s ? Math.round(this.weekAc / s * 100) : 0;
      });
      prop(d, 'dayRate', function () {
        var d0 = this.day;
        return d0.sub ? Math.round(d0.ac / d0.sub * 100) : 0;
      });
      prop(d, 'todoRows', function () {
        return (this.todos || []).filter(function (t) { return t.count > 0; });
      });
      prop(d, 'pendingRows', function () {
        return (this.todos || []).filter(function (t) { return t.count > 0 && !t.gone; });
      });
      prop(d, 'healthyTodos', function () {
        return (this.todos || []).filter(function (t) { return !(t.count > 0); });
      });
      prop(d, 'rankRows', function () { return (this.ranks || []).slice(); });
      /* 「第 N 周」标签：契约没给周序号，用趋势首末日期如实表达 */
      prop(d, 'weekLabel', function () {
        var ds = this.days || [];
        return ds.length ? (ds[0].date + ' 至 ' + ds[ds.length - 1].date) : '—';
      });
      return d;
    };
  }

  /* ══════════════════════════════════════════════════════════════════
     ③ t-analytics.html —— 班级学情
        #51 classes、#56 class/{id}、#57 knowledge、#58 students、#59 risk、
        #60 heatmap、#54 hard-problems、#61 ai-usage、#53 week-trend
        ⚠️ 原型的 KP_BASE（82/64/41/73/55）与 s-analytics 的 TAGS 同源同值，
           两页都必须换成真实的 masteryPercent（03 指南 §4.13 第 6 条），
           本文件把 KP_BASE / buildStudents / rnd() 整段弃用。
     ══════════════════════════════════════════════════════════════════ */
  if (PAGE === 't-analytics' && g.analyticsPage) {
    var oldTA = g.analyticsPage;
    g.analyticsPage = function () {
      var d = oldTA();
      if (H.useMock()) return d;

      d.classes = [];
      d.all = [];
      d.kpsData = [];
      d.heatRowsData = [];
      d.risksData = [];
      d.aiTopData = [];
      d.daysData = [];
      d.todosData = [];
      d.ov = null;
      d.riskTotalS = 0;
      d.aiRiskCountS = 0;
      d.totalStudentsS = 0;
      d.heatFootNote = '';
      d.pageSize = 10;
      d.page = 1;
      d.loading = true;
      d.loadErr = false;
      d.cls = '';

      var RANGE_OF = ['week', 'month', 'term'];

      method(d, 'init', async function () {
        var q = g.OJ.qs('range');
        if (q === 'week') this.range = 0;
        else if (q === 'term') this.range = 2;
        var want = g.OJ.qs('cls');
        if (want) this.cls = want;
        await this.load();
        var sid = g.OJ.qs('sid');
        if (sid) {
          var hit = (this.all || []).filter(function (s) { return String(s.sid) === String(sid); })[0];
          if (hit) this.detail = hit;
        }
        if (g.OJ.qs('err') === '1') this.loadErr = true;
      });
      method(d, 'retry', async function () { this.loadErr = false; await this.load(); });
      /* 模板里时间范围/班级都是「先改 this.range|cls，再调 resetSort()」，
         所以把重拉挂在 resetSort 上——保证「切了就重拉」，不是前端切片。 */
      method(d, 'resetSort', async function () {
        this.page = 1;
        await this.load();
      });
      method(d, 'load', async function () {
        this.loading = true; this.loadErr = false;
        try {
          var range = RANGE_OF[this.range] || 'month';
          var list = await S.classes(true).catch(function () { return []; });
          this.classes = (list || []).map(A.anClass);

          /* 班级 id：URL ?cls= / 当前选中 → 第一个班 */
          var want = this.cls;
          var picked = (this.classes || []).filter(function (c) { return String(c.id) === String(want); })[0]
            || at(this.classes, 0);
          var classId = picked ? picked.id : null;
          this.cls = classId == null ? '' : String(classId);
          if (classId == null) {
            this.all = []; this.kpsData = []; this.heatRowsData = []; this.risksData = [];
            this.aiTopData = []; this.daysData = []; this.ov = null;
            return;
          }

          var res = await settle([
            S.classOverview(classId, range),
            S.classKnowledge(classId, range),
            S.allStudents(classId, { range: range }),
            S.classRisk(classId, null),
            S.classHeatmap(classId, 6),
            S.aiUsage({ classId: classId, range: range, groupBy: 'student' }),
            S.weekTrend({ classId: classId, weeks: 1 })
          ]);
          var ov = res[0], kp = res[1], stu = res[2], risk = res[3], heat = res[4], ai = res[5], trend = res[6];

          this.ov = ov ? {
            className: ov.className, studentCount: ov.studentCount, avgScore: ov.avgScore,
            acRate: ov.acRate, activeCount: ov.activeCount, aiRiskCount: ov.aiRiskCount,
            peakDay: ov.peakDay, peakCount: ov.peakCount, weakestKp: ov.weakestKp,
            avgKpMastery: ov.avgKpMastery, weakCount: ov.weakCount,
            dist: A.anDist(ov.dist)
          } : null;
          this.totalStudentsS = (ov && ov.studentCount != null) ? ov.studentCount : (stu || []).length;
          this.aiRiskCountS = (ov && ov.aiRiskCount != null) ? ov.aiRiskCount : 0;

          /* 班级页的「掌握度」**就是班级掌握度**（#57 masteryPercent），
             不是「某个学生的掌握度」。所以第二个参数（个人值）也传班级值：
             anKp(..., null) 会让 pct=null，而模板写的是 `${k.pct}%` → 渲染出 "null%"，
             而且 `null < 50` 为 true → 所有知识点被涂成薄弱的低色。
             s-analytics（学生页）才需要区分「个人 pct / 班级 cls」。 */
          this.kpsData = ((kp && kp.list) || []).map(function (k) {
            return A.anKp(k, k && k.masteryPercent);
          });
          this.all = (stu || []).map(A.anStudent);
          this.heatRowsData = ((heat && heat.rows) || []).map(A.anHeatRow);
          this.heatFootNote = (heat && heat.footNote) ? heat.footNote : '';

          var risks = ((risk && risk.list) || []).map(A.anRisk);
          this.risksData = risks.slice(0, 5);
          this.riskTotalS = (risk && risk.total != null) ? risk.total : risks.length;

          var tops = (ai && (ai.topStudents || ai.top)) || [];
          this.aiTopData = tops.slice(0, 5).map(function (t) {
            return { name: t.name || '—', sid: t.stuNo || '—',
                     ai: t.count == null ? 0 : t.count, stage: t.stageName || '' };
          });
          this.daysData = ((trend && trend.days) || []).map(A.anTrendDay);
          /* 本周期待办（#52）：同一页右侧「本周期待办」四条静态文案的数据源 */
          var td = await S.todos(4).catch(function () { return null; });
          this.todosData = ((td && td.list) || []).map(A.anTodo);
        } catch (e) {
          fail(this, e);
        } finally {
          this.loading = false;
          ready(this);
        }
      });

      /* ── 覆盖派生 getter（原型全部基于 buildStudents 的伪随机数组） ── */
      prop(d, 'rangeLabel', function () { return ({ 0: '本周', 1: '本月', 2: '学期' })[this.range] || '本月'; });
      prop(d, 'rangeShort', function () { return this.rangeLabel; });
      prop(d, 'rangeTip', function () {
        var last = at(this.daysData, (this.daysData || []).length - 1);
        return last ? last.date : '—';
      });
      prop(d, 'classSize', function () {
        if (this.totalStudentsS) return this.totalStudentsS;
        var self = this;
        var c = (this.classes || []).filter(function (x) { return x.k === self.cls; })[0];
        return c && c.studentCount != null ? c.studentCount : (this.all || []).length;
      });
      prop(d, 'className', function () {
        var self = this;
        var c = (this.classes || []).filter(function (x) { return x.k === self.cls; })[0];
        return c ? c.className : (this.ov && this.ov.className) || '我的班级';
      });
      prop(d, 'avgScore', function () {
        if (this.ov && this.ov.avgScore != null) return Math.round(this.ov.avgScore);
        var s = 0, n = 0;
        (this.all || []).forEach(function (x) { if (x.score != null) { s += x.score; n += 1; } });
        return n ? Math.round(s / n) : '—';
      });
      prop(d, 'acRate', function () {
        if (this.ov && this.ov.acRate != null) return Math.round(this.ov.acRate);
        var s = 0, n = 0;
        (this.all || []).forEach(function (x) { if (x.rate != null) { s += x.rate; n += 1; } });
        return n ? Math.round(s / n) : '—';
      });
      prop(d, 'dist', function () {
        if (this.ov && this.ov.dist) return this.ov.dist;
        return { ac: 0, part: 0, fail: 0, max: '—', min: '—', median: '—', sd: '—' };
      });
      prop(d, 'activeCount', function () {
        return (this.ov && this.ov.activeCount != null) ? this.ov.activeCount : '—';
      });
      prop(d, 'aiRiskCount', function () { return this.aiRiskCountS; });
      prop(d, 'kps', function () { return this.kpsData || []; });
      prop(d, 'kpsN', function () { return (this.kpsData || []).length; });
      prop(d, 'risks', function () { return this.risksData || []; });
      prop(d, 'riskTotal', function () { return this.riskTotalS; });
      prop(d, 'aiTop', function () { return this.aiTopData || []; });
      prop(d, 'maxAi', function () {
        var m = 1;
        (this.aiTop || []).forEach(function (t) { if (t.ai > m) m = t.ai; });
        return m;
      });
      prop(d, 'weakest', function () {
        var k = (this.kps || []).filter(function (x) { return x.pct != null; })
          .slice().sort(function (a, b) { return a.pct - b.pct; });
        return at(k, 0) || { name: '—', pct: null, ac: null, tried: null, delta: 0, recs: [] };
      });
      prop(d, 'avgKp', function () {
        if (this.ov && this.ov.avgKpMastery != null) return Math.round(this.ov.avgKpMastery);
        var k = (this.kps || []).filter(function (x) { return x.pct != null; });
        return k.length ? Math.round(k.reduce(function (n, x) { return n + x.pct; }, 0) / k.length) : 0;
      });
      prop(d, 'weakCount', function () {
        if (this.ov && this.ov.weakCount != null) return this.ov.weakCount;
        return (this.kps || []).filter(function (k) { return k.pct != null && k.pct < 70; }).length;
      });
      prop(d, 'heatRows', function () { return this.heatRowsData || []; });
      prop(d, 'heatFoot', function () {
        var pick = this.heatPick;
        if (pick) {
          var row = (this.heatRows || []).filter(function (r) { return r.pid === pick; })[0];
          if (row) return '#' + row.pid + ' ' + row.title + '：平均通过率 ' +
            (row.pass == null ? '—' : row.pass + '%') + '，每格 1 个测试点。';
        }
        return this.heatFootNote || '点一行看这道题的通过率。';
      });
      /* #58 已由 allStudents() 翻页拉全，这里按当前排序键本地排序 + 前端分页；
         服务端排序参数同样可用，但整班数据量小，一次拉全更稳（翻页不会错位）。 */
      prop(d, 'sorted', function () {
        var r = this.riskOnly
          ? (this.all || []).filter(function (s) { return s.risk !== 'ok'; })
          : (this.all || []).slice();
        var k = this.sortKey;
        r.sort(function (a, b) {
          var x = a[k], y = b[k];
          if (typeof x === 'string' || typeof y === 'string') {
            return String(x == null ? '' : x).localeCompare(String(y == null ? '' : y), 'zh');
          }
          return (x == null ? -1 : x) - (y == null ? -1 : y);
        });
        if (!this.sortAsc) r.reverse();
        return r;
      });
      prop(d, 'pages', function () {
        return Math.max(1, Math.ceil((this.sorted || []).length / this.pageSize));
      });
      prop(d, 'pageRows', function () {
        if (this.page > this.pages) this.page = this.pages;
        var s = (this.page - 1) * this.pageSize;
        return (this.sorted || []).slice(s, s + this.pageSize);
      });
      prop(d, 'peakDay', function () {
        if (this.ov && this.ov.peakDay) return this.ov.peakDay;
        var last = at(this.daysData, (this.daysData || []).length - 1);
        return last ? last.date : '—';
      });
      prop(d, 'peakCount', function () {
        if (this.ov && this.ov.peakCount != null) return this.ov.peakCount;
        var m = 0;
        (this.daysData || []).forEach(function (x) { if (x.sub > m) m = x.sub; });
        return m;
      });
      /* 学生抽屉的「峰值」行：spark 为空时不能算出 NaN（原来会显示「峰值 undefined · NaN 次」） */
      prop(d, 'peakNote', function () {
        var sp = (this.detail && this.detail.spark) || [];
        if (!sp.length) return '近 7 天暂无提交';
        var bi = 0;
        for (var i = 1; i < sp.length; i++) if (sp[i] > sp[bi]) bi = i;
        return '近 7 天峰值 ' + Math.max(0, sp[bi]) + '%';
      });
      /* 本周期待办四行：原型是写死的四条静态记录，真实态取 #52。
         不足四行时如实写「暂无待办」，不补造条目。 */
      [0, 1, 2, 3].forEach(function (i) {
        prop(d, 'stepT' + i, function () {
          var t = (this.todosData || [])[i];
          return t ? t.label : '暂无待办';
        });
        prop(d, 'stepD' + i, function () {
          var t = (this.todosData || [])[i];
          return t ? t.sub : '当前没有需要处理的事项';
        });
      });
      prop(d, 'pctText', function () { return pctText; });
      return d;
    };
  }
})(window);
