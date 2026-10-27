package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import com.oj.teaching.entity.*;
import com.oj.teaching.mapper.ActivityMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssignmentServiceTest {
    @Test void atomicUndoDoesNotTouchJudgedScores() {
        // 使用真实support以保留final Mapper依赖与归属校验。
        ActivityMapper mapper=mock(ActivityMapper.class);
        var users=mock(com.oj.teaching.mapper.SysUserMapper.class);
        var s=spy(new ActivitySupport(mapper,users,new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.oj.common.api.feign.ProblemInnerFeignClient.class),mock(com.oj.common.api.feign.ProblemTestCaseFeignClient.class),mock(org.springframework.data.redis.core.StringRedisTemplate.class)));
        SysUser student=new SysUser(); student.setId(7L); student.setClassId(10L); student.setRole("STUDENT");
        TeachAssignment a=new TeachAssignment(); a.setId(20L); a.setModeType(2); a.setStatus(2); a.setClassId(10L); a.setStartTime(ActivitySupport.now().minusHours(1)); a.setEndTime(ActivitySupport.now().plusHours(1));
        doReturn(student).when(s).student(); when(mapper.lock(20L)).thenReturn(a); when(mapper.revoke(eq(20L),eq(7L),any())).thenReturn(1);
        AssignmentService service=new AssignmentService(s,mock(ExamService.class));
        assertEquals("UNSUBMITTED",service.revoke(20L).get("status"));
        verify(mapper).revoke(eq(20L),eq(7L),any()); verify(mapper,never()).improve(any(),any(),any(),any(),any(),any());
        when(mapper.revoke(eq(20L),eq(7L),any())).thenReturn(0); assertThrows(BusinessException.class,()->service.revoke(20L));
    }
    @Test void pagingCapsPageSizeAndKeepsOutOfRangeEmpty() {
        var page=ActivitySupport.page(List.of(1,2,3),2,200);
        assertEquals(100,page.get("pageSize")); assertEquals(List.of(),page.get("list")); assertEquals(false,page.get("hasNext"));
        assertThrows(BusinessException.class,()->ActivitySupport.page(List.of(),0,20));
    }
}

