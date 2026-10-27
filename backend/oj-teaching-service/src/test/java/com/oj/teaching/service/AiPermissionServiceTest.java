package com.oj.teaching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.dto.teaching.AiPermissionVerifyDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import com.oj.teaching.constant.AuthConstants;
import com.oj.teaching.entity.SysUser;
import com.oj.teaching.entity.TeachAssignment;
import com.oj.teaching.mapper.AiPermissionMapper;
import com.oj.teaching.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiPermissionServiceTest {
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final AiPermissionMapper activities = mock(AiPermissionMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"), AuthConstants.DB_ZONE);
    private final LocalDateTime now = LocalDateTime.now(clock);
    private AiPermissionService service;

    @BeforeEach
    void setUp() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setClassId(8L);
        user.setRole("STUDENT");
        user.setStatus(1);
        when(users.selectById(1L)).thenReturn(user);
        service = new AiPermissionService(users, activities, redis, new ObjectMapper(), clock);
    }

    @Test
    void freePracticeIsUnlimitedAndDoesNotReadAnAssignmentQuota() {
        AiPermissionVerifyDTO result = service.verify(1L, 0L, 1006L, 4);
        assertTrue(result.getAllowed());
        assertEquals(1, result.getModeType());
        assertEquals(-1, result.getMaxQuota());
        assertNull(result.getRemainingQuota());
        assertEquals(List.of(1, 2, 3, 4), result.getAllowedStages());
        verifyNoInteractions(redis);
        verify(activities, never()).selectPolicy(anyLong());
    }

    @Test
    void enabledTeacherCanUseFreePracticeWithoutAStudentClass() {
        useTeacher();
        AiPermissionVerifyDTO result = service.verify(1L, 0L, 1006L, 4);
        assertTrue(result.getAllowed());
        assertEquals(-1, result.getMaxQuota());
        assertNull(result.getRemainingQuota());
        verifyNoInteractions(activities, redis);
    }

    @Test
    void teacherMustOwnTheActivityAndProblemMustBelongToIt() {
        useTeacher();
        TeachAssignment practice = policy(1);
        practice.setTeacherId(1L);
        when(activities.selectPolicy(41L)).thenReturn(practice);
        assertTrue(service.verify(1L, 41L, 1006L, 4).getAllowed());
        practice.setTeacherId(2L);
        assertEquals("NOT_IN_ASSIGNMENT", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        practice.setTeacherId(1L);
        practice.setProblemIds("[9999]");
        assertEquals("NOT_IN_ASSIGNMENT", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        verifyNoInteractions(redis);
    }

    @Test
    void owningTeacherStillFollowsHomeworkExamAndTimePolicies() {
        useTeacher();
        TeachAssignment assignment = policy(2);
        assignment.setTeacherId(1L);
        when(activities.selectPolicy(41L)).thenReturn(assignment);
        values();
        assertTrue(service.verify(1L, 41L, 1006L, 3).getAllowed());
        assertEquals("STAGE_NOT_ALLOWED", service.verify(1L, 41L, 1006L, 2).getDenyCode());
        assignment.setEndTime(now);
        assertEquals("ACTIVITY_CLOSED", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        assignment.setModeType(3);
        assertEquals("EXAM_FORBIDDEN", service.verify(1L, 41L, 1006L, 1).getDenyCode());
    }

    @Test
    void homeworkRestrictsStagesAndReadsUsedCountWithoutDeductingIt() {
        TeachAssignment assignment = policy(2);
        when(activities.selectPolicy(41L)).thenReturn(assignment);
        ValueOperations<String, String> values = values();
        when(values.get(RedisKeys.aiQuota(41L, 1006L, 1L))).thenReturn("1");

        AiPermissionVerifyDTO permitted = service.verify(1L, 41L, 1006L, 3);
        assertTrue(permitted.getAllowed());
        assertEquals(List.of(1, 3), permitted.getAllowedStages());
        assertEquals(3, permitted.getMaxQuota());
        assertEquals(2, permitted.getRemainingQuota());

        AiPermissionVerifyDTO prohibited = service.verify(1L, 41L, 1006L, 2);
        assertFalse(prohibited.getAllowed());
        assertEquals("STAGE_NOT_ALLOWED", prohibited.getDenyCode());
        verify(values, times(2)).get(RedisKeys.aiQuota(41L, 1006L, 1L));
        verifyNoMoreInteractions(values);
    }

    @Test
    void activityStageConfigurationFurtherRestrictsHomework() {
        TeachAssignment assignment = policy(2);
        assignment.setAllowedAiStages("[1,2,4]");
        when(activities.selectPolicy(41L)).thenReturn(assignment);
        values();
        AiPermissionVerifyDTO result = service.verify(1L, 41L, 1006L, 3);
        assertEquals(List.of(1), result.getAllowedStages());
        assertEquals("STAGE_NOT_ALLOWED", result.getDenyCode());
    }

    @Test
    void exhaustedQuotaDisablesCallingEvenWhenTheStageIsAllowed() {
        when(activities.selectPolicy(41L)).thenReturn(policy(2));
        when(values().get(RedisKeys.aiQuota(41L, 1006L, 1L))).thenReturn("3");
        AiPermissionVerifyDTO result = service.verify(1L, 41L, 1006L, 1);
        assertFalse(result.getAllowed());
        assertTrue(result.getStageAllowed());
        assertEquals(0, result.getRemainingQuota());
        assertEquals("QUOTA_EXHAUSTED", result.getDenyCode());
    }

    @Test
    void cannotUseAnotherClassOrAnUnassignedProblem() {
        TeachAssignment assignment = policy(2);
        assignment.setClassId(9L);
        when(activities.selectPolicy(41L)).thenReturn(assignment);
        assertEquals("NOT_IN_ASSIGNMENT", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        assignment.setClassId(8L);
        assignment.setProblemIds("[9999]");
        assertEquals("NOT_IN_ASSIGNMENT", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        verifyNoInteractions(redis);
    }

    @Test
    void activeExamCannotBeBypassedByChangingAssignmentIdToZero() {
        TeachAssignment exam = policy(3);
        when(activities.selectActiveExam(eq(8L), eq(1006L), any())).thenReturn(exam);
        AiPermissionVerifyDTO result = service.verify(1L, 0L, 1006L, 1);
        assertEquals("EXAM_FORBIDDEN", result.getDenyCode());
        assertEquals(3, result.getModeType());
        assertTrue(result.getExamMode());
        assertEquals(List.of(), result.getAllowedStages());
        assertEquals(0, result.getMaxQuota());
        assertEquals(0, result.getRemainingQuota());
        assertFalse(result.getAllowed());
        verifyNoInteractions(redis);
    }

    @Test
    void examWorkspaceCanShowDisabledPolicyWithoutThrowingAnException() {
        when(activities.selectPolicy(41L)).thenReturn(policy(3));
        assertEquals("EXAM_FORBIDDEN", service.verify(1L, 41L, 1006L, 1).getDenyCode());
    }

    @Test
    void actualActivityStatusAndTimeWindowBothMustBeOpen() {
        TeachAssignment practice = policy(1);
        when(activities.selectPolicy(41L)).thenReturn(practice);
        for (int status : List.of(0, 1, 3)) {
            practice.setStatus(status);
            assertEquals("ACTIVITY_CLOSED", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        }
        practice.setStatus(2);
        practice.setStartTime(now.plusSeconds(1));
        assertEquals("ACTIVITY_CLOSED", service.verify(1L, 41L, 1006L, 1).getDenyCode());
        practice.setStartTime(now.minusDays(1));
        practice.setEndTime(now);
        assertEquals("ACTIVITY_CLOSED", service.verify(1L, 41L, 1006L, 1).getDenyCode());
    }

    @Test
    void assignedPracticeStillHasUnlimitedQuota() {
        TeachAssignment practice = policy(1);
        practice.setAiQuotaPerProb(3);
        when(activities.selectPolicy(41L)).thenReturn(practice);
        AiPermissionVerifyDTO result = service.verify(1L, 41L, 1006L, 4);
        assertTrue(result.getAllowed());
        assertEquals(-1, result.getMaxQuota());
        assertNull(result.getRemainingQuota());
        assertEquals(now.plusDays(1).atZone(AuthConstants.DB_ZONE).toInstant().toEpochMilli(),
                result.getDeadlineAt());
        verifyNoInteractions(redis);
    }

    @Test
    void unavailableUserAndInvalidStageCannotReachPermissionData() {
        when(users.selectById(1L)).thenReturn(null);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.verify(1L, 0L, 1006L, 1)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.verify(1L, 0L, 1006L, 5)).getCode());
        verifyNoInteractions(activities, redis);
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> values() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return values;
    }

    private TeachAssignment policy(int mode) {
        TeachAssignment assignment = new TeachAssignment();
        assignment.setId(41L);
        assignment.setClassId(8L);
        assignment.setModeType(mode);
        assignment.setStatus(2);
        assignment.setAllowedAiStages("[1,2,3,4]");
        assignment.setAiQuotaPerProb(3);
        assignment.setProblemIds("[1006]");
        assignment.setStartTime(now.minusDays(1));
        assignment.setEndTime(now.plusDays(1));
        return assignment;
    }

    private void useTeacher() {
        SysUser teacher = new SysUser();
        teacher.setId(1L);
        teacher.setRole("TEACHER");
        teacher.setStatus(1);
        when(users.selectById(1L)).thenReturn(teacher);
    }
}
