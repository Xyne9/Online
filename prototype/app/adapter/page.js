/* adapter/page.js —— 表⑦：{list,total,page,pageSize,hasNext} → .pager 组件
   ─────────────────────────────────────────────────────────────────────
   原型 .pager 有三种形态：装饰性（s-problems / a-models，两个 <button> 无
   @click）、本地过滤式（s-assignments / s-submissions / t-problems /
   t-analytics / t-assignments）、真分页（a-audit，有 page/pageSize/pageCount）。
   三种都收敛到同一份状态，DOM 与 class 不变。
   注意 s-submissions.html 用的字段名是 per: 8 而不是 pageSize，改造时保留
   该局部字段名不动，只在 fetch 时映射成 pageSize 传出去——这就是 adapter
   存在的意义：改数据不改模板。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ⑦。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function paginate(list, q) {                       // MOCK 侧复用同一个语义
    var arr = Array.isArray(list) ? list : [];
    var page = Math.max(1, Number(q && q.page) || 1);
    var size = Math.min(100, Math.max(1, Number(q && q.pageSize) || 20));
    var from = (page - 1) * size;
    var slice = arr.slice(from, from + size);
    return { list: slice, total: arr.length, page: page, pageSize: size,
             hasNext: from + slice.length < arr.length };
  }
  /* 页面侧统一派生，模板里不写算术 */
  function pagerOf(res) {
    res = res || {};
    var total = Number(res.total) || 0;
    var pageSize = Number(res.pageSize) || 20;
    var page = Number(res.page) || 1;
    var pages = Math.max(1, Math.ceil(total / pageSize));
    return { page: page, pageSize: pageSize, total: total, pages: pages,
             hasPrev: page > 1, hasNext: !!res.hasNext };
  }

  (global.adapter = global.adapter || {}).page = { paginate: paginate, pagerOf: pagerOf };
})(window);
