package com.oj.problem.service.impl;

import com.oj.common.api.dto.problem.ProblemSimpleDTO;
import com.oj.problem.entity.ProbQuestion;
import com.oj.problem.mapper.JudgeSubmissionReadMapper;
import com.oj.problem.mapper.ProbKnowledgePointMapper;
import com.oj.problem.mapper.ProbQuestionMapper;
import com.oj.problem.mapper.ProbTestCaseMapper;
import com.oj.problem.mapper.row.ProblemListRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemRecommendationTest {
    private final ProbQuestionMapper questions = mock(ProbQuestionMapper.class);
    private final ProblemQueryServiceImpl service = new ProblemQueryServiceImpl(questions,
            mock(ProbTestCaseMapper.class), mock(ProbKnowledgePointMapper.class),
            mock(JudgeSubmissionReadMapper.class));

    @Test
    void recommendationExcludesCurrentDraftArchivedDeletedAndAcceptedBeforeRankingTags() {
        current();
        ProblemListRow draft = candidate(2L, 0, 0);
        ProblemListRow archived = candidate(3L, 2, 0);
        ProblemListRow deleted = candidate(4L, 1, 0);
        deleted.setDeletedAt(LocalDateTime.of(2026, 10, 27, 12, 0));
        ProblemListRow accepted = candidate(5L, 1, 1);
        ProblemListRow attempted = candidate(8L, 1, 0);
        attempted.setMyAttempts(2);
        ProblemListRow sameKnowledge = candidate(6L, 1, 0);
        sameKnowledge.setTags("[\"数学\"]");
        ProblemListRow matchingTag = candidate(7L, 1, 0);
        matchingTag.setKnowledgePointId(9L);
        matchingTag.setDifficulty(4);
        when(questions.selectRecommendationPool(11L)).thenReturn(List.of(
                candidate(1006L, 1, 0), draft, archived, deleted, accepted, attempted, sameKnowledge, matchingTag));

        ProblemSimpleDTO result = service.recommendProblemByWeakTag(11L, 1006L, "动态规划");
        assertEquals(7L, result.getProblemId());
        assertEquals("动态规划", result.getMatchedTag());
        assertEquals("todo", result.getMyStatus());

        when(questions.selectRecommendationPool(11L)).thenReturn(List.of(sameKnowledge));
        assertEquals(6L, service.recommendProblemByWeakTag(11L, 1006L, "不存在的标签").getProblemId());
        verify(questions, times(2)).selectRecommendationPool(11L);
    }

    @Test
    void noEligibleCandidateReturnsNullRatherThanFabricatingARecommendation() {
        current();
        when(questions.selectRecommendationPool(11L)).thenReturn(List.of(
                candidate(1006L, 1, 0), candidate(2L, 0, 0), candidate(3L, 1, 1)));
        assertNull(service.recommendProblemByWeakTag(11L, 1006L, "动态规划"));
    }

    private void current() {
        ProbQuestion current = new ProbQuestion();
        current.setId(1006L);
        current.setDifficulty(3);
        current.setKnowledgePointId(2L);
        when(questions.selectByIdNotDeleted(1006L)).thenReturn(current);
    }

    private ProblemListRow candidate(long id, int state, int acCount) {
        ProblemListRow row = new ProblemListRow();
        row.setId(id);
        row.setState(state);
        row.setMyAcCount(acCount);
        row.setMyAttempts(acCount);
        row.setTitle("推荐练习 " + id);
        row.setKnowledgePointId(2L);
        row.setDifficulty(3);
        row.setTags("[\"动态规划\"]");
        return row;
    }
}
