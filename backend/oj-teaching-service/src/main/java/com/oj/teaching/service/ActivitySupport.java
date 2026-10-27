package com.oj.teaching.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.api.feign.ProblemInnerFeignClient;
import com.oj.common.api.feign.ProblemTestCaseFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import com.oj.common.web.context.UserContext;
import com.oj.teaching.constant.AuthConstants;
import com.oj.teaching.entity.*;
import com.oj.teaching.mapper.ActivityMapper;
import com.oj.teaching.mapper.SysUserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class ActivitySupport {
    final ActivityMapper mapper;
    final SysUserMapper users;
    final ObjectMapper json;
    final ProblemInnerFeignClient problems;
    final ProblemTestCaseFeignClient problemEdits;
    final StringRedisTemplate redis;

    public ActivitySupport(ActivityMapper mapper, SysUserMapper users, ObjectMapper json,
                           ProblemInnerFeignClient problems, ProblemTestCaseFeignClient problemEdits,
                           StringRedisTemplate redis) {
        this.mapper=mapper; this.users=users; this.json=json; this.problems=problems;
        this.problemEdits=problemEdits; this.redis=redis;
    }
    public SysUser current() {
        SysUser user=users.selectById(UserContext.userId());
        if(user==null || !user.isEnabled() || !Objects.equals(user.getRole(),UserContext.role()))
            throw BusinessException.forbidden("用户不可用或角色已变更");
        return user;
    }
    public SysUser student() {
        SysUser user=current();
        if(!"STUDENT".equals(user.getRole())) throw BusinessException.forbidden("仅学生可作答或提交");
        return user;
    }
    public SysUser teacher() {
        SysUser user=current();
        if(!Set.of("TEACHER","ADMIN").contains(user.getRole())) throw BusinessException.forbidden("仅教师可管理教学活动");
        return user;
    }
    public TeachAssignment load(Long id, boolean lock) {
        if(id==null || id<=0) throw BusinessException.badRequest("活动ID不合法");
        TeachAssignment a=lock?mapper.lock(id):mapper.selectById(id);
        if(a==null) throw BusinessException.notFound("教学活动不存在");
        return a;
    }
    public void access(TeachAssignment a, SysUser user) {
        if("ADMIN".equals(user.getRole())) return;
        if("TEACHER".equals(user.getRole()) && Objects.equals(a.getTeacherId(),user.getId())) { ownsClass(a.getClassId(),user); return; }
        if("STUDENT".equals(user.getRole()) && Objects.equals(a.getClassId(),user.getClassId()) && status(a,now())!=0) return;
        throw BusinessException.forbidden("无权访问该教学活动");
    }
    public TeachClass ownsClass(Long id, SysUser user) {
        if(id==null || id<=0) throw BusinessException.badRequest("班级ID不合法");
        TeachClass c=mapper.classes().stream().filter(v->Objects.equals(v.getId(),id)).findFirst()
                .orElseThrow(()->BusinessException.notFound("班级不存在"));
        if(!"ADMIN".equals(user.getRole()) && !Objects.equals(c.getTeacherId(),user.getId()))
            throw BusinessException.forbidden("无权向该班级发布活动");
        return c;
    }
    public void open(TeachAssignment a) {
        if(status(a,now())!=2) throw BusinessException.conflict("教学活动不在开放时间内");
    }
    public void exam(TeachAssignment a) {
        if(!Integer.valueOf(3).equals(a.getModeType())) throw BusinessException.badRequest("该活动不是考试");
    }
    public List<Long> ids(TeachAssignment a) { return longList(a.getProblemIds()); }
    public List<Long> longList(String value) {
        try { return value==null?List.of():json.readValue(value,new TypeReference<List<Long>>(){}); }
        catch(Exception e) { throw BusinessException.internalError("活动题目数据异常"); }
    }
    public List<Integer> stages(TeachAssignment a) {
        try { return json.readValue(a.getAllowedAiStages(),new TypeReference<List<Integer>>(){}); }
        catch(Exception e) { throw BusinessException.internalError("活动AI策略异常"); }
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception e) { throw BusinessException.internalError("活动数据序列化失败"); }
    }
    public Map<String,Object> payload(ExamMonitorEvent e) {
        try { return e.getPayload()==null?Map.of():json.readValue(e.getPayload(),new TypeReference<Map<String,Object>>(){}); }
        catch(Exception ex) { throw BusinessException.internalError("监考事件数据异常"); }
    }
    public void member(TeachAssignment a,Long problemId) {
        if(!ids(a).contains(problemId)) throw BusinessException.badRequest("题目不属于本教学活动");
    }
    public ProblemAiRagDTO problem(Long id) {
        var response=problems.getProblemRagForAi(id);
        if(response==null || response.getCode()!=200 || response.getData()==null)
            throw BusinessException.badGateway("无法获取题目 "+id);
        return response.getData();
    }
    public void publishedProblems(List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(i->i==null || i<=0)
                || new HashSet<>(ids).size()!=ids.size()) throw BusinessException.badRequest("题单应包含1至100道不重复题目");
        for(Long id:ids) {
            var r=problemEdits.getForEdit(id);
            if(r==null || r.getCode()!=200 || r.getData()==null) throw BusinessException.badRequest("题目不存在："+id);
            if(!Integer.valueOf(1).equals(r.getData().getState())) throw BusinessException.conflict("题单只能包含已发布题目："+id);
        }
    }
    public String nextAssignmentNo() {
        long floor=mapper.activities().stream().map(TeachAssignment::getDisplayNo).filter(Objects::nonNull)
                .filter(v->v.matches("A-[0-9]+")) .mapToLong(v->Long.parseLong(v.substring(2))).max().orElse(0);
        DefaultRedisScript<Long> script=new DefaultRedisScript<>("local n=math.max(tonumber(redis.call('GET',KEYS[1]) or '0'),tonumber(ARGV[1]))+1; redis.call('SET',KEYS[1],n); return n",Long.class);
        Long seq=redis.execute(script,List.of(RedisKeys.seq(RedisKeys.SEQ_ASSIGNMENT)),Long.toString(floor));
        if(seq==null) throw BusinessException.internalError("作业序列生成失败");
        return "A-"+String.format(Locale.ROOT,"%04d",seq);
    }
    /** 调用方已持有活动锁；撤销窗由数据库毫秒精度列保存。 */
    public AssignmentSubmission snapshot(TeachAssignment a, SysUser user, String remark, boolean undo) {
        AssignmentSubmission s=mapper.submission(a.getId(),user.getId());
        if(s!=null && "SUBMITTED".equals(s.getStatus())) throw BusinessException.conflict("已提交，请勿重复提交");
        LocalDateTime submitted=now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(s==null) {
            s=new AssignmentSubmission();
            s.setAssignmentId(a.getId()); s.setUserId(user.getId()); s.setClassId(a.getClassId());
            s.setDisplayNo("SA-"+a.getDisplayNo().substring(2)+"-"+String.format(Locale.ROOT,"%02d",mapper.submissions(a.getId()).size()+1));
        }
        s.setStatus("SUBMITTED"); s.setSubmittedAt(submitted); s.setUndoDeadlineAt(undo?submitted.plusSeconds(5):submitted);
        s.setProblemCount(ids(a).size());
        s.setAnsweredCount((int)mapper.scores(a.getId()).stream().filter(v->Objects.equals(v.getUserId(),user.getId()))
                .filter(v->n(v.getSubmitCount())>0 || v.getDraftCode()!=null && !v.getDraftCode().isBlank()).count());
        s.setRemark(remark);
        if(s.getId()==null) mapper.insertSubmission(s); else if(mapper.resubmit(s)!=1) throw BusinessException.conflict("提交状态已变更");
        return s;
    }
    public static LocalDateTime now() { return LocalDateTime.now(AuthConstants.DB_ZONE); }
    public static LocalDateTime date(Long epoch) { return Instant.ofEpochMilli(epoch).atZone(AuthConstants.DB_ZONE).toLocalDateTime(); }
    public static Long epoch(LocalDateTime date) { return date==null?null:date.atZone(AuthConstants.DB_ZONE).toInstant().toEpochMilli(); }
    public static int n(Integer value) { return value==null?0:value; }
    public static int status(TeachAssignment a,LocalDateTime now) {
        if(n(a.getStatus())==0 || n(a.getStatus())==3) return n(a.getStatus());
        if(!now.isBefore(a.getEndTime())) return 3;
        return now.isBefore(a.getStartTime())?1:2;
    }
    public static String statusName(int state) { return List.of("草稿","未开始","进行中","已结束").get(state); }
    public static String modeName(int mode) { return List.of("","练习","课后作业","考试").get(mode); }
    public static String difficultyName(Integer difficulty) { int v=n(difficulty); return v>=1 && v<=5?List.of("","入门","简单","中等","较难","困难").get(v):"—"; }
    public static Integer myStatus(ActivityScore score) {
        if(score==null || n(score.getSubmitCount())==0) return null;
        return n(score.getMaxScore())>=100?3:n(score.getMaxScore())>0?4:5;
    }
    public static double percent(long part,long all) { return all==0?0:Math.round(part*10000d/all)/100d; }
    public static Map<String,Object> map(Object... fields) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2) out.put((String)fields[i],fields[i+1]);
        return out;
    }
    public static Map<String,Object> page(List<?> list,int page,int size) {
        if(page<1 || size<1) throw BusinessException.badRequest("分页参数必须大于0");
        int safe=Math.min(size,100); long offset=(long)(page-1)*safe;
        int from=(int)Math.min(offset,list.size()),to=Math.min(from+safe,list.size());
        return map("list",list.subList(from,to),"total",list.size(),"page",page,"pageSize",safe,"hasNext",to<list.size());
    }
    public static void text(String value,int limit,String field) {
        if(value==null || value.isBlank() || value.length()>limit) throw BusinessException.badRequest(field+"不能为空且长度不可超过"+limit);
    }
}



