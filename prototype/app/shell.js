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

  function buildSide(role, page) {
    var r = ROLES[role] || ROLES.student;
    var groups = NAV[role] || NAV.student;
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
         '<span class="avatar">' + r.avatar + '</span>' +
         '<span class="user-meta"><span class="user-name">' + r.user + '</span>' +
         '<span class="user-role">' + r.sub + '</span></span>' +
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
    h += '<button class="tb-btn" type="button" title="帮助" aria-label="帮助">' + svg('info') + '</button>';
    return h;
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

    var side = document.createElement('aside');
    side.className = 'side';
    side.innerHTML = buildSide(role, page);

    var main = document.createElement('div');
    main.className = 'main';

    var top = document.createElement('header');
    top.className = 'topbar';
    top.innerHTML = buildTop(crumb);

    main.appendChild(top);
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

  /* ── 4. 共享模拟数据 ────────────────────────────────────────────── */
  var PROBLEMS = [
    { id: 1024, title: '最大子段和', lvl: 2, tags: ['动态规划', '前缀和'], ac: 62, tried: 1841,
      status: 'wa', score: 80 },
    { id: 1001, title: '两数之和', lvl: 1, tags: ['哈希表', '数组'], ac: 88, tried: 3210,
      status: 'ac', score: 100 },
    { id: 1017, title: '二叉树层序遍历', lvl: 2, tags: ['树', '广度优先'], ac: 74, tried: 1502,
      status: 'ac', score: 100 },
    { id: 1032, title: '最短路径（Dijkstra）', lvl: 3, tags: ['图', '最短路', '堆'], ac: 41, tried: 986,
      status: 'none', score: 0 },
    { id: 1045, title: '区间合并', lvl: 2, tags: ['排序', '贪心'], ac: 67, tried: 1233,
      status: 'none', score: 0 },
    { id: 1058, title: '背包问题（01）', lvl: 3, tags: ['动态规划'], ac: 55, tried: 1740,
      status: 'tle', score: 60 },
    { id: 1063, title: '并查集 · 朋友圈', lvl: 3, tags: ['并查集', '图'], ac: 48, tried: 812,
      status: 'none', score: 0 },
    { id: 1071, title: '滑动窗口最大值', lvl: 3, tags: ['单调队列', '滑动窗口'], ac: 39, tried: 640,
      status: 'none', score: 0 },
    { id: 1088, title: '字符串匹配（KMP）', lvl: 4, tags: ['字符串', 'KMP'], ac: 27, tried: 508,
      status: 'none', score: 0 },
    { id: 1092, title: '拓扑排序 · 课程表', lvl: 3, tags: ['图', '拓扑排序'], ac: 52, tried: 733,
      status: 'none', score: 0 },
    { id: 1105, title: '最长递增子序列', lvl: 3, tags: ['动态规划', '二分'], ac: 44, tried: 901,
      status: 're', score: 0 },
    { id: 1118, title: '回文串分割', lvl: 4, tags: ['动态规划', '字符串'], ac: 31, tried: 412,
      status: 'none', score: 0 }
  ];

  var SUBMISSIONS = [
    { id: 'S-20481', pid: 1024, title: '最大子段和', lang: 'C++ 17', status: 'wa', score: 80,
      pass: '8/10', time: '12ms', mem: '4096KB', at: '10-24 23:41', first: 3 },
    { id: 'S-20477', pid: 1024, title: '最大子段和', lang: 'C++ 17', status: 'wa', score: 70,
      pass: '7/10', time: '9ms', mem: '3902KB', at: '10-24 23:18', first: 3 },
    { id: 'S-20466', pid: 1024, title: '最大子段和', lang: 'C++ 17', status: 'tle', score: 40,
      pass: '4/10', time: '1024ms', mem: '4108KB', at: '10-24 22:52', first: 7 },
    { id: 'S-20440', pid: 1001, title: '两数之和', lang: 'C++ 17', status: 'ac', score: 100,
      pass: '10/10', time: '6ms', mem: '3204KB', at: '10-24 21:03', first: null },
    { id: 'S-20412', pid: 1017, title: '二叉树层序遍历', lang: 'C++ 17', status: 'ac', score: 100,
      pass: '10/10', time: '8ms', mem: '3512KB', at: '10-24 20:11', first: null },
    { id: 'S-20398', pid: 1058, title: '背包问题（01）', lang: 'C++ 17', status: 'tle', score: 60,
      pass: '6/10', time: '2048ms', mem: '512000KB', at: '10-23 19:42', first: 8 },
    { id: 'S-20371', pid: 1105, title: '最长递增子序列', lang: 'Python 3', status: 're', score: 0,
      pass: '0/10', time: '—', mem: '—', at: '10-23 17:20', first: 1 },
    { id: 'S-20355', pid: 1001, title: '两数之和', lang: 'Java 17', status: 'ce', score: 0,
      pass: '0/10', time: '—', mem: '—', at: '10-23 16:05', first: null },
    { id: 'S-20341', pid: 1017, title: '二叉树层序遍历', lang: 'C++ 17', status: 'ac', score: 100,
      pass: '10/10', time: '7ms', mem: '3408KB', at: '10-23 15:33', first: null },
    { id: 'S-20318', pid: 1045, title: '区间合并', lang: 'C++ 17', status: 'wa', score: 30,
      pass: '3/10', time: '11ms', mem: '3711KB', at: '10-23 14:02', first: 4 }
  ];

  var ASSIGNMENTS = [
    { id: 'A-0412', title: '第五章 动态规划 · 课后作业', type: 'homework', typeName: '作业',
      pidCount: 6, done: 3, score: 82, full: 100, due: '10-26 23:59', left: '2 天',
      ai: '仅阶段 ①③ · 上限 3 次', status: 'doing' },
    { id: 'A-0415', title: '树与堆 · 课堂练习', type: 'practice', typeName: '练习',
      pidCount: 4, done: 1, score: 25, full: 100, due: '10-27 23:59', left: '3 天',
      ai: '全阶段开放 · 不限次', status: 'doing' },
    { id: 'A-0419', title: '字符串算法 · 课后作业', type: 'homework', typeName: '作业',
      pidCount: 5, done: 0, score: 0, full: 100, due: '10-29 23:59', left: '5 天',
      ai: '仅阶段 ①③ · 上限 3 次', status: 'todo' },
    { id: 'A-0408', title: '数据结构期中测验', type: 'exam', typeName: '考试',
      pidCount: 4, done: 4, score: 96, full: 100, due: '10-22 16:00', left: '已结束',
      ai: '全部关闭', status: 'done' },
    { id: 'A-0403', title: '图论专题 · 课堂练习', type: 'practice', typeName: '练习',
      pidCount: 5, done: 5, score: 100, full: 100, due: '10-20 23:59', left: '已结束',
      ai: '全阶段开放 · 不限次', status: 'done' }
  ];

  var STUDENTS = [
    { name: '林昭', sid: '20220101', done: 8, total: 10, score: 82, ai: 6, last: '10-24 23:41', risk: 'ok' },
    { name: '陈砚', sid: '20220102', done: 10, total: 10, score: 96, ai: 3, last: '10-24 22:15', risk: 'ok' },
    { name: '苏晚', sid: '20220103', done: 7, total: 10, score: 74, ai: 11, last: '10-24 21:50', risk: 'ai' },
    { name: '周叙', sid: '20220104', done: 4, total: 10, score: 42, ai: 1, last: '10-23 20:02', risk: 'low' },
    { name: '何知遥', sid: '20220105', done: 9, total: 10, score: 91, ai: 4, last: '10-24 20:33', risk: 'ok' },
    { name: '沈亦', sid: '20220106', done: 3, total: 10, score: 31, ai: 0, last: '10-22 19:11', risk: 'low' },
    { name: '顾南', sid: '20220107', done: 8, total: 10, score: 85, ai: 5, last: '10-24 19:47', risk: 'ok' },
    { name: '许清和', sid: '20220108', done: 6, total: 10, score: 63, ai: 8, last: '10-24 18:20', risk: 'ai' },
    { name: '温言', sid: '20220109', done: 10, total: 10, score: 98, ai: 2, last: '10-24 17:55', risk: 'ok' },
    { name: '裴照', sid: '20220110', done: 5, total: 10, score: 55, ai: 7, last: '10-24 16:40', risk: 'ai' }
  ];

  var MODELS = [
    { id: 'local-qwen', name: 'Qwen2.5-Coder 7B', vendor: 'Ollama · 本地', role: 'Router / Guardrail',
      base: 'http://127.0.0.1:11434', state: 'on', ttft: '0.42s', calls: 12840, tokens: '3.1M', cost: '¥0' },
    { id: 'cloud-ds', name: 'DeepSeek-V3', vendor: 'DeepSeek · 云端', role: 'Tutor 主通道',
      base: 'https://api.deepseek.com', state: 'on', ttft: '1.12s', calls: 5420, tokens: '8.6M', cost: '¥214.80' },
    { id: 'cloud-qwen', name: '通义千问 Max', vendor: '阿里云 · 云端', role: 'Tutor 备用通道',
      base: 'https://dashscope.aliyuncs.com', state: 'off', ttft: '1.48s', calls: 218, tokens: '0.4M', cost: '¥12.40' },
    { id: 'cloud-zhipu', name: 'GLM-4-Plus', vendor: '智谱 · 云端', role: 'Tutor 备用通道',
      base: 'https://open.bigmodel.cn', state: 'off', ttft: '1.35s', calls: 96, tokens: '0.2M', cost: '¥6.20' }
  ];

  var AI_CALLS = [
    { at: '23:41:08', who: '林昭', stage: '③ 排错诊断', model: 'DeepSeek-V3', route: 'cloud', ttft: 1180,
      tok: 1240, guard: 'pass', note: '取回 #3 测试点特征' },
    { at: '23:38:52', who: '陈砚', stage: '① 读题拆解', model: 'Qwen2.5-Coder', route: 'local', ttft: 396,
      tok: 640, guard: 'pass', note: '—' },
    { at: '23:36:19', who: '苏晚', stage: '② 编码引导', model: 'DeepSeek-V3', route: 'cloud', ttft: 1042,
      tok: 2180, guard: 'rewrite', note: '检出 7 行完整源码，已改写为苏格拉底式提问' },
    { at: '23:34:03', who: '周叙', stage: '③ 排错诊断', model: 'Qwen2.5-Coder', route: 'local', ttft: 421,
      tok: 880, guard: 'pass', note: '云端超时 8.1s，自动降级本地' },
    { at: '23:31:44', who: '何知遥', stage: '④ AC 复盘', model: 'DeepSeek-V3', route: 'cloud', ttft: 1266,
      tok: 1560, guard: 'pass', note: '推荐 2 道同标签题目' },
    { at: '23:29:10', who: '许清和', stage: '② 编码引导', model: 'DeepSeek-V3', route: 'cloud', ttft: 980,
      tok: 3120, guard: 'block', note: '泄漏率拦截：整段题解源码' },
    { at: '23:26:35', who: '温言', stage: '① 读题拆解', model: 'Qwen2.5-Coder', route: 'local', ttft: 388,
      tok: 520, guard: 'pass', note: '—' },
    { at: '23:24:12', who: '裴照', stage: '③ 排错诊断', model: 'Qwen2.5-Coder', route: 'local', ttft: 452,
      tok: 990, guard: 'pass', note: '—' },
    { at: '23:21:50', who: '顾南', stage: '④ AC 复盘', model: 'DeepSeek-V3', route: 'cloud', ttft: 1310,
      tok: 1420, guard: 'pass', note: '—' },
    { at: '23:19:27', who: '沈亦', stage: '① 读题拆解', model: 'Qwen2.5-Coder', route: 'local', ttft: 402,
      tok: 610, guard: 'pass', note: '—' }
  ];

  var SANDBOX = [
    { id: 'sbx-8841', lang: 'C++ 17', user: '林昭', state: 'run', cpu: 12, mem: 4096, age: '0.4s' },
    { id: 'sbx-8840', lang: 'Python 3', user: '苏晚', state: 'run', cpu: 88, mem: 38200, age: '1.1s' },
    { id: 'sbx-8839', lang: 'Java 17', user: '陈砚', state: 'idle', cpu: 0, mem: 0, age: '3.2s' },
    { id: 'sbx-8838', lang: 'C++ 17', user: '周叙', state: 'run', cpu: 96, mem: 51200, age: '2.4s' },
    { id: 'sbx-8837', lang: 'C++ 17', user: '何知遥', state: 'idle', cpu: 0, mem: 0, age: '5.0s' }
  ];

  var PLAGIARISM = [
    { id: 'P-0088', a: '林昭', b: '许清和', pid: 1024, title: '最大子段和', sim: 96.4, stage: 2,
      state: 'flagged', tokens: 214, level: 'high', verdict: 'pending' },
    { id: 'P-0087', a: '沈亦', b: '裴照', pid: 1058, title: '背包问题（01）', sim: 88.1, stage: 2,
      state: 'flagged', tokens: 176, level: 'high', verdict: 'pending' },
    { id: 'P-0086', a: '苏晚', b: '顾南', pid: 1017, title: '二叉树层序遍历', sim: 71.2, stage: 2,
      state: 'review', tokens: 92, level: 'mid', verdict: 'pending' },
    { id: 'P-0085', a: '周叙', b: '温言', pid: 1045, title: '区间合并', sim: 42.6, stage: 1,
      state: 'pass', tokens: 12, level: 'low', verdict: 'clean' },
    { id: 'P-0084', a: '陈砚', b: '何知遥', pid: 1001, title: '两数之和', sim: 33.8, stage: 1,
      state: 'pass', tokens: 5, level: 'low', verdict: 'clean' }
  ];

  /* ── 5. 工具函数 ────────────────────────────────────────────────── */
  var OJ = {
    svg: svg, icons: ICONS, roles: ROLES, nav: NAV,
    problems: PROBLEMS, submissions: SUBMISSIONS, assignments: ASSIGNMENTS,
    students: STUDENTS, models: MODELS, aiCalls: AI_CALLS,
    sandbox: SANDBOX, plagiarism: PLAGIARISM,

    statusName: function (s) {
      return { ac: 'AC', wa: 'WA', tle: 'TLE', mle: 'MLE', re: 'RE', ce: 'CE',
               pac: 'PAC', pending: '判题中', none: '未提交', run: '运行中', idle: '空闲' }[s] || s;
    },
    statusTag: function (s, extra) {
      return '<span class="tag ' + s + (extra ? ' ' + extra : '') + '">' + OJ.statusName(s) + '</span>';
    },
    lvlName: function (n) { return ['', '入门', '简单', '中等', '较难', '困难'][n] || '简单'; },
    lvl: function (n) {
      return '<span class="lvl" data-l="' + n + '"><b>' + OJ.lvlName(n) + '</b><i></i><i></i><i></i></span>';
    },
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
