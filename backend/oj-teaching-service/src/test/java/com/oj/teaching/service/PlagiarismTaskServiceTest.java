package com.oj.teaching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.api.dto.ai.AiPlagiarismResultDTO;
import com.oj.common.api.dto.judge.SubmissionCodeDTO;
import com.oj.common.api.feign.*;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.result.Result;
import com.oj.teaching.dto.PlagiarismRequests;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.*;
import org.springframework.web.context.request.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlagiarismTaskServiceTest {
    private final PlagiarismRepository repository=mock(PlagiarismRepository.class);
    private final PlagiarismQueryService queries=mock(PlagiarismQueryService.class);
    private final PlagiarismProgressService progress=mock(PlagiarismProgressService.class);
    private final JudgeInnerFeignClient judge=mock(JudgeInnerFeignClient.class);
    private final AiInnerFeignClient ai=mock(AiInnerFeignClient.class);
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private PlagiarismTaskService tasks;
    @BeforeEach void setUp() {
        var request=new MockHttpServletRequest(); request.addHeader("X-User-Id","9"); request.addHeader("X-User-Role","TEACHER");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(repository.assignment(eq(12L),anyBoolean())).thenReturn(Map.of("teacher_id",9L,"class_teacher_id",9L,"problem_ids","[1006]"));
        when(repository.activeTasks(12)).thenReturn(List.of());
        when(repository.insertTask(anyString(),eq(12L),anyString(),any(),anyDouble(),any(),eq(9L),anyLong())).thenReturn(31L);
        when(repository.sampleIds(12,1006)).thenReturn(List.of(1L,2L));
        when(repository.reports(eq(12L),any())).thenReturn(List.of());
        when(repository.insertReport(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyDouble(),any(),any(),any())).thenReturn(88L);
        ValueOperations<String,String> values=mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values); when(values.increment(anyString())).thenReturn(31L,88L);
        when(judge.getBatchBestCodes(List.of(1L,2L))).thenReturn(Result.ok(List.of(code(1,4,"int main(){int sum=1;return sum;}"),code(2,7,"int main(){int answer=1;return answer;}"))));
        when(queries.level(anyDouble(),any())).thenReturn("high");
        when(repository.report(88L)).thenReturn(Map.of("stage1_algo_sim",100.0,"stage2_ai_sim",96.0,"display_no","P-0088"));
        var access=new PlagiarismAccess(repository);
        var analyzer=new CodeSimilarityAnalyzer();
        tasks=new PlagiarismTaskService(repository,access,new PlagiarismReviewService(repository,access,manager),queries,progress,analyzer,new CodeSimilarityAlignment(analyzer),judge,ai,new ObjectMapper(),redis,mock(ThreadPoolTaskExecutor.class),manager);
    }
    @AfterEach void clean() { RequestContextHolder.resetRequestAttributes(); }
    private static SubmissionCodeDTO code(long id,long user,String source) {
        var code=new SubmissionCodeDTO();code.setSubmissionId(id);code.setUserId(user);code.setProblemId(1006L);code.setLanguage("cpp");code.setCode(source);code.setScore(87);code.setSubmittedAt(id*1000);return code;
    }
    private static AiPlagiarismResultDTO conclusion() {
        var segment=new AiPlagiarismResultDTO.SegmentDTO();segment.setSegName("共同初值");segment.setAFrom(1);segment.setATo(1);segment.setBFrom(1);segment.setBTo(1);
        var pair=new AiPlagiarismResultDTO.PairResultDTO(); pair.setSubmissionId(2L);pair.setUserId(7L);pair.setSimilarity(.96);pair.setReason("共同的非常规初值");pair.setSegments(List.of(segment));
        var data=new AiPlagiarismResultDTO();data.setPairs(List.of(pair));data.setModelName("real-model-name");return data;
    }
    @Test void semanticStageReceivesBothActualSourcesAndTheSelectedChannel() {
        when(ai.checkCodeSemanticSimilarity(any())).thenReturn(Result.ok(conclusion()));
        var result=tasks.check(new PlagiarismRequests.Check(12L,1006L,"cpp",75.0,"local-qwen"));
        assertEquals(1,result.get("totalPairsCompared"));assertEquals(1,result.get("stage1SuspiciousCount"));
        var request=ArgumentCaptor.forClass(com.oj.common.api.dto.ai.AiPlagiarismCheckDTO.class);verify(ai).checkCodeSemanticSimilarity(request.capture());
        assertEquals("local-qwen",request.getValue().getChannelCode());assertTrue(request.getValue().getBaseCode().contains("sum"));assertTrue(request.getValue().getCandidates().get(0).getCode().contains("answer"));
        verify(repository).insertReport(anyString(),eq(31L),eq(12L),eq(1006L),eq("cpp"),eq(4L),eq(1L),eq(7L),eq(2L),eq(100.0),eq(96.0),eq("real-model-name"),eq("共同的非常规初值"));
        verify(repository).taskProgress(eq(31L),eq("DONE"),eq(100),eq(1),eq(1),eq(1),eq(1),anyLong(),anyLong(),isNull());
    }
    @Test void modelFailurePersistsFailedAndDoesNotReplacePreviousReports() {
        when(ai.checkCodeSemanticSimilarity(any())).thenThrow(BusinessException.badGateway("语义模型不可用"));
        assertEquals(502,assertThrows(BusinessException.class,()->tasks.check(new PlagiarismRequests.Check(12L,1006L,"cpp",75.0,null))).getCode());
        verify(repository).taskProgress(eq(31L),eq("FAILED"),anyInt(),eq(1),anyInt(),eq(1),eq(0),anyLong(),anyLong(),eq("语义模型不可用"));
        verify(repository,never()).deleteReport(anyLong());verify(repository,never()).insertReport(anyString(),anyLong(),anyLong(),anyLong(),anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyDouble(),any(),any(),any());
    }
    @Test void duplicateActiveScopeIsRejectedBeforeEnqueueing() {
        when(repository.activeTasks(12)).thenReturn(List.of(Map.of("problem_ids","[1006]","language","cpp")));
        assertEquals(409,assertThrows(BusinessException.class,()->tasks.create(new PlagiarismRequests.Task(12L,List.of(1006L),"cpp",75.0,null,"problem"))).getCode());
        verifyNoInteractions(ai,judge);verify(repository,never()).insertTask(anyString(),anyLong(),anyString(),any(),anyDouble(),any(),anyLong(),anyLong());
    }
    @Test void foreignProblemIsRejectedBeforeFetchingStudentCode() {
        assertEquals(400,assertThrows(BusinessException.class,()->tasks.check(new PlagiarismRequests.Check(12L,9999L,"cpp",75.0,null))).getCode());verifyNoInteractions(judge,ai);
    }
}
