package com.oj.teaching.service;

import com.oj.common.api.dto.judge.ExamJudgeRequestDTO;
import com.oj.common.api.dto.teaching.*;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.dto.ActivityRequests.*;
import com.oj.teaching.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.*;
import java.util.*;
import static com.oj.teaching.service.ActivitySupport.*;

/** 权威考试运行时：时间、交卷、草稿与切屏全由服务端裁决。 */
@Service
public class ExamService {
    private final ActivitySupport s;
    private final JudgeInnerFeignClient judge;
    private final MonitorStreamService streams;
    public ExamService(ActivitySupport s,JudgeInnerFeignClient judge,MonitorStreamService streams) { this.s=s; this.judge=judge; this.streams=streams; }

    @Transactional
    public Map<String,Object> paper(Long id) {
        SysUser user=s.student(); TeachAssignment a=s.load(id,true); s.exam(a); s.access(a,user); s.open(a);
        List<ExamMonitorEvent> events=s.mapper.events(id);
        if(events.stream().noneMatch(e->user.getId().equals(e.getUserId()) && "STUDENT_ENTER".equals(e.getEventType())))
            event(a,user.getId(),"STUDENT_ENTER","info",user.getRealName()+"进入考场",map());
        else event(a,user.getId(),"HEARTBEAT","info","考场心跳",map());
        List<ActivityScore> scores=s.mapper.scores(id).stream().filter(v->v.getUserId().equals(user.getId())).toList();
        Set<Long> submitted=handedQuestions(events,user.getId()); List<Map<String,Object>> problems=new ArrayList<>(); int seq=0;
        for(Long pid:s.ids(a)) {
            var p=s.problem(pid); ActivityScore score=scores.stream().filter(v->v.getProblemId().equals(pid)).findFirst().orElse(null);
            problems.add(map("problemId",pid,"displayNo",p.getDisplayNo(),"title",p.getTitle(),"difficulty",p.getDifficulty(),"score",100,"seqNo",++seq,
                    "statement",p.getStatement(),"inputSpec",p.getInputSpec(),"outputSpec",p.getOutputSpec(),"samples",p.getPublicSamples()==null?List.of():p.getPublicSamples(),
                    "myDraftCode",score==null?null:score.getDraftCode(),"myDraftLanguage",score==null?"cpp":score.getDraftLanguage(),"myStatus",myStatus(score),"handedIn",submitted.contains(pid)));
        }
        long now=epoch(now());
        return map("examId",id,"title",a.getTitle(),"startAt",epoch(a.getStartTime()),"endAt",epoch(a.getEndTime()),"serverNow",now,
                "remainingMs",remainingMs(a,now),"durationMs",epoch(a.getEndTime())-epoch(a.getStartTime()),"allowAiStages",List.of(),
                "tabSwitchLimit",a.getTabSwitchLimit(),"problems",problems,"myProgress",map("answered",scores.stream().filter(v->v.getDraftCode()!=null && !v.getDraftCode().isBlank() || n(v.getSubmitCount())>0).count(),
                        "submitted",submitted.size()),"handedIn",isWholeSubmitted(a,user.getId()));
    }

    @Transactional
    public Map<String,Object> answer(Long id,Answer request) {
        SysUser user=s.student(); TeachAssignment a=s.load(id,true); s.exam(a); s.access(a,user); s.open(a); s.member(a,request.problemId());
        if(isWholeSubmitted(a,user.getId()) || handedQuestions(s.mapper.events(id),user.getId()).contains(request.problemId())) throw BusinessException.conflict("该题已交卷，不能继续修改");
        if(!Set.of("cpp","java","python").contains(request.language()==null?"":request.language())) throw BusinessException.badRequest("语言只支持cpp/java/python");
        if(request.code()==null || request.code().length()>65536) throw BusinessException.badRequest("代码不能为null或超过65536字符");
        if(request.clientSavedAt()==null || request.clientSavedAt()<0) throw BusinessException.badRequest("clientSavedAt必须为有效毫秒时间戳");
        List<ExamMonitorEvent> history=s.mapper.events(id);
        ExamMonitorEvent latest=history.stream().filter(e->user.getId().equals(e.getUserId()) && "SAVE".equals(e.getEventType())
                && Objects.equals(number(s.payload(e).get("problemId")),request.problemId())).reduce((x,y)->y).orElse(null);
        if(latest!=null) {
            long previous=number(s.payload(latest).get("clientSavedAt"));
            if(request.clientSavedAt()<previous) throw BusinessException.conflict("旧草稿不能覆盖新版本");
            if(request.clientSavedAt()==previous) {
                ActivityScore existing=s.mapper.score(id,user.getId(),request.problemId());
                if(existing!=null && Objects.equals(existing.getDraftLanguage(),request.language()) && Objects.equals(existing.getDraftCode(),request.code()))
                    return map("savedAt",s.payload(latest).get("savedAt"),"serverNow",epoch(now()),"version",latest.getSeq());
                throw BusinessException.conflict("草稿时间戳冲突，请重新保存");
            }
        }
        LocalDateTime now=now(); s.mapper.ensureScore(id,user.getId(),request.problemId()); s.mapper.draft(id,user.getId(),request.problemId(),request.language(),request.code(),now);
        ExamMonitorEvent saved=event(a,user.getId(),"SAVE","info","保存作答草稿",map("problemId",request.problemId(),"clientSavedAt",request.clientSavedAt(),"savedAt",epoch(now)));
        studentChanged(a,user.getId());
        return map("savedAt",epoch(now),"serverNow",epoch(now()),"version",saved.getSeq());
    }

    @Transactional
    public Map<String,Object> handIn(Long id,HandIn request) {
        SysUser user=s.student(); TeachAssignment a=s.load(id,true); s.exam(a); s.access(a,user); s.open(a);
        if(!Boolean.TRUE.equals(request.confirm())) throw BusinessException.badRequest("交卷需confirm=true");
        if(isWholeSubmitted(a,user.getId())) throw BusinessException.conflict("已交卷，请勿重复提交");
        List<Long> requested=request.problemIds(); boolean whole=requested==null || requested.isEmpty();
        if(whole) requested=s.ids(a);
        if(requested.stream().anyMatch(Objects::isNull) || new HashSet<>(requested).size()!=requested.size()) throw BusinessException.badRequest("交卷题目不得重复或为空");
        for(Long pid:requested) s.member(a,pid);
        Set<Long> done=handedQuestions(s.mapper.events(id),user.getId());
        List<Long> fresh=requested.stream().filter(pid->!done.contains(pid)).toList();
        if(fresh.isEmpty()) throw BusinessException.conflict("所选题目均已交卷");
        int count=submitDrafts(a,user,fresh); done.addAll(fresh);
        event(a,user.getId(),"SUBMIT","info",user.getRealName()+(whole?"提交整份试卷":"提交题目"),map("problemIds",fresh,"wholePaper",whole,"submittedCount",count));
        if(whole || done.containsAll(s.ids(a))) s.snapshot(a,user,null,false);
        studentChanged(a,user.getId());
        return map("handedIn",true,"handedInAt",epoch(now()),"submittedCount",count,"unsubmitted",s.ids(a).stream().filter(pid->!done.contains(pid)).toList());
    }

    @Transactional
    public Map<String,Object> remaining(Long id) {
        SysUser user=s.current(); TeachAssignment a=s.load(id,true); s.exam(a); s.access(a,user);
        if(n(a.getStatus())!=0 && n(a.getStatus())!=3 && !now().isBefore(a.getEndTime())) finishInternal(a,true,true);
        if("STUDENT".equals(user.getRole()) && status(a,now())==2) event(a,user.getId(),"HEARTBEAT","info","考场心跳",map());
        long now=epoch(now());
        return map("examId",id,"serverNow",now,"endAt",epoch(a.getEndTime()),"remainingMs",remainingMs(a,now),
                "extendedMs",n(a.getExtendedMinutes())*60000L,"paused",false,"status",status(a,date(now)));
    }

    @Transactional
    public Map<String,Object> tabSwitch(TabSwitch request) {
        SysUser user=s.student(); TeachAssignment a=s.load(request.assignmentId(),true); s.exam(a); s.access(a,user); s.open(a); s.member(a,request.problemId());
        if(isWholeSubmitted(a,user.getId())) throw BusinessException.conflict("交卷后不能上报切屏");
        Double away=request.awaySeconds();
        if(away==null || !Double.isFinite(away) || away<0) throw BusinessException.badRequest("awaySeconds必须为有效非负秒数");
        int before=s.mapper.scores(a.getId()).stream().filter(v->v.getUserId().equals(user.getId())).mapToInt(v->n(v.getTabSwitchCount())).sum();
        int limit=n(a.getTabSwitchLimit());
        if(away<3) return map("tabSwitchCount",before,"limit",limit,"redFlagTriggered",before>=limit,"alertMessage",null);
        s.mapper.ensureScore(a.getId(),user.getId(),request.problemId()); s.mapper.incrementSwitch(a.getId(),user.getId(),request.problemId());
        int count=before+1; boolean red=count>=limit;
        if(red) s.mapper.flag(a.getId(),user.getId());
        // 离开秒数是浏览器 performance.now() 差出来的浮点（3.1042000000011174），
        // 直接拼进中文句子会变成「离开3.1042000000011174秒」这种监考台上丢人的文案。
        // 统一保留一位小数，且整数秒不带 .0。
        String awayText = away == Math.rint(away)
                ? String.valueOf((long) Math.rint(away))
                : String.format(java.util.Locale.ROOT, "%.1f", away);
        String message=user.getRealName()+"第"+count+"次切屏（离开"+awayText+"秒）";
        event(a,user.getId(),"TAB_SWITCH",red?"danger":"warn",message,map("problemId",request.problemId(),"awaySeconds",away,"tabSwitchCount",count,"limit",limit,"redFlagTriggered",red));
        if(before<limit && red) event(a,user.getId(),"WARN","danger",user.getRealName()+"切屏达到"+limit+"次，已标红",map("tabSwitchCount",count));
        studentChanged(a,user.getId());
        return map("tabSwitchCount",count,"limit",limit,"redFlagTriggered",red,"alertMessage",red?"切屏次数达到预警阈值，已通知监考教师":message);
    }

    public Map<String,Object> monitor(Long id,String filter,String kw) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,false); s.exam(a); s.access(a,user);
        if(!Set.of("all","doing","submitted","offline","abnormal").contains(filter)) throw BusinessException.badRequest("监考筛选条件不合法");
        return monitorData(a,filter,kw);
    }

    @Transactional
    public Map<String,Object> extend(Long id,Extend request) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,true); s.exam(a); s.access(a,user); s.open(a);
        if(request.minutes()==null || request.minutes()<1 || request.minutes()>240) throw BusinessException.badRequest("延长分钟数须为1至240");
        text(request.reason(),255,"延时原因"); a.setEndTime(a.getEndTime().plusMinutes(request.minutes())); a.setExtendedMinutes(n(a.getExtendedMinutes())+request.minutes());
        a.setStatus(2); s.mapper.updateById(a);
        event(a,null,"WARN","info","考试延长"+request.minutes()+"分钟："+request.reason(),map("minutes",request.minutes(),"endAt",epoch(a.getEndTime())));
        return map("examId",id,"endAt",epoch(a.getEndTime()),"extendedMs",n(a.getExtendedMinutes())*60000L,"serverNow",epoch(now()));
    }

    @Transactional
    public Map<String,Object> finish(Long id,boolean autoSubmit) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,true); s.exam(a); s.access(a,user);
        if(n(a.getStatus())==3 || status(a,now())==0 || status(a,now())==1) throw BusinessException.conflict("考试尚未开始或已结束");
        return finishInternal(a,autoSubmit,false);
    }

    @Transactional
    public void expire(Long id) {
        TeachAssignment a=s.load(id,true);
        if(n(a.getStatus())==0 || n(a.getStatus())==3 || now().isBefore(a.getEndTime())) return;
        if(n(a.getModeType())==3) finishInternal(a,true,true);
        else { a.setStatus(3); s.mapper.updateById(a); }
    }

    public SseEmitter events(Long id,String lastEventId) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,false); s.exam(a); s.access(a,user);
        Long from=null;
        if(lastEventId!=null && !lastEventId.isBlank()) {
            try { from=Long.valueOf(lastEventId); if(from<0) throw new NumberFormatException(); }
            catch(NumberFormatException e) { throw BusinessException.badRequest("Last-Event-ID须为非负整数"); }
        }
        // 先注册再读取数据库，确保连接窗口中没有丢失的事件；重复帧由seq去重。
        SseEmitter emitter=streams.connect(id,user.getId(),()->summary(s.load(id,false)));
        List<ExamMonitorEvent> history=s.mapper.events(id); long latest=history.stream().mapToLong(ExamMonitorEvent::getSeq).max().orElse(0);
        if(from==null) {
            Map<String,Object> snapshot=monitorData(a,"all",null); snapshot.remove("list");
            streams.send(emitter,"snapshot",Long.toString(latest),snapshot);
            // 首次连接也回放持久化最近事件，监考页刷新后仍有证据。
            List<ExamMonitorEvent> visible=history.stream().filter(this::visible).toList();
            for(ExamMonitorEvent e:visible.subList(Math.max(0,visible.size()-50),visible.size())) streams.send(emitter,"event",e.getSeq().toString(),eventData(e));
        } else if(from>latest) streams.send(emitter,"resync",Long.toString(latest),map("reason","history_trimmed","latestSeq",latest));
        else for(ExamMonitorEvent e:history) if(e.getSeq()>from && visible(e)) streams.send(emitter,"event",e.getSeq().toString(),eventData(e));
        if(status(a,now())==3) streams.send(emitter,"exam_end",Long.toString(latest),map("examId",id,"endedAt",epoch(a.getEndTime()),"autoSubmittedCount",0));
        return emitter;
    }

    /** 公共判题入口的活动归属/窗口/交卷状态校验；自由练习保持兼容。 */
    public void verifyJudgeSubmission(Long userId,Long assignmentId,Long problemId) {
        SysUser user=s.users.selectById(userId);
        if(user==null || !user.isEnabled() || !Set.of("STUDENT","TEACHER").contains(user.getRole())) throw BusinessException.forbidden("仅学生或教师可提交判题");
        if(assignmentId==null || assignmentId==0) return;
        TeachAssignment a=s.load(assignmentId,false); s.access(a,user); s.open(a); s.member(a,problemId);
        if("STUDENT".equals(user.getRole()) && isWholeSubmitted(a,userId)) throw BusinessException.conflict("已提交整份活动，不能继续判题");
        if(n(a.getModeType())==3 && handedQuestions(s.mapper.events(a.getId()),userId).contains(problemId)) throw BusinessException.conflict("该考试题目已交卷");
    }

    @Transactional
    public void syncScore(ScoreSyncRequestDTO request) {
        Long id=request.getAssignmentId()!=null && request.getAssignmentId()>0?request.getAssignmentId():request.getExamId();
        if(id==null || id==0) return;
        if(request.getSubmissionId()==null || request.getSubmissionId()<=0 || request.getUserId()==null || request.getProblemId()==null
                || request.getScore()==null || request.getScore()<0 || request.getScore()>100 || request.getStatus()==null || request.getStatus()<3 || request.getStatus()>9)
            throw BusinessException.badRequest("评分同步字段不合法");
        TeachAssignment a=s.load(id,true); s.member(a,request.getProblemId());
        SysUser user=s.users.selectById(request.getUserId());
        if(user==null || !Objects.equals(user.getClassId(),a.getClassId()) || !"STUDENT".equals(user.getRole())) {
            if(user!=null && "TEACHER".equals(user.getRole()) && user.getId().equals(a.getTeacherId())) return;
            throw BusinessException.forbidden("评分不属于本班学生");
        }
        boolean exists=s.mapper.events(id).stream().filter(e->"SCORE_SYNC".equals(e.getEventType()))
                .anyMatch(e->Objects.equals(number(s.payload(e).get("submissionId")),request.getSubmissionId()));
        if(exists) return;
        s.mapper.ensureScore(id,user.getId(),request.getProblemId()); ActivityScore old=s.mapper.score(id,user.getId(),request.getProblemId());
        int prior=n(old.getRedFlagType())==2?n(old.getPrevMaxScore()):n(old.getMaxScore());
        int time=(int)Math.min(Integer.MAX_VALUE,Math.max(0,request.getTimeUsedMs()==null?0:request.getTimeUsedMs()));
        if(old.getBestSubmissionId()==null || request.getScore()>prior || request.getScore()==prior && time<n(old.getMinTimeUsed()))
            s.mapper.improve(id,user.getId(),request.getProblemId(),request.getScore(),request.getSubmissionId(),time);
        else s.mapper.countSubmission(id,user.getId(),request.getProblemId());
        event(a,user.getId(),"SCORE_SYNC","info","判题成绩同步",map("submissionId",request.getSubmissionId(),"problemId",request.getProblemId(),"status",request.getStatus(),"score",request.getScore()));
        if(n(a.getModeType())==3) studentChanged(a,user.getId());
    }

    public AssignmentBriefDTO assignmentBrief(Long id) {
        TeachAssignment a=s.load(id,false); AssignmentBriefDTO dto=new AssignmentBriefDTO();
        dto.setAssignmentId(id); dto.setDisplayNo(a.getDisplayNo()); dto.setTitle(a.getTitle()); dto.setClassId(a.getClassId()); dto.setTeacherId(a.getTeacherId());
        dto.setState(status(a,now())); dto.setStartAt(epoch(a.getStartTime())); dto.setDeadlineAt(epoch(a.getEndTime())); dto.setAllowLateSubmit(false);
        dto.setProblemIds(s.ids(a)); dto.setAllowedAiStages(s.stages(a)); dto.setAiQuotaPerProblem(a.getAiQuotaPerProb()); dto.setExamMode(n(a.getModeType())==3); dto.setExamId(n(a.getModeType())==3?id:null);
        return dto;
    }
    public ExamRuntimeDTO runtime(Long id) {
        TeachAssignment a=s.load(id,false); s.exam(a); ExamRuntimeDTO dto=new ExamRuntimeDTO(); long now=epoch(now());
        dto.setExamId(id); dto.setExamName(a.getTitle()); dto.setClassId(a.getClassId()); dto.setState(status(a,date(now))); dto.setStartAt(epoch(a.getStartTime())); dto.setEndAt(epoch(a.getEndTime()));
        dto.setRemainingSeconds(remainingMs(a,now)/1000); dto.setExtended(n(a.getExtendedMinutes())>0); dto.setExtendedMinutes(n(a.getExtendedMinutes())); dto.setCandidateIds(List.of());
        dto.setCandidateCount(s.mapper.students(a.getClassId()).size()); dto.setSubmittedCount((int)s.mapper.submissions(id).stream().filter(v->"SUBMITTED".equals(v.getStatus())).count());
        dto.setLatestEventSeq(s.mapper.latestSeq(id)); dto.setServerNow(now); return dto;
    }

    private int submitDrafts(TeachAssignment a,SysUser user,List<Long> ids) {
        int count=0;
        for(Long pid:ids) {
            ActivityScore score=s.mapper.score(a.getId(),user.getId(),pid);
            if(score==null || score.getDraftCode()==null || score.getDraftCode().isBlank()) continue;
            ExamJudgeRequestDTO dto=new ExamJudgeRequestDTO(); dto.setUserId(user.getId()); dto.setAssignmentId(a.getId()); dto.setProblemId(pid); dto.setLanguage(score.getDraftLanguage()); dto.setCode(score.getDraftCode());
            var result=judge.submitExamCode(dto);
            if(result==null || result.getCode()!=200) throw BusinessException.badGateway("交卷判题入队失败，请重试");
            count++;
        }
        return count;
    }
    private Map<String,Object> finishInternal(TeachAssignment a,boolean autoSubmit,boolean expired) {
        int count=0;
        if(autoSubmit) for(SysUser user:s.mapper.students(a.getClassId())) {
            if(isWholeSubmitted(a,user.getId())) continue;
            Set<Long> done=handedQuestions(s.mapper.events(a.getId()),user.getId()); List<Long> fresh=s.ids(a).stream().filter(pid->!done.contains(pid)).toList();
            submitDrafts(a,user,fresh);
            s.snapshot(a,user,"自动收卷",false);
            event(a,user.getId(),"SUBMIT","info",user.getRealName()+"已自动收卷",map("problemIds",fresh,"wholePaper",true,"automatic",true)); count++;
        }
        a.setStatus(3); if(!expired) a.setEndTime(now().withNano(0)); s.mapper.updateById(a);
        Map<String,Object> result=map("examId",a.getId(),"endedAt",epoch(a.getEndTime()),"autoSubmittedCount",count,"status",3);
        event(a,null,"EXAM_END","info",expired?"考试时间到，已自动收卷":"教师结束考试",result);
        afterCommit(()->streams.publish(a.getId(),"exam_end",null,result));
        return result;
    }
    private boolean isWholeSubmitted(TeachAssignment a,Long userId) {
        AssignmentSubmission sub=s.mapper.submission(a.getId(),userId); return sub!=null && "SUBMITTED".equals(sub.getStatus());
    }
    private Set<Long> handedQuestions(List<ExamMonitorEvent> events,Long userId) {
        Set<Long> out=new LinkedHashSet<>();
        for(ExamMonitorEvent e:events) if(userId.equals(e.getUserId()) && "SUBMIT".equals(e.getEventType())) {
            Object raw=s.payload(e).get("problemIds"); if(raw instanceof List<?> ids) for(Object id:ids) out.add(number(id));
        }
        return out;
    }
    private Map<String,Object> monitorData(TeachAssignment a,String filter,String kw) {
        List<Map<String,Object>> rows=studentRows(a); long now=epoch(now());
        return map("examId",a.getId(),"serverNow",now,"remainingMs",remainingMs(a,now),"endAt",epoch(a.getEndTime()),"title",a.getTitle(),"status",status(a,date(now)),"summary",summaryRows(a,rows),
                "list",rows.stream().filter(v->kw==null || kw.isBlank() || (v.get("name")+" "+v.get("stuNo")).contains(kw))
                        .filter(v->"all".equals(filter) || "abnormal".equals(filter) && Boolean.TRUE.equals(v.get("redFlag")) || filter.equals(v.get("status"))).toList());
    }
    private Map<String,Object> summary(TeachAssignment a) { return summaryRows(a,studentRows(a)); }
    private Map<String,Object> summaryRows(TeachAssignment a,List<Map<String,Object>> rows) {
        return map("total",rows.size(),"submitted",rows.stream().filter(v->"submitted".equals(v.get("status"))).count(),"doing",rows.stream().filter(v->"doing".equals(v.get("status"))).count(),
                "offline",rows.stream().filter(v->"offline".equals(v.get("status"))).count(),"abnormal",rows.stream().filter(v->Boolean.TRUE.equals(v.get("redFlag"))).count(),
                "switchWarn",rows.stream().filter(v->((Number)v.get("switchCount")).intValue()>=n(a.getTabSwitchLimit())).count(),"remainingMs",remainingMs(a,epoch(now())));
    }
    private List<Map<String,Object>> studentRows(TeachAssignment a) {
        List<ExamMonitorEvent> events=s.mapper.events(a.getId()); List<ActivityScore> scores=s.mapper.scores(a.getId()); List<Map<String,Object>> rows=new ArrayList<>();
        for(SysUser user:s.mapper.students(a.getClassId())) {
            List<ActivityScore> mine=scores.stream().filter(v->v.getUserId().equals(user.getId())).toList();
            List<ExamMonitorEvent> personal=events.stream().filter(e->user.getId().equals(e.getUserId())).toList();
            Long entered=personal.stream().filter(e->"STUDENT_ENTER".equals(e.getEventType())).map(e->epoch(e.getCreateTime())).findFirst().orElse(null);
            Long active=personal.stream().filter(e->!"SCORE_SYNC".equals(e.getEventType())).map(e->epoch(e.getCreateTime())).max(Long::compareTo).orElse(null);
            boolean submitted=isWholeSubmitted(a,user.getId()); int switched=mine.stream().mapToInt(v->n(v.getTabSwitchCount())).sum();
            boolean red=switched>=n(a.getTabSwitchLimit()) || mine.stream().anyMatch(v->n(v.getRedFlagType())>0);
            String state=submitted?"submitted":entered==null || active==null || epoch(now())-active>65000?"offline":"doing";
            Set<Long> handed=handedQuestions(events,user.getId()); List<Map<String,Object>> problems=new ArrayList<>(); int seq=0;
            for(Long pid:s.ids(a)) {
                ActivityScore score=mine.stream().filter(v->pid.equals(v.getProblemId())).findFirst().orElse(null);
                problems.add(map("problemId",pid,"seqNo",++seq,"costMs",score==null?0:n(score.getMinTimeUsed()),"status",myStatus(score),"handedIn",handed.contains(pid)));
            }
            List<ExamMonitorEvent> visible=personal.stream().filter(this::visible).toList();
            rows.add(map("userId",user.getId(),"name",user.getRealName(),"stuNo",user.getUsername(),"doneCount",handed.size(),"problemCount",s.ids(a).size(),"switchCount",switched,
                    "aiUsed",mine.stream().mapToInt(v->n(v.getAiUsedCount())).sum(),"status",state,"statusName",submitted?"已交卷":"offline".equals(state)?"离线":"作答中", "redFlag",red,
                    "enterAt",entered,"lastActiveAt",active,"problems",problems,"timeline",visible.subList(Math.max(0,visible.size()-50),visible.size()).stream()
                            .map(e->map("at",epoch(e.getCreateTime()),"text",e.getMessage(),"tone",e.getTone())).toList()));
        }
        return rows;
    }
    private ExamMonitorEvent event(TeachAssignment a,Long userId,String type,String tone,String message,Map<String,Object> payload) {
        ExamMonitorEvent e=new ExamMonitorEvent(); e.setExamId(a.getId()); e.setSeq(s.mapper.latestSeq(a.getId())+1); e.setUserId(userId); e.setEventType(type); e.setTone(tone); e.setMessage(message);
        e.setPayload(s.encode(payload)); e.setCreateTime(now()); s.mapper.insertEvent(e);
        if(n(a.getModeType())==3 && visible(e)) afterCommit(()->streams.publish(a.getId(),"event",e.getSeq().toString(),eventData(e)));
        return e;
    }
    private void studentChanged(TeachAssignment a,Long userId) {
        Map<String,Object> row=studentRows(a).stream().filter(v->userId.equals(v.get("userId"))).findFirst().orElse(null);
        if(row!=null) { row.put("at",epoch(now())); afterCommit(()->streams.publish(a.getId(),"student",null,row)); }
    }
    private boolean visible(ExamMonitorEvent e) { return !Set.of("SAVE","HEARTBEAT","SCORE_SYNC").contains(e.getEventType()); }
    private Map<String,Object> eventData(ExamMonitorEvent e) { return map("seq",e.getSeq(),"eventType",e.getEventType(),"tone",e.getTone(),"message",e.getMessage(),"userId",e.getUserId(),"at",epoch(e.getCreateTime()),"payload",s.payload(e)); }
    private static long remainingMs(TeachAssignment a,long now) { return n(a.getStatus())==0 || n(a.getStatus())==3?0:Math.max(0,epoch(a.getEndTime())-now); }
    private static Long number(Object value) { return value==null?null:value instanceof Number n?n.longValue():Long.valueOf(value.toString()); }
    private static void afterCommit(Runnable action) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){ @Override public void afterCommit(){ action.run(); } });
        else action.run();
    }
}
