/* api/poll.js —— 自适应轮询器（判题进度）
   ─────────────────────────────────────────────────────────────────────
   判题是唯一必须轮询的链路（0.5–10s 一次的短周期状态查询用 GET 比 SSE 省得多）。
   四条硬要求：1s 一次、指数退避、页面隐藏暂停、终态自停。
   严禁 setInterval：① 网络一慢就堆请求；② 退避要动态改间隔，clear 再建更绕；
   ③ 后台标签页 setInterval 节流不可控，轮询会在用户看不见的时候烧配额。
   规格来源：docs/analysis/03-前端对接实施指南.md §2.5（源码照抄）。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function Poller(task, opts) {
    opts = opts || {};
    this.task = task;
    this.interval = opts.interval || 1000;      // 起始 1s
    this.max = opts.max || 5000;                // 退避上限
    this.limit = opts.limit || 120;             // 最多 120 次 ≈ 2 分钟
    this.isDone = opts.isDone || function () { return false; };
    this.onData = opts.onData || function () {};
    this.onError = opts.onError || function () {};
    this.onStop = opts.onStop || function () {};
    this.n = 0; this.timer = null; this.stopped = false; this.hidden = false;
  }

  Poller.prototype.start = function () {
    var self = this;
    this.stopped = false;
    this.n = 0;                                            // 重新 start 视为新一轮，重置计数
    if (!this._bound) {
      this._bound = true;
      this._onVis = function () {
        self.hidden = document.hidden;
        if (self.hidden) self.clear();                     // 页面隐藏立即停表
        else if (!self.stopped) { self.n = 0; self.tick(); }   // 回到前台补齐并重新计次
      };
      document.addEventListener('visibilitychange', this._onVis);
    }
    this.tick();
    return this;
  };

  Poller.prototype.clear = function () { clearTimeout(this.timer); this.timer = null; };

  Poller.prototype.tick = function () {
    var self = this;
    if (this.stopped || this.hidden) return;
    this.n += 1;
    this.task().then(function (data) {
      if (self.stopped) return;
      self.onData(data);
      if (self.isDone(data)) { self.stop('done'); return; }
      self.schedule(self.interval);                        // 有响应就回到 1s
    }).catch(function (e) {
      if (self.stopped) return;
      self.onError(e);
      /* 这三类错误继续轮询没有意义：401 要重新登录，403 无权限，404 提交号不存在 */
      if (e.code === 401 || e.code === 403 || e.code === 404) { self.stop('fatal'); return; }
      self.schedule(Math.min(self.max, self.interval * Math.pow(2, Math.min(self.n, 3))));
    });
  };

  Poller.prototype.schedule = function (ms) {
    var self = this;
    this.clear();
    if (this.n >= this.limit) { this.stop('limit'); return; }
    this.timer = setTimeout(function () { self.tick(); }, ms);
  };

  Poller.prototype.stop = function (why) {
    if (this.stopped) return;
    this.stopped = true;
    this.clear();
    if (this._bound) document.removeEventListener('visibilitychange', this._onVis);
    this.onStop(why);
  };

  global.api = global.api || {};
  global.api.poll = function (task, opts) { return new Poller(task, opts); };
  global.api.Poller = Poller;              // 需要拿实例做类型判断时用
})(window);
