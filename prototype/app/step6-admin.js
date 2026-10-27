/* step6-admin.js —— 管理两页（a-models / a-audit）真实态控制器
   ─────────────────────────────────────────────────────────────────────
   范式同 step6-analytics.js / step5-assignments.js：
     · 第一行 `if (H.useMock()) return d;` —— mock 态原样返回，视觉零回归；
     · 只改 x-data 对象内部，不碰 DOM / class / 内联样式；
     · 页尾只追加 <script src>。
   ───────────────────────────────────────────────────────────────────── */
(function (g) {
  'use strict';
  var H = g.api.http, D = g.api.admin, A = g.adapter;
  if (!H || !D) return;
  var PAGE = document.body.dataset.page;

  function prop(d, name, getter) {
    Object.defineProperty(d, name, { configurable: true, enumerable: true, get: getter });
  }
  function method(d, name, fn) { d[name] = fn; }
  function fail(d, e) {
    d.loadErr = true;
    if (typeof d.say === 'function') d.say(H.describe(e), 'warn');
  }
  function settle(list) {
    return Promise.all(list.map(function (p) {
      return Promise.resolve(p).catch(function () { return null; });
    }));
  }
  function at(list, i) { return (Array.isArray(list) && list.length > (i || 0)) ? list[i || 0] : null; }
  /* 数据就绪 → 通知 step6-bindings.js 改写既有文本节点 */
  function ready(d) {
    try { document.dispatchEvent(new CustomEvent('oj:step6-data')); } catch (e) {}
  }
  function num(v) { var n = Number(v); return isNaN(n) ? 0 : n; }

  /* ══════════════════════════════════════════════════════════════════
     ① a-models.html —— 模型通道
        #72 channels / #73 detail / #74~#79 CRUD+探活+恢复 / #80 health / #87 stats
     ══════════════════════════════════════════════════════════════════ */
  if (PAGE === 'a-models' && g.modelsPage) {
    var oldModels = g.modelsPage;
    g.modelsPage = function () {
      var d = oldModels();
      if (H.useMock()) return d;

      d.channels = [];
      d.degradeEvents = [];
      d.health = null;
      d.stats = null;
      d.todayTotalS = 0;
      d.autoDegrade = true;
      d.loading = true;
      d.loadErr = false;
      d.routeDist = null;          /* 运行概览「路由分布」 */
      d.ttftSeries = [];           /* 运行概览 24 小时 TTFT（真实：全部通道逐小时均值） */
      d.ttftPeak = 0;
      d.ttftAvg = 0;

      method(d, 'init', async function () {
        var s = g.OJ.qs('state');
        if (this.stateFilters.some(function (f) { return f.k === s; })) this.st = s;
        await this.load();
        if (g.OJ.qs('err') === '1') this.loadErr = true;
        /* 打开抽屉时按需拉 #73 详情（24h 趋势 / 近期调用 / 降级事件） */
        var self = this;
        this.$watch('open', function (v) { if (v && !v.detailLoaded) self.loadDetail(v); });
      });
      method(d, 'retryAll', async function () { this.loadErr = false; await this.load(); });
      method(d, 'load', async function () {
        this.loading = true; this.loadErr = false;
        try {
          var res = await settle([D.channels(), D.health(false), D.stats(7)]);
          var ch = res[0], health = res[1], stats = res[2];
          this.channels = ((ch && ch.list) || []).map(A.admChannel);
          this.summary = (ch && ch.summary) || null;
          this.health = health || null;
          this.stats = stats ? A.admStats(stats) : null;
          this.todayTotalS = this.summary && this.summary.todayTotal != null
            ? this.summary.todayTotal
            : this.channels.reduce(function (n, m) { return n + m.today; }, 0);
          this.degradeEvents = (health && health.degradedChannels)
            ? health.degradedChannels.map(function (c) {
                return { t: '—', n: c.channelName || c.channelCode || '—', d: c.reason || '—' };
              })
            : [];

          /* 运行概览：路由分布按「本地 / 云端」两档从真实通道的今日调用量汇总；
             降级次数用 #87 的 fallbackRate 换算（同一份口径，不另造数据）。 */
          var local = 0, cloud = 0;
          this.channels.forEach(function (m) {
            if (/ollama|本地/i.test(m.vendor)) local += m.today; else cloud += m.today;
          });
          var tot = local + cloud;
          var fb = this.stats && this.stats.fallbackRate != null
            ? Math.round(tot * num(this.stats.fallbackRate) / 100) : 0;
          this.routeDist = {
            local: local, cloud: cloud, fallback: fb, total: tot,
            cloudPct: tot ? Math.round(cloud / tot * 100) : 0,
            localPct: tot ? Math.round(local / tot * 100) : 0,
            fallbackPct: tot ? Math.round(fb / tot * 100) : 0
          };

          /* 24 小时 TTFT：并发拉每通道详情，再逐小时求均值。
             只有详情接口（#73 trend24h）才有逐小时数据，不拿别的指标冒充。 */
          var details = await settle(this.channels.map(function (m) {
            return D.channel(m.id).then(function (r) { return { m: m, r: r }; });
          }));
          var acc = [], cnt = [];
          details.forEach(function (x) {
            var t = (x && x.r && x.r.trend24h) || [];
            t.forEach(function (p, i) {
              if (p && p.ttftMs != null) { acc[i] = (acc[i] || 0) + Number(p.ttftMs); cnt[i] = (cnt[i] || 0) + 1; }
            });
          });
          /* ⚠️ acc 是**稀疏数组**（按小时下标赋值）。Array.prototype.map 会跳过空洞，
             留下 undefined，接着 Math.max(...undefined) 就是 NaN，
             整个「峰值 / 均值」全变 NaN。所以先按 0..maxHour 定长补 0 再算。 */
          var maxHour = -1;
          details.forEach(function (x) {
            var t = (x && x.r && x.r.trend24h) || [];
            if (t.length - 1 > maxHour) maxHour = t.length - 1;
          });
          var series = [];
          for (var hi = 0; hi <= maxHour; hi++) {
            series.push(cnt[hi] ? Math.round(acc[hi] / cnt[hi]) : 0);
          }
          this.ttftSeries = series;
          this.ttftPeak = series.length ? Math.max.apply(null, series) : 0;
          this.ttftAvg = series.filter(function (v) { return v > 0; }).length
            ? Math.round(series.reduce(function (a, b) { return a + b; }, 0) /
                         series.filter(function (v) { return v > 0; }).length)
            : 0;
        } catch (e) {
          fail(this, e);
        } finally {
          this.loading = false;
          ready(this);
        }
      });
      method(d, 'loadDetail', async function (m) {
        if (!m) return;
        m.testing = true;
        try {
          var r = await D.channel(m.id);
          var x = A.admChannelDetail(r);
          m.trend = x.trend; m.hours = x.hours; m.recent = x.recent;
          m.degradeEvents = x.degradeEvents; m.apiKeyMasked = x.apiKeyMasked;
          m.detailLoaded = true;
          if (r && r.healthStatus) m.state = A.admChannel(r).state;
          if (r && r.healthNote) m.note = r.healthNote;
        } catch (e) { fail(this, e); } finally { m.testing = false; }
      });
      /* 刷新：重拉列表 + 健康 + 统计（不再 800ms setTimeout） */
      method(d, 'refreshAll', async function () {
        if (this.refreshing) return;
        this.refreshing = true;
        try {
          await this.load();
          this.say('已同步 ' + this.channels.length + ' 条通道的健康状态');
        } finally { this.refreshing = false; }
      });
      /* 探活：真实发一次最小请求（#78），回显延迟 */
      method(d, 'test', async function (m) {
        if (!m || m.testing) return;
        m.testing = true;
        try {
          var r = await D.probeChannel(m.id);
          m.state = r && r.healthStatus ? (A.admChannel({ healthStatus: r.healthStatus }).state) : m.state;
          this.say(m.name + (r && r.latencyMs != null
            ? ' 连通正常 · ' + (r.latencyMs / 1000).toFixed(2) + 's'
            : ' 连通性已更新'));
          await this.load();
        } catch (e) {
          this.say(m.name + ' 探活失败：' + H.describe(e), 'err');
        } finally { m.testing = false; }
      });
      /* 降级通道的「重试」= #79 recover */
      method(d, 'retry', async function (m) {
        if (!m || m.testing) return;
        m.testing = true;
        try {
          await D.recoverChannel(m.id);
          this.say(m.name + ' 已恢复在线');
          await this.load();
        } catch (e) { this.say(m.name + ' 仍不可用：' + H.describe(e), 'warn'); }
        finally { m.testing = false; }
      });
      /* 启停：#76 state */
      method(d, 'toggleState', async function (m) {
        if (!m) return;
        var action = m.state === 'off' ? 'enable' : 'disable';
        try {
          await D.setChannelState(m.id, action);
          this.say(m.name + (action === 'enable' ? ' 已启用' : ' 已停用，流量切至备用通道'));
          await this.load();
          if (this.open && this.open.id === m.id) {
            var fresh = this.channels.filter(function (x) { return x.id === m.id; })[0];
            if (fresh) this.open = fresh;
          }
        } catch (e) { this.say(H.describe(e), 'err'); }
      });
      /* 新增：#74 */
      method(d, 'save', async function () {
        if (this.saving) return;
        this.formErr = '';
        var f = this.form;
        if (!f.name.trim()) { this.formErr = '请填写通道名称'; return; }
        if (!f.base.trim()) { this.formErr = '请填写接口地址'; return; }
        if (!f.roles.length) { this.formErr = '至少选择一个承担角色'; return; }
        this.saving = true;
        try {
          var code = (f.name.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '') || 'ch')
            .slice(0, 24);
          if (!/^[a-z]/.test(code)) code = 'c' + code;
          await D.createChannel({
            channelCode: code, channelName: f.name.trim(), vendor: f.vendor,
            modelName: f.model || f.name.trim(), roleBindings: f.roles,
            baseUrl: f.base.trim(), apiKey: f.key || '', timeoutMs: Number(f.timeout) || 8000,
            maxTokens: 2048, temperature: 0.3, maxConcurrency: 8, isEnabled: true
          });
          this.adding = false;
          await this.load();
          this.say('通道「' + f.name.trim() + '」已创建，正在做首次连通校验');
        } catch (e) {
          this.formErr = H.describe(e);
        } finally { this.saving = false; }
      });
      /* 自动降级开关：契约没有独立端点，作为本地展示开关，不再谎称已下发 */
      method(d, 'onDegradeSwitch', function () {
        this.say(this.autoDegrade ? '已开启自动降级（云端超时回落本地）' : '已关闭自动降级，超时请求将直接失败',
                 this.autoDegrade ? '' : 'warn');
      });

      prop(d, 'todayTotal', function () { return this.todayTotalS; });
      /* stateName / stKey 原本就是方法，仍按方法覆盖（四态：ON/DEGRADE/OFF/PROBING） */
      method(d, 'stateName', function (s) {
        if (s === 'on') return '在线';
        if (s === 'degrade') return '降级';
        if (s === 'probing') return '探测中';
        return '离线';
      });
      method(d, 'stKey', function (s) {
        return s === 'degrade' ? 'deg' : (s === 'off' ? 'off' : 'on');
      });
      /* PROBING 归到「在线」筛选里，否则刚建的通道会在任何筛选下都看不见 */
      method(d, 'countBy', function (k) {
        var self = this;
        if (k === 'all') return this.channels.length;
        return this.channels.filter(function (m) {
          return k === 'on' ? (m.state === 'on' || m.state === 'probing') : m.state === k;
        }).length;
      });
      prop(d, 'filtered', function () {
        var r = this.channels.slice();
        var self = this;
        if (this.st !== 'all') {
          r = r.filter(function (m) {
            return self.st === 'on' ? (m.state === 'on' || m.state === 'probing') : m.state === self.st;
          });
        }
        var k = this.kw.trim().toLowerCase();
        if (k) r = r.filter(function (m) {
          return (m.name + m.vendor + m.code + m.model).toLowerCase().indexOf(k) >= 0;
        });
        var key = this.sort.k, d0 = this.sort.d;
        r.sort(function (a, b) {
          var x = key === 'ttft' ? (a.ttftMs == null ? 1e9 : a.ttftMs) : a.today;
          var y = key === 'ttft' ? (b.ttftMs == null ? 1e9 : b.ttftMs) : b.today;
          return (x - y) * d0;
        });
        return r;
      });
      prop(d, 'healthSummary', function () {
        var bad = this.channels.filter(function (m) { return m.state !== 'on'; }).length;
        return bad
          ? this.channels.length + ' 条通道 · ' + bad + ' 条需要处理'
          : this.channels.length + ' 条通道 · 全部在线';
      });
      /* 抽屉里的「最近调用」：用 #73 的真实 recentCalls，不再从 OJ.aiCalls 里按名字猜 */
      method(d, 'recentCalls', function (m) { return (m && m.recent) || []; });
      prop(d, 'degradedCount', function () {
        return this.health && this.health.channels && this.health.channels.degrade != null
          ? this.health.channels.degrade
          : this.channels.filter(function (m) { return m.state === 'degrade'; }).length;
      });
      return d;
    };
  }

  /* ══════════════════════════════════════════════════════════════════
     ② a-audit.html —— 调用审计 + 沙箱运维
        #81 calls / #82 detail / #83 CSV / #84 sandbox / #85 logs / #86 kill / #80 health
        ⚠️ 审计 page 与沙箱 boxPage **必须分开**（原型两处共用一个 page，是已知 bug）。
     ══════════════════════════════════════════════════════════════════ */
  if (PAGE === 'a-audit' && g.auditPage) {
    var oldAudit = g.auditPage;
    g.auditPage = function () {
      var d = oldAudit();
      if (H.useMock()) return d;

      d.calls = [];
      d.boxes = [];
      d.hitDist = [];
      d.todayTotal = 0;
      d.summary = null;
      d.boxStats = null;
      d.health = null;
      d.loading = true;
      d.loadErr = false;
      d.page = 1;
      d.pageSize = 8;
      d.boxPage = 1;               /* ← 与审计 page 分开 */
      d.boxPageSize = 10;
      d.totalCalls = 0;
      d.boxTotal = 0;
      d.pageCountS = 1;
      d.exporting = false;
      d.refreshing = false;
      d.logLoading = false;

      var STAGE_NO = { '① 读题拆解': 1, '② 编码引导': 2, '③ 排错诊断': 3, '④ AC 复盘': 4 };

      method(d, 'init', async function () {
        if (location.hash === '#sandbox') this.tab = 'sandbox';
        await this.load();
        if (g.OJ.qs('err') === '1') this.loadErr = true;
        var self = this;
        /* 筛选 / 排序 / 翻页都走服务端（#81 支持 stage/channelCode/guard/kw/range/sortBy） */
        this.$watch('fStage', function () { self.page = 1; self.load(); });
        this.$watch('fGuard', function () { self.page = 1; self.load(); });
        this.$watch('fKw', function () { clearTimeout(self._kwT); self._kwT = setTimeout(function () { self.page = 1; self.load(); }, 350); });
        this.$watch('page', function () { self.load(); });
        this.$watch('sort', function () { self.page = 1; self.load(); });
        this.$watch('openCall', function (v) { if (v && !v.detailLoaded) self.loadCallDetail(v); });
        this.$watch('openBox', function (v) { if (v && !v.logLoaded) self.loadLogs(v); });
      });
      method(d, 'retryAll', async function () { this.loadErr = false; await this.load(); });

      method(d, 'load', async function () {
        this.loading = true; this.loadErr = false;
        try {
          var q = {
            page: this.page, pageSize: this.pageSize,
            stage: STAGE_NO[this.fStage] || null,
            guard: this.fGuard === 'hit' ? 'hit' : null,
            kw: this.fKw.trim() || null,
            sortBy: this.sort.k === 'tok' ? 'tokens' : this.sort.k,
            sortOrder: this.sort.d > 0 ? 'asc' : 'desc'
          };
          var res = await settle([
            D.calls(q),
            D.sandboxes({ page: this.boxPage, pageSize: this.boxPageSize, autoRefresh: this.autoRefresh }),
            D.health(false)
          ]);
          var calls = res[0], box = res[1], health = res[2];

          this.calls = ((calls && calls.list) || []).map(A.admCall);
          this.totalCalls = calls && calls.total != null ? calls.total : this.calls.length;
          this.pageCountS = calls && calls.hasNext === false
            ? Math.max(1, Math.ceil(this.totalCalls / this.pageSize))
            : Math.max(1, Math.ceil(this.totalCalls / this.pageSize));
          var sum = (calls && calls.summary) || null;
          this.summary = sum;
          this.todayTotal = sum && sum.todayTotal != null ? sum.todayTotal : 0;
          this.hitDist = A.admHitDist(sum && sum.hitDist);

          this.boxes = ((box && box.list) || []).map(A.admSandbox);
          this.boxTotal = box && box.total != null ? box.total : this.boxes.length;
          this.boxStats = (box && box.stats) || null;
          this.health = health || null;
        } catch (e) {
          fail(this, e);
        } finally {
          this.loading = false;
          ready(this);
        }
      });
      method(d, 'loadCallDetail', async function (c) {
        if (!c) return;
        try {
          var r = await D.call(c.recordId);
          c.detail = A.admCallDetail(r);
          if (r) {
            c.promptText = c.detail.promptText;
            c.replyText = c.detail.replyText;
            c.guardReason = c.detail.guardReason;
            if (r.guardCode) c.guard = r.guardCode;
            if (r.channelCode) c.channelCode = r.channelCode;
            if (r.userName) c.who = r.userName;
          }
          c.detailLoaded = true;
        } catch (e) { fail(this, e); }
      });
      method(d, 'loadLogs', async function (b) {
        if (!b || this.logLoading) return;
        this.logLoading = true;
        try {
          var r = await D.sandboxLogs(b.id, 200);
          b.logs = ((r && r.lines) || []).map(A.admLogLine);
          b.logTruncated = !!(r && r.truncated);
          b.logLoaded = true;
        } catch (e) { fail(this, e); } finally { this.logLoading = false; }
      });
      /* 刷新：重拉列表 + 沙箱（真实请求，不再 800ms setTimeout） */
      method(d, 'refresh', async function () {
        if (this.refreshing) return;
        this.refreshing = true;
        try { await this.load(); this.say('已同步最新 ' + this.totalCalls + ' 条调用记录'); }
        finally { this.refreshing = false; }
      });
      /* CSV 导出：api.http.get(url,{raw:true}) → blob 下载（#83） */
      method(d, 'exportCsv', async function () {
        if (this.exporting) return;
        this.exporting = true;
        try {
          var q = {
            stage: STAGE_NO[this.fStage] || null,
            guard: this.fGuard === 'hit' ? 'hit' : null,
            kw: this.fKw.trim() || null,
            sortBy: this.sort.k === 'tok' ? 'tokens' : this.sort.k,
            sortOrder: this.sort.d > 0 ? 'asc' : 'desc'
          };
          var r = await D.exportCalls(q, 'AI调用审计.csv');
          this.say('已导出 CSV（' + Math.max(1, Math.round((r.size || 0) / 1024)) + ' KB）');
        } catch (e) {
          this.say('导出失败：' + H.describe(e), 'err');
        } finally { this.exporting = false; }
      });
      /* 终止容器：#86，成功后重拉（不再本地改 state + 清零 cpu/mem） */
      method(d, 'kill', async function (b) {
        if (!b || b.killing || b.state === 'exited') return;
        b.killing = true;
        try {
          var r = await D.killSandbox(b.id, '管理员手动终止');
          b.state = 'exited';
          b.stateRaw = 'KILLED';
          b.cpu = 0; b.mem = 0;
          this.say(b.id + ' 已终止，资源已回收' +
            (r && r.releasedMemKb != null ? '（释放 ' + Math.round(r.releasedMemKb / 1024) + ' MB）' : ''), 'warn');
          await this.load();
        } catch (e) { this.say('终止失败：' + H.describe(e), 'err'); }
        finally { b.killing = false; }
      });
      /* 日志下载：用已拉到的真实日志拼文本下载（不再只 toast「已加入队列」） */
      method(d, 'downloadLog', function (b) {
        var lines = this.logLines(b);
        if (!lines.length) { this.say('该容器还没有日志可下载', 'warn'); return; }
        var txt = lines.map(function (l) { return l.ts + ' ' + l.msg; }).join('\r\n');
        D.saveBlob(new Blob(['\ufeff' + txt], { type: 'text/plain;charset=utf-8' }), (b.id || 'sandbox') + '.log');
        this.say('已下载 ' + lines.length + ' 行容器日志');
      });
      method(d, 'copyLog', function (b) {
        var lines = this.logLines(b);
        var txt = lines.map(function (l) { return l.ts + ' ' + l.msg; }).join('\n');
        if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(txt).catch(function () {});
        this.say('已复制 ' + lines.length + ' 行容器日志');
      });

      /* ── 派生（服务端已筛选/分页，前端不再二次过滤） ── */
      prop(d, 'filteredCalls', function () {
        var r = this.calls.slice();
        /* #81 的契约参数里没有 route / model 两维，这两个筛选只能落在当页；
           其余（阶段/护栏/关键词/排序）都是服务端口径。 */
        if (this.fRoute !== 'all') r = r.filter(function (c) { return c.route === this.fRoute; }.bind(this));
        if (this.fModels.length) {
          var self = this;
          r = r.filter(function (c) { return self.fModels.indexOf(c.model) >= 0; });
        }
        return r;
      });
      prop(d, 'pagedCalls', function () { return this.filteredCalls; });
      prop(d, 'pageCount', function () {
        return Math.max(1, Math.ceil(this.totalCalls / this.pageSize));
      });
      prop(d, 'filteredBoxes', function () { return this.boxes.slice(); });
      prop(d, 'pagedBoxes', function () { return this.boxes.slice(); });
      prop(d, 'boxCount', function () {
        var st = this.boxStats;
        var total = this.boxTotal, boxes = this.boxes;
        return function (k) {
          if (k === 'all') return total != null ? total : boxes.length;
          if (st && st[k] != null) return st[k];
          return boxes.filter(function (b) { return b.state === k; }).length;
        };
      });
      /* 护栏主带：今日拦截数 = 命中分布求和（#81 summary.hitDist，契约只给 block 类命中） */
      prop(d, 'guardBlocked', function () {
        var n = 0;
        (this.hitDist || []).forEach(function (h) { n += h.v; });
        return n;
      });
      /* 今日 rewrite 次数：契约 #81 的 summary 只有 todayTotal 与 hitDist，
         没有全局 rewrite 计数。给 null（页面文案改为拦截次数/拦截率），不臆造。 */
      prop(d, 'todayRewrite', function () { return null; });
      /* prompt / reply / 判分链：真实数据来自 #82 详情，字段缺失给 '—' */
      method(d, 'promptOf', function (c) { return (c && c.promptText) || '（本次调用没有留下 prompt 摘要）'; });
      method(d, 'replyOf', function (c) { return (c && c.replyText) || '（本次调用没有留下回复摘要）'; });
      method(d, 'chainOf', function (c) {
        var chain = (c && c.detail && c.detail.chain) || [];
        if (!chain.length) return new Array(10).fill('skip');
        var out = chain.slice(0, 10).map(function (x) { return x.status || ''; });
        while (out.length < 10) out.push('skip');
        return out;
      });
      method(d, 'chainNote', function (c) {
        var chain = (c && c.detail && c.detail.chain) || [];
        if (!chain.length) return '这次调用没有触碰任何测试点';
        var hit = chain.filter(function (x) { return x.status && x.status !== 'ac'; })[0];
        return hit ? ('判分链共 ' + chain.length + ' 个测试点，本次停在 #' + hit.seqNo) : ('判分链共 ' + chain.length + ' 个测试点');
      });
      method(d, 'logLines', function (b) {
        return (b && b.logs && b.logs.length) ? b.logs : [{ ts: '--:--:--', msg: '[sandbox] 没有可用的日志（或尚未加载）', cls: '' }];
      });
      method(d, 'openBoxFromCall', function (c) {
        this.openCall = null;
        this.tab = 'sandbox';
        var sid = c && (c.submissionId != null ? c.submissionId :
          (c.detail && c.detail.sandboxInstanceId));
        var hit = this.boxes.filter(function (b) {
          return (sid != null && b.submissionId === sid) ||
                 (c && c.userId != null && b.userId === c.userId);
        })[0];
        this.openBox = hit || at(this.boxes, 0) || null;
      });
      return d;
    };
  }
})(window);
