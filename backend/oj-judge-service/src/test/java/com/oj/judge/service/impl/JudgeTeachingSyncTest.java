package com.oj.judge.service.impl;

import com.oj.common.api.dto.judge.ExamJudgeRequestDTO;
import com.oj.common.api.feign.TeachingInnerFeignClient;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.judge.adapter.JudgeExecutor;
import com.oj.judge.entity.*;
import com.oj.judge.mapper.*;
import com.oj.judge.mq.JudgeMessageProducer;
import com.oj.judge.service.support.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;

class JudgeTeachingSyncTest {
    final JudgeSubmissionMapper submissions=mock(JudgeSubmissionMapper.class);
    final ProbQuestionMapper questions=mock(ProbQuestionMapper.class);
    final ProbTestCaseMapper cases=mock(ProbTestCaseMapper.class);
    final JudgeRedisSupport redis=mock(JudgeRedisSupport.class);
    final JudgeMessageProducer producer=mock(JudgeMessageProducer.class);
    final JudgeResultWriter writer=mock(JudgeResultWriter.class);
    final TeachingInnerFeignClient teaching=mock(TeachingInnerFeignClient.class);
    final SandboxLifecycle sandboxes=mock(SandboxLifecycle.class);
    final JudgeServiceImpl service=new JudgeServiceImpl(submissions,questions,cases,mock(JudgeExecutor.class),redis,producer,writer,teaching,65536,sandboxes,"testcases");

    @Test void queueFailureCanBeRetriedInsteadOfSealingAnUnjudgedAnswer() {
        doAnswer(call->{ ((Runnable)call.getArgument(2)).run(); return null; })
            .when(sandboxes).enqueue(any(),anyInt(),any());
        when(sandboxes.finish(any(),anyInt(),anyInt(),anyString(),any())).thenAnswer(call->{
            ((Runnable)call.getArgument(4)).run(); return true;
        });
        var config=new ProbQuestionJudgeConfig(); config.setId(123L); config.setState(1);
        when(questions.selectJudgeConfig(123L)).thenReturn(config);
        var point=new ProbTestCaseMeta(); point.setId(1L); point.setSeqNo(1); point.setScore(100);
        when(cases.selectMetaByProblem(123L)).thenReturn(List.of(point));
        when(redis.nextSubmissionDisplayNo()).thenReturn("S-1","S-2");
        final JudgeSubmission[] stored={null};
        when(submissions.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(x->stored[0]);
        when(submissions.insert(any(JudgeSubmission.class))).thenAnswer(call->{
            var row=(JudgeSubmission)call.getArgument(0); row.setId(stored[0]==null?1L:2L); stored[0]=row; return 1;
        });
        doAnswer(call->{stored[0].setStatus(8);stored[0].setErrorMsg(JudgeServiceImpl.QUEUE_FAILURE);return null;})
            .when(writer).finishWithFailure(anyLong(),any(),anyString());
        when(producer.sendJudgeTask(anyLong(),eq(123L),eq(4L))).thenReturn(false,true);
        var request=new ExamJudgeRequestDTO();request.setUserId(4L);request.setAssignmentId(12L);request.setProblemId(123L);request.setLanguage("cpp");request.setCode("int main(){}");
        assertEquals(502,assertThrows(BusinessException.class,()->service.submitExamCode(request)).getCode());
        assertEquals(2L,service.submitExamCode(request).get("submissionId"));
        verify(producer,times(2)).sendJudgeTask(anyLong(),eq(123L),eq(4L));
        verifyNoInteractions(teaching); // final exam codes have already been authorized and sealed by teaching.
    }

    @Test void pendingScoresRetryWithoutAnyBrowserStatusPolling() {
        var row=new JudgeSubmission();row.setId(22L);row.setUserId(4L);row.setProblemId(123L);row.setAssignmentId(12L);row.setStatus(3);row.setOiScore(100);
        when(submissions.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(row));
        when(teaching.syncSubmissionScore(any())).thenThrow(BusinessException.badGateway("temporarily unavailable")).thenReturn(Result.ok());
        service.syncPendingScores();
        verify(submissions,never()).updateById(any(JudgeSubmission.class));
        service.syncPendingScores();
        verify(teaching,times(2)).syncSubmissionScore(argThat(d->d.getSubmissionId()==22L && d.getScore()==100));
        verify(submissions).updateById(argThat((JudgeSubmission p)->p.getId()==22L && p.getTeachingSyncedAt()!=null));
    }

    @Test void termRangeIsForwardedAndUnsupportedRangeRejected() {
        when(submissions.countStatusDistribution(anyLong(),any())).thenReturn(new java.util.ArrayList<>());
        when(submissions.countLanguageDistribution(anyLong(),any())).thenReturn(new java.util.ArrayList<>());
        assertDoesNotThrow(()->service.submissions(1,20,null,null,126,null,null,null,4L));
        verify(submissions).countSubmissionPage(4L,null,null,null,126);
        assertEquals(400,assertThrows(BusinessException.class,
            ()->service.submissions(1,20,null,null,125,null,null,null,4L)).getCode());
    }
}
