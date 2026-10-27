package com.oj.teaching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.dto.teaching.ScoreSyncRequestDTO;
import com.oj.common.api.feign.JudgeInnerFeignClient;
import com.oj.common.api.feign.ProblemInnerFeignClient;
import com.oj.common.api.feign.ProblemTestCaseFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.dto.ActivityRequests.*;
import com.oj.teaching.entity.*;
import com.oj.teaching.mapper.ActivityMapper;
import com.oj.teaching.mapper.SysUserMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.LocalDateTime;
import java.util.*;
import static com.oj.teaching.service.ActivitySupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 关键边界测试：时间与角色服务端裁决、草稿竞态、阈值及幂等评分。 */
class ExamServiceTest {
    ActivityMapper mapper;
    SysUserMapper users;
    ActivitySupport support;
    MonitorStreamService streams;
    ExamService service;
    TeachAssignment exam;
    SysUser student;
    List<ExamMonitorEvent> history;

    @BeforeEach void setup() {
        mapper=mock(ActivityMapper.class); users=mock(SysUserMapper.class); streams=mock(MonitorStreamService.class);
        support=new ActivitySupport(mapper,users,new ObjectMapper(),mock(ProblemInnerFeignClient.class),mock(ProblemTestCaseFeignClient.class),mock(StringRedisTemplate.class));
        service=new ExamService(support,mock(JudgeInnerFeignClient.class),streams);
        student=new SysUser(); student.setId(1L); student.setRole("STUDENT"); student.setStatus(1); student.setClassId(10L); student.setRealName("学生甲"); student.setUsername("S001");
        when(users.selectById(1L)).thenReturn(student);
        MockHttpServletRequest req=new MockHttpServletRequest(); req.addHeader("X-User-Id","1"); req.addHeader("X-User-Role","STUDENT");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        exam=new TeachAssignment(); exam.setId(20L); exam.setDisplayNo("A-0020"); exam.setTeacherId(9L); exam.setClassId(10L); exam.setTitle("考试"); exam.setModeType(3); exam.setStatus(2);
        exam.setStartTime(now().minusHours(1)); exam.setEndTime(now().plusHours(1)); exam.setProblemIds("[1001,1002]"); exam.setAllowedAiStages("[]"); exam.setAiQuotaPerProb(0); exam.setTabSwitchLimit(3); exam.setExtendedMinutes(0);
        when(mapper.lock(20L)).thenReturn(exam); when(mapper.selectById(20L)).thenReturn(exam);
        when(mapper.students(10L)).thenReturn(List.of(student)); when(mapper.scores(20L)).thenReturn(List.of());
        history=new ArrayList<>(); when(mapper.events(20L)).thenReturn(history);
        when(mapper.latestSeq(20L)).thenAnswer(i->history.stream().mapToLong(ExamMonitorEvent::getSeq).max().orElse(0));
        when(mapper.insertEvent(any())).thenAnswer(i->{ history.add(i.getArgument(0)); return 1; });
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void activityBoundaryUsesServerClockAndNeverReopensFinishedExam() {
        LocalDateTime start=now(); exam.setStartTime(start); exam.setEndTime(start.plusSeconds(5)); exam.setStatus(1);
        assertEquals(1,status(exam,start.minusNanos(1))); assertEquals(2,status(exam,start));
        assertEquals(2,status(exam,start.plusSeconds(5).minusNanos(1))); assertEquals(3,status(exam,start.plusSeconds(5)));
        exam.setStatus(3); assertEquals(3,status(exam,start));
    }
    @Test void awayUnderThreeSecondsDoesNotCountOrPersist() {
        var result=service.tabSwitch(new TabSwitch(20L,1001L,2.999));
        assertEquals(0,result.get("tabSwitchCount")); verify(mapper,never()).incrementSwitch(any(),any(),any()); verify(mapper,never()).insertEvent(any());
    }
    @Test void exactlyThreeSecondsCountsAndThirdSwitchFlagsWholeStudent() {
        ActivityScore score=new ActivityScore(); score.setUserId(1L); score.setProblemId(1002L); score.setTabSwitchCount(2);
        when(mapper.scores(20L)).thenReturn(List.of(score));
        var result=service.tabSwitch(new TabSwitch(20L,1001L,3d));
        assertEquals(3,result.get("tabSwitchCount")); assertEquals(true,result.get("redFlagTriggered"));
        verify(mapper).flag(20L,1L); verify(mapper).incrementSwitch(20L,1L,1001L);
        assertTrue(history.stream().anyMatch(e->e.getEventType().equals("WARN") && e.getTone().equals("danger")));
        verify(streams,atLeastOnce()).publish(eq(20L),eq("event"),any(),any());
    }
    @Test void studentOutsideClassCannotSaveOrReport() {
        student.setClassId(11L);
        assertThrows(BusinessException.class,()->service.tabSwitch(new TabSwitch(20L,1001L,3d)));
        verify(mapper,never()).incrementSwitch(any(),any(),any());
    }
    @Test void closedWindowBlocksDraftEvenIfClientSendsFutureTime() {
        exam.setEndTime(now().minusSeconds(1));
        assertThrows(BusinessException.class,()->service.answer(20L,new Answer(1001L,"cpp","int main(){}",Long.MAX_VALUE)));
        verify(mapper,never()).draft(any(),any(),any(),any(),any(),any());
    }
    @Test void staleAutosaveCannotOverwriteNewerDraft() {
        addEvent("SAVE",map("problemId",1001L,"clientSavedAt",200L,"savedAt",900L));
        assertThrows(BusinessException.class,()->service.answer(20L,new Answer(1001L,"cpp","old",199L)));
        verify(mapper,never()).draft(any(),any(),any(),any(),any(),any());
    }
    @Test void identicalAutosaveRetryReturnsPersistedVersion() {
        addEvent("SAVE",map("problemId",1001L,"clientSavedAt",200L,"savedAt",900L));
        ActivityScore score=new ActivityScore(); score.setDraftCode("same"); score.setDraftLanguage("cpp"); when(mapper.score(20L,1L,1001L)).thenReturn(score);
        var response=service.answer(20L,new Answer(1001L,"cpp","same",200L));
        assertEquals(1L,response.get("version")); assertEquals(900,response.get("savedAt")); verify(mapper,never()).draft(any(),any(),any(),any(),any(),any());
    }
    @Test void handedQuestionCannotBeEditedAndOtherQuestionRemainsOpen() {
        addEvent("SUBMIT",map("problemIds",List.of(1001L)));
        assertThrows(BusinessException.class,()->service.answer(20L,new Answer(1001L,"cpp","new",300L)));
        var response=service.answer(20L,new Answer(1002L,"python","print(1)",300L));
        assertNotNull(response.get("version")); verify(mapper).draft(eq(20L),eq(1L),eq(1002L),eq("python"),eq("print(1)"),any());
    }
    @Test void finishedWholeSubmissionBlocksJudgeAndTabSwitch() {
        AssignmentSubmission submitted=new AssignmentSubmission(); submitted.setStatus("SUBMITTED"); when(mapper.submission(20L,1L)).thenReturn(submitted);
        assertThrows(BusinessException.class,()->service.verifyJudgeSubmission(1L,20L,1001L));
        assertThrows(BusinessException.class,()->service.tabSwitch(new TabSwitch(20L,1001L,3d)));
    }
    @Test void repeatedScoreSynchronizationDoesNotInflateSubmissionCount() {
        ActivityScore existing=new ActivityScore(); existing.setUserId(1L); existing.setProblemId(1001L); existing.setMaxScore(0);
        when(mapper.score(20L,1L,1001L)).thenReturn(existing);
        ScoreSyncRequestDTO dto=new ScoreSyncRequestDTO(); dto.setSubmissionId(50L); dto.setAssignmentId(20L); dto.setUserId(1L); dto.setProblemId(1001L); dto.setScore(80); dto.setStatus(4); dto.setTimeUsedMs(12L);
        service.syncScore(dto); service.syncScore(dto);
        verify(mapper,times(1)).improve(20L,1L,1001L,80,50L,12); verify(mapper,never()).countSubmission(any(),any(),any());
    }
    @Test void flaggedScoreKeepsZeroWhileRecordingTrueBestForReviewReversal() {
        ActivityScore existing=new ActivityScore(); existing.setUserId(1L); existing.setProblemId(1001L); existing.setMaxScore(0); existing.setPrevMaxScore(60); existing.setRedFlagType(2); existing.setBestSubmissionId(49L);
        when(mapper.score(20L,1L,1001L)).thenReturn(existing);
        ScoreSyncRequestDTO dto=new ScoreSyncRequestDTO(); dto.setSubmissionId(50L); dto.setAssignmentId(20L); dto.setUserId(1L); dto.setProblemId(1001L); dto.setScore(90); dto.setStatus(4); dto.setTimeUsedMs(12L);
        service.syncScore(dto); verify(mapper).improve(20L,1L,1001L,90,50L,12);
    }
    private void addEvent(String type,Map<String,Object> payload) {
        ExamMonitorEvent e=new ExamMonitorEvent(); e.setSeq((long)history.size()+1); e.setExamId(20L); e.setUserId(1L); e.setEventType(type); e.setPayload(support.encode(payload)); e.setCreateTime(now()); history.add(e);
    }
}
