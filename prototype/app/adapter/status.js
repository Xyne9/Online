/* adapter/status.js —— 表①：判题状态 0-9 整型 → statusCode / statusLabel
   ─────────────────────────────────────────────────────────────────────
   关键事实：shell.js 的 statusTag(s, extra) 是 '<span class="tag ' + s + …
   —— 把入参直接当 CSS class 用。传 3 会得到 .tag 3，components.css 里没有
   这个类，标签只剩裸文字。所以模板里永远传 statusCode，绝不传 status。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ①。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  var CODE = { 0: 'pending', 1: 'pending', 2: 'pending', 3: 'ac', 4: 'pac', 5: 'wa', 6: 'tle', 7: 'mle', 8: 're', 9: 'ce' };
  var LABEL = { 0: '排队中', 1: '编译中', 2: '判题中', 3: 'AC', 4: 'PAC', 5: 'WA', 6: 'TLE', 7: 'MLE', 8: 'RE', 9: 'CE' };

  /* 三字段可能只来一个：优先用后端给的 statusCode（它已按 CSS 命名），缺了才用整型查表 */
  function statusCodeOf(row) {
    if (!row) return 'pending';
    if (row.statusCode) return String(row.statusCode).toLowerCase();
    if (row.status !== null && row.status !== undefined) return CODE[row.status] || 'pending';
    return 'pending';
  }
  function statusLabelOf(row) {
    if (!row) return '未知';
    if (row.statusLabel) return row.statusLabel;                  // 后端文案优先，含"WA (答案错误)"
    if (row.status !== null && row.status !== undefined) return LABEL[row.status] || '未知';
    return '未知';
  }
  function isFinal(row) { return !!row && typeof row.status === 'number' && row.status >= 3; }
  /* 未提交不是一种判题状态：契约用 myStatus/myScore = null 表达，模板判 null 走"未提交" */

  (global.adapter = global.adapter || {}).status = {
    CODE: CODE, LABEL: LABEL,
    statusCodeOf: statusCodeOf, statusLabelOf: statusLabelOf, isFinal: isFinal
  };
})(window);
