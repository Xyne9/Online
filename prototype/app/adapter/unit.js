/* adapter/unit.js —— 表⑤：内存 / 耗时 / 金额 / 百分比 / TTFT
   ─────────────────────────────────────────────────────────────────────
   三处必须警惕的 1024/1000 混用：
   ① MODELS.cost 是 '¥214.80' 字符串（要拆成 costCents: 21480）；
   ② SANDBOX.mem 是 KB（51200）；
   ③ t-problem-edit.html 的 ml: 256 是 MB。
   三者单位不同，函数名必须跟着改，否则后面一定有人把 MB 当 KB 传。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ⑤。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function kbText(kb) { return kb === null || kb === undefined ? '—' : Math.round(kb) + 'KB'; }
  function mbText(mb) { return mb === null || mb === undefined ? '—' : Math.round(mb) + 'MB'; }
  /* 只在 judge 适配层用：Go-Judge 沙箱的 memoryLimit 是字节 */
  function bytesToKb(b) { return Math.round(Number(b || 0) / 1024); }
  function centsText(c) { return c === null || c === undefined ? '—' : '¥' + (Number(c) / 100).toFixed(2); }
  function pctText(p) { return p === null || p === undefined ? '—' : Math.round(p) + '%'; }
  function ttftText(ms) { return ms === null || ms === undefined ? '—' : (ms / 1000).toFixed(2) + 's'; }

  (global.adapter = global.adapter || {}).unit = {
    kbText: kbText, mbText: mbText, bytesToKb: bytesToKb,
    centsText: centsText, pctText: pctText, ttftText: ttftText
  };
})(window);
