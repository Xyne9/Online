(function (g) {
  'use strict';
  var original = g.assignmentsPage, H = g.api.http, T = g.api.teaching, A = g.adapter;
  if (!original) return;
  var teacher = document.body.dataset.page === 't-assignments';
  function prop(d, name, getter) { Object.defineProperty(d, name, { configurable: true, enumerable: true, get: getter }); }
  function csv(rows) {
    var text = '\ufeff' + rows.map(r => r.map(x => '"' + String(x == null ? '' : x).replace(/"/g, '""') + '"').join(',')).join('\r\n');
    var a = document.createElement('a'), url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    a.href = url; a.download = '作业成绩.csv'; a.click(); URL.revokeObjectURL(url);
  }
  g.assignmentsPage = function () {
    var d = original(); if (H.useMock()) return d;
    d.acts = []; d.problemRows = []; d.rosterCache = {}; d.quota = []; d.weekBars = []; d.scoreTrend = [];
    d.classes = []; d.undoId = null; d.submitting = false;
    function fail(self, e) { teacher ? self.say(H.describe(e), false, 'warn') : self.toast(H.describe(e), 'warn', false); }
    Object.assign(d, {
      async init() { await this.retry(); },
      async retry() {
        this.loading = true; this.loadErr = false;
        try {
          var rows = (await T.assignments()).map(a => A.teachingAssignment(a, teacher));
          if (teacher) this.acts = rows; else this.OJ._assignments = rows;
        } catch (e) { this.loadErr = true; fail(this, e); }
        finally { this.loading = false; }
      },
      isOver(a) { return a.status === 'over' || (a.dueAt <= Date.now() && a.status !== 'done'); },
      async open(a) {
        this.detail = a; this.problemRows = [];
        try {
          var r = await H.get('/api/teaching/assignments/' + a.id + '/problems', { query: { withMine: true } });
          if (!this.detail || this.detail.id !== a.id) return;
          this.problemRows = (r.list || []).map(p => A.adaptProblem(Object.assign({}, p, { id: p.problemId })));
        } catch (e) { fail(this, e); }
      },
      async handIn(a) {
        if (a.type === 'exam') { g.location.href = 's-exam.html?examId=' + a.id; return; }
        if (this.submitting) return; this.submitting = true;
        try {
          var r = await H.post('/api/teaching/assignments/' + a.id + '/submit', {});
          this.undoId = a.id; this.undoDeadlineAt = r.undoDeadlineAt; a.status = 'done';
          this.toast('作业已提交', 'ok', r.canUndo); await this.retry();
        } catch (e) { fail(this, e); } finally { this.submitting = false; }
      },
      async undoToast() {
        if (!this.undoId || this.submitting) return; this.submitting = true;
        try {
          await H.del('/api/teaching/assignments/' + this.undoId + '/submit');
          this.undoId = null; this.toast('已撤销提交', 'ok', false);
          var selected = this.detail && this.detail.id; await this.retry();
          this.detail = selected ? this.OJ.assignments.find(a => a.id === selected) || null : null;
        } catch (e) { fail(this, e); } finally { this.submitting = false; }
      },
      subsOf(pid) { var p = this.problemRows.find(p => p.id === pid); return p && p.raw ? p.raw.submitCount || '—' : '—'; },
      aiTrace(a) {
        var stages = a.raw.aiStagesUsed || [];
        return { cells: [1,2,3,4].map(n => stages.includes(n) ? 'ac' : ''),
          label: stages.length ? '已使用' : '—', why: a.ai };
      }
    });
    if (!teacher) {
      prop(d, 'detailPids', function () { return this.problemRows.map(p => p.id); });
      prop(d, 'detailProblems', function () { return this.problemRows; });
      prop(d, 'upcomingAll', function () { return this.OJ.assignments.filter(a => !this.isOver(a) && a.status !== 'done').sort((a,b) => a.dueAt - b.dueAt); });
      prop(d, 'filtered', function () {
        var rows = this.OJ.assignments.filter(a => (this.status === 'all' || (this.status === 'over' ? this.isOver(a) : a.status === this.status))
          && (!this.kw.trim() || a.title.toLowerCase().includes(this.kw.trim().toLowerCase())));
        return rows.sort((a,b) => this.sort === 'score' ? b.score - a.score : this.sort === 'progress'
          ? (b.done / (b.pidCount || 1)) - (a.done / (a.pidCount || 1)) : a.dueAt - b.dueAt);
      });
      d.stName = a => ({ done:'已提交', doing:'进行中', over:'已截止', todo:'待开始' }[a.status] || '—');
      return d;
    }
    Object.assign(d, {
      async openDetail(a) {
        this.detail = a; this.stuFilter = 'all';
        try {
          var rows = await T.all('/api/teaching/assignments/' + a.id + '/submissions');
          this.rosterCache[a.id] = rows.map(r => A.teachingStudent(r, a.pidCount));
        } catch (e) { fail(this, e); }
      },
      roster(a) { return this.rosterCache[a.id] || []; },
      pct(a) { return a.raw.progressPercent || 0; },
      riskName(r) { return {ok:'正常',high:'查重确认',medium:'切屏异常'}[r]||r; },
      async openCreate() {
        this.form = { title:'', type:'homework', pidCount:1, due: new Date(Date.now() + 86400000).toLocaleString('sv-SE', { timeZone:'Asia/Shanghai' }).slice(0,16) };
        this.creating = true;
        try { var r = await H.get('/api/teaching/classes'); this.classes = (r.list || r).map(c=>({id:c.classId,name:c.className})); }
        catch (e) { fail(this, e); }
      },
      async createAct() {
        try {
          if (!this.classes.length) throw new Error('请先配置教师所属班级');
          var pickedClass = this.classes.length === 1 ? this.classes[0].id : Number(g.prompt('班级编号：' + this.classes.map(c => c.id + ' ' + c.name).join('；')));
          if (!pickedClass) return;
          var ids = g.prompt('输入本次活动的题号（逗号分隔，必须与题目数量一致）');
          if (ids == null) return;
          var problemIds = ids.split(/[,，\s]+/).filter(Boolean).map(Number);
          if (problemIds.length !== Number(this.form.pidCount) || problemIds.some(n => !Number.isInteger(n) || n <= 0)) throw new Error('题号或题目数量不正确');
          var dueText = this.form.due.trim();
          if (/^\d{2}-\d{2}/.test(dueText)) dueText = new Date().getFullYear() + '-' + dueText;
          var dueAt = Date.parse(dueText.replace(' ', 'T') + (dueText.length <= 16 ? ':00+08:00' : '+08:00'));
          if (!Number.isFinite(dueAt) || dueAt <= Date.now()) throw new Error('截止时间须晚于当前时间');
          var mode = { practice:1, homework:2, exam:3 }[this.form.type];
          await H.post('/api/teaching/assignments', { title:this.form.title, modeType:mode, classId:pickedClass,
            problemIds:problemIds, startAt:Date.now(), dueAt:dueAt, allowedAiStages:mode===3?[]:mode===2?[1,3]:[1,2,3,4],
            aiQuotaPerProb:mode===1?-1:mode===3?0:3, tabSwitchLimit:3, publishNow:false });
          this.creating = false; await this.retry(); this.say('草稿已保存', false);
        } catch (e) { fail(this, e); }
      },
      async toggle(a) {
        try { await H.put('/api/teaching/assignments/' + a.id + '/state', { action: a.status === 'doing' ? 'finish' : 'publish' });
          await this.retry(); this.say('活动状态已更新', false); }
        catch (e) { fail(this, e); }
      },
      exportList() {
        var rows = this.detail ? this.roster(this.detail).map(r => [r.sid,r.name,r.score,r.sub?'已交':'未交'])
          : this.acts.map(a => [a.idStr,a.title,a.done,a.totalStudents,a.avg]);
        csv([this.detail?['学号','姓名','成绩','提交']:['活动号','标题','已交','人数','均分']].concat(rows));
      }
    });
    prop(d, 'filtered', function () {
      var r = this.acts.filter(a => (!this.types.length || this.types.includes(a.type)) && (this.status === 'all' || a.status === this.status)
        && (!this.kw.trim() || (a.title + a.idStr).toLowerCase().includes(this.kw.trim().toLowerCase())));
      var dir = this.sortAsc ? 1 : -1, rank = {doing:0,todo:1,done:2};
      return r.sort((a,b) => this.sortKey === 'title' ? a.title.localeCompare(b.title,'zh')*dir
        : this.sortKey === 'rate' ? (this.pct(a)-this.pct(b))*dir : this.sortKey === 'avg' ? ((a.avg||0)-(b.avg||0))*dir : (rank[a.status]-rank[b.status])*dir);
    });
    return d;
  };
  if (!H.useMock() && !teacher) document.addEventListener('click', function (e) {
    var a = e.target.closest('a[href*="s-workbench.html"]'); if (!a) return;
    var root = document.querySelector('[x-data="assignmentsPage()"]'), d = root && g.Alpine.$data(root);
    if (!d || !d.detail) return;
    e.preventDefault();
    var id = d.detail.id, pid = d.detailPids[0];
    if (d.detail.type === 'exam') g.location.href = 's-exam.html?examId=' + id;
    else if (pid) g.location.href = 's-workbench.html?pid=' + pid + '&assignmentId=' + id;
  });
})(window);
