/* boot.js —— 鉴权守卫 + 身份注入 + MOCK 跨页透传
   ─────────────────────────────────────────────────────────────────────
   对齐 02-契约补全规范 §2.2 的 accessToken / user / homePath 结构，
   与 04-终极完整设计文档 §7.2（第二版）一致。
   加载位置：所有 api/ 与 adapter/ 之后、vendor/alpine.min.js（defer）之前。
   关键点：守卫是「跳转」而不是「隐藏页面」——15 页的 DOM 结构、class、
   初始渲染时序全部不受影响。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  var WHITE = ['login.html', 'index.html'];                 // 门户与登录页免登录
  var page = (location.pathname.split('/').pop() || 'index.html');

  /* ── 程序化跳转前掐掉跨文档 View Transition ────────────────────────
     tokens.css:192 有 @view-transition{navigation:auto}，本意是让链接点击
     淡入淡出。但 location.replace() 这种脚本跳转会让浏览器在过渡还没开始
     就切文档，Chrome 直接抛
       "Transition was aborted because of invalid state. ViewTransition opt-in disabled"
     —— 一次跳转刷一条 pageerror，验收时看着像我们写崩了。
     所以只在「确认要跳走」的分支里把那条 @ 规则删掉：页面本来就要离开，
     这段动画谁也看不到，删了只有好处。链接点击（<a>）仍走浏览器原生过渡，
     不受影响。读不到的样式表（跨域）直接跳过，非 Chromium 天然无此规则。 */
  function dropViewTransition() {
    var sheets = document.styleSheets || [];
    for (var i = 0; i < sheets.length; i++) {
      var rules = null;
      try { rules = sheets[i].cssRules; } catch (e) { continue; }
      if (!rules) continue;
      for (var j = rules.length - 1; j >= 0; j--) {
        var r = rules[j];
        var nm = (r && r.constructor && r.constructor.name) || '';
        if (nm === 'CSSViewTransitionRule') {
          try { sheets[i].deleteRule(j); } catch (e2) {}
        }
      }
    }
  }

  /* 全站统一的脚本跳转入口：先掐过渡，再 replace。
     暴露成 OJ_GO，login.html 登录成功后也走它。 */
  function go(url) {
    dropViewTransition();
    /* 强制一次样式重算，保证删掉的规则在导航发起前已经生效 */
    try { void document.documentElement.offsetHeight; } catch (e) {}
    location.replace(url);
  }
  global.OJ_GO = go;

  /* ?mock=1 只在当前 URL 生效，页面间跳转会丢 → 写进 sessionStorage 让全站都认 */
  if (/[?&]mock=(1|true)\b/.test(location.search)) {
    try { sessionStorage.setItem('OJ_USE_MOCK', '1'); } catch (e) {}
  }

  function guard() {
    /* 演示模式：自动发一枚假 token。
       角色取值优先级：?role= 显式指定 > 当前页 body[data-role] > student。
       —— 必须优先用页面自己的 data-role：否则从 t-dashboard.html?mock=1
       直接打开时，OJ_ROLE 会被写成 student，紧接着下面的角色校验
       立刻把教师页踢回 s-problems.html，16 页就再也不是"原版一模一样的
       16 页"了（这是视觉零回归红线，也是演示时最刺眼的 bug）。
       ?role= 仍保留，用于在同一角色外壳里跨角色演示。 */
    if (api.http.useMock()) {
      if (!api.http.token.get()) api.http.token.set('mock-token');
      var br = (document.body && document.body.getAttribute('data-role')) || '';
      var dflt = (br && br !== 'guest') ? br : 'student';
      try {
        localStorage.setItem('OJ_ROLE', (global.OJ && OJ.qs('role', dflt)) || dflt);
      } catch (e) {}
      return true;
    }
    if (WHITE.indexOf(page) !== -1) return true;
    if (!api.http.token.get()) {
      go('login.html?redirect=' + encodeURIComponent(page + location.search));
      return false;
    }
    return true;
  }
  if (!guard()) return;                                      // 已跳转，后面什么都不做

  /* 角色校验：body[data-role] 与登录角色不符时纠正到该角色的首页。
     guest（登录页）不参与纠偏。 */
  var want = document.body && document.body.getAttribute('data-role');
  var has = (localStorage.getItem('OJ_ROLE') || '').toLowerCase();
  if (want && has && want !== has && want !== 'guest') {
    var home = { student: 's-problems.html', teacher: 't-dashboard.html', admin: 'a-models.html' }[has];
    /* 只在「页面自己也是登录态角色」时才纠偏：mock 演示已按本页 data-role
       写过 OJ_ROLE，正常不会走到这里；真实的跨角色越权仍会被踢回首页。 */
    if (home && home !== page) { go(home); return; }
  }

  /* 登录成功后把真实身份写进 .user-card（不新增节点，只改 shell.js 模板的入参） */
  var user = null;
  try { user = JSON.parse(localStorage.getItem('OJ_USER') || 'null'); } catch (e) { user = null; }
  global.OJ_BOOT = { user: user, role: has || want };

  /* ── MOCK 链接透传：捕获阶段给站内 <a> 补 mock=1 ──────────────────
     演示时点导航不会掉回真实接口。跳过锚点 / 外链 / mailto / 已带 mock=1 的。 */
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest && ev.target.closest('a[href]');
    if (!a || !api.http.useMock()) return;
    var href = a.getAttribute('href');
    if (href === null || /^(#|https?:|mailto:|javascript:)/i.test(href) || href.indexOf('mock=1') !== -1) return;
    a.setAttribute('href', href + (href.indexOf('?') === -1 ? '?' : '&') + 'mock=1');
  }, true);

  /* ── 卸载前通知页面组件释放（SSE / Poller / 定时器） ──────────────
     不做的话切题后旧流还会继续往旧气泡吐字。 */
  window.addEventListener('beforeunload', function () {
    var root = document.getElementById('page-content');
    var data = root && window.Alpine && Alpine.$data ? Alpine.$data(root) : null;
    if (data && typeof data.destroy === 'function') data.destroy();
  });
})(window);
