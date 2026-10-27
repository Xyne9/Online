/* api/sse.js —— fetch + response.body.getReader() + TextDecoder，禁用 EventSource
   ─────────────────────────────────────────────────────────────────────
   为什么不用 EventSource：① 无法设置 Authorization 头；② 只能 GET，而
   /api/ai/chat/stream 要 POST body；③ 无法用 AbortController 取消，切题后
   旧流会继续往旧气泡里吐字；④ 读不到 HTTP 错误码，429 配额耗尽会被当成
   一次普通断线自动重连，越重连越耗额度。
   规格来源：docs/analysis/03-前端对接实施指南.md §2.3（源码照抄）。
   ───────────────────────────────────────────────────────────────────── */
(function (global) {
  'use strict';

  function SSEClient(url, opts) {
    this.url = url; this.opts = opts || {};
    this.ctrl = new AbortController();
    this.closed = false; this.handlers = {}; this.buf = ''; this.frames = 0;
  }
  SSEClient.prototype.on = function (name, fn) { this.handlers[name] = fn; return this; };
  SSEClient.prototype.emit = function (name, payload, raw) {
    var fn = this.handlers[name] || this.handlers['*'];
    if (fn) { try { fn(payload, raw); } catch (e) { console.error('[sse]', name, e); } }
  };

  /* 帧格式：event: token / data: {...} / 空行结束；':' 开头是心跳注释；data 可多行 */
  SSEClient.prototype.parse = function (chunk) {
    var self = this, idx;
    this.buf += chunk;
    while ((idx = this.buf.indexOf('\n\n')) !== -1) {
      var block = this.buf.slice(0, idx);
      this.buf = this.buf.slice(idx + 2);
      var ev = 'message', data = [];
      block.split('\n').forEach(function (line) {
        if (!line || line.charAt(0) === ':') return;          // 心跳 / 注释行整行丢弃
        var p = line.indexOf(':');
        var field = p === -1 ? line : line.slice(0, p);
        var value = p === -1 ? '' : line.slice(p + 1).replace(/^ /, '');   // 规范：冒号后只吃一个空格
        if (field === 'event') ev = value;
        else if (field === 'data') data.push(value);
      });
      if (!data.length) continue;
      var text = data.join('\n');                             // 多行 data 按规范拼接
      if (text === '[DONE]') { self.close(); return; }         // 终止帧
      var payload;
      try { payload = JSON.parse(text); } catch (e) { payload = { raw: text }; }
      self.frames++;
      if (self.opts.onFrame) self.opts.onFrame(ev, payload);
      self.emit(ev, payload, text);
    }
  };

  /* MOCK 分支：mock.js 的 /api/ai/chat/stream 返回 {frames:[[事件名, 载荷], …]}，
     这里按 260~420ms 的节奏回放，让演示时能看见"逐字吐字 + 阶段胶囊切换"。
     这是对文档源码的唯一增量（文档 §2.4 的注释里已写明「由 sse.js 的 mock 分支消费」），
     真实链路一个字节都不受影响。 */
  SSEClient.prototype._startMock = function () {
    var self = this, frames = [];
    var done = false;
    var stop = function () { done = true; self.close(); };
    this._mockStop = stop;
    global.OJMock.request('POST', this.url, { body: this.opts.body || {}, query: {} })
      .then(function (d) {
        frames = (d && d.frames) || [];
        var i = 0;
        (function step() {
          if (self.closed || done) return;
          if (i >= frames.length) { self.emit('end', {}); return; }
          var f = frames[i++];
          self.frames++;
          if (self.opts.onFrame) self.opts.onFrame(f[0], f[1]);
          self.emit(f[0], f[1]);
          /* done 事件之后不再继续推，避免"配额已写、还在吐字"的错觉 */
          if (f[0] === 'done') { self.emit('end', {}); return; }
          self.timer = setTimeout(step, 260 + (i % 3) * 80);
        })();
      })
      .catch(function (e) {
        if (self.closed) return;
        self.emit('error', { code: e.code || 0, message: e.message });
        self.emit('fatal', { message: e.message || '连接失败，请稍后重试' });
      });
  };

  SSEClient.prototype.start = function () {
    var self = this;
    if (global.api.http.useMock() && global.OJMock) { this._startMock(); return Promise.resolve(); }
    var headers = { 'Content-Type': 'application/json;charset=UTF-8', 'Accept': 'text/event-stream' };
    var tk = global.api.http.token.get();
    if (tk) headers.Authorization = 'Bearer ' + tk;
    headers['X-Trace-Id'] = 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    this.buf = '';
    this.frames = 0;                                        // 每次（重）连都重置首字计数
    this.ctrl = new AbortController();                       // 重连必须换一个新的 controller

    var guard = setTimeout(function () {                    // 首字超时：模型侧卡死时给用户一个交代
      if (self.frames === 0 && !self.closed) { self.emit('error', { code: 504, message: '模型首字超时' }); self.close(); }
    }, this.opts.firstTokenTimeoutMs || 15000);

    return fetch(this.url, {
      method: 'POST', headers: headers, credentials: 'omit',
      body: JSON.stringify(this.opts.body || {}), signal: this.ctrl.signal
    }).then(function (res) {
      if (res.status === 401) {
        global.api.http.token.set('');
        /* 走全局跳转口（先掐 View Transition），没有就退回裸 replace */
        if (global.OJ_GO) global.OJ_GO('login.html'); else location.replace('login.html');
        throw new Error('401');
      }
      if (!res.ok) {
        return res.text().then(function (t) {
          var env = {}; try { env = JSON.parse(t); } catch (e) {}
          var err = new Error(env.message || ('SSE HTTP ' + res.status));
          err.code = env.code || res.status; err.data = env.data; throw err;
        });
      }
      if (!res.body || !res.body.getReader) throw new Error('当前浏览器不支持 ReadableStream');
      var reader = res.body.getReader();
      var decoder = new TextDecoder('utf-8');
      function pump() {
        return reader.read().then(function (r) {
          if (r.done) { clearTimeout(guard); self.emit('end', {}); return; }
          clearTimeout(guard);
          /* stream:true 处理跨 chunk 的多字节字符——不加会把中文切成乱码 */
          self.parse(decoder.decode(r.value, { stream: true }));
          return pump();
        });
      }
      return pump();
    }).catch(function (e) {
      clearTimeout(guard);
      if (self.closed || e.name === 'AbortError') return;
      self.emit('error', { code: e.code || 0, message: e.message, data: e.data });
      if (self.opts.reconnect !== false) self.retry(e);
    });
  };

  SSEClient.prototype.retry = function (cause) {
    var self = this;
    if (cause && (cause.code === 429 || cause.code === 403 || cause.code === 401)) return;   // 不可重试
    this.tries = (this.tries || 0) + 1;
    if (this.tries > (this.opts.maxRetries === undefined ? 2 : this.opts.maxRetries)) {
      this.emit('fatal', { message: '连接失败，请稍后重试' }); return;
    }
    var wait = 800 * Math.pow(2, this.tries - 1);            // 800ms → 1600ms
    this.emit('reconnecting', { inMs: wait, attempt: this.tries });
    this.timer = setTimeout(function () { if (!self.closed) self.start(); }, wait);
  };

  SSEClient.prototype.close = function () {
    this.closed = true;
    clearTimeout(this.timer);
    try { this.ctrl.abort(); } catch (e) {}
  };

  global.api = global.api || {};
  /* 便捷函数：api.sse(url, opts) 既建实例又自动 start，
     返回的是同一枚 SSEClient（可继续 .on(...) 链式注册） */
  global.api.sse = function (url, opts) {
    var c = new SSEClient(url, opts);
    c.start();
    return c;
  };
  global.api.SSEClient = SSEClient;      // 需要先挂监听再启动的场景用这个（如 mock 回放）
})(window);
