/* adapter/difficulty.js —— 表②：difficulty 1-5 → 难度名 / 刻度 HTML
   ─────────────────────────────────────────────────────────────────────
   ⚠️ 本文件**不得依赖 window.OJ**。
   s-workbench.html 全文不引 shell.js（它是独立的全屏工作台，脚本只有
   s-workbench.js + alpine.min.js + 本层 api/adapter），所以那页没有 OJ。
   原先这里写 `OJ.lvlName(clamp(n))`，导致工作台一调 /api/problem/questions
   就抛 "OJ is not defined"（adaptProblem → A.lvlName → OJ）。工作台是 P0
   主链路，这个洞必须堵死。
   做法：把 5 档字典与刻度 HTML 自带一份，与 _SPEC.md:105 的规范级约定同值；
   shell.js 的 OJ.lvlName/OJ.lvl 保留（其它 15 页还在用），两边同源同值。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ②。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  /* 与 _SPEC.md:105 / shell.js 完全同值的 5 档字典（索引 0 占位） */
  var LVL_NAMES = ['', '入门', '简单', '中等', '较难', '困难'];

  function clamp(n) { return Math.max(1, Math.min(5, Number(n) || 1)); }
  function lvlName(n) { return LVL_NAMES[clamp(n)]; }

  /* 迁移注意：老数据是 1/2/3 三档，SQL 已在 00-基线 §5 给出
     UPDATE prob_question SET difficulty = CASE difficulty WHEN 1 THEN 2 WHEN 2 THEN 3 WHEN 3 THEN 5 END; */

  /* lvlHtml 供「需要现成刻度 HTML」的地方用；adaptProblem.lvl 仍是整数，
     因为模板写的是 OJ.lvl(p.lvl) 由页面自己现画。 */
  function lvlHtml(n) {
    var v = clamp(n), h = '';
    for (var i = 1; i <= 5; i++) {
      h += '<span class="lvl' + (i <= v ? ' on' : '') + '" data-l="' + i + '"></span>';
    }
    return h;
  }

  (global.adapter = global.adapter || {}).difficulty = {
    lvlName: lvlName, lvlHtml: lvlHtml, LVL_NAMES: LVL_NAMES, clamp: clamp
  };
})(window);
