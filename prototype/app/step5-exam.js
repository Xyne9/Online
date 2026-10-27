(function (g) {
  'use strict';
  var H = g.api.http, T = g.api.teaching, A = g.adapter;
  function prop(d, name, getter) { Object.defineProperty(d, name, { configurable:true, enumerable:true, get:getter }); }
  async function resolveExam() {
    var explicit = Number(new URLSearchParams(location.search).get('examId') || new URLSearchParams(location.search).get('assignmentId'));
    if (explicit) return explicit;
    var exams = await T.assignments({ type:3 });
    var selected = exams.find(a => a.status === 2) || exams.find(a => a.status === 1) || exams[0];
    if (!selected) throw new Error('当前没有可进入的考试');
    return selected.id;
  }
  var oldExam = g.examPage;
  if (oldExam) g.examPage = function () {
    var d = oldExam(); if (H.useMock()) return d;
    Object.assign(d, { qs:[], examId:null, tabSwitchCount:0, tabSwitchLimit:3, secs:0,
      _draftQueue:Promise.resolve(), _buffers:{}, _clipboard:'', _clipboardMark:crypto.randomUUID(), _deadline:0,
      _closed:false, _draftErrors:false, _lastServerSync:0,
      async init() {
        await this.retry();
        if (this.loadErr) return;
        this.$watch('cur', (value, old) => {
          clearTimeout(this._saveTimer);
          var previous = this.qs[old];
          if (previous && this._editorQuestion === previous.id) {
            var text = this._buffers[previous.id] == null ? previous.code.join('\n') : this._buffers[previous.id];
            previous.code = text.split('\n'); this.saveQuestion(previous, text);
          }
          this._editorQuestion = this.q.id;
          this.runState = 'idle';
          this.$nextTick(() => this.lockEditor());
        });
        this._visibility = () => {
          if (document.hidden) this._awayAt = performance.now();
          else if (this._awayAt != null) {
            var seconds = (performance.now() - this._awayAt) / 1000; this._awayAt = null;
            if (seconds >= 3 && !this.submitted) this.reportAway(seconds);
          }
        };
        document.addEventListener('visibilitychange', this._visibility);
        this._input = e => {
          if (!e.target.closest('.codebox2') || this.submitted) return;
          var q = this.q, code = this.readEditor(); clearTimeout(this._saveTimer);
          this._buffers[q.id] = code;
          this._saveTimer = setTimeout(() => this.saveQuestion(q, code), 1200);
        };
        /* 失焦时重建行号结构（见 normalizeEditor 注释）。 */
        this._blur = e => { if (e.target.closest && e.target.closest('.codebox2')) this.normalizeEditor(); };
        document.addEventListener('focusout', this._blur);
        this._copy = e => {
          if (!e.target.closest('.codebox2') || !e.clipboardData) return;
          this._clipboard = g.getSelection().toString();
          e.clipboardData.setData('text/plain', this._clipboard);
          e.clipboardData.setData('application/x-oj-exam', this._clipboardMark); e.preventDefault();
        };
        this._paste = e => {
          if (!e.target.closest('.codebox2')) return;
          e.preventDefault();
          if (!this.submitted && e.clipboardData && e.clipboardData.getData('application/x-oj-exam') === this._clipboardMark) {
            document.execCommand('insertText', false, this._clipboard);
          } else this.say('考试期间仅允许粘贴本编辑器内部复制的内容', false);
        };
        this._drop = e => { if (e.target.closest('.codebox2')) { e.preventDefault(); this.say('考试期间禁止拖入外部代码', false); } };
        document.addEventListener('input', this._input); document.addEventListener('copy', this._copy);
        document.addEventListener('paste', this._paste); document.addEventListener('drop', this._drop);
        this.timer = setInterval(() => {
          if (this.submitted) return;
          this.secs = Math.max(0, Math.ceil((this._deadline - performance.now()) / 1000));
          if (this.secs === 0 && !this._expiring) { this._expiring = true; this.handIn(); }
        }, 250);
        this._syncTimer = setInterval(() => this.syncRemaining(), 30000);
        this.$nextTick(() => {
          var editor = document.querySelector('.codebox2');
          if (editor) { editor.contentEditable = this.submitted||this.q.handedIn?'false':'plaintext-only'; editor.spellcheck = false; }
          this._editorQuestion = this.q.id;
        });
      },
      async retry() {
        this.loading = true; this.loadErr = false;
        try {
          this.examId = this.examId || await resolveExam();
          var paper = await H.get('/api/teaching/exam/' + this.examId + '/paper');
          this.qs = (paper.problems || []).map(A.examQuestion); this.cur = 0;
          this._deadline = performance.now() + paper.remainingMs;
          this.secs = Math.max(0, Math.ceil(paper.remainingMs/1000));
          this.tabSwitchLimit = paper.tabSwitchLimit; this.tabSwitchCount = paper.tabSwitchCount || 0;
          this.submitted = !!paper.handedIn;
          var sub = document.querySelector('.ph-sub'); if (sub) sub.textContent = paper.title + ' · ' + this.qs.length + ' 题';
        } catch (e) { this.loadErr = true; this.say(H.describe(e), false); }
        finally { this.loading = false; }
      },
      readEditor() {
        var el = document.querySelector('.codebox2');
        if (!el) return this.q.code.join('\n');
        /* 编辑器是「行号 + 源码」的 .cl 结构，但用户一旦全选覆盖，
           contenteditable 会把整块内容塌成一个纯文本节点，.cl 全部消失。
           此时若继续按 .cl 解析就会读出空串 —— 草稿保存成空、判题拿不到代码。
           真实用户敲「全选 → 粘贴自己的模板」时 100% 会触发。
           所以这里两条路：有 .cl 就逐行剥行号，没有就退回 innerText。 */
        var rows = Array.from(el.children).filter(function (e) { return e.classList && e.classList.contains('cl'); });
        if (!rows.length) return el.innerText.replace(/\r/g, '').replace(/^\n+|\n+$/g, '');
        return rows.map(function (e) {
          var line = e.querySelector('span');
          return (line ? line.innerText : e.innerText).replace(/\r/g, '');
        }).join('\n');
      },
      /* 失焦后把塌掉的编辑器还原成「行号 + 源码」结构：
         编辑时不动 DOM（动就会把光标顶飞），只在失焦这一个安全时点重建。
         不重建的话，用户全选覆盖过一次之后整页就再也看不到行号了。 */
      normalizeEditor() {
        var el = document.querySelector('.codebox2');
        if (!el) return;
        var hasRows = Array.from(el.children).some(function (e) { return e.classList && e.classList.contains('cl'); });
        if (hasRows) return;
        var text = el.innerText.replace(/\r/g, '');
        if (this.q) this.q.code = text.split('\n');
        this.$nextTick && this.$nextTick(() => this.lockEditor());
      },
      saveQuestion(q, code) {
        if (!q || q.handedIn || this.submitted || this._closed) return this._draftQueue;
        this._draftQueue = this._draftQueue.catch(() => {}).then(async () => {
          try {
            await H.post('/api/teaching/exam/' + this.examId + '/answer', { problemId:q.id, language:q.language, code:code, clientSavedAt:Date.now() });
            this._draftErrors = false;
          } catch (e) { this._draftErrors = true; this.say(H.describe(e), false); throw e; }
        });
        // 自动保存失败在界面提示，保持可等待的队列供交卷核对。
        this._draftQueue.catch(() => {});
        return this._draftQueue;
      },
      async syncRemaining() {
        try {
          var r = await H.get('/api/teaching/exam/' + this.examId + '/remaining');
          this._deadline = performance.now() + r.remainingMs; this.secs = Math.max(0, Math.ceil(r.remainingMs/1000));
          if (r.status === 3) { this.submitted = true; this.lockEditor(); }
        } catch (e) { this.say(H.describe(e), false); }
      },
      async reportAway(seconds) {
        try {
          var r = await H.post('/api/teaching/exam/tab-switch', { assignmentId:this.examId, problemId:this.q.id, awaySeconds:seconds });
          this.tabSwitchCount = r.tabSwitchCount; this.say(r.alertMessage || ('切屏已记录 ' + r.tabSwitchCount + ' 次'), false);
        } catch (e) { this.say(H.describe(e), false); }
      },
      async run() {
        if (this.running || this.submitted) return; this.running = true; this.runState = 'running';
        try {
          var r = await H.post('/api/judge/run-sample', { problemId:this.q.id, language:this.q.language, code:this.readEditor(), mode:'sample' });
          this.q.cases = r.cases || []; this.runState = !r.compileError && this.q.cases.every(c => c.statusCode === 'ac') ? 'ok' : 'bad';
          this.say(r.compileError || this.q.cases.map(c => '#' + c.seqNo + ' ' + (c.statusCode || c.status) + ' · ' + c.timeMs + 'ms').join('；'), false);
        } catch (e) { this.runState = 'idle'; this.say(H.describe(e), false); }
        finally { this.running = false; }
      },
      async submitOne() {
        if (this.submitted) return; clearTimeout(this._saveTimer);
        try {
          var q=this.q; await this.saveQuestion(q, this.readEditor());
          await H.post('/api/teaching/exam/' + this.examId + '/hand-in', { problemIds:[q.id],confirm:true });
          q.handedIn=true; q.state='done'; q.tries++; this.lockEditor();
          this.submitted=this.qs.every(q=>q.handedIn); this.say('本题已交卷，已进入真实判题队列', false);
        }
        catch (e) { this.say(H.describe(e), false); }
      },
      lockEditor() { var el = document.querySelector('.codebox2'); if (el) el.contentEditable = this.submitted||this.q.handedIn?'false':'plaintext-only'; },
      async handIn() {
        if (this._handingIn || this.submitted) return; this._handingIn = true; this.askSubmit = false;
        clearTimeout(this._saveTimer);
        try {
          if (this.secs > 0) await this.saveQuestion(this.q, this.readEditor()); else await this._draftQueue;
          await H.post('/api/teaching/exam/' + this.examId + '/hand-in', { confirm:true });
          this.submitted = true; this.lockEditor(); this.say('试卷已提交，正在真实判题', false);
        } catch (e) { this.say(H.describe(e), false); await this.syncRemaining(); }
        finally { this._handingIn = false; }
      },
      destroy() {
        this._closed = true; clearInterval(this.timer); clearInterval(this._syncTimer); clearTimeout(this._saveTimer); clearTimeout(this._t);
        document.removeEventListener('visibilitychange', this._visibility); document.removeEventListener('input', this._input);
        document.removeEventListener('copy', this._copy); document.removeEventListener('paste', this._paste); document.removeEventListener('drop', this._drop);
        document.removeEventListener('focusout', this._blur);
      }
    });
    prop(d, 'q', function () { return this.qs[this.cur] || { id:0, title:'', lvl:1, score:0, tags:[], code:[], desc:[], examples:[], tries:0, cases:[], state:'todo' }; });
    prop(d, 'lastPass', function () { return this.q.cases.filter(c => c.statusCode === 'ac').length; });
    prop(d, 'lastChain', function () { return this.q.cases.map(c => c.statusCode || ''); });
    return d;
  };
  var oldMonitor = g.monitorPage;
  if (oldMonitor) g.monitorPage = function () {
    var d = oldMonitor(); if (H.useMock()) return d;
    Object.assign(d, { roster:[], evts:[], leftSec:0, examId:null, _closed:false, lastSeq:0,
      async init() {
        try { this.examId = await resolveExam(); await this.reload(); this.connect();
          this._timer = setInterval(() => { if (!this.ended) this.leftSec = Math.max(0, Math.ceil((this._deadline-performance.now())/1000)); }, 250);
          this._poll = setInterval(() => this.reload(), 10000);
        } catch (e) { this.say(H.describe(e)); }
      },
      async reload() {
        try {
          var r = await H.get('/api/teaching/exam/' + this.examId + '/monitor');
          if (this._closed) return; this.roster = (r.list || []).map(A.monitorStudent);
          this._deadline = performance.now() + r.remainingMs; this.leftSec = Math.ceil(r.remainingMs/1000);
          this.ended = r.status === 3; this.monitorSummary = r.summary || {};
          var sub=document.querySelector('.ph-sub');if(sub)sub.textContent=r.title+' · '+this.roster.length+' 名考生 · '+(this.roster.length?this.roster[0].total:0)+' 题';
          if (this.detail) this.detail = this.roster.find(s => s.userId === this.detail.userId) || null;
        } catch (e) { if (!this._closed) this.say(H.describe(e)); }
      },
      connect() {
        if (this._closed || this.ended) return;
        this.stream = T.events('/api/teaching/exam/' + this.examId + '/events', {
          snapshot:r => { this._deadline = performance.now() + r.remainingMs; },
          student:() => this.reload(),
          summary:r => { if (r.remainingMs != null) this._deadline = performance.now()+r.remainingMs; },
          event:r => {
            if (r.seq && r.seq <= this.lastSeq) return; this.lastSeq = r.seq || this.lastSeq;
            var s = this.roster.find(s => s.userId === r.userId);
            /* 后端 message 已经是完整句子（"张三切屏达到3次，已标红" / "张三进入考场"），
               前端模板又固定渲染 `{who} {what}`，直接透传会变成"张三 张三切屏达到3次"。
               这里按 message 是否已含学生姓名决定 who 留空还是填名，
               模板一行不动，重复的姓名就不会再出现。 */
            var msg = r.message || r.eventType || '';
            var named = s && s.name && msg.indexOf(s.name) === 0;
            this.evts.unshift({ id:r.seq, sid:s?s.sid:'', who:named?'':(s?s.name:'系统'), what:msg,
              note:r.payload && r.payload.tabSwitchCount != null ? '切屏 ' + r.payload.tabSwitchCount + ' 次' : '',
              at:A.fmtDateTime(r.at), tone:r.tone || '', icon:r.tone==='warn'?'alert':'checkC', fresh:true });
            this.evts.splice(8); this.reload();
          },
          exam_end:() => { this.ended=true; this.leftSec=0; this.reload(); },
          resync:() => this.reload(),
          error:e => { if (!this._closed) this.say(e.message); },
          end:() => { if (!this._closed && !this.ended) this._reconnect=setTimeout(() => this.connect(),2000); }
        }, this.lastSeq);
      },
      async endExam() {
        this.askEnd=false;
        try { await H.post('/api/teaching/exam/' + this.examId + '/finish', { autoSubmitUnsubmitted:true });
          this.ended=true; await this.reload(); this.say('考试已结束，已自动收卷'); }
        catch(e) { this.say(H.describe(e)); }
      },
      async extend(minutes) {
        try { await H.post('/api/teaching/exam/' + this.examId + '/extend', { minutes:minutes, reason:'教师监考台延时' }); await this.reload(); this.say('考试已延长 ' + minutes + ' 分钟'); }
        catch(e) { this.say(H.describe(e)); }
      },
      warn() { this.say('当前接口契约未提供单独警告发送操作'); },
      tickEvent() {},
      destroy() { this._closed=true; if(this.stream)this.stream.close(); clearInterval(this._timer); clearInterval(this._poll); clearTimeout(this._reconnect); clearTimeout(this._t); }
    });
    prop(d, 'avgDone', function () { return this.roster.length ? (this.roster.reduce((n,s)=>n+s.done,0)/this.roster.length).toFixed(1) : '—'; });
    return d;
  };
})(window);
