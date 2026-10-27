/* api/admin.js —— 平台运维（admin）请求封装
   ─────────────────────────────────────────────────────────────────────
   覆盖 02 契约 §7（236 行起共 16 端点）：
     §7.2 模型通道簇 #72~#79（列表 / 详情 / 新增 / 更新 / 删除 / 状态 / 探活 / 恢复）
     §7.3 健康聚合 #80
     §7.4 AI 调用审计 #81~#83（列表 / 详情 / CSV 导出）
     §7.5 沙箱运维 #84~#86（列表 / 日志 / 终止）
     §7.6 全站统计 #87

   ⚠️ CSV 导出走 http.get(url, {raw:true})：http.js 在 opts.raw 下直接回
   Response（不解信封、不判 code），这里再 blob() 触发下载。
   ───────────────────────────────────────────────────────────────────── */
(function (g) {
  'use strict';
  var http = g.api.http;

  /* 文件名从 Content-Disposition 里取。
     ⚠️ 必须**优先** `filename*=`（RFC 5987，UTF-8 百分号编码）而不是 `filename=`：
     后端实际下发的是
       attachment; filename="ai-calls-202610272327.csv"; filename*=UTF-8''ai-calls-202610272327.csv
     两个都在时，`filename=` 是给老客户端的 ASCII 兜底（可能被替换成 ??? 或拼音），
     `filename*=` 才是原文。先匹配带星号的，取不到再退普通名。 */
  function fileNameOf(res, fallback) {
    var cd = '';
    try { cd = res.headers.get('Content-Disposition') || ''; } catch (e) {}
    if (!cd) return fallback;
    var star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/i.exec(cd);
    if (star && star[1]) {
      var raw = star[1].trim().replace(/^"|"$/g, '');
      try { return decodeURIComponent(raw); } catch (e) { return raw; }
    }
    var plain = /filename\s*=\s*"?([^";]+)"?/i.exec(cd);
    if (plain && plain[1]) {
      var v = plain[1].trim();
      try { return decodeURIComponent(v); } catch (e) { return v; }
    }
    return fallback;
  }
  function saveBlob(blob, name) {
    var a = document.createElement('a'), url = URL.createObjectURL(blob);
    a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
  }

  g.api.admin = {
    /* ── §7.2 模型通道 ── */
    /* 不翻页：通道数量是个位数，直接要一页 100 条；返回原始响应（含 summary） */
    channels: function (query) {
      return http.get('/api/admin/channels', { query: Object.assign({ page: 1, pageSize: 100 }, query || {}) });
    },
    channel: function (id) { return http.get('/api/admin/channels/' + id); },
    createChannel: function (body) { return http.post('/api/admin/channels', body); },
    updateChannel: function (id, body) { return http.put('/api/admin/channels/' + id, body); },
    deleteChannel: function (id, force) {
      return http.del('/api/admin/channels/' + id, { query: { force: force ? true : null } });
    },
    setChannelState: function (id, action) {
      return http.put('/api/admin/channels/' + id + '/state', { action: action });
    },
    probeChannel: function (id, timeoutMs) {
      return http.post('/api/admin/channels/' + id + '/probe', timeoutMs ? { timeoutMs: timeoutMs } : {});
    },
    recoverChannel: function (id) { return http.post('/api/admin/channels/' + id + '/recover', {}); },

    /* ── §7.3 健康聚合 ── */
    health: function (deep) { return http.get('/api/admin/health', { query: { deep: deep ? true : null } }); },

    /* ── §7.4 AI 调用审计 ── */
    calls: function (query) {
      return http.get('/api/admin/audit/calls', { query: Object.assign({ page: 1, pageSize: 100 }, query || {}) });
    },
    call: function (recordId) { return http.get('/api/admin/audit/calls/' + recordId); },
    /* 导出：raw 拿 Response → blob 下载。超时放宽到 60s（后端要流式扫 5000 行） */
    exportCalls: function (query, fallbackName) {
      return http.get('/api/admin/audit/calls/export', {
        query: query || {}, raw: true, timeoutMs: 60000
      }).then(function (res) {
        /* ⚠️ http.js 在 opts.raw 下**直接返回 Response**，不解信封、也不处理 401
           （那条逻辑在非 raw 分支里）。所以这里必须自己处理 401：
           否则用户会看到「导出失败（HTTP 401）」而不是被引导去重新登录。 */
        if (res.status === 401) {
          http.token.set('');
          throw http.ApiError(401, http.MESSAGES[401], null);
        }
        if (!res.ok) return res.text().then(function (t) {
          var msg = '导出失败（HTTP ' + res.status + '）';
          try { var j = JSON.parse(t); if (j && j.message) msg = j.message; } catch (e) {}
          throw http.ApiError(res.status, msg, null);
        });
        return res.blob().then(function (b) {
          saveBlob(b, fileNameOf(res, fallbackName || 'AI调用审计.csv'));
          return { size: b.size, name: fileNameOf(res, fallbackName || 'AI调用审计.csv') };
        });
      });
    },

    /* ── §7.5 沙箱运维 ── */
    sandboxes: function (query) {
      return http.get('/api/admin/sandbox/instances', { query: Object.assign({ page: 1, pageSize: 100 }, query || {}) });
    },
    sandboxLogs: function (sandboxInstanceId, lines) {
      return http.get('/api/admin/sandbox/instances/' + encodeURIComponent(sandboxInstanceId) + '/logs',
        { query: { lines: lines || null } });
    },
    killSandbox: function (sandboxInstanceId, reason) {
      return http.del('/api/admin/sandbox/instances/' + encodeURIComponent(sandboxInstanceId),
        { query: { reason: reason || null } });
    },

    /* ── §7.6 全站统计 ── */
    stats: function (range) { return http.get('/api/admin/stats', { query: { range: range || null } }); },

    /* 小工具复用（页面不要各写一份 blob 下载） */
    saveBlob: saveBlob
  };
})(window);
