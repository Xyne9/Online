/* step6-bindings.js —— 真实态给既有节点补数据绑定
   ─────────────────────────────────────────────────────────────────────
   ⚠️ 两条硬规则（agent.md §3.1 红线 #1 + §10 末尾那条坑）：
   ① **绝不新增元素节点**。要改既有文案只有两条合法路径：
      · `x-text` —— 只加在「本身没有元素子节点」的纯文本节点上；
        给含 `<span class="u">%</span>` 这类节点的父元素加 x-text 会把它
        子节点整片抹掉，等于运行时改结构（视觉丢单位，极难发现）；
      · `setText` —— 节点有元素子节点时，只改其中第 i 个文本节点的
        textContent（同 syncProblemFoot 的写法，不加节点）。
   ② **?mock=1 整段 return**，演示基线一个字节都不动。

   数据同步时机：包装器每次 load() 成功后 dispatch `oj:step6-data`，
   本文件收到后再改写文案节点（不用定时器、不用轮询）。
   ───────────────────────────────────────────────────────────────────── */
(function (g) {
  'use strict';
  if (g.api.http.useMock()) return;
  var page = document.body.dataset.page;

  /* 纯属性绑定（不改结构）。原型里 `:x` / `@x` 简写要先删，否则与 x-bind 打架。 */
  function bind(selector, attribute, value) {
    function visit(root) {
      root.querySelectorAll(selector).forEach(function (el) {
        if (attribute.indexOf('x-bind:') === 0) el.removeAttribute(':' + attribute.slice(7));
        if (attribute.indexOf('x-on:') === 0) el.removeAttribute('@' + attribute.slice(5));
        el.setAttribute(attribute, value);
      });
      root.querySelectorAll('template').forEach(function (t) { visit(t.content); });
    }
    visit(document);
  }
  /* x-text 只给纯文本节点用（下面每个调用点都确认过没有元素子节点） */
  function text(selector, value) { bind(selector, 'x-text', value); }

  /* ── 文案改写（只动文本节点） ── */
  var COPY = [];
  function copy(selector, fn) { COPY.push([selector, fn]); }
  function nodeText(el, i) {
    var n = 0;
    for (var c = el.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) {
        if (n === (i || 0)) return c;
        n += 1;
      }
    }
    return null;
  }
  function setText(el, i, v) {
    var t = nodeText(el, i);
    if (t && t.textContent !== v) t.textContent = v;
  }
  function sync() {
    var root = document.getElementById('page-content') || document.body;
    var data = (g.Alpine && g.Alpine.$data) ? g.Alpine.$data(root) : null;
    if (!data) return;
    COPY.forEach(function (pair) {
      var els;
      try { els = document.querySelectorAll(pair[0]); } catch (e) { return; }
      Array.prototype.forEach.call(els, function (el) {
        try { pair[1](el, data); } catch (e) { /* 单条文案失败不该炸整页 */ }
      });
    });
  }
  document.addEventListener('oj:step6-data', sync);

  /* 三个 trend / 数值小工具（只做展示换算，不做业务判断） */
  function nz(v, d) { return v === null || v === undefined || v === '' ? (d === undefined ? '—' : d) : v; }
  function pct(v) { return v === null || v === undefined ? '—' : Math.round(v) + '%'; }
  function int(v) { return v === null || v === undefined ? '—' : Math.round(v); }

  /* ══════════════════════════════════════════════════════════════════
     s-analytics.html —— 学生学情
     页头副标题、AI 卡的三行、统计带四格、标签卡「班级 x%」、AI 总数。
     这些原本都是写死的裸文本；真实态必须跟服务端数字走，否则
     「47 次提交 / 班级 62%」会和下面的真数据自相矛盾。
     ══════════════════════════════════════════════════════════════════ */
  if (page === 's-analytics') {
    text('.ph-sub',
      "`${(mine && mine.name) || '本账号'} · ${className || '本班'} · ${rangeLabel} ${range.total} 次提交 · 班级平均 AC 率 ${classAcRate == null ? '—' : classAcRate + '%'}`");
    text('.aihero-k .tag', "`基于 ${range.total} 次提交 · ${triedCount} 道题`");
    text('.aihero-t',
      "weakTags.length ? `下一步：先把${weakTags[0].name}补到班级线（${weakTags[0].pct}% → ${weakTags[0].cls == null ? '—' : weakTags[0].cls + '%'}）` : (tags.length ? '下一步：当前没有低于 70% 的标签，挑一道更难的题练手' : '下一步：先完成一次提交，学情才会开始统计')");
    text('.aihero-side-h .n', "`${weakCount} 个 · 掌握度低于 70%`");

    /* AI 卡正文：<strong>/<span class="up"/>/<span class="dn"> 都在，不能整体 x-text。
       strong 与 up/dn 本身是纯文本节点 → 绑 x-text；句子其余部分走 COPY。 */
    text('.aihero-p strong:nth-of-type(1)',
      "tags.length ? tags.slice().sort((a, b) => (b.pct || 0) - (a.pct || 0))[0].name : '—'");
    text('.aihero-p strong:nth-of-type(2)', "weakTags.length ? weakTags[0].name : '—'");
    text('.aihero-p .up',
      "tags.length ? tags.slice().sort((a, b) => (b.pct || 0) - (a.pct || 0))[0].pct + '%' : '—'");
    text('.aihero-p .dn', "weakTags.length ? weakTags[0].pct + '%' : '—'");
    copy('.aihero-p', function (el, d) {
      /* 该 <p> 的 5 个直接文本节点依次是：
         T0「你的」 T1「已明显高于班级（」 T2「 vs 66%），」
         T3「是最短板（」 T4「），近 30 天有 3 次……」
         strong / span 夹在中间，因此只能逐文本节点改写，绝不能整体 x-text。 */
      var best = d.tags.slice().sort(function (a, b) { return (b.pct || 0) - (a.pct || 0); })[0];
      var weak = d.weakTags[0];
      setText(el, 0, '你的');
      setText(el, 1, best ? '已明显高于班级（' : '暂无掌握度统计（');
      setText(el, 2, ' vs ' + (best && best.cls != null ? best.cls + '%' : '—') + '），');
      setText(el, 3, weak ? '是最短板（' : '当前没有低于 70% 的标签（');
      setText(el, 4, '），' + (d.range.total
        ? '本周期共 ' + d.range.total + ' 次提交、' + d.triedCount + ' 道题有过提交；'
        : '本周期还没有提交记录；') + '建议先补薄弱标签再回到作业。');
    });
    /* AI 卡三行元信息：图标 span + 文本，只能改文本节点 */
    copy('.aihero-meta > span:nth-child(1)', function (el, d) {
      setText(el, 0, '更新于 ' + (d.summary && d.summary.peakDate ? d.summary.peakDate : '—'));
    });
    copy('.aihero-meta > span:nth-child(2)', function (el, d) {
      setText(el, 0, '样本：' + d.rangeLabel + ' ' + d.range.total + ' 次提交 / ' + d.triedCount + ' 道题');
    });
    copy('.aihero-meta > span:nth-child(3)', function (el) {
      setText(el, 0, '口径：标签掌握度 = 该标签下已 AC 题数占比');
    });

    /* 统计带①：AC 率 vs 班级均值（62 是写死的假基线） */
    bind('.stats .stat:nth-child(1) .stat-d > span:nth-child(1)', 'x-bind:class',
      "classAcRate == null ? '' : (range.acRate >= classAcRate ? 'up' : 'down')");
    bind('.stats .stat:nth-child(1) .stat-d > span:nth-child(1) > span:nth-child(1)', 'x-show',
      "classAcRate != null && range.acRate >= classAcRate");
    bind('.stats .stat:nth-child(1) .stat-d > span:nth-child(1) > span:nth-child(2)', 'x-show',
      "classAcRate == null || range.acRate < classAcRate");
    text('.stats .stat:nth-child(1) .stat-d > span:nth-child(1) > span:nth-child(3)',
      "classAcRate == null ? '—' : `${Math.abs(range.acRate - classAcRate)} 个百分点`");
    text('.stats .stat:nth-child(1) .stat-d > span:nth-child(2)',
      "classAcRate == null ? '班级均值暂无统计' : (range.acRate >= classAcRate ? `高于班级均值 ${classAcRate}%` : `低于班级均值 ${classAcRate}%`)");
    /* 统计带②③：已有 x-text 的直接覆盖（stat2 是纯文本节点；stat3 有 <b>） */
    text('.stats .stat:nth-child(2) .stat-d',
      "`${rangeLabel} ${range.total} 次提交 · 覆盖 ${triedCount} 道题`");
    text('.stats .stat:nth-child(3) .stat-d b', 'avgTry');
    copy('.stats .stat:nth-child(3) .stat-d', function (el, d) {
      setText(el, 0, '个人均值 ');
      setText(el, 1, ' 次 · ' + d.triedCount + ' 道题有过提交');
    });
    copy('.stats .stat:nth-child(4) .stat-d', function (el, d) {
      setText(el, 0, '掌握度低于 70% 的标签 ' + d.weakCount + ' 个 · 建议优先补强');
    });

    /* 标签卡「班级 x%」：真实态没有班级值就显示 '—'，不写死 66% */
    text('.cap-c', "`班级 ${t.cls == null ? '—' : t.cls + '%'}`");
    /* ⚠️ #62 的 weakKps 只下发**最弱的 5 个**知识点（后端 sort 后 subList(0,5)），
       而 #57 下发全部（当前库里 10 个）。于是标签卡里有约一半的 pct 是 null。
       原型模板写的是 `${t.pct}%` 与 `t.pct < 50 ? ...`，pct=null 时会渲染出
       字符串 "null%"、且 `null < 50` 为 true（会被涂成"薄弱"色）—— 必须改。
       约定：拿不到个人掌握度就显示 '—'，**绝不拿班级值冒充个人值**。 */
    text('.cap-v', "t.pct == null ? '—' : `${t.pct}%`");
    bind('.cap-v', 'x-bind:class', "t.pct == null ? '' : (t.pct < 50 ? 'low' : (t.pct < 70 ? 'mid' : ''))");
    bind('.cap-bar > i', 'x-bind:style',
      "t.pct == null ? 'transform:scaleX(0)' : `transform:scaleX(${t.pct / 100})`");
    bind('.cap-bar > i', 'x-bind:class',
      "t.pct == null ? '' : (t.pct < 50 ? 'low' : (t.pct < 70 ? 'mid' : ''))");
    /* 弱点点数那行也要跟着改：pct=null 的标签不能算进「薄弱」 */
    text('.aihero-side-h .n', "`${weakCount} 个 · 掌握度低于 70%`");
    /* 卡头注释如实说明口径，否则「一半是 —」看着像坏了 */
    text('.panel-h .ph-note',
      "weakCount < tags.length && tags.length ? '按掌握度排序 · 薄弱项下发个人掌握度，其余显示 —' : '按掌握度排序 · 点击查看该标签题目'");
    /* AI 使用统计：总数与四阶段分母都取 #61 */
    text('.ai-total b', 'aiTotal');
    text('.ai-total span',
      "`次调用 · ${aiStages.map(s => s.no + ' ' + s.n).join(' / ')}`");
    bind('.ai-bar i', 'x-bind:style', '`transform:scaleX(${aiTotal ? s.n / aiTotal : 0})`');
    /* 抽屉：「近 30 天提交」跟着 range 走 */
    text('.drawer .kvcard > div:nth-child(4) dt', '`${rangeLabel}提交`');
    text('.drawer .kvcard > div:nth-child(4) dd', "`${weak.subs == null ? '—' : weak.subs + ' 次'}`");
    /* 薄弱点抽屉「去练习」：没有推荐题时退回题库，避免 weak.recs[0].id 抛错 */
    bind('[\\:href*="weak.recs"]', 'x-bind:href',
      "weak.recs.length ? `s-workbench.html?pid=${weak.recs[0].id}&tag=${encodeURIComponent(weak.name)}` : 's-problems.html?status=fail'");
  }

  /* ══════════════════════════════════════════════════════════════════
     t-dashboard.html —— 教师工作台
     ══════════════════════════════════════════════════════════════════ */
  if (page === 't-dashboard') {
    text('.ph-sub',
      "`${clsName || '我的班级'} · ${days.length ? days[0].date + ' 至 ' + days[days.length - 1].date : '—'}`");
    text('.todo-h .sub', "`共 ${todoTotal} 项，每一件都能就地处理`");
    copy('.todo-foot > span:nth-child(2)', function (el, d) {
      setText(el, 0, '状态正常' + (d.healthy == null ? '' : ' · ' + d.healthy + ' 项'));
    });
    text('.lcol .panel:nth-child(2) .panel-h .sub', "`${activities.length} 项进行中`");
    /* 环比：契约未下发，隐藏而不是写死「4.0 个百分点」 */
    bind('.hero-delta', 'x-show', 'false');
    bind('.hero-delta + .t-xs.t-dim', 'x-show', 'false');
    text('.panel-h .sub.nowrap', 'weekLabel');
    text('.hero-note',
      "overview && overview.weakestKp ? `失分集中在${overview.weakestKp}一章，该知识点全班平均掌握度 ${Math.round(overview.avgKpMastery == null ? 0 : overview.avgKpMastery)}%。` : '本周还没有形成明显的失分集中点。'");
    /* 每日 AC 率：分母为 0 时给 0，不出 NaN */
    text('.wkday > span:last-child b', '`${dayRate}%`');
    /* 三个次要指标：48 / 48 / 1 都是写死的 */
    copy('.aigrid.c3 .aig:nth-child(1) .aig-v', function (el, d) {
      setText(el, 0, String(d.overview && d.overview.studentCount != null ? d.overview.studentCount : '—'));
    });
    text('.aigrid.c3 .aig:nth-child(1) .aig-d',
      "`本周活跃 ${overview && overview.activeCount != null ? overview.activeCount : '—'}`");
    copy('.aigrid.c3 .aig:nth-child(3) .aig-v', function (el, d) {
      var t = d.todos.filter(function (x) { return x.k === 'plag'; })[0];
      setText(el, 0, String((t && t.count) || 0));
    });
    copy('.aigrid.c3 .aig:nth-child(3) .aig-d', function (el, d) {
      var t = d.todos.filter(function (x) { return x.k === 'plag'; })[0];
      setText(el, 0, ((t && t.count) || 0) + ' 组待判定');
    });
    /* AI 价值带：1840 → 真实 totalCalls；四阶段条宽按真实分母 */
    text('.ai-band .aival b', 'aiTotal');
    text('.ai-band .aival span',
      "aiTotal ? `次调用 · ${aiStages.filter(s => s.n > 0).length} 个阶段有使用` : '本周期还没有 AI 调用'");
    bind('.ailine .bar.thin i', 'x-bind:style', '`width:${aiTotal ? s.n / aiTotal * 100 : 0}%`');
    /* 云端/本地拆分：#61/#56 都不下发，隐藏三段堆叠并如实说明 */
    bind('.ai-band-col:nth-child(3) .stackbar', 'x-show', 'false');
    bind('.ai-band-col:nth-child(3) .legend', 'x-show', 'false');
    text('.ai-band-col:nth-child(3) p.t-sm',
      "'本周期 AI 调用已按教学阶段拆分（见左侧）；云端/本地路由拆分不在 ai-usage 的下发字段内。'");
    /* 抽屉：平均尝试契约未下发时给 '—'，不显示「— 次」 */
    text('.drawer .kvcard > div:nth-child(3) dd',
      "detail.avg == null || detail.avg === '—' ? '—' : `${detail.avg} 次`");
  }

  /* ══════════════════════════════════════════════════════════════════
     t-analytics.html —— 班级学情
     ══════════════════════════════════════════════════════════════════ */
  if (page === 't-analytics') {
    text('.ph-sub', "`${className} · ${classSize} 人 · 数据截至 ${rangeTip}`");
    text('.aihero-k .tag', "`基于 ${classSize} 人 · ${rangeLabel}数据`");
    text('.aihero-t',
      "weakest.pct == null ? `本周先补「${weakest.name}」：掌握度暂无统计` : `本周先补「${weakest.name}」：${weakest.pct}%，比班级线低 ${avgKp - weakest.pct} 个百分点`");
    text('.aihero-p > span:nth-child(1)',
      "`${weakest.name}是全班最短板（${weakest.pct == null ? '—' : weakest.pct + '%'}），${kpsN} 个知识点里有 ${weakCount} 个没到 70% 的班级线；`");
    text('.aihero-p > span:nth-child(2)',
      "`该知识点的推荐题共 ${weakest.recs ? weakest.recs.length : 0} 道，先做完再回作业。`");
    text('.aihero-p > span:nth-child(3)', "'建议本周先布置一组该知识点的专项练习，再回到课后作业。'");
    text('.aihero-side-h .n', "`${weakCount} / ${kpsN} 低于 70%`");
    text('.aihero-side-foot > span',
      "`达标 ${kpsN - weakCount} 个，低于班级线 ${weakCount} 个。`");
    text('.aihero-side-b .kp .kp-s',
      "`${k.ac == null ? '—' : k.ac}/${k.tried == null ? '—' : k.tried} 人 AC`");
    text('.aiwhy .wy:nth-child(3) span:last-child',
      "`对比达标线 70%，这个知识点还差 ${Math.max(0, 70 - (weakest.pct == null ? 70 : weakest.pct))} 个百分点。`");
    text('.stats .stat:nth-child(1) .stat-d', "`在籍 ${classSize} · 本周活跃 ${activeCount}`");
    text('.stats .stat:nth-child(2) .stat-d',
      "`中位数 ${dist.median == null ? '—' : dist.median} · 最高 ${dist.max == null ? '—' : dist.max}`");
    bind('.stats .stat:nth-child(3) .up', 'x-show', 'false');
    copy('.stats .stat:nth-child(3) .stat-d', function (el, d) {
      setText(el, 0, '环比未下发 · 当前 ' + pct(d.acRate));
    });
    text('.stats .stat:nth-child(4) .stat-d', "`含 ${aiRiskCount} 人 AI 依赖偏高`");
    text('.dist .dl > span:nth-child(1)', "`满分 100 · 最高 ${dist.max == null ? '—' : dist.max}`");
    text('.dist .dl > span:nth-child(2)', "`最低 ${dist.min == null ? '—' : dist.min} · 标准差 ${dist.sd}`");
    text('.lcol > div.panel:nth-of-type(3) .panel-h .sub', "`共 ${classSize} 人 · 按${sortLabel}排序`");
    text('.pager > span:nth-child(1)', "`共 ${sorted.length} 人 · 第 ${page} / ${pages} 页`");
    /* 本周期待办：四条静态文案换成 #52 的真实待办 */
    text('.steps .step:nth-child(1) .st-t', 'stepT0');
    text('.steps .step:nth-child(1) .st-d', 'stepD0');
    text('.steps .step:nth-child(2) .st-t', 'stepT1');
    text('.steps .step:nth-child(2) .st-d', 'stepD1');
    text('.steps .step:nth-child(3) .st-t', 'stepT2');
    text('.steps .step:nth-child(3) .st-d', 'stepD2');
    text('.steps .step:nth-child(4) .st-t', 'stepT3');
    text('.steps .step:nth-child(4) .st-d', 'stepD3');
    /* 学生抽屉：spark 为空时原模板会算出「峰值 undefined · NaN 次」 */
    text('.drawer .legend > span:last-child', 'peakNote');
  }

  /* ══════════════════════════════════════════════════════════════════
     a-models.html —— 模型通道
     ══════════════════════════════════════════════════════════════════ */
  if (page === 'a-models') {
    text('.hfoot > span:nth-child(1) b',
      "channels.filter(m => !/ollama|本地/i.test(m.vendor)).length");
    text('.hfoot > span:nth-child(3) b',
      "channels.filter(m => /ollama|本地/i.test(m.vendor)).length");
    text('.hfoot > span:nth-child(7) b', 'degradedCount');
    text('.hfoot > span:nth-child(9) b', "ttftAvg ? (ttftAvg / 1000).toFixed(2) : '—'");
    copy('.hfoot > span:nth-child(9)', function (el, d) {
      setText(el, 1, 's · 全部通道 24 小时逐小时均值（共 ' + (d.channels || []).length + ' 条）');
    });
    text('.pager > span:nth-child(1)', "`共 ${channels.length} 条通道`");
    /* 运行概览：路由分布用真实今日调用量按「本地 / 云端」分档 */
    bind('.ov > div:nth-child(1) .stackbar i:nth-child(1)', 'x-bind:style',
      "`width:${routeDist ? routeDist.cloudPct : 0}%;background:var(--blue)`");
    bind('.ov > div:nth-child(1) .stackbar i:nth-child(2)', 'x-bind:style',
      "`width:${routeDist ? routeDist.localPct : 0}%;background:var(--line-strong)`");
    bind('.ov > div:nth-child(1) .stackbar i:nth-child(3)', 'x-bind:style',
      "`width:${routeDist ? routeDist.fallbackPct : 0}%;background:var(--tle)`");
    text('.ov > div:nth-child(1) .legend .sw-blue', "`云端通道 ${routeDist ? routeDist.cloud : 0} 次`");
    text('.ov > div:nth-child(1) .legend .sw-local', "`本地通道 ${routeDist ? routeDist.local : 0} 次`");
    text('.ov > div:nth-child(1) .legend .sw-tle', "`降级回落 ${routeDist ? routeDist.fallback : 0} 次`");
    /* 24 小时 TTFT：真实 #73 trend24h 的逐小时均值，用 $el 自己取序号 */
    bind('.ov > div:nth-child(2) .trend i', 'x-bind:style',
      "`height:${(function(){var i=[].indexOf.call($el.parentNode.children,$el);var v=(ttftSeries||[])[i];return ttftPeak&&v?Math.max(6,Math.round(v/ttftPeak*88)):6;})()}%`");
    bind('.ov > div:nth-child(2) .trend i', 'x-bind:class',
      "(function(){var i=[].indexOf.call($el.parentNode.children,$el);return (ttftSeries||[])[i]===ttftPeak && ttftPeak ? 'hi' : '';})()");
    text('.ov > div:nth-child(2) .legend .sw-blue', "`峰值 ${ttftPeak ? (ttftPeak / 1000).toFixed(2) : '—'}s`");
    text('.ov > div:nth-child(2) .legend .sw-local', "`均值 ${ttftAvg ? (ttftAvg / 1000).toFixed(2) : '—'}s`");
  }

  /* ══════════════════════════════════════════════════════════════════
     a-audit.html —— 调用审计 + 沙箱运维
     ⚠️ 审计 page 与沙箱 boxPage 已分开（原型两处共用一个 page 是已知 bug）。
     ══════════════════════════════════════════════════════════════════ */
  if (page === 'a-audit') {
    text('.gband-v', 'guardBlocked');
    /* 原型文案是「改写 21 次 · 拦截率 0.4%」。改写的**全局次数**契约没下发
       （#81 的 summary 只有 todayTotal 与 hitDist），所以不编一个数，
       改成两个都能算出来的真值：今日拦截次数 / 今日调用量。
       ⚠️ hitDist 是**今日全库**口径，与表格当前页的 guardCode 不是一回事：
       表格这 8 条可能全是 pass，但今日累计确实有拦截 —— 文案要把口径写清，
       否则“表里全是 pass 却说拦了 10 次”看着像 bug。 */
    text('.gband-sub',
      "`今日拦截 ${guardBlocked} 次 / 今日 ${todayTotal} 次调用 · 拦截率 ${todayTotal ? (guardBlocked / todayTotal * 100).toFixed(1) : '0.0'}%`");
    /* 命中原因分布上方的小标题加一句口径说明（不加节点，改既有 .oh 的文本节点） */
    copy('.gband-r .oh', function (el) {
      setText(el, 0, '拦截原因分布（今日累计，与下方表格当前页口径不同）');
    });
    bind('.hit .b i', 'x-bind:style',
      "`width:${hitDist.length && Math.max.apply(null, hitDist.map(h => h.v)) ? h.v / Math.max.apply(null, hitDist.map(h => h.v)) * 100 : 0}%`");
    text('.gfoot > span:nth-child(1) b', 'todayTotal');
    text('.gfoot > span:nth-child(3) b',
      "`${health && health.channels && health.channels.degrade != null ? health.channels.degrade : 0}`");
    text('.gfoot > span:nth-child(5) b', "'—'");
    copy('.gfoot > span:nth-child(5)', function (el) {
      setText(el, 0, '平均 TTFT ');
      setText(el, 1, 's · 本接口不下发分路由 TTFT（见模型通道页）');
    });
    text('.gfoot > span:nth-child(7) b',
      "`${new Set(calls.map(c => c.userId).filter(v => v != null)).size}`");
    text('.pager > span:nth-child(1)', "`共 ${totalCalls} 条记录 · 第 ${page} / ${pageCount} 页`");
    /* 沙箱四格：.stat-v 里有 <span class="u"> 单位，只能改文本节点 */
    copy('section.aud-tab:nth-of-type(2) .stats .stat:nth-child(1) .stat-v', function (el, d) {
      setText(el, 0, String(d.boxStats && d.boxStats.running != null ? d.boxStats.running : '—'));
    });
    copy('section.aud-tab:nth-of-type(2) .stats .stat:nth-child(2) .stat-v', function (el, d) {
      setText(el, 0, String(d.boxStats && d.boxStats.queued != null ? d.boxStats.queued : '—'));
    });
    copy('section.aud-tab:nth-of-type(2) .stats .stat:nth-child(3) .stat-v', function (el, d) {
      setText(el, 0, String(d.boxStats && d.boxStats.exited != null ? d.boxStats.exited : '—'));
    });
    copy('section.aud-tab:nth-of-type(2) .stats .stat:nth-child(4) .stat-v', function (el, d) {
      setText(el, 0, String(d.boxStats && d.boxStats.avgRunMs != null ? Math.round(d.boxStats.avgRunMs) : '—'));
    });
    copy('section.aud-tab:nth-of-type(2) .stats .stat:nth-child(2) .stat-d', function (el, d) {
      var s = d.boxStats || {};
      setText(el, 0, '并发上限 ' + int(s.capacity) + ' · 平均等待 ' + int(s.avgWaitMs) + 'ms');
    });
    copy('section.aud-tab:nth-of-type(2) .stats .stat:nth-child(4) .stat-d', function (el, d) {
      var s = d.boxStats || {};
      setText(el, 0, '回收率 ' + (s.recycleRate == null ? '—' : Math.round(s.recycleRate * 100) + '%') +
        ' · 平均运行 ' + int(s.avgRunMs) + 'ms');
    });
    text('section.aud-tab:nth-of-type(2) .pager > span',
      "`共 ${boxTotal} 个容器 · 并发上限 ${boxStats && boxStats.capacity != null ? boxStats.capacity : '—'} · 单容器内存 256MB`");
  }
})(window);
