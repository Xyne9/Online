/* api/http.js —— 无构建工具，直接挂 window.api.http
   ─────────────────────────────────────────────────────────────────────
   Day2 Stage 0 地基①：统一请求层。
   负责 BASE / Bearer / X-Trace-Id / 9 个业务错误码 / 401 回收 /
   15s 超时 / GET 指数退避重试 / MOCK 短路。
   规格来源：docs/analysis/03-前端对接实施指南.md §2.2（源码照抄）。
   风格：纯 ES5（var + function），浏览器直接执行，不经任何编译。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  var BASE = '';                       // 同源留空；直连网关改 'http://localhost:8080'
  var TOKEN_KEY = 'OJ_TOKEN', MOCK_KEY = 'OJ_USE_MOCK', TRACE_HEAD = 'X-Trace-Id';
  var TIMEOUT = 15000;

  /* 用工厂函数而不是 class：调用处写 ApiError(...) 更贴近契约语义，
     同时保留 Error 的 stack，方便在 DevTools 里定位调用点。 */
  function ApiError(code, message, traceId, data) {
    var e = new Error(message || '请求失败');
    e.name = 'ApiError'; e.code = code; e.traceId = traceId; e.data = data;
    return e;
  }

  /* 三个入口都要认：① 地址栏 ?mock=1 ② sessionStorage（boot.js 跨页透传）
     ③ localStorage（用户手动开过演示开关）。 */
  function useMock() {
    if (/[?&]mock=(1|true)\b/.test(String(location.search || ''))) return true;
    try { return localStorage.getItem(MOCK_KEY) === '1' || sessionStorage.getItem(MOCK_KEY) === '1'; }
    catch (e) { return false; }
  }
  function getToken() { try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; } }
  function setToken(t) {
    try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch (e) {}
  }
  function newTraceId() { return 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8); }

  /* 数组拍平成重复 key（tag=a&tag=b）；空串 / null / undefined 一律不发——
     否则后端会把 ?status= 当成「筛选 status 为空」并返回 400。 */
  function buildQuery(q) {
    if (!q) return '';
    var out = [];
    Object.keys(q).forEach(function (k) {
      var v = q[k];
      if (v === null || v === undefined || v === '') return;
      (Array.isArray(v) ? v : [v]).forEach(function (x) {
        out.push(encodeURIComponent(k) + '=' + encodeURIComponent(x));
      });
    });
    return out.length ? '?' + out.join('&') : '';
  }

  /* 9 条中文错误文案，与 00-基线决策.md §4 的业务码同值 */
  var MESSAGES = {
    400: '请求参数不合法', 401: '登录已过期，请重新登录', 403: '没有权限执行该操作',
    404: '数据不存在或已被删除', 409: '数据状态已变化，请刷新后重试',
    429: '操作太频繁，请稍候再试', 500: '服务器内部错误',
    502: '网关错误：下游服务不可用', 504: '请求超时'
  };

  function toLogin() {
    if ((location.pathname.split('/').pop() || '') === 'login.html') return;   // 防死循环
    var to = 'login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
    /* 脚本跳转前掐掉跨文档 View Transition，避免 Chrome 抛
       "Transition was aborted because of invalid state"（详见 boot.js 同名注释） */
    if (global.OJ_GO) { global.OJ_GO(to); return; }
    location.replace(to);
  }
  function describe(err) {
    if (!err) return '未知错误';
    var msg = err.message || MESSAGES[err.code] || '请求失败';
    return err.traceId ? msg + '（traceId: ' + err.traceId + '）' : msg;
  }
  function delay(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  /* 单次请求：超时、请求头、信封解包都在这里；重试只负责重复调用它。 */
  function once(method, path, opts) {
    opts = opts || {};
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, opts.timeoutMs || TIMEOUT);
    var tid = newTraceId();
    var headers = Object.assign({}, opts.headers || {}, { 'X-Trace-Id': tid });
    var token = getToken();
    if (token) headers.Authorization = 'Bearer ' + token;      // credentials 用 omit，Bearer 与 Cookie 不混用
    var body = opts.body;
    if (body instanceof FormData) { delete headers['Content-Type']; }
    else if (body !== undefined) { headers['Content-Type'] = 'application/json;charset=UTF-8'; body = JSON.stringify(body); }

    return fetch(BASE + path + buildQuery(opts.query), {
      method: method, headers: headers, body: body, credentials: 'omit', signal: ctrl.signal
    }).then(function (res) {
      clearTimeout(timer);
      if (opts.raw) return res;                                  // 导出 CSV 等场景
      if (res.status === 401) { setToken(''); toLogin(); throw ApiError(401, MESSAGES[401], tid); }
      return res.json().catch(function () { throw ApiError(res.status, '响应不是合法 JSON', tid); })
        .then(function (env) {
          /* 网关把业务错误包在 200 里时也要抛：信封里的 code 才是真值 */
          if (env && env.code !== undefined && env.code !== 200) {
            if (env.code === 401) { setToken(''); toLogin(); }
            throw ApiError(env.code, env.message || MESSAGES[env.code], env.traceId || tid, env.data);
          }
          return env && env.data !== undefined ? env.data : env;
        });
    }, function (err) {
      clearTimeout(timer);
      if (err && err.name === 'AbortError') {
        throw ApiError(504, '请求超时（' + ((opts.timeoutMs || TIMEOUT) / 1000) + 's）', tid);
      }
      throw ApiError(0, '网络不可达：请确认网关已启动，或用 ?mock=1 演示', tid);
    });
  }

  function request(method, path, opts) {
    opts = opts || {};
    /* MOCK 短路：一个真实请求都不发。180ms 假延迟是为了让骨架屏/转圈
       真的闪一下——真实接口不可能瞬时返回，同步 resolve 会让演示看起来像卡住。 */
    if (useMock()) {
      if (!global.OJMock) return Promise.reject(ApiError(500, 'api/mock.js 未加载'));
      return global.OJMock.request(method, path, opts)
        .then(function (d) { return new Promise(function (r) { setTimeout(function () { r(d); }, 180); }); });
    }
    /* 只退避重试网关 / 网络类失败；POST 默认 0 次，
       避免判题提交被重放成两次（§2.2 的重试铁律）。 */
    var maxRetry = opts.retries !== undefined ? opts.retries : (method === 'GET' ? 2 : 0);
    var attempt = 0;
    function fire() {
      return once(method, path, opts).catch(function (e) {
        var retriable = (e.code === 502 || e.code === 504 || e.code === 0);
        if (attempt < maxRetry && retriable) {
          attempt += 1;
          return delay(400 * Math.pow(2, attempt - 1)).then(fire);   // 400ms → 800ms
        }
        throw e;
      });
    }
    return fire();
  }

  global.api = global.api || {};
  global.api.http = {
    /* 常量也一并暴露：页面要拼 URL / 调超时的时候不用再抄一遍魔数 */
    BASE: BASE, TIMEOUT: TIMEOUT, TRACE_HEAD: TRACE_HEAD, MESSAGES: MESSAGES,
    ApiError: ApiError, request: request,
    get: function (p, o) { return request('GET', p, o); },
    post: function (p, b, o) { return request('POST', p, Object.assign({ body: b }, o)); },
    put: function (p, b, o) { return request('PUT', p, Object.assign({ body: b }, o)); },
    del: function (p, o) { return request('DELETE', p, o); },
    token: { get: getToken, set: setToken },
    useMock: useMock, setMock: function (on) {
      try { on ? localStorage.setItem(MOCK_KEY, '1') : localStorage.removeItem(MOCK_KEY); } catch (e) {}
    },
    describe: describe, buildQuery: buildQuery
  };
})(window);
