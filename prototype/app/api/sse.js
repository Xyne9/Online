/* fetch SSE：沿用 api.http 的 BASE / Bearer；POST 不自动重放，避免重复扣配额。 */
(function (global) {
  'use strict';

  function SSEClient(url, opts) {
    this.url = url; this.opts = opts || {};
    this.ctrl = new AbortController();
    this.closed = false; this.started = false; this.ended = false;
    this.handlers = {}; this.buf = ''; this.frames = 0;
  }
  SSEClient.prototype.on = function (name, fn) { this.handlers[name] = fn; return this; };
  SSEClient.prototype.emit = function (name, payload, raw) {
    var fn = this.handlers[name] || this.handlers['*'];
    if (fn) { try { fn(payload, raw); } catch (e) { console.error('[sse]', name, e); } }
  };
  SSEClient.prototype.finish = function () {
    if (this.ended) return;
    this.ended = true;
    this.close();
    this.emit('end', {});
  };
  SSEClient.prototype.dispatch = function (name, payload, raw) {
    if (this.closed) return;
    this.frames++;
    clearTimeout(this.guard);
    if (this.opts.onFrame) this.opts.onFrame(name, payload);
    this.emit(name, payload, raw);
    if (name === 'done' || name === 'error') this.finish();
  };

  /* 保留跨 chunk 的 CRLF 和多字节字符；注释心跳不算业务首帧。 */
  SSEClient.prototype.parse = function (chunk) {
    this.buf += chunk;
    var sep;
    while (!this.closed && (sep = /\r\n\r\n|\n\n|\r\r/.exec(this.buf))) {
      var block = this.buf.slice(0, sep.index);
      this.buf = this.buf.slice(sep.index + sep[0].length);
      var ev = 'message', data = [];
      block.split(/\r\n|\n|\r/).forEach(function (line) {
        if (!line || line.charAt(0) === ':') return;
        var p = line.indexOf(':');
        var field = p === -1 ? line : line.slice(0, p);
        var value = p === -1 ? '' : line.slice(p + 1).replace(/^ /, '');
        if (field === 'event') ev = value;
        else if (field === 'data') data.push(value);
      });
      if (!data.length) continue;
      var raw = data.join('\n'), payload;
      if (raw === '[DONE]') { this.finish(); return; }
      try { payload = JSON.parse(raw); } catch (e) { payload = { raw: raw }; }
      this.dispatch(ev, payload, raw);
    }
  };

  SSEClient.prototype.fail = function (e) {
    if (this.closed) return;
    this.emit('error', { code: Number(e.code) || 0, message: e.message || '连接失败，请稍后重试', data: e.data });
    this.finish();
  };
  SSEClient.prototype.touch = function () {
    var self = this;
    clearTimeout(this.idle);
    this.idle = setTimeout(function () {
      self.fail({ code: 504, message: 'AI 连接长时间无响应，请手动重试' });
    }, this.opts.idleTimeoutMs || 45000);
  };
  SSEClient.prototype._startMock = function () {
    var self = this;
    return global.OJMock.request('POST', this.url, { body: this.opts.body || {}, query: {} })
      .then(function (d) {
        var frames = (d && d.frames) || [], i = 0;
        (function step() {
          if (self.closed) return;
          if (i >= frames.length) { self.finish(); return; }
          var f = frames[i++];
          self.dispatch(f[0], f[1]);
          if (!self.closed) self.timer = setTimeout(step, 260 + (i % 3) * 80);
        })();
      }).catch(function (e) { self.fail(e); });
  };

  SSEClient.prototype.start = function () {
    if (this.started || this.closed) return this.promise || Promise.resolve();
    this.started = true;
    var self = this, http = global.api.http;
    if (http.useMock() && global.OJMock) {
      this.promise = this._startMock();
      return this.promise;
    }
    var headers = { 'Content-Type': 'application/json;charset=UTF-8', 'Accept': 'text/event-stream' };
    var tk = http.token.get();
    if (tk) headers.Authorization = 'Bearer ' + tk;
    headers['X-Trace-Id'] = 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    var target = /^(?:https?:)?\/\//i.test(this.url) ? this.url : (http.BASE || '') + this.url;
    this.guard = setTimeout(function () {
      self.fail({ code: 504, message: '模型首字超时，请手动重试' });
    }, this.opts.firstTokenTimeoutMs || 15000);

    this.promise = fetch(target, {
      method: 'POST', headers: headers, credentials: 'omit',
      body: JSON.stringify(this.opts.body || {}), signal: this.ctrl.signal
    }).then(function (res) {
      if (self.closed) return;
      if (res.status === 401) {
        http.token.set('');
        if (global.OJ_GO) global.OJ_GO('login.html'); else global.location.replace('login.html');
      }
      if (!res.ok) {
        return res.text().then(function (t) {
          var env = {}; try { env = JSON.parse(t); } catch (e) {}
          var err = new Error(env.message || ('SSE HTTP ' + res.status));
          err.code = env.code || res.status; err.data = env.data; throw err;
        });
      }
      if (!res.body || !res.body.getReader) throw new Error('当前浏览器不支持 ReadableStream');
      self.reader = res.body.getReader();
      self.touch();
      var decoder = new TextDecoder('utf-8');
      function pump() {
        if (self.closed) return;
        return self.reader.read().then(function (r) {
          if (self.closed) return;
          if (r.done) {
            self.parse(decoder.decode());
            self.finish();
            return;
          }
          self.touch();
          self.parse(decoder.decode(r.value, { stream: true }));
          return pump();
        });
      }
      return pump();
    }).catch(function (e) {
      if (!self.closed && e.name !== 'AbortError') self.fail(e);
    });
    return this.promise;
  };

  SSEClient.prototype.close = function () {
    this.closed = true;
    clearTimeout(this.timer); clearTimeout(this.guard); clearTimeout(this.idle);
    try { this.ctrl.abort(); } catch (e) {}
    if (this.reader) { try { this.reader.cancel().catch(function () {}); } catch (e) {} }
  };

  global.api = global.api || {};
  global.api.sse = function (url, opts) {
    var c = new SSEClient(url, opts);
    /* 允许调用者在同步 mock/失败之前完整挂好监听。 */
    Promise.resolve().then(function () { c.start(); });
    return c;
  };
  global.api.SSEClient = SSEClient;
})(window);
