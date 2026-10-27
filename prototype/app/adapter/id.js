/* adapter/id.js —— 表⑥：唯一职责是「禁止前端拼单号」
   ─────────────────────────────────────────────────────────────────────
   单号（S-20481 / A-0412 / P-0088 / sbx-8841）一律由服务端下发，
   前端只透传 displayNo。缺失时宁可留空，也不拼一个假号——
   假号一旦被复制出去当查询条件，后端必 404，用户会以为系统坏了。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ⑥。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function displayNo(row) {
    if (!row) return '';
    if (row.displayNo) return row.displayNo;
    if (row.display_no) return row.display_no;        // 容错：DB 直出场景
    return '';                                        // 缺失宁可留空，也不拼假号
  }
  function problemNo(row) { return row && row.id ? '#' + row.id : ''; }

  (global.adapter = global.adapter || {}).id = { displayNo: displayNo, problemNo: problemNo };
})(window);
