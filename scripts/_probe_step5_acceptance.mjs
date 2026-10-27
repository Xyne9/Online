/** 第五步真实浏览器验收：编辑/创建、草稿切题、剪贴板、切屏SSE、查重左右对齐。 */
import { chromium } from './browser-runtime.mjs';
import fs from 'node:fs';
import path from 'node:path';
const ROOT=path.resolve('.'), OUT=path.join(ROOT,'logs/day12-frontend');
fs.mkdirSync(OUT,{recursive:true});
const backend=JSON.parse(fs.readFileSync(path.join(ROOT,'logs/day12-backend/report.json'),'utf8'));
const f=backend.fixtures, checks=[], errors=[];
function check(name,pass,detail=''){checks.push({name,pass:!!pass,detail});console.log((pass?'PASS ':'FAIL ')+name+(detail?' '+detail:''));}
async function request(method,url,body,token){
  const r=await fetch('http://127.0.0.1:8080'+url,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  const env=await r.json();if(!r.ok)throw new Error(r.status+' '+url+' '+env.message);return env.data;
}
const login=async name=>request('POST','/api/auth/login',{username:name,password:'123456'});
const teacher=await login('T001'), student=await login('s5demoa');
const classes=await request('GET','/api/teaching/classes',undefined,teacher.accessToken);
const list=await request('GET','/api/problem/questions?pageSize=20',undefined,teacher.accessToken);
const second=list.list.find(p=>p.id!==f.problemId);
const activity=await request('POST','/api/teaching/assignments',{title:'第五步浏览器验收-进行中考试',modeType:3,classId:classes.list[0].classId,
  problemIds:[f.problemId,second.id],startAt:Date.now()-2000,dueAt:Date.now()+1800000,allowedAiStages:[],aiQuotaPerProb:0,tabSwitchLimit:3,publishNow:true},teacher.accessToken);
f.browserExamId=activity.id;
const browser=await chromium.launch();
async function context(auth){
  const ctx=await browser.newContext({viewport:{width:1440,height:1000}});
  await ctx.addInitScript(auth=>{localStorage.setItem('OJ_TOKEN',auth.accessToken);localStorage.setItem('OJ_ROLE',auth.user.role.toLowerCase());localStorage.setItem('OJ_USER',JSON.stringify(auth.user));},auth);
  const p=await ctx.newPage();p.on('pageerror',e=>errors.push(e.message));
  p.on('console',m=>{if(m.type()==='error'&&!/Failed to load resource|net::ERR_|favicon/i.test(m.text()))errors.push(m.text());});
  return {ctx,p};
}
const {ctx:tc,p:t}=await context(teacher),{ctx:sc,p:s}=await context(student);
const data=p=>p.evaluate(()=>Alpine.$data(document.getElementById('page-content')));
const state=(p,fn,arg)=>p.evaluate(({fn,arg})=>new Function('d','arg','return ('+fn+')(d,arg)')(Alpine.$data(document.getElementById('page-content')),arg),{fn:fn.toString(),arg});
try{
  await t.goto('http://127.0.0.1:3000/t-problems.html');await t.waitForFunction(()=>!Alpine.$data(document.getElementById('page-content')).loading);
  check('教师题库从真实接口加载',(await state(t,d=>d.list.length))>1000);
  await t.goto('http://127.0.0.1:3000/t-problem-edit.html?id='+f.problemId);
  await t.waitForFunction(()=>!Alpine.$data(document.getElementById('page-content')).loading);
  check('编辑页回显真实题名及测试点',await state(t,d=>d.title==='第五步验收-正数累加'&&d.tps.length===2));
  await t.locator('#f-title').fill('第五步验收-正数累加');
  await state(t,async d=>{await d.saveDraft();});
  check('真实保存后刷新仍有测试点',await state(t,d=>!d.dirty&&!d.saving&&d.tps.length===2));

  await t.goto('http://127.0.0.1:3000/t-assignments.html');await t.waitForFunction(()=>!Alpine.$data(document.getElementById('page-content')).loading);
  await state(t,async d=>{await d.openCreate();});
  check('班级字段已适配',await state(t,d=>d.classes.length>0&&Number.isInteger(d.classes[0].id)));
  t.once('dialog',dialog=>dialog.accept(String(f.problemId)));
  await state(t,async d=>{d.form.title='第五步浏览器验收-新建作业';d.form.pidCount=1;await d.createAct();});
  check('页面创建作业真实落库',await state(t,d=>d.acts.some(a=>a.title==='第五步浏览器验收-新建作业')));
  await t.screenshot({path:path.join(OUT,'teacher-assignments.png'),fullPage:true});

  await t.goto('http://127.0.0.1:3000/t-monitor.html?examId='+activity.id);
  await t.waitForFunction(()=>Alpine.$data(document.getElementById('page-content')).roster.length>0);
  await s.goto('http://127.0.0.1:3000/s-exam.html?examId='+activity.id);
  await s.waitForFunction(()=>!Alpine.$data(document.getElementById('page-content')).loading);
  check('考试页已有代码编辑能力',await s.locator('.codebox2').getAttribute('contenteditable')==='plaintext-only');
  const editor=s.locator('.codebox2');await editor.click();await s.keyboard.press('Control+a');
  const unique='int main(){ return 42; }';await s.keyboard.type(unique);
  // 在1200ms自动保存前切题，验证post-change watcher不会把新题内容覆盖旧题。
  await s.locator('.qitem').nth(1).click();await s.locator('.qitem').nth(0).click();
  check('输入后立即切题保留原题草稿',(await editor.innerText()).includes(unique));
  await s.waitForTimeout(1700);
  const paper=await request('GET','/api/teaching/exam/'+activity.id+'/paper',undefined,student.accessToken);
  check('草稿真实保存到本题',paper.problems[0].myDraftCode.includes(unique));
  const before=await editor.innerText();
  await editor.evaluate(el=>{const dt=new DataTransfer();dt.setData('text/plain','external-code');el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));});
  check('外部粘贴被拦截',(await editor.innerText())===before);
  for(let n=1;n<=3;n++){
    // 真实触发visibilitychange事件；只替换测试页的hidden读数，避免浏览器后台节流干扰时长断言。
    await s.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});
    await s.waitForTimeout(3100);
    await s.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));});
    await s.waitForFunction(n=>Alpine.$data(document.getElementById('page-content')).tabSwitchCount===n,n);
  }
  await t.waitForFunction(()=>Alpine.$data(document.getElementById('page-content')).abnormalCount>=1);
  check('第三次切屏在教师端实时显示异常花名册',await state(t,d=>d.attention.some(s=>s.switch===3&&s.status==='abnormal')));
  check('教师事件流收到真实红色告警',await state(t,d=>d.evts.some(e=>e.tone==='danger')));
  await t.screenshot({path:path.join(OUT,'monitor-red-alert.png'),fullPage:true});
  await s.screenshot({path:path.join(OUT,'exam.png'),fullPage:true});
  // 刷新后由服务端校准，而不是还原固定38分钟。
  const remaining=await state(s,d=>d.secs);await s.reload();await s.waitForFunction(()=>!Alpine.$data(document.getElementById('page-content')).loading);
  check('刷新考试后权威倒计时不重置',Math.abs((await state(s,d=>d.secs))-remaining)<3);
  check('刷新考试恢复真实草稿',(await s.locator('.codebox2').innerText()).includes(unique));

  await t.goto('http://127.0.0.1:3000/t-plagiarism.html?assignmentId='+f.examId);
  await t.waitForFunction(()=>Alpine.$data(document.getElementById('page-content')).cur.rows.length>0);
  check('查重页显示真实AI评语与两侧原始代码',await state(t,d=>!!d.cur.diagnosis&&d.rowsA.length===d.rowsB.length&&d.segInfo.length>0));
  // 语言标签在 .cs-head .cm（不是 .cmp-code .cm —— 后者根本不存在，选择器会恒为空数组）
  const languages=await t.locator('.cs-head .cm').allTextContents();check('查重语言标签统一C++ 17',languages.length===2&&languages.every(x=>x==='C++ 17'));
  await state(t,d=>{d.onlySeg=true;d.gotoSeg(d.segInfo[0].id);});
  check('分段导航保留对齐行',await state(t,d=>d.rowsA.length>0&&d.rowsA.length===d.rowsB.length));
  await t.screenshot({path:path.join(OUT,'plagiarism-aligned.png'),fullPage:true});
  await s.goto('http://127.0.0.1:3000/s-assignments.html');await s.waitForFunction(()=>!Alpine.$data(document.getElementById('page-content')).loading);
  check('学生作业页使用真实教学列表',await state(s,d=>d.OJ.assignments.some(a=>a.id===Number(arg)),f.assignmentId));
  check('七个页面没有Alpine/pageerror',errors.length===0,errors.join('\n'));
}catch(e){check('浏览器验收执行完整',false,e.stack);}
finally{
  await tc.close();await sc.close();await browser.close();
  fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify({passed:checks.filter(c=>c.pass).length,total:checks.length,checks,errors,fixtures:f},null,2));
  console.log(checks.filter(c=>c.pass).length+'/'+checks.length+' passed');
}
process.exitCode=checks.every(c=>c.pass)?0:1;
