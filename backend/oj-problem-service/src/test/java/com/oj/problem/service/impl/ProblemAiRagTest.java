package com.oj.problem.service.impl;

import com.oj.common.api.dto.problem.ProblemAiRagDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.problem.entity.ProbQuestion;
import com.oj.problem.entity.ProbTestCase;
import com.oj.problem.mapper.JudgeSubmissionReadMapper;
import com.oj.problem.mapper.ProbKnowledgePointMapper;
import com.oj.problem.mapper.ProbQuestionMapper;
import com.oj.problem.mapper.ProbTestCaseMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ProblemAiRagTest {
    private final ProbQuestionMapper questions = mock(ProbQuestionMapper.class);
    private final ProbTestCaseMapper cases = mock(ProbTestCaseMapper.class);
    private final ProblemQueryServiceImpl service = new ProblemQueryServiceImpl(
            questions, cases, mock(ProbKnowledgePointMapper.class), mock(JudgeSubmissionReadMapper.class));

    @Test
    void ragContainsConfidentialSolutionAndPublicSamplesWithoutReadingHiddenCases() {
        ProbQuestion question = question();
        question.setSampleCases("[{\"input\":\"1 2\",\"output\":\"3\"}]");
        when(questions.selectByIdNotDeleted(1006L)).thenReturn(question);

        ProblemAiRagDTO result = service.getProblemRagForAi(1006L);
        assertEquals("只供模型参考的答案", result.getOfficialSolution());
        assertEquals("输入两个整数", result.getInputSpec());
        assertEquals("输出它们的和", result.getOutputSpec());
        assertEquals("1 2", result.getPublicSamples().get(0).getInput());
        assertEquals(List.of(1, 2, 3, 4), result.getAiStages());
        verifyNoInteractions(cases);
    }

    @Test
    void missingSampleColumnFallsBackOnlyToPublicCaseQuery() {
        when(questions.selectByIdNotDeleted(1006L)).thenReturn(question());
        ProbTestCase sample = new ProbTestCase();
        sample.setInputText("4 5");
        sample.setOutputText("9");
        when(cases.selectPublicByProblemId(1006L)).thenReturn(List.of(sample));

        assertEquals("9", service.getProblemRagForAi(1006L).getPublicSamples().get(0).getOutput());
        verify(cases).selectPublicByProblemId(1006L);
        verify(cases, never()).selectByProblemId(anyLong());
    }

    @Test
    void unpublishedQuestionDoesNotProvideOfficialSolutionToAi() {
        ProbQuestion question = question();
        question.setState(0);
        when(questions.selectByIdNotDeleted(1006L)).thenReturn(question);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.getProblemRagForAi(1006L)).getCode());
        verifyNoInteractions(cases);
    }

    private ProbQuestion question() {
        ProbQuestion question = new ProbQuestion();
        question.setId(1006L);
        question.setState(1);
        question.setTitle("A+B");
        question.setContent("## 输入格式\n输入两个整数\n## 输出格式\n输出它们的和");
        question.setOfficialSolution("只供模型参考的答案");
        question.setAiEnabled(true);
        question.setAiStages("[1,2,3,4]");
        question.setSampleCases("[]");
        return question;
    }
}
