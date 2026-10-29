/* 仅真实态给既有节点补数据绑定；不添加元素、不改class/style，mock沿用验收基线。 */
(function(g){
  'use strict';
  if(g.api.http.useMock())return;
  var page=document.body.dataset.page;
  function bind(selector,attribute,value){
    function visit(root){
      root.querySelectorAll(selector).forEach(el=>{
        if(attribute.startsWith('x-bind:'))el.removeAttribute(':'+attribute.slice(7));
        if(attribute.startsWith('x-on:'))el.removeAttribute('@'+attribute.slice(5));
        el.setAttribute(attribute,value);
      });
      root.querySelectorAll('template').forEach(t=>visit(t.content));
    }
    visit(document);
  }
  if(page==='t-problem-edit')bind('.panel-h .tag.mono','x-text',"pid ? '#'+pid : '新题'");
  if(page==='t-assignments'){
    bind('.ph-sub','x-text',"'按班级发布与管理教学活动 · '+acts.length+' 项'");
    bind('[x-text="a.id"]','x-text','a.idStr');
    bind('[x-text="detail.id"]','x-text','detail.idStr');
    bind('[x-text*="a.pidCount"][x-text*="48"]','x-text','`${a.pidCount} 题 · ${a.totalStudents} 人`');
    bind('[x-text*="a.done"][x-text*="48"]','x-text','`${a.done}/${a.totalStudents}`');
    bind('[x-text*="detail.done"][x-text*="48"]','x-text','`${detail.done} / ${detail.totalStudents} 人`');
    bind('.bar.thin[x-bind\\:class],.bar.thin[\\:class]','x-bind:class',"pct(a)>=80?'':pct(a)>=50?'soft':'low'");
  }
  if(page==='s-exam'){
    bind('.qlist + .rules .rl:nth-child(3) span:last-child','x-text',"'切屏达到 '+tabSwitchLimit+' 次记为异常'");
    bind('.navcard .ph-act .t-xs','x-text','`${doneCount} / ${qs.length} 已作答`');
    bind('.modal-body > p','x-text','`已交 ${doneCount} / ${qs.length} 题。交卷后不能修改代码，未作答的题目按0分计。`');
    bind('.tally > div:first-child dd','x-text','`${doneCount}/${qs.length}`');
    bind('.tally > div:last-child dd','x-text','tabSwitchCount');
    bind('.lastjudge .lj-t','x-text','`样例 ${lastPass}/${q.cases.length}`');
    bind('.row b:first-child','x-text',"q.cases.length ? q.cases[0].timeMs+'ms' : '—'");
    bind('.row b:last-child','x-text',"q.cases.length ? q.cases[0].memKb+'KB' : '—'");
    bind('.row:has([x-ic="\'x\'"]) b:first-child','x-text',"q.cases.length ? q.cases[0].expected : '—'");
    bind('.row:has([x-ic="\'x\'"]) b:last-child','x-text',"q.cases.length ? q.cases[0].actual : '—'");
    bind('.probcard .spec > span:nth-child(2) b','x-text',"(q.raw && q.raw.memoryLimitMb || '—')+'MB'");
    bind('.probcard .spec > span:nth-child(3) b','x-text',"q.raw && q.raw.testCaseCount != null ? q.raw.testCaseCount+' 组' : '—'");
  }
  if(page==='t-monitor'){
    bind('.eb-meta','x-text',"ended ? '考试已结束，已自动收卷' : '实时监考 · SSE推送 · 10秒同步花名册'");
    bind('.ac-p','x-text','`${s.done}/${s.total}`');
    bind('.q-p','x-text','`${s.done}/${s.total}`');
    bind('[x-bind\\:style*="s.done/5"],[\\:style*="s.done/5"]','x-bind:style','`--pg:${s.total?s.done/s.total*100:0}%`');
    bind('.ac-b .bar i','x-bind:style','`width:${s.total?s.done/s.total*100:0}%`');
    bind('.drawer .stackbar template','x-for','q in detail.total');
    bind('.drawer .legend template','x-for','q in detail.total');
    bind('[x-text*="detail.done/5"]','x-text','`进度 ${detail.done}/${detail.total}`');
    bind('[x-text*="avgDone"]','x-text','`${avgDone} / ${roster.length?roster[0].total:0} 题`');
    bind('[x-text*="submittedCount/48"]','x-text','`${roster.length?Math.round(submittedCount/roster.length*100):0}% 完成`');
    bind('[x-bind\\:style*="b.c/48"],[\\:style*="b.c/48"]','x-bind:style','`width:${roster.length?b.c/roster.length*100:0}%;background:${b.color}`');
    bind('.drawer-foot .btn.primary','x-on:click','detail=null;extend(5)');
    bind('.drawer-foot .btn.primary','x-text',"'全场延长5分钟'");
  }
  if(page==='t-plagiarism'){
    bind('.score .meta','x-text',"`${segInfo.length} 处相似片段 · 模型 ${cur.model||'未调用'} · ${taskState||'已保存报告'}`");
    bind('.state p','x-text',"checking ? '正在进行'+taskState+'，进度'+progress+'%' : taskState==='FAILED' ? '查重失败，请检查提示后重新启动。' : '当前活动没有可展示的查重报告，可点击重新查重启动分析。'");
    bind('.ph-act .btn.primary','x-bind:disabled','checking');
  }
})(window);
