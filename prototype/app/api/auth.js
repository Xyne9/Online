/* api/auth.js —— 登录/登出/回跳目标 三个业务封装
   只做「契约 → 本地存储」的翻译：http.js 管请求，login.html / boot.js 管界面，
   这里把 02-契约补全规范 §2.2 的大写角色与小写 OJ_ROLE 的差异吃掉，页面侧就不用各写一遍。 */
(function (global) {
  'use strict';

  var ROLE_HOME = {
    student: 's-problems.html',
    teacher: 't-dashboard.html',
    admin: 'a-models.html'
  };
  var DEFAULT_HOME = 's-problems.html';

  function readRole() {
    try { return (localStorage.getItem('OJ_ROLE') || '').toLowerCase(); } catch (e) { return ''; }
  }
  function homeFor(role) { return ROLE_HOME[String(role || '').toLowerCase()] || DEFAULT_HOME; }

  /* 开放重定向校验：?redirect= 来自地址栏，攻击者可塞 //evil.com 或 javascript: 诱导钓鱼。
     只放行站内相对路径（含以单个 / 开头的绝对路径），其余一律回落角色首页。 */
  function safeRedirect(raw) {
    if (!raw) return '';
    var p = String(raw);
    try { p = decodeURIComponent(p); } catch (e) { return ''; }
    /* 浏览器解析 URL 前会剔除制表/换行，攻击者常借 "java\tscript:" 绕过前缀匹配，先清掉 */
    p = p.replace(/[\u0000-\u001F\u007F]/g, '').trim();
    if (!p) return '';
    if (p.indexOf('\\') !== -1) return '';                      // 反斜杠在部分浏览器等价于 '/'
    if (/^[a-zA-Z][a-zA-Z0-9+.\-]*:/.test(p)) return '';        // http: / https: / javascript: / data:
    if (p.charAt(0) === '/' && p.charAt(1) === '/') return '';  // 协议相对 //evil.com
    if ((p.split('/').pop() || '').split('?')[0] === 'login.html') return '';  // 别跳回登录页自循环
    return p;
  }

  function redirectTarget() {
    var q = '';
    try { q = new URLSearchParams(location.search).get('redirect') || ''; } catch (e) { q = ''; }
    return safeRedirect(q) || homeFor(readRole());
  }

  function login(account, password) {
    return global.api.http.post('/api/auth/login', { account: account, password: password })
      .then(function (data) {
        var user = (data && data.user) || {};
        var role = String(user.role || '').toLowerCase();        // 契约给 STUDENT，前端全站用小写
        var token = (data && (data.accessToken || data.token)) || '';
        var name = user.realName || user.username || account || '';
        var displayNo = user.username || account || '';
        var homePath = (data && data.homePath) || homeFor(role);

        global.api.http.token.set(token);
        try {
          localStorage.setItem('OJ_ROLE', role);
          localStorage.setItem('OJ_USER', JSON.stringify({ name: name, no: displayNo }));
        } catch (e) {}                                            // 隐私模式下写不进去也不该让登录失败

        return { token: token, role: role, name: name, displayNo: displayNo, homePath: homePath };
      });
  }

  function logout() {
    global.api.http.token.set('');
    try {
      localStorage.removeItem('OJ_ROLE');
      localStorage.removeItem('OJ_USER');
    } catch (e) {}
    location.replace('login.html');                                // 不带 redirect：登出后不该回到原页
  }

  global.api = global.api || {};
  global.api.auth = {
    login: login,
    logout: logout,
    redirectTarget: redirectTarget
  };
})(window);
