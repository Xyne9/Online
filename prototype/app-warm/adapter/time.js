/* adapter/time.js —— 表④：epoch ms → 展示时刻 / 剩余时间 / 时长
   ─────────────────────────────────────────────────────────────────────
   契约规定所有时刻字段以 At 结尾、epoch 毫秒（dueAt: 1761310860000），
   所有时长字段以 Ms 结尾（timeUsedMs / ttftMs）。原型是 '10-24 23:41' 与
   '2 天' 两种字符串。
   本文件同时把 fmtDateTime 挂到 OJ.fmt.at —— 00-基线决策.md 里写的
   OJ.fmt.at() 是待新增能力，shell.js 原本没有。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ④。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function p2(n) { return n < 10 ? '0' + n : String(n); }

  /* 列表里的紧凑展示：10-24 23:41（跨年才补年份） */
  function fmtDateTime(ms) {
    if (!ms) return '—';
    var d = new Date(ms), now = new Date();
    if (isNaN(d.getTime())) return '—';
    var head = d.getFullYear() === now.getFullYear() ? p2(d.getMonth() + 1) + '-' + p2(d.getDate())
                                                     : d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate());
    return head + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes());
  }

  /* 倒计时/剩余时间：'2 天' / '3 小时' / '12 分钟' / '已结束' */
  function leftText(dueAt, now) {
    if (!dueAt) return '—';
    var gap = dueAt - (now || Date.now());
    if (gap <= 0) return '已结束';
    var day = Math.floor(gap / 86400000);
    if (day >= 1) return day + ' 天';
    var hour = Math.floor(gap / 3600000);
    if (hour >= 1) return hour + ' 小时';
    return Math.max(1, Math.floor(gap / 60000)) + ' 分钟';
  }

  /* 时长：12ms / 1.2s / 1m03s */
  function fmtDuration(ms) {
    if (ms === null || ms === undefined) return '—';
    if (ms < 1000) return ms + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(1).replace(/\.0$/, '') + 's';
    return Math.floor(ms / 60000) + 'm' + p2(Math.round((ms % 60000) / 1000)) + 's';
  }

  (global.adapter = global.adapter || {}).time = {
    p2: p2,
    fmtDateTime: fmtDateTime, leftText: leftText, fmtDuration: fmtDuration
  };

  /* 给 OJ 补上基线文档里已经提到、但 shell.js 里还没有的那个方法
     （OJ.fmt 是函数对象，可以直接挂属性） */
  if (global.OJ && global.OJ.fmt) global.OJ.fmt.at = fmtDateTime;
  else if (global.OJ) { global.OJ.fmt = { at: fmtDateTime }; }
})(window);
