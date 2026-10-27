/* adapter/difficulty.js —— 表②：difficulty 1-5 → OJ.lvlName / OJ.lvl
   ─────────────────────────────────────────────────────────────────────
   OJ.lvlName(n) 的字典就是 ['', '入门','简单','中等','较难','困难']，与
   _SPEC.md:105 的规范级约定同值，因此只需把后端的 1-5 原样透传，不做映射。
   但也不许把裸数字丢进模板——夹取一次，防御后端的 0 / null / 8。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ②。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function clamp(n) { return Math.max(1, Math.min(5, Number(n) || 1)); }
  function lvlName(n) { return OJ.lvlName(clamp(n)); }
  function lvl(n) { return OJ.lvl(clamp(n)); }

  /* 迁移注意：老数据是 1/2/3 三档，SQL 已在 00-基线 §5 给出
     UPDATE prob_question SET difficulty = CASE difficulty WHEN 1 THEN 2 WHEN 2 THEN 3 WHEN 3 THEN 5 END; */

  (global.adapter = global.adapter || {}).difficulty = { lvlName: lvlName, lvl: lvl, clamp: clamp };
})(window);
