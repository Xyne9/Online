/* adapter/language.js —— 表③：cpp / java / python → 展示名
   ─────────────────────────────────────────────────────────────────────
   原型自身不统一：s-submissions.html 的 langs 是 ['all','C++ 17','Python 3',
   'Java 17']（带空格），而 t-plagiarism.html 的数据写 lang:'C++17'（无空格），
   配色表 langStat 的 key 也是带空格的那种。统一到带空格的展示名，
   'C++17' 一律判为脏数据并从 KEY 兼容掉。
   规格来源：docs/analysis/03-前端对接实施指南.md §3.2 ③。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  var LABEL = { cpp: 'C++ 17', java: 'Java 17', python: 'Python 3' };
  var KEY = { 'C++ 17': 'cpp', 'C++17': 'cpp', 'C++': 'cpp', 'cpp': 'cpp',
              'Java 17': 'java', 'Java': 'java', 'java': 'java',
              'Python 3': 'python', 'Python': 'python', 'python': 'python' };

  function languageLabel(code) { return LABEL[String(code).toLowerCase()] || String(code); }
  function languageKey(label) { return KEY[label] || String(label).toLowerCase(); }
  /* 提交时限倍率：cpp×1、java×2、python×2（展示"时限 2000ms"时乘完再进模板） */
  function timeLimitFor(baseMs, lang) { return languageKey(lang) === 'cpp' ? baseMs : baseMs * 2; }

  (global.adapter = global.adapter || {}).language = {
    LABEL: LABEL, KEY: KEY,
    languageLabel: languageLabel, languageKey: languageKey,
    timeLimitFor: timeLimitFor
  };
})(window);
