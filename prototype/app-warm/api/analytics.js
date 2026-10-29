/* api/analytics.js —— 学情（teaching）请求封装
   ─────────────────────────────────────────────────────────────────────
   只负责「拼 URL + 收参数 + 翻页聚合」，鉴权 / 信封 / 错误码 / 超时 / 重试
   全部仍由 api/http.js 处理（页面禁止直接 fetch，也不许自己拼 Authorization）。

   覆盖 02 契约 §6.5 班级与待办簇（#51~#53）+ §6.6 学情分析簇（#54~#62）。
   分页聚合沿用 api/teaching.js 的 all() 模式：一页 100 条、按 hasNext 翻到底。
   ───────────────────────────────────────────────────────────────────── */
(function (g) {
  'use strict';
  var http = g.api.http;

  /* 逐页拉到底。服务端 maxLimit=100（跟 PageResult.normalizePageSize 同口径），
     所以 pageSize 固定 100，多一页都不多要。 */
  function all(url, query) {
    var list = [], page = 1, result;
    return (function next() {
      return http.get(url, { query: Object.assign({}, query, { page: page++, pageSize: 100 }) })
        .then(function (r) {
          result = r;
          list = list.concat((r && r.list) || []);
          if (r && r.hasNext && page <= 50) return next();       // 50 页硬闸：防止 hasNext 恒 true 死循环
          return list;
        });
    })();
  }

  /* 当前登录用户（id / classId / role）。
     ⚠️ 不能只读 localStorage.OJ_USER：探针与部分入口只写 token 不写 user，
     那样 classId / userId 全空，学情端点直接拼出 /student/undefined。
     所以先问服务端要（#4 /api/auth/me），失败再退回本地缓存。 */
  var meCache = null, mePromise = null;
  function localUser() {
    try { return JSON.parse(localStorage.getItem('OJ_USER') || 'null') || {}; } catch (e) { return {}; }
  }
  function me(force) {
    if (meCache && !force) return Promise.resolve(meCache);
    if (mePromise && !force) return mePromise;
    mePromise = http.get('/api/auth/me').then(function (d) {
      meCache = d || {};
      return meCache;
    }, function () {
      meCache = localUser();
      return meCache;
    });
    return mePromise;
  }
  function cachedMe() { return meCache || localUser() || {}; }

  function q(o) { return o || {}; }

  g.api.analytics = {
    all: all,
    me: me,
    cachedMe: cachedMe,

    /* ── §6.5 班级与待办 ── */
    classes: function (withStats) {
      return http.get('/api/teaching/classes', { query: { withStats: withStats ? true : null } })
        .then(function (r) { return (r && r.list) || r || []; });
    },
    todos: function (limit) { return http.get('/api/teaching/todos', { query: { limit: limit || null } }); },
    /* #53 week-trend 的窗口参数有两套：
         · weeks   —— 「第几周」，1~26，实际天数 = weeks × 7
         · range   —— '7d' / '30d' / 'term' / 'all'，两者同时给出时 **range 优先**
       学情页的「近 7/30/90 天」要的是精确天数，所以用 range（35 天 ≠ 30 天，
       用 weeks=5 会让「近 30 天」多算 5 天，和旁边 #56/#57 的 30 天窗口对不上）。 */
    weekTrend: function (query) { return http.get('/api/teaching/week-trend', { query: q(query) }); },

    /* ── §6.6 学情分析簇 ── */
    hardProblems: function (query) { return http.get('/api/teaching/analytics/hard-problems', { query: q(query) }); },
    problemErrors: function (problemId, query) {
      return http.get('/api/teaching/analytics/problem/' + problemId + '/errors', { query: q(query) });
    },
    classOverview: function (classId, range) {
      return http.get('/api/teaching/analytics/class/' + classId, { query: { range: range || null } });
    },
    classKnowledge: function (classId, range) {
      return http.get('/api/teaching/analytics/class/' + classId + '/knowledge', { query: { range: range || null } });
    },
    /* 分页 + 排序 + 风险筛选都在服务端；单页直接给原响应（要 total / hasNext） */
    classStudents: function (classId, query) {
      return http.get('/api/teaching/analytics/class/' + classId + '/students', { query: q(query) });
    },
    /* 整班（用于前端排序/分页之外的统计）走翻页聚合 */
    allStudents: function (classId, query) {
      return all('/api/teaching/analytics/class/' + classId + '/students', q(query));
    },
    classRisk: function (classId, level) {
      return http.get('/api/teaching/analytics/class/' + classId + '/risk', { query: { level: level || null } });
    },
    classHeatmap: function (classId, limit) {
      return http.get('/api/teaching/analytics/class/' + classId + '/heatmap', { query: { limit: limit || null } });
    },
    aiUsage: function (query) { return http.get('/api/teaching/analytics/ai-usage', { query: q(query) }); },
    student: function (userId, range) {
      return http.get('/api/teaching/analytics/student/' + userId, { query: { range: range || null } });
    },

    /* t-dashboard 的「进行中的教学活动」直接复用第 5 步的作业列表端点 */
    assignments: function (query) { return all('/api/teaching/assignments', q(query)); },

    /* s-analytics 的「题目掌握」需要「我的提交 → 按题聚合」，
       数据源是第 3 步就有的 #22 提交列表与 #6 题库列表（都不是新造的字段）。 */
    mySubmissions: function (query) { return all('/api/judge/submissions', q(query)); },
    questions: function (query) { return all('/api/problem/questions', q(query)); }
  };
})(window);
