/* 教师题库真实态控制器。原型工厂只用于 ?mock=1，模板与样式保持不变。 */
(function (g) {
  'use strict';
  var H = g.api.http, T = g.api.teaching, A = g.adapter;
  var oldList = g.tProblemsPage, oldEdit = g.tProblemEditPage;
  function error(page, e) { page.toast(H.describe(e), 'warn'); }
  if (oldList) g.tProblemsPage = function () {
    var d = oldList();
    if (H.useMock()) return d;
    d.list = [];
    Object.assign(d, {
      async init() { this.state = g.OJ.qs('state') || 'all'; await this.retry(); },
      async retry() {
        this.loading = true; this.loadErr = false;
        try {
          this.list = (await T.problems({ includeDraft: true })).map(function (p) {
            return Object.assign(A.adaptProblem(p), { state: ['draft','published','archived'][p.state],
              updated: A.fmtDateTime(p.updatedAt), submits: p.triedCount || 0,
              tps: [], onCount: p.testCaseCount || 0, offCount: 0, raw: p });
          });
        } catch (e) { this.loadErr = true; error(this, e); }
        finally { this.loading = false; }
      },
      async openDetail(p) {
        this.detail = p;
        try {
          var r = await H.get('/api/problem/questions/' + p.id + '/test-cases', { query: { withContent: false } });
          p.tps = r.list.map(function (t) { return { id: t.id, kind: t.kind,
            size: t.inputSize + ' B', out: t.isPublic ? (t.output || '—') : '—', on: true }; });
        } catch (e) { error(this, e); }
      },
      // 配置测试点不代表通过测试点，不由整题 AC 率伪造逐点结果。
      chainOf(p) { return Array(p.raw.testCaseCount || 0).fill(''); },
      okCells() { return 0; },
      tpTitle(p) { return (p.raw.testCaseCount || 0) + ' 个测试点 · 逐点通过率尚未统计'; },
      async change(ids, action) {
        try {
          for (var id of ids) await H.put('/api/problem/questions/' + id + '/state', { action: action });
          this.sel = []; await this.retry(); this.toast('状态已更新', 'ok', false);
        } catch (e) { error(this, e); await this.retry(); }
      },
      batchPublish() { return this.change(this.sel.slice(), 'publish'); },
      batchArchive() { return this.change(this.sel.slice(), 'archive'); },
      archive(p) { return this.change([p.id], p.state === 'archived' ? 'restore' : 'archive'); },
      async importProblems() {
        var picker = document.createElement('input'); picker.type = 'file'; picker.accept = '.json,application/json';
        picker.onchange = async () => {
          try {
            var json = JSON.parse(await picker.files[0].text());
            var r = await H.post('/api/problem/questions/import', { format:'json', items:Array.isArray(json)?json:json.items, dryRun:false });
            await this.retry(); this.toast('导入完成：成功 ' + r.succeeded + ' 道，失败 ' + r.failed + ' 道', r.failed?'warn':'ok');
          } catch (e) { error(this, e); }
        };
        picker.click();
      },
      undoToast() { this.toast('当前状态流转不支持撤销，请使用恢复操作', 'warn', false); }
    });
    return d;
  };
  if (oldEdit) g.tProblemEditPage = function () {
    var d = oldEdit();
    if (H.useMock()) return d;
    Object.assign(d, { pid: Number(g.OJ.qs('id') || g.OJ.qs('pid')) || null, version: 0,
      title: '', statement: '', tags: [], tps: [], officialSolution: '', knowledgePoints:[], savedAt:'未保存',
      async init() {
        try { this.knowledgePoints=await H.get('/api/problem/knowledge-points'); this.kpOptions=this.knowledgePoints.map(k=>k.name); }
        catch(e) { error(this,e); }
        if (this.pid) await this.retryLoad(); else { this.loading = false; this.dirty = true; }
      },
      async retryLoad() {
        this.loading = true; this.loadErr = false;
        try {
          var r = await H.get('/api/problem/questions/' + this.pid + '/edit');
          this.title = r.title; this.statement = r.statement || ''; this.lvl = r.difficulty;
          this.tags = r.tags || []; this.kp = r.knowledgePoint || '';
          this.tl = r.timeLimitMs; this.ml = r.memoryLimitMb; this.version = r.version;
          this.officialSolution = r.officialSolution || ''; this.codeTemplates=r.codeTemplates||{};
          this.aiOn = r.aiEnabled !== false; this.aiLimit = r.aiQuotaLimit == null ? 3 : r.aiQuotaLimit;
          this.stages.forEach(s => { s.on = (r.aiStages || []).includes(s.k); });
          var cases = r.testCases;
          if (!Array.isArray(cases)) cases = (await H.get('/api/problem/questions/' + this.pid + '/test-cases')).list;
          this.tps = (cases || []).map(function (t) { return { uid: 'case-' + t.id, id: t.id,
            kind: t.kind, input: t.input || '', output: t.output || '', score: t.score, open: false,
            isPublic: t.isPublic, inputTruncated: t.inputTruncated, outputTruncated: t.outputTruncated,
            originalInput: t.input || '', originalOutput: t.output || '' }; });
          this.savedAt = A.fmtDateTime(r.updatedAt || r.savedAt); this.dirty = false;
          if (this.tps.some(t => t.inputTruncated || t.outputTruncated)) this.toast('大测试点显示摘要；未修改内容时保存会保留磁盘全文');
        } catch (e) { this.loadErr = true; error(this, e); }
        finally { this.loading = false; }
      },
      async persist() {
        var body = { version: this.version, title: this.title, difficulty: Number(this.lvl),
          tags: this.tags, statement: this.statement, timeLimitMs: Number(this.tl), memoryLimitMb: Number(this.ml),
          officialSolution: this.officialSolution, codeTemplates:this.codeTemplates||{},
          knowledgePointId:(this.knowledgePoints.find(k=>k.name===this.kp)||{}).id||null,
          aiEnabled:this.aiOn, aiQuotaLimit:Number(this.aiLimit),
          aiStages: this.aiOn ? this.stages.filter(s => s.on).map(s => s.k) : [] };
        var r = this.pid ? await H.put('/api/problem/questions/' + this.pid, body) : await H.post('/api/problem/questions', body);
        this.pid = r.id; this.version = r.version == null ? this.version : r.version;
        var items = this.tps.map(function (t, i) {
          var x = { id: t.id || null, seqNo: i + 1, kind: t.kind, score: Number(t.score), isPublic: t.isPublic == null ? t.kind === 'sample' : t.isPublic };
          if (!t.inputTruncated || t.input !== t.originalInput) x.input = t.input;
          if (!t.outputTruncated || t.output !== t.originalOutput) x.output = t.output;
          return x;
        });
        r = await H.put('/api/problem/questions/' + this.pid + '/test-cases/batch', { version: this.version, items: items });
        this.version = r.version; this.dirty = false;
        history.replaceState(null, '', 't-problem-edit.html?id=' + this.pid);
      },
      async saveDraft() {
        if (this.saving) return; this.saving = true;
        try { await this.persist(); await this.retryLoad(); this.toast('题目和测试点已保存'); }
        catch (e) { error(this, e); } finally { this.saving = false; }
      },
      async publish() {
        if (this.saving) return; this.saving = true; this.confirmOpen = false;
        try {
          await this.persist(); await H.put('/api/problem/questions/' + this.pid + '/state', { action: 'publish' });
          await this.retryLoad(); this.toast('题目已发布');
        } catch (e) { error(this, e); } finally { this.saving = false; }
      },
      resetForm() { return this.retryLoad(); }
    });
    return d;
  };
})(window);
