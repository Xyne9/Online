/* 教学请求：所有鉴权、错误和传输仍由 http.js / sse.js 处理。 */
(function (g) {
  'use strict';
  var http = g.api.http;
  g.api.teaching = {
    async all(url, query) {
      var list = [], page = 1, result;
      do {
        result = await http.get(url, { query: Object.assign({}, query, { page: page++, pageSize: 100 }) });
        list = list.concat(result.list || []);
      } while (result.hasNext);
      return list;
    },
    assignments(query) { return this.all('/api/teaching/assignments', query); },
    problems(query) { return this.all('/api/problem/questions', query); },
    events(url, handlers, lastId) {
      var client = g.api.sse(url, { method: 'GET', firstTokenTimeoutMs: 20000,
        idleTimeoutMs: 45000, lastEventId: lastId });
      Object.keys(handlers).forEach(function (name) { client.on(name, handlers[name]); });
      client.start();
      return client;
    }
  };
})(window);
