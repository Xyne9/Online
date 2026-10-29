(function (g) {
  'use strict';
  var original=g.plagiarismPage, H=g.api.http, T=g.api.teaching, A=g.adapter;
  if(!original)return;
  var esc=s=>String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  function prop(d,n,fn){Object.defineProperty(d,n,{configurable:true,enumerable:true,get:fn});}
  function empty(){return {id:null,pid:'—',title:'',struct:null,sem:null,overallSim:null,level:'normal',
    a:{name:'—',sid:'—',lang:'—',at:'—',code:[]},b:{name:'—',sid:'—',lang:'—',at:'—',code:[]},segs:{},segments:[],rows:[]};}
  g.plagiarismPage=function(){
    var d=original();if(H.useMock())return d;
    Object.assign(d,{pairs:[],cur:empty(),assignmentId:null,taskId:null,taskState:'',progress:0,checking:false,_closed:false,
      async init(){
        try{
          this.assignmentId=Number(new URLSearchParams(location.search).get('assignmentId'))||null;
          if(!this.assignmentId){var acts=await T.assignments();var act=acts.find(a=>a.status===3)||acts.find(a=>a.status===2);this.assignmentId=act&&act.id;}
          if(!this.assignmentId){this.say('当前没有可查重的教学活动',false);return;}
          var act=await H.get('/api/teaching/assignments/'+this.assignmentId);
          var sub=document.querySelector('.ph-sub');if(sub)sub.textContent=act.title+' · '+act.className+' · 等待启动查重';
          await this.loadPairs();
        }catch(e){this.say(H.describe(e),false);}
      },
      async loadPairs(){
        var rows=await T.all('/api/teaching/plagiarism/pairs',{assignmentId:this.assignmentId});
        var selected=this.cur.id;this.pairs=rows.map(A.plagiarismPair);
        var p=this.pairs.find(p=>p.id===selected)||this.pairs[0];
        if(p)await this.select(p);else this.cur=empty();
      },
      pct(v){if(v==null)return '—';var n=Math.round(v*10)/10;return Number.isInteger(n)?String(n):n.toFixed(1);},
      overall(p){return p.overallSim;},level(p){return p.level||'normal';},
      verdictText(p){return p.diagnosis?esc(p.diagnosis):'该对未进入语义精筛，尚无 AI 鉴定结论。';},
      async select(p){
        this.cur=p;this.activeSeg=0;var id=p.id;
        try{
          var r=await H.get('/api/teaching/plagiarism/pairs/'+id);
          if(this.cur.id!==id||this._closed)return;
          p.overallSim=r.overallSim;p.struct=r.structSim;p.sem=r.semSim;p.level=r.level;
          p.diagnosis=r.aiDiagnosis;p.model=r.aiModelUsed;p.segments=r.segments||[];p.rows=r.rows||[];
          p.review=r.review&&r.review.auditStatus===1?'confirmed':r.review&&r.review.auditStatus===2?'independent':null;
          this.activeSeg=p.segments.length?p.segments[0].segId:0;
          this.$nextTick(()=>this.scrollPanes(0));
        }catch(e){this.say(H.describe(e),false);}
      },
      alignedRows(){return (this.cur.rows||[]).map((r,i,all)=>({seg:r.segId||0,last:!all[i+1]||all[i+1].segId!==r.segId,
        a:r.aLineNo==null?null:{n:r.aLineNo,t:r.aText||'',s:r.segId},
        b:r.bLineNo==null?null:{n:r.bLineNo,t:r.bText||'',s:r.segId}}));},
      prevSeg(){var i=this.segInfo.findIndex(s=>s.id===this.activeSeg);if(i>0)this.gotoSeg(this.segInfo[i-1].id);},
      nextSeg(){var i=this.segInfo.findIndex(s=>s.id===this.activeSeg);if(i<this.segInfo.length-1)this.gotoSeg(this.segInfo[i+1].id);},
      async review(p,r){
        if(!p.id||this.auditing)return;this.auditing=true;
        try{
          await H.put('/api/teaching/plagiarism/audit',{reportId:p.id,auditStatus:r==='confirmed'?1:2});
          this.hist.push({id:p.id});await this.loadPairs();this.say(r==='confirmed'?'复核已确认，相关成绩已清零':'已排除该疑似对',true);
        }catch(e){this.say(H.describe(e),false);}finally{this.auditing=false;}
      },
      async undo(){
        var last=this.hist[this.hist.length-1];if(!last||this.auditing)return;this.auditing=true;
        try{await H.del('/api/teaching/plagiarism/audit/'+last.id);this.hist.pop();await this.loadPairs();this.say('已撤销复核并恢复原成绩',false);}
        catch(e){this.say(H.describe(e),false);}finally{this.auditing=false;}
      },
      async rescan(){
        if(this.checking||!this.assignmentId)return;
        this.checking=true;
        try{
          var q=new URLSearchParams(location.search),pid=Number(q.get('problemId'));
          var r=await H.post('/api/teaching/plagiarism/tasks',{assignmentId:this.assignmentId,
            problemIds:pid?[pid]:[],scope:pid?'problem':'assignment',stage1SimThreshold:75,
            stage2ChannelCode:q.get('channelCode')||null});
          this.taskId=r.taskId;this.taskState=r.state;this.progress=0;this.pollTask();
        }catch(e){this.checking=false;this.say(H.describe(e),false);}
      },
      async pollTask(){
        if(this._closed)return;
        try{
          var r=await H.get('/api/teaching/plagiarism/tasks/'+this.taskId);
          this.taskState=r.state;this.progress=r.progressPercent;
          var sub=document.querySelector('.ph-sub');if(sub)sub.textContent='任务 '+this.taskId+' · '+r.state+' · '+r.progressPercent+'% · 比对 '+r.comparedPairs+'/'+r.totalPairs+' 对';
          if(r.state==='DONE'){this.checking=false;await this.loadPairs();this.say('双阶段查重完成',false);return;}
          if(r.state==='FAILED'){this.checking=false;this.say(r.errorMsg||'查重失败，请检查模型通道',false);return;}
          this._poll=setTimeout(()=>this.pollTask(),2000);
        }catch(e){this.checking=false;this.say(H.describe(e),false);}
      },
      destroy(){this._closed=true;clearTimeout(this._poll);clearTimeout(this._t);}
    });
    prop(d,'flagged',function(){return this.pairs.filter(p=>p.level==='high'||p.level==='suspect');});
    prop(d,'segInfo',function(){return this.cur.segments.map(s=>({id:s.segId,from:s.aFrom,to:s.aTo,name:s.segName}));});
    prop(d,'segRows',function(){return this.cur.segments.map(s=>({id:s.segId,name:s.segName,aRange:s.aFrom+'–'+s.aTo,bRange:s.bFrom+'–'+s.bTo,count:s.rowCount||Math.max(s.aTo-s.aFrom+1,s.bTo-s.bFrom+1)}));});
    prop(d,'curSegLabel',function(){var s=this.segInfo.find(s=>s.id===this.activeSeg);return s?'第 '+s.from+'–'+s.to+' 行 · '+s.name:'';});
    return d;
  };
})(window);
