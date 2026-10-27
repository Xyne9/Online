package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.dto.ActivityRequests.*;
import com.oj.teaching.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import static com.oj.teaching.service.ActivitySupport.*;

@Service
public class AssignmentService {
    private final ActivitySupport s;
    private final ExamService exams;
    public AssignmentService(ActivitySupport s,ExamService exams) { this.s=s; this.exams=exams; }

    public Map<String,Object> list(int page,int pageSize,Integer type,Integer filterStatus,String kw,String sortBy,String order) {
        SysUser user=s.current();
        if(type!=null && (type<1 || type>3) || filterStatus!=null && (filterStatus<0 || filterStatus>3)) throw BusinessException.badRequest("活动类型或状态不合法");
        if(!Set.of("status","dueAt","progress").contains(sortBy) || !Set.of("asc","desc").contains(order)) throw BusinessException.badRequest("排序字段或顺序不合法");
        List<Map<String,Object>> rows=new ArrayList<>();
        for(TeachAssignment a:s.mapper.activities()) {
            if("STUDENT".equals(user.getRole()) && (!Objects.equals(user.getClassId(),a.getClassId()) || status(a,now())==0)) continue;
            if("TEACHER".equals(user.getRole()) && !Objects.equals(user.getId(),a.getTeacherId())) continue;
            if(type!=null && !type.equals(a.getModeType()) || filterStatus!=null && filterStatus!=status(a,now())) continue;
            if(kw!=null && !kw.isBlank() && !(a.getTitle()+" "+a.getDisplayNo()).toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) continue;
            List<SysUser> students=s.mapper.students(a.getClassId());
            List<ActivityScore> scores=s.mapper.scores(a.getId());
            List<AssignmentSubmission> submissions=s.mapper.submissions(a.getId());
            long submitted=submissions.stream().filter(v->"SUBMITTED".equals(v.getStatus()) && students.stream().anyMatch(u->u.getId().equals(v.getUserId()))).count();
            Map<String,Object> row=base(a);
            row.putAll(map("problemCount",s.ids(a).size(),"submittedCount",submitted,"studentCount",students.size(),"progressPercent",percent(submitted,students.size()),
                    "avgScore",average(studentTotals(students,scores)),"aiUsedTotal",scores.stream().mapToInt(v->n(v.getAiUsedCount())).sum()));
            AssignmentSubmission mine=submissions.stream().filter(v->v.getUserId().equals(user.getId())).findFirst().orElse(null);
            row.put("mySubmissionStatus",mine==null?"UNSUBMITTED":mine.getStatus());
            row.put("mySubmittedAt",mine==null?null:epoch(mine.getSubmittedAt()));
            row.put("undoDeadlineAt",mine==null?null:epoch(mine.getUndoDeadlineAt()));
            row.put("myScore",scores.stream().filter(v->v.getUserId().equals(user.getId())).mapToInt(v->n(v.getMaxScore())).sum());
            row.put("myAnsweredCount",scores.stream().filter(v->v.getUserId().equals(user.getId()) && n(v.getSubmitCount())>0).count());
            rows.add(row);
        }
        String key="progress".equals(sortBy)?"progressPercent":sortBy;
        Comparator<Map<String,Object>> cmp=Comparator.comparingDouble(v->((Number)v.get(key)).doubleValue());
        if("desc".equals(order)) cmp=cmp.reversed();
        rows.sort(cmp.thenComparingLong(v->((Number)v.get("id")).longValue()));
        return page(rows,page,pageSize);
    }

    @Transactional
    public Map<String,Object> create(AssignmentWrite request) {
        SysUser user=s.teacher();
        TeachClass clazz=s.ownsClass(request.getClassId(),user);
        TeachAssignment a=new TeachAssignment();
        a.setTeacherId("ADMIN".equals(user.getRole())?clazz.getTeacherId():user.getId());
        apply(a,request,true);
        a.setDisplayNo(s.nextAssignmentNo()); a.setCreateTime(now());
        a.setStatus(Boolean.TRUE.equals(request.getPublishNow())?1:0);
        a.setStatus(status(a,now())); s.mapper.insert(a);
        return map("id",a.getId(),"displayNo",a.getDisplayNo(),"status",a.getStatus(),"problemCount",s.ids(a).size());
    }

    public Map<String,Object> detail(Long id) {
        TeachAssignment a=s.load(id,false); s.access(a,s.current());
        Map<String,Object> out=base(a);
        out.putAll(map("allowedAiStages",s.stages(a),"aiQuotaPerProb",a.getAiQuotaPerProb(),"tabSwitchLimit",a.getTabSwitchLimit(),
                "problems",problemRows(a,false),"createdBy",a.getTeacherId(),"createdAt",epoch(a.getCreateTime())));
        return out;
    }

    @Transactional
    public Map<String,Object> update(Long id,AssignmentWrite request) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,true); s.access(a,user);
        int state=status(a,now());
        if(state==3) throw BusinessException.conflict("已结束活动不可编辑");
        if(state!=0 && (request.getStartAt()!=null && !Objects.equals(request.getStartAt(),epoch(a.getStartTime()))
                || request.getDueAt()!=null && !Objects.equals(request.getDueAt(),epoch(a.getEndTime())))) throw BusinessException.conflict("发布后不可修改时间窗，请使用考试延时操作");
        if(request.getClassId()!=null) s.ownsClass(request.getClassId(),user);
        apply(a,request,false); a.setStatus(state); s.mapper.updateById(a);
        return map("id",id,"updatedAt",epoch(now()));
    }

    @Transactional
    public Map<String,Object> state(Long id,String action) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,true); s.access(a,user);
        int from=status(a,now());
        if("publish".equals(action)) {
            if(from!=0) throw BusinessException.conflict("仅草稿可发布");
            if(!now().isBefore(a.getEndTime())) throw BusinessException.conflict("截止时间已过，无法发布");
            s.publishedProblems(s.ids(a)); a.setStatus(1); a.setStatus(status(a,now())); s.mapper.updateById(a);
        } else if("finish".equals(action)) {
            if(from!=2) throw BusinessException.conflict("仅进行中的活动可结束");
            if(n(a.getModeType())==3) exams.finish(id,true);
            else { a.setStatus(3); s.mapper.updateById(a); }
        } else throw BusinessException.badRequest("action只能为publish或finish");
        return map("id",id,"fromStatus",from,"toStatus","finish".equals(action)?3:a.getStatus(),"effectiveAt",epoch(now()));
    }

    public Map<String,Object> problems(Long id,boolean withMine) {
        TeachAssignment a=s.load(id,false); s.access(a,s.current());
        List<Map<String,Object>> rows=problemRows(a,withMine);
        return map("list",rows,"total",rows.size());
    }

    public List<Map<String,Object>> problemRows(TeachAssignment a,boolean withMine) {
        Long userId=s.current().getId(); List<ActivityScore> scores=s.mapper.scores(a.getId());
        int total=s.mapper.students(a.getClassId()).size(); List<Map<String,Object>> rows=new ArrayList<>(); int seq=0;
        for(Long id:s.ids(a)) {
            var p=s.problem(id);
            ActivityScore mine=scores.stream().filter(v->id.equals(v.getProblemId()) && userId.equals(v.getUserId())).findFirst().orElse(null);
            long ac=scores.stream().filter(v->id.equals(v.getProblemId()) && n(v.getMaxScore())>=100).count();
            rows.add(map("problemId",id,"displayNo",p.getDisplayNo(),"title",p.getTitle(),"difficulty",p.getDifficulty(),"difficultyLabel",difficultyName(p.getDifficulty()),
                    "score",100,"seqNo",++seq,"myStatus",withMine?myStatus(mine):null,"myScore",withMine && mine!=null?mine.getMaxScore():null,"acRate",percent(ac,total)));
        }
        return rows;
    }

    public Map<String,Object> summary(Long id) {
        SysUser user=s.teacher(); TeachAssignment a=s.load(id,false); s.access(a,user);
        List<SysUser> users=s.mapper.students(a.getClassId()); List<ActivityScore> scores=s.mapper.scores(id);
        List<Integer> totals=studentTotals(users,scores); int full=s.ids(a).size()*100;
        long submitted=s.mapper.submissions(id).stream().filter(v->"SUBMITTED".equals(v.getStatus()) && users.stream().anyMatch(u->u.getId().equals(v.getUserId()))).count();
        int ai=scores.stream().mapToInt(v->n(v.getAiUsedCount())).sum();
        List<Integer> sorted=totals.stream().sorted().toList();
        double median=sorted.isEmpty()?0:sorted.size()%2==1?sorted.get(sorted.size()/2):(sorted.get(sorted.size()/2-1)+sorted.get(sorted.size()/2))/2d;
        Map<String,Object> hardest=problemRows(a,false).stream().min(Comparator.comparingDouble(v->((Number)v.get("acRate")).doubleValue())).orElse(null);
        return map("assignmentId",id,"totalStudents",users.size(),"submitted",submitted,"unsubmitted",users.size()-submitted,
                "overdue",status(a,now())==3?users.size()-submitted:0,"submitRate",percent(submitted,users.size()),"avgScore",average(totals),
                "maxScore",sorted.isEmpty()?0:sorted.get(sorted.size()-1),"minScore",sorted.isEmpty()?0:sorted.get(0),"medianScore",median,
                "dist",map("full",totals.stream().filter(v->v==full).count(),"partial",totals.stream().filter(v->v>0 && v<full && v>=full*.6).count(),
                        "fail",totals.stream().filter(v->v>0 && v<full*.6).count(),"zero",totals.stream().filter(v->v==0).count()),
                "hardestProblem",hardest==null?null:map("problemId",hardest.get("problemId"),"title",hardest.get("title"),"acRate",hardest.get("acRate")),
                "aiUsedTotal",ai,"avgAiPerStudent",users.isEmpty()?0:Math.round(ai*100d/users.size())/100d);
    }

    @Transactional
    public Map<String,Object> submit(Long id,String remark) {
        SysUser user=s.student(); TeachAssignment a=s.load(id,true); s.access(a,user); s.open(a);
        if(n(a.getModeType())==3) throw BusinessException.conflict("考试请使用交卷接口");
        if(remark!=null && remark.length()>255) throw BusinessException.badRequest("备注不能超过255字");
        AssignmentSubmission v=s.snapshot(a,user,remark,true);
        return map("submissionId",v.getId(),"displayNo",v.getDisplayNo(),"submittedAt",epoch(v.getSubmittedAt()),"undoDeadlineAt",epoch(v.getUndoDeadlineAt()),
                "canUndo",true,"problemCount",v.getProblemCount(),"answeredCount",v.getAnsweredCount(),"status","SUBMITTED");
    }

    @Transactional
    public Map<String,Object> revoke(Long id) {
        SysUser user=s.student(); TeachAssignment a=s.load(id,true); s.access(a,user);
        if(n(a.getModeType())==3) throw BusinessException.conflict("考试交卷不能撤销");
        LocalDateTime now=now();
        if(s.mapper.revoke(id,user.getId(),now)!=1) throw BusinessException.conflict("撤销窗口已过（5秒）或当前未提交");
        return map("revoked",true,"status","UNSUBMITTED","revokedAt",epoch(now));
    }

    public Map<String,Object> submissions(Long id,String filter,int page,int size,String kw) {
        SysUser user=s.current(); TeachAssignment a=s.load(id,false); s.access(a,user);
        if(!Set.of("all","sub","nosub","risk").contains(filter)) throw BusinessException.badRequest("学生筛选条件不合法");
        List<ActivityScore> scores=s.mapper.scores(id); List<AssignmentSubmission> submitted=s.mapper.submissions(id);
        List<Map<String,Object>> all=new ArrayList<>();
        for(SysUser u:s.mapper.students(a.getClassId())) {
            if("STUDENT".equals(user.getRole()) && !u.getId().equals(user.getId())) continue;
            AssignmentSubmission sub=submitted.stream().filter(v->v.getUserId().equals(u.getId())).findFirst().orElse(null);
            List<ActivityScore> mine=scores.stream().filter(v->v.getUserId().equals(u.getId())).toList();
            boolean done=sub!=null && "SUBMITTED".equals(sub.getStatus());
            int risk=mine.stream().mapToInt(v->n(v.getRedFlagType())).max().orElse(0);
            all.add(map("userId",u.getId(),"name",u.getRealName(),"stuNo",u.getUsername(),"submitted",done,"submittedAt",sub==null?null:epoch(sub.getSubmittedAt()),
                    "progressPercent",percent(mine.stream().filter(v->n(v.getSubmitCount())>0).count(),s.ids(a).size()),"score",mine.stream().mapToInt(v->n(v.getMaxScore())).sum(),
                    "aiUsed",mine.stream().mapToInt(v->n(v.getAiUsedCount())).sum(),"riskLevel",risk==2?"high":risk==1?"medium":"none","riskReason",risk==2?"查重确认抄袭":risk==1?"考试切屏达到阈值":null));
        }
        long count=all.stream().filter(v->Boolean.TRUE.equals(v.get("submitted"))).count();
        List<Map<String,Object>> rows=all.stream().filter(v->kw==null || kw.isBlank() || (v.get("name")+" "+v.get("stuNo")).contains(kw))
                .filter(v->"all".equals(filter) || "sub".equals(filter) && Boolean.TRUE.equals(v.get("submitted")) || "nosub".equals(filter) && Boolean.FALSE.equals(v.get("submitted")) || "risk".equals(filter) && !"none".equals(v.get("riskLevel"))).toList();
        Map<String,Object> out=page(rows,page,size);
        out.put("stats",map("submitted",count,"unsubmitted",all.size()-count,"risk",all.stream().filter(v->!"none".equals(v.get("riskLevel"))).count()));
        return out;
    }

    public Map<String,Object> classes(boolean withStats) {
        SysUser user=s.current(); List<Map<String,Object>> rows=new ArrayList<>();
        for(TeachClass c:s.mapper.classes()) {
            if("TEACHER".equals(user.getRole()) && !user.getId().equals(c.getTeacherId()) || "STUDENT".equals(user.getRole()) && !c.getId().equals(user.getClassId())) continue;
            var teacher=s.users.selectById(c.getTeacherId()); List<SysUser> students=s.mapper.students(c.getId());
            rows.add(map("classId",c.getId(),"className",c.getClassName(),"teacherId",c.getTeacherId(),"teacherName",teacher==null?null:teacher.getRealName(),"studentCount",students.size(),
                    "activeCount",withStats?students.stream().filter(v->v.getLastLoginAt()!=null && v.getLastLoginAt().isAfter(now().minusDays(7))).count():null,"avgScore",null,"acRate",null));
        }
        return map("list",rows,"total",rows.size());
    }

    private Map<String,Object> base(TeachAssignment a) {
        TeachClass c=s.mapper.classes().stream().filter(v->v.getId().equals(a.getClassId())).findFirst().orElse(null);
        int state=status(a,now());
        return map("id",a.getId(),"displayNo",a.getDisplayNo(),"title",a.getTitle(),"modeType",a.getModeType(),"modeTypeName",modeName(a.getModeType()),
                "status",state,"statusName",statusName(state),"classId",a.getClassId(),"className",c==null?null:c.getClassName(),"startAt",epoch(a.getStartTime()),"dueAt",epoch(a.getEndTime()));
    }
    private void apply(TeachAssignment a,AssignmentWrite r,boolean create) {
        if(create || r.getTitle()!=null) { text(r.getTitle(),128,"标题"); a.setTitle(r.getTitle().trim()); }
        if(create || r.getModeType()!=null) {
            if(r.getModeType()==null || r.getModeType()<1 || r.getModeType()>3) throw BusinessException.badRequest("modeType只能为1、2、3"); a.setModeType(r.getModeType());
        }
        if(create || r.getClassId()!=null) a.setClassId(r.getClassId());
        if(create || r.getProblemIds()!=null) { s.publishedProblems(r.getProblemIds()); a.setProblemIds(s.encode(r.getProblemIds())); }
        if(create || r.getStartAt()!=null) { if(r.getStartAt()==null || r.getStartAt()<0) throw BusinessException.badRequest("缺少有效开始时间"); a.setStartTime(date(r.getStartAt()).withNano(0)); }
        if(create || r.getDueAt()!=null) { if(r.getDueAt()==null || r.getDueAt()<0) throw BusinessException.badRequest("缺少有效截止时间"); a.setEndTime(date(r.getDueAt()).withNano(0)); }
        if(!a.getEndTime().isAfter(a.getStartTime()) || create && !a.getEndTime().isAfter(now())) throw BusinessException.badRequest("截止时间必须晚于开始时间和当前时间");
        int mode=n(a.getModeType()); List<Integer> stages=r.getAllowedAiStages();
        if(stages==null) stages=create?mode==1?List.of(1,2,3,4):mode==2?List.of(1,3):List.of():s.stages(a);
        List<Integer> allowed=mode==1?List.of(1,2,3,4):mode==2?List.of(1,3):List.of();
        if(stages.stream().anyMatch(v->v==null || !allowed.contains(v)) || new HashSet<>(stages).size()!=stages.size()) throw BusinessException.badRequest("AI阶段不符合活动模式");
        a.setAllowedAiStages(s.encode(stages));
        int quota=r.getAiQuotaPerProb()==null?create?mode==1?-1:mode==2?3:0:n(a.getAiQuotaPerProb()):r.getAiQuotaPerProb();
        if(quota< -1 || mode==2 && quota<0 || mode==3 && quota!=0) throw BusinessException.badRequest("AI配额不符合活动模式"); a.setAiQuotaPerProb(quota);
        int limit=r.getTabSwitchLimit()==null?create?3:n(a.getTabSwitchLimit()):r.getTabSwitchLimit();
        if(limit<1 || limit>100) throw BusinessException.badRequest("切屏阈值须为1至100"); a.setTabSwitchLimit(limit);
        if(create) a.setExtendedMinutes(0);
    }
    private static List<Integer> studentTotals(List<SysUser> users,List<ActivityScore> scores) {
        return users.stream().map(u->scores.stream().filter(v->v.getUserId().equals(u.getId())).mapToInt(v->n(v.getMaxScore())).sum()).toList();
    }
    private static double average(List<Integer> values) { return values.isEmpty()?0:Math.round(values.stream().mapToInt(v->v).average().orElse(0)*100)/100d; }
}
