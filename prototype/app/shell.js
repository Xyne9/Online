/* ═══════════════════════════════════════════════════════════════════
   智判 OJ · 外壳脚本
   1) 图标 sprite   2) 角色与导航   3) 外壳注入   4) 共享模拟数据
   页面写法：<body data-role="student" data-page="s-problems" data-crumb="题库|全部题目">
             <div id="app"><main class="page" id="page-content"> …页面内容… </main></div>
   注意：本文件必须在 alpine.min.js 之前引入，且两者都加 defer。
   ═══════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  /* ── 1. 图标 ─────────────────────────────────────────────────────
     24×24 网格 / 1.5 描边 / 圆头圆角。不用 emoji 当图标。          */
  var ICONS = {
    logo:      '<path d="M4 6h16M4 12h10M4 18h7"/><circle cx="18" cy="16" r="3.2"/>',
    book:      '<path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10a2 2 0 0 1 2 2v13a2 2 0 0 0-2-2H4z"/><path d="M20 5.5A1.5 1.5 0 0 0 18.5 4H14a2 2 0 0 0-2 2v13a2 2 0 0 1 2-2h6z"/>',
    task:      '<path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><path d="m9 13 2 2 4-4"/>',
    clock:     '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 1.8"/>',
    chart:     '<path d="M4 19V5"/><path d="M4 19h16"/><rect x="7.5" y="11" width="3" height="5" rx=".6"/><rect x="13" y="7.5" width="3" height="8.5" rx=".6"/>',
    gauge:     '<path d="M4.5 17a8.5 8.5 0 1 1 15 0"/><path d="m12 12.5 3.5-3"/><circle cx="12" cy="13.4" r="1.2"/>',
    edit:      '<path d="M4 20h4L19 9a2.1 2.1 0 0 0-3-3L5 17z"/><path d="m14.5 6.5 3 3"/>',
    users:     '<circle cx="9" cy="8" r="3.2"/><path d="M3.5 19.5a5.5 5.5 0 0 1 11 0"/><path d="M16 5.6a3.2 3.2 0 0 1 0 6.2"/><path d="M17.6 14.4a5.5 5.5 0 0 1 3 5.1"/>',
    scan:      '<path d="M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16"/><path d="M4 12h16"/>',
    cpu:       '<rect x="7" y="7" width="10" height="10" rx="1.6"/><path d="M10 3.5v2M14 3.5v2M10 18.5v2M14 18.5v2M3.5 10h2M3.5 14h2M18.5 10h2M18.5 14h2"/>',
    server:    '<rect x="3.5" y="4" width="17" height="6" rx="1.6"/><rect x="3.5" y="14" width="17" height="6" rx="1.6"/><path d="M7 7h.01M7 17h.01"/>',
    plug:      '<path d="M9 3v5M15 3v5"/><path d="M6.5 8h11v3.5a5.5 5.5 0 0 1-11 0z"/><path d="M12 17v4"/>',
    coin:      '<ellipse cx="12" cy="7" rx="7" ry="3"/><path d="M5 7v10c0 1.7 3.1 3 7 3s7-1.3 7-3V7"/><path d="M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>',
    monitor:   '<rect x="3" y="4.5" width="18" height="12" rx="1.8"/><path d="M9 20h6M12 16.5V20"/>',
    home:      '<path d="M4 10.5 12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19z"/><path d="M9.5 20.5v-6h5v6"/>',
    search:    '<circle cx="11" cy="11" r="6.2"/><path d="m16 16 4 4"/>',
    filter:    '<path d="M4 6h16l-6.2 7.2V19l-3.6-2v-3.8z"/>',
    plus:      '<path d="M12 5v14M5 12h14"/>',
    check:     '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    checkC:    '<circle cx="12" cy="12" r="8.5"/><path d="m8.4 12.2 2.6 2.6 4.6-4.8"/>',
    x:         '<path d="M6 6l12 12M18 6 6 18"/>',
    xC:        '<circle cx="12" cy="12" r="8.5"/><path d="m9 9 6 6M15 9l-6 6"/>',
    alert:     '<path d="M12 4.5 20.5 19h-17z"/><path d="M12 10v4M12 16.5h.01"/>',
    info:      '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/>',
    play:      '<path d="M8 5.5v13l11-6.5z"/>',
    send:      '<path d="M20 4 4 10.5l6.5 2.6L13 20z"/><path d="M20 4 10.5 13.1"/>',
    chevD:     '<path d="m6 9.5 6 6 6-6"/>',
    chevR:     '<path d="m9.5 6 6 6-6 6"/>',
    chevL:     '<path d="m14.5 6-6 6 6 6"/>',
    arrowR:    '<path d="M4 12h15M13.5 6.5 20 12l-6.5 5.5"/>',
    arrowL:    '<path d="M20 12H5M10.5 6.5 4 12l6.5 5.5"/>',
    arrowU:    '<path d="M12 20V4M6.5 9.5 12 4l5.5 5.5"/>',
    arrowD:    '<path d="M12 4v16M6.5 14.5 12 20l5.5-5.5"/>',
    more:      '<circle cx="5.5" cy="12" r="1.1"/><circle cx="12" cy="12" r="1.1"/><circle cx="18.5" cy="12" r="1.1"/>',
    refresh:   '<path d="M19 12a7 7 0 1 1-2.1-5"/><path d="M19.5 4.5V9H15"/>',
    download:  '<path d="M12 4v10"/><path d="m8 10.5 4 4 4-4"/><path d="M5 19h14"/>',
    upload:    '<path d="M12 16V5"/><path d="m8 8.5 4-4 4 4"/><path d="M5 19h14"/>',
    copy:      '<rect x="9" y="9" width="10" height="10" rx="1.6"/><path d="M15 6.5A1.5 1.5 0 0 0 13.5 5h-8A1.5 1.5 0 0 0 4 6.5v8A1.5 1.5 0 0 0 5.5 16"/>',
    external:  '<path d="M14 4h6v6"/><path d="M20 4 11 13"/><path d="M18 14v4.5A1.5 1.5 0 0 1 16.5 20h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10"/>',
    settings:  '<circle cx="12" cy="12" r="2.8"/><path d="M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M6 6l1.4 1.4M16.6 16.6 18 18M18 6l-1.4 1.4M7.4 16.6 6 18"/>',
    logout:    '<path d="M9 20H5.5A1.5 1.5 0 0 1 4 18.5v-13A1.5 1.5 0 0 1 5.5 4H9"/><path d="M16 8.5 19.5 12 16 15.5"/><path d="M10 12h9"/>',
    code:      '<path d="m8.5 8-4.5 4 4.5 4M15.5 8l4.5 4-4.5 4"/>',
    spark:     '<path d="M12 4.5 13.6 9l4.4 1.6L13.6 12 12 16.5 10.4 12 6 10.6 10.4 9z"/><path d="M18.5 15.5 19.2 17l1.5.7-1.5.7-.7 1.5-.7-1.5-1.5-.7 1.5-.7z"/>',
    flag:      '<path d="M5 4v16"/><path d="M5 5h11l-2 3.5 2 3.5H5"/>',
    tag:       '<path d="M4 11.5V5.5A1.5 1.5 0 0 1 5.5 4h6L20 12.5 12.5 20z"/><circle cx="8" cy="8" r="1.1"/>',
    file:      '<path d="M13 4H7a1.6 1.6 0 0 0-1.6 1.6v12.8A1.6 1.6 0 0 0 7 20h10a1.6 1.6 0 0 0 1.6-1.6V9.6z"/><path d="M13 4v5.6h5.6"/>',
    layers:    '<path d="m12 3.5 8 4.2-8 4.3-8-4.3z"/><path d="m4 12.5 8 4.2 8-4.2"/><path d="m4 16.8 8 4.2 8-4.2"/>',
    diff:      '<path d="M8 4v16M16 4v16"/><path d="M4 9h3M4 15h3M17 9h3M17 15h3"/>',
    bolt:      '<path d="M13.5 3 5.5 13.5h5.2L10 21l8.2-10.7H13z"/>',
    lock:      '<rect x="5" y="10.5" width="14" height="9.5" rx="1.8"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>',
    unlock:    '<rect x="5" y="10.5" width="14" height="9.5" rx="1.8"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2"/>',
    bell:      '<path d="M18 16.5V11a6 6 0 1 0-12 0v5.5L4.5 18.5h15z"/><path d="M10 21h4"/>',
    eye:       '<path d="M2.5 12S6 6 12 6s9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z"/><circle cx="12" cy="12" r="2.6"/>',
    trash:     '<path d="M4.5 7h15"/><path d="M9 7V5.2A1.2 1.2 0 0 1 10.2 4h3.6A1.2 1.2 0 0 1 15 5.2V7"/><path d="M6.5 7v11.5A1.5 1.5 0 0 0 8 20h8a1.5 1.5 0 0 0 1.5-1.5V7"/>',
    sort:      '<path d="M8 5v14M8 5 5 8M8 5l3 3"/><path d="M16 19V5M16 19l3-3M16 19l-3-3"/>',
    grid:      '<rect x="4" y="4" width="7" height="7" rx="1.2"/><rect x="13" y="4" width="7" height="7" rx="1.2"/><rect x="4" y="13" width="7" height="7" rx="1.2"/><rect x="13" y="13" width="7" height="7" rx="1.2"/>',
    calendar:  '<rect x="3.5" y="5.5" width="17" height="15" rx="1.8"/><path d="M3.5 10h17M8 3.5V7M16 3.5V7"/>',
    award:     '<circle cx="12" cy="9.5" r="5"/><path d="m8.6 13.6-1.1 6.4L12 17.6l4.5 2.4-1.1-6.4"/>',
    target:    '<circle cx="12" cy="12" r="8.2"/><circle cx="12" cy="12" r="4.4"/><circle cx="12" cy="12" r=".9"/>',
    trend:     '<path d="M4 16.5 9.5 11l3.5 3.5L20 7.5"/><path d="M20 7.5h-4.5M20 7.5V12"/>',
    stop:      '<rect x="6.5" y="6.5" width="11" height="11" rx="1.6"/>',
    pause:     '<path d="M9 5.5v13M15 5.5v13"/>',
    expand:    '<path d="M9 4.5H5.5A1 1 0 0 0 4.5 5.5V9M15 4.5h3.5a1 1 0 0 1 1 1V9M9 19.5H5.5a1 1 0 0 1-1-1V15M15 19.5h3.5a1 1 0 0 0 1-1V15"/>',
    user:      '<circle cx="12" cy="8" r="3.4"/><path d="M5 20a7 7 0 0 1 14 0"/>',
    key:       '<circle cx="8" cy="14" r="3.4"/><path d="m10.6 11.4 8.4-8.4M16 5l2.6 2.6M13.6 7.4 16 9.8"/>',
    folder:    '<path d="M4 7.5A1.5 1.5 0 0 1 5.5 6h3.8l1.6 2h7.6A1.5 1.5 0 0 1 20 9.5v8A1.5 1.5 0 0 1 18.5 19h-13A1.5 1.5 0 0 1 4 17.5z"/>',
    kbd:       '<rect x="2.5" y="6" width="19" height="12" rx="2"/><path d="M6.2 10h.01M9.6 10h.01M13 10h.01M16.4 10h.01M6.2 13.6h.01M9.6 13.6h4.8M16.4 13.6h.01"/>',
    shield:    '<path d="M12 3.5 19 6v6c0 4.2-3 7.2-7 8.5-4-1.3-7-4.3-7-8.5V6z"/><path d="m9 12 2 2 4-4"/>'
  };

  function svg(name, cls) {
    var d = ICONS[name] || ICONS.info;
    return '<svg class="ic ' + (cls || '') + '" viewBox="0 0 24 24" aria-hidden="true">' + d + '</svg>';
  }

  /* ── 2. 角色与导航 ──────────────────────────────────────────────── */
  var ROLES = {
    student: { name: '学生端', user: '林昭', avatar: '林', sub: '计算机 2022 级 · 1 班', home: 's-problems.html' },
    teacher: { name: '教师端', user: '周明远', avatar: '周', sub: '数据结构 · 主讲教师', home: 't-dashboard.html' },
    admin:   { name: '管理端', user: '运维管理员', avatar: '运', sub: '系统与模型运维', home: 'a-models.html' }
  };

  var NAV = {
    student: [
      { title: '学习', items: [
        { id: 's-problems',    label: '题库',      icon: 'book',    href: 's-problems.html' },
        { id: 's-assignments', label: '我的作业',  icon: 'task',    href: 's-assignments.html', badge: '3' },
        { id: 's-submissions', label: '提交记录',  icon: 'clock',   href: 's-submissions.html' },
        { id: 's-analytics',   label: '学情分析',  icon: 'chart',   href: 's-analytics.html' }
      ]},
      { title: '正在做', items: [
        { id: 's-workbench',   label: '做题工作台', icon: 'code',   href: 's-workbench.html' }
      ]}
    ],
    teacher: [
      { title: '教学', items: [
        { id: 't-dashboard',    label: '教师概览', icon: 'gauge',  href: 't-dashboard.html' },
        { id: 't-problems',     label: '题目管理', icon: 'book',   href: 't-problems.html' },
        { id: 't-assignments',  label: '教学活动', icon: 'task',   href: 't-assignments.html', badge: '2' },
        { id: 't-analytics',    label: '班级学情', icon: 'chart',  href: 't-analytics.html' }
      ]},
      { title: '监控与审查', items: [
        { id: 't-monitor',      label: '考试监考', icon: 'monitor', href: 't-monitor.html' },
        { id: 't-plagiarism',   label: '查重报告', icon: 'scan',    href: 't-plagiarism.html', badge: '5' }
      ]}
    ],
    admin: [
      { title: 'AI 平台', items: [
        { id: 'a-models',  label: '模型通道', icon: 'plug', href: 'a-models.html' },
        { id: 'a-audit',   label: '调用审计', icon: 'coin', href: 'a-audit.html' }
      ]},
      { title: '基础设施', items: [
        { id: 'a-sandbox', label: '沙箱监控', icon: 'cpu',  href: 'a-audit.html#sandbox' }
      ]}
    ]
  };

  /* data-page 是"页面身份"，用于埋点与角色守卫；侧栏高亮认的是"导航项 id"。
     二者在 PRD v2.0 微创修正 #2 之后不再一一对应（s-exam 原为 s-assignments、
     t-problem-edit 原为 t-problems 的错值，修正后变成了独立页面），
     所以这里补一张别名表，让这两个子页面继续高亮其所属的父级导航项。
     只影响高亮，不影响 data-page 的值。 */
  var NAV_ALIAS = {
    's-exam': 's-assignments',
    't-problem-edit': 't-problems'
  };

  function buildSide(role, page) {
    var r = ROLES[role] || ROLES.student;
    var groups = NAV[role] || NAV.student;
    page = NAV_ALIAS[page] || page;

    /* 身份三行优先读登录态（boot.js 写的 OJ_BOOT.user），
       没有就回退到 ROLES 的演示身份——保证 ?mock=1 与未登录预览都不断裂。
       DOM 结构与 class 一个都不动，只换文案来源。 */
    var u = (window.OJ_BOOT && window.OJ_BOOT.user) || null;
    var uName = (u && (u.realName || u.name)) || r.user;
    var uAvatar = (u && (u.avatarText || String(uName).charAt(0))) || r.avatar;
    var uSub = (u && (u.subtitle || u.className || u.roleLabel)) || r.sub;

    var h = '<a class="brand" href="' + r.home + '">' +
      '<span class="brand-mark">' + svg('logo') + '</span>' +
      '<span class="brand-txt"><span class="brand-name">智判 OJ</span>' +
      '<span class="brand-sub">启发式智能判题</span></span></a>';

    h += '<nav class="side-scroll" aria-label="主导航">';
    groups.forEach(function (g) {
      h += '<div class="nav-group"><div class="nav-title">' + g.title + '</div>';
      g.items.forEach(function (it) {
        var on = it.id === page ? ' on' : '';
        h += '<a class="nav-item' + on + '" href="' + it.href + '"' + (on ? ' aria-current="page"' : '') + '>' +
             svg(it.icon) + '<span>' + it.label + '</span>' +
             (it.badge ? '<span class="badge ' + (on ? 'blue' : 'gray') + '">' + it.badge + '</span>' : '') +
             '</a>';
      });
      h += '</div>';
    });
    h += '</nav>';

    h += '<div class="side-foot">';
    h += '<div class="role-switch" role="tablist" aria-label="切换角色">';
    ['student', 'teacher', 'admin'].forEach(function (k) {
      var on = k === role;
      h += '<a class="' + (on ? 'on' : '') + '" href="' + ROLES[k].home + '"' +
           (on ? ' aria-current="true"' : '') + '>' + ROLES[k].name + '</a>';
    });
    h += '</div>';
    h += '<button class="user-card" type="button">' +
         '<span class="avatar">' + uAvatar + '</span>' +
         '<span class="user-meta"><span class="user-name">' + uName + '</span>' +
         '<span class="user-role">' + uSub + '</span></span>' +
         svg('chevD', 'sm') + '</button>';
    h += '</div>';
    return h;
  }

  function buildTop(crumb) {
    var parts = (crumb || '').split('|').filter(Boolean);
    var h = '<nav class="crumbs" aria-label="面包屑">';
    parts.forEach(function (p, i) {
      if (i) h += '<span class="sep">/</span>';
      h += '<span class="' + (i === parts.length - 1 ? 'cur' : '') + '">' + p + '</span>';
    });
    h += '</nav><span class="grow"></span>';
    h += '<label class="tb-search">' + svg('search', 'sm') +
         '<input type="search" placeholder="搜索题目、学号或提交号" aria-label="全局搜索"></label>';
    h += '<button class="tb-btn" type="button" title="通知" aria-label="通知">' + svg('bell') +
         '<span class="badge">2</span></button>';
    h += '<div class="tb-help">' +
         '<button class="tb-btn" type="button" title="快捷键" aria-label="快捷键" ' +
         'aria-haspopup="dialog" aria-expanded="false" data-kbd-btn>' + svg('kbd') + '</button>' +
         '<div class="kbdpop" role="dialog" aria-label="快捷键" hidden data-kbd-pop></div>' +
         '</div>';
    return h;
  }

  /* ── 2.5 快捷键面板 ──────────────────────────────────────────────
     顶栏原来那个 ⓘ 没有任何用途，换成真正的快捷键入口。面板里只列
     「按下真的会发生什么」的组合，不做装饰性清单。                  */
  var KBD_GLOBAL = [
    ['打开全局搜索', ['/']],
    ['打开这张快捷键表', ['?']],
    ['关闭面板或弹层', ['Esc']]
  ];
  var KBD_WORKBENCH = [
    ['运行样例', ['Ctrl', "'"]],
    ['提交判题', ['Ctrl', 'Enter']]
  ];

  function kbdRows(page) {
    var rows = KBD_GLOBAL.slice();
    if (page === 's-workbench') rows = KBD_WORKBENCH.concat(rows);
    return rows.map(function (r) {
      var keys = r[1].map(function (k) { return '<b>' + k + '</b>'; }).join('<i>+</i>');
      return '<div class="kbd-row"><span>' + r[0] + '</span><span class="kbd">' + keys + '</span></div>';
    }).join('');
  }

  function wireKbd(top, page) {
    var btn = top.querySelector('[data-kbd-btn]');
    var pop = top.querySelector('[data-kbd-pop]');
    if (!btn || !pop) return;
    pop.tabIndex = -1;
    pop.innerHTML = '<div class="kbdpop-h"><b>快捷键</b>' +
      '<button class="tb-btn sm" type="button" aria-label="关闭" data-kbd-x>' + svg('x', 'sm') + '</button></div>' +
      kbdRows(page);

    function open(v) {
      pop.hidden = !v;
      btn.setAttribute('aria-expanded', v ? 'true' : 'false');
      // 聚焦面板本身而不是关闭按钮：焦点进得来，又不会在头上顶一圈蓝框
      if (v) pop.focus();
    }
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      open(pop.hidden);
    });
    pop.addEventListener('click', function (e) { e.stopPropagation(); });
    var x = pop.querySelector('[data-kbd-x]');
    if (x) x.addEventListener('click', function () { open(false); btn.focus(); });
    document.addEventListener('click', function () { if (!pop.hidden) open(false); });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !pop.hidden) { open(false); btn.focus(); return; }
      var t = e.target;
      var typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' ||
        t.tagName === 'SELECT' || t.isContentEditable);
      if (e.key === '/' && !typing && !e.ctrlKey && !e.metaKey && !e.altKey) {
        var s = top.querySelector('.tb-search input');
        if (s) { e.preventDefault(); s.focus(); s.select(); }
        return;
      }
      if (e.key === '?' && !typing && !e.ctrlKey && !e.metaKey && !e.altKey) {
        e.preventDefault();
        open(true);
      }
    });

    /* 遮罩上的 Esc：全站抽屉/弹窗都靠点 .scrim 关闭，键盘用户此前无路可退。
       这里替他们点一下那个遮罩，其余逻辑一概不动（页面自己的 @click 仍然唯一事实源）。 */
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape' || !pop.hidden) return;
      var scrims = document.querySelectorAll('.scrim');
      if (!scrims.length) return;
      var top1 = scrims[scrims.length - 1];
      var r = top1.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return;   // 被 x-show/x-if 藏住的跳过
      top1.click();
    });
  }

  /* ── 3. 外壳注入 ────────────────────────────────────────────────── */
  function mount() {
    var host = document.getElementById('app');
    if (!host || host.dataset.mounted) return;
    host.dataset.mounted = '1';

    var body = document.body;
    var role = body.dataset.role || 'student';
    var page = body.dataset.page || '';
    var crumb = body.dataset.crumb || '';

    // 页面内容先摘下来，注入后再放回主区，避免被当作兄弟节点
    var content = document.getElementById('page-content');

    // 登录页只呈现未登录入口，避免挂载带有演示身份和通知的业务外壳。
    if (page === 'login') return;

    var side = document.createElement('aside');
    side.className = 'side';
    side.innerHTML = buildSide(role, page);

    var main = document.createElement('div');
    main.className = 'main';

    var top = document.createElement('header');
    top.className = 'topbar';
    top.innerHTML = buildTop(crumb);

    main.appendChild(top);
    wireKbd(top, page);
    if (content) {
      if (!content.classList.contains('page')) content.classList.add('page');
      main.appendChild(content);
    }

    host.innerHTML = '';
    host.appendChild(side);
    host.appendChild(main);

    // 当前角色标签不可点
    side.querySelectorAll('.role-switch a.on').forEach(function (a) {
      a.addEventListener('click', function (e) { e.preventDefault(); });
    });
  }

  /* ── 3.5 进场动画去重 ───────────────────────────────────────────
     tokens.css 的 @view-transition 已经让内容区整体淡入一次（180ms）。
     如果这时页面里 rise 又跑一遍，两层透明度相乘，用户看到的是
     「先白一下再慢慢出现」。所以凡是跨文档过渡进来的，就标记 .vt-in，
     components.css 据此关掉 rise。首次打开、浏览器不支持过渡、
     用户开了减弱动效，都不会有 viewTransition，rise 照常生效。      */
  if (window.addEventListener) {
    window.addEventListener('pagereveal', function (e) {
      if (e.viewTransition) document.documentElement.classList.add('vt-in');
    });
  }

  /* ── 4. 共享模拟数据（已迁至 api/mock.js） ───────────────────────
     原来这里的 8 组常量（PROBLEMS / SUBMISSIONS / ASSIGNMENTS / STUDENTS /
     MODELS / AI_CALLS / SANDBOX / PLAGIARISM，共 135 行）整块搬进了
     api/mock.js —— 全站唯一数据源就在那边，MOCK 与真实走同一条 adapter。
     下面的 OJ 对象用惰性 getter 代理过去：14 个页面里现有的
     `OJ.problems` / `OJ.submissions` … 引用一行都不用改，依旧拿得到数据。
     注意：mock.js 必须在 shell.js 之后加载（见 boot 加载顺序），
     所以这里只能在「读取的那一刻」取值，不能提前缓存。 */

  /* ── 5. 工具函数 ────────────────────────────────────────────────── */
  /* 是否处于 ?mock=1 演示态。这里不直接调 api.http.useMock()：
     shell.js 比 api/http.js 先加载，且 login.html 之前也可能没有 api。
     三个入口与 api/http.js:useMock() 完全一致（地址栏 / sessionStorage / localStorage）。 */
  function isMock() {
    try {
      if (/[?&]mock=(1|true)\b/.test(String(location.search || ''))) return true;
      return localStorage.getItem('OJ_USE_MOCK') === '1' || sessionStorage.getItem('OJ_USE_MOCK') === '1';
    } catch (e) { return false; }
  }

  /* ── 真实数据槽位（Day 4 起） ────────────────────────────────────
     页面从 api.http 拿到真实数据后，**写这里**，不写自己的闭包变量：
        this.OJ._problems = res.list
     getter 读槽位 → Alpine 的 reactive 代理能追踪到赋值 → 模板自动重渲染。
     若页面用闭包变量存，Alpine 追踪不到，页面会永远停在空态——这是
     Day 4 真连时最容易踩、也最难查的一个坑。

     null  = 还没加载（getter 返回 []，页面走已有空态）
     [...] = 已加载（返回真实数据）
     ?mock=1 时**完全不看槽位**，永远返回 fixtures，保证演示与真实彻底隔离。 */
  var DATA = {
    problems: null, submissions: null, assignments: null, students: null,
    models: null, aiCalls: null, sandbox: null, plagiarism: null
  };

  /* 取数：mock 态读 fixtures，真实态读槽位（未加载给空数组） */
  function slot(key, fixtureKey) {
    if (isMock()) return (window.OJMock && window.OJMock.fixtures[fixtureKey]) || [];
    return DATA[key] || [];
  }

  var OJ = {
    svg: svg, icons: ICONS, roles: ROLES, nav: NAV,

    /* 惰性代理：写成 getter 而不是赋值，因为 shell.js 比 api/mock.js 先执行。

       ⚠️ **必须只在 ?mock=1 时返回假数据**。
       原实现无条件返回 fixtures，导致「不带 mock、已登录」时页面照常渲染
       同一份假数据——真实态根本不存在，演示时把参数删掉也看不出区别，
       违反 03 指南 §8.2「mock 与真实不混用」。
       现在非 mock 态读 DATA 槽位，未加载时返回空数组，页面走各自已有的空态
       （_SPEC 要求每页必须写空态）。视觉零回归不受影响——基线比对全部在
       ?mock=1 下进行。 */
    get problems()    { return slot('problems',    'problems'); },
    get submissions() { return slot('submissions', 'submissions'); },
    get assignments() { return slot('assignments', 'assignments'); },
    get students()    { return slot('students',    'students'); },
    get models()      { return slot('models',      'models'); },
    get aiCalls()     { return slot('aiCalls',     'aiCalls'); },
    get sandbox()     { return slot('sandbox',     'sandbox'); },
    get plagiarism()  { return slot('plagiarism',  'plagiarism'); },

    /* 可写槽位：页面赋值走这里。写成显式 setter 而不是直接暴露 DATA，
       是为了在控制台能一眼看到「谁在什么时候写了什么」——调试真实态时
       加个断点就有 stack。 */
    set _problems(v)    { DATA.problems = v; },
    set _submissions(v) { DATA.submissions = v; },
    set _assignments(v) { DATA.assignments = v; },
    set _students(v)    { DATA.students = v; },
    set _models(v)      { DATA.models = v; },
    set _aiCalls(v)     { DATA.aiCalls = v; },
    set _sandbox(v)     { DATA.sandbox = v; },
    set _plagiarism(v)  { DATA.plagiarism = v; },

    /* 附加统计（题库四格 / 提交四格）。单独放一个对象，页面按需读，
       不与列表混在一起——列表分页后 stats 仍是全量口径。 */
    stats: {},
    /* 分页元信息（hasNext / total），页面翻页按钮用 */
    page: { total: 0, page: 1, pageSize: 20, hasNext: false },
    /* 当前登录用户（boot.js 已注入，这里给个稳定引用，避免页面各自读 localStorage） */
    me: null,

    /* 暴露给页面：判断当前是不是演示态，用于渲染「演示数据」角标或空态文案 */
    isMock: isMock,

    statusName: function (s) {
      return { ac: 'AC', wa: 'WA', tle: 'TLE', mle: 'MLE', re: 'RE', ce: 'CE',
               pac: 'PAC', pending: '判题中', none: '未提交', run: '运行中', idle: '空闲' }[s] || s;
    },
    statusTag: function (s, extra) {
      return '<span class="tag ' + s + (extra ? ' ' + extra : '') + '">' + OJ.statusName(s) + '</span>';
    },
    lvlName: function (n) { return ['', '入门', '简单', '中等', '较难', '困难'][n] || '简单'; },
    /* 难度＝5 格实心刻度，统一中性色。颜色只留给判题结果，
       避免「简单」蓝撞主色、「较难」红撞 WA、「困难」紫撞 MLE。 */
    lvl: function (n) {
      var bars = '';
      for (var i = 1; i <= 5; i++) bars += '<i' + (i <= n ? ' class="on"' : '') + '></i>';
      return '<span class="lvl" data-l="' + n + '" title="难度 ' + n + ' / 5"><b>' + OJ.lvlName(n) + '</b>' +
             '<span class="lvl-bars" aria-hidden="true">' + bars + '</span></span>';
    },
    /* 轻提示：OJ.toast('已发布', { undo: fn, type: 'ok'|'err'|'warn' })
       带撤销时停留 5 秒，否则 2.6 秒；退场有动画，不是瞬间消失。 */
    toast: function (msg, opt) {
      opt = opt || {};
      var old = document.querySelector('.toast[data-oj]');
      if (old) old.remove();
      var el = document.createElement('div');
      el.className = 'toast' + (opt.type && opt.type !== 'ok' ? ' ' + opt.type : '');
      el.setAttribute('data-oj', '');
      el.setAttribute('role', 'status');
      var icon = opt.type === 'err' ? 'xC' : opt.type === 'warn' ? 'alert' : 'checkC';
      el.innerHTML = svg(icon) + '<span></span>';
      el.querySelector('span').textContent = msg;
      var close = function () {
        if (!el.isConnected || el.classList.contains('out')) return;
        el.classList.add('out');
        setTimeout(function () { el.remove(); }, 160);
      };
      if (opt.undo) {
        var b = document.createElement('button');
        b.className = 'toast-undo';
        b.textContent = '撤销';
        b.onclick = function () { opt.undo(); close(); };
        el.appendChild(b);
      }
      document.body.appendChild(el);
      setTimeout(close, opt.undo ? 5000 : 2600);
      return close;
    },
    reduced: function () { return matchMedia('(prefers-reduced-motion: reduce)').matches; },
    fmt: function (n) { return n >= 10000 ? (n / 1000).toFixed(1) + 'K' : String(n); },
    qs: function (k, d) {
      var m = new RegExp('[?&]' + k + '=([^&#]*)').exec(location.search);
      return m ? decodeURIComponent(m[1]) : (d === undefined ? '' : d);
    }
  };

  window.OJ = OJ;

  /* ── 6. Alpine 指令 ─────────────────────────────────────────────── */
  document.addEventListener('alpine:init', function () {
    if (!window.Alpine) return;
    // x-ic="'search'"  →  <svg class="ic">…</svg>
    // x-ic.lg="'x'"    →  <svg class="ic lg">…</svg>
    window.Alpine.directive('ic', function (el, binding, { evaluateLater, effect }) {
      var get = evaluateLater(binding.expression);
      effect(function () {
        get(function (name) {
          if (!name) { el.innerHTML = ''; return; }
          var cls = binding.modifiers.join(' ');
          el.innerHTML = svg(name, cls);
        });
      });
    });

    // x-count="62"  /  x-count="4.2"  →  数字从 0（或上一次的值）滚到目标值。
    // 480ms ease-out；小数位数跟随目标值；减弱动效时直接显示终值。
    // 只用于「关键数字」（Hero、统计带），不要用在表格里。
    window.Alpine.directive('count', function (el, binding, { evaluateLater, effect }) {
      var get = evaluateLater(binding.expression);
      var shown = 0, raf = 0;
      effect(function () {
        get(function (target) {
          target = Number(target) || 0;
          var dec = (String(target).split('.')[1] || '').length;
          var from = shown, t0 = 0, dur = 480;
          cancelAnimationFrame(raf);
          if (OJ.reduced() || from === target) { el.textContent = target.toFixed(dec); shown = target; return; }
          var step = function (t) {
            if (!t0) t0 = t;
            var p = Math.min(1, (t - t0) / dur);
            var e = 1 - Math.pow(1 - p, 3);
            shown = from + (target - from) * e;
            el.textContent = shown.toFixed(dec);
            if (p < 1) raf = requestAnimationFrame(step); else shown = target;
          };
          raf = requestAnimationFrame(step);
        });
      });
    });
  });

  // 本文件在 </body> 前同步执行：#app 与 #page-content 已存在，立即挂载，
  // 保证随后 defer 加载的 Alpine 看到的是完整 DOM。
  if (document.getElementById('app')) {
    mount();
  } else if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
})();
