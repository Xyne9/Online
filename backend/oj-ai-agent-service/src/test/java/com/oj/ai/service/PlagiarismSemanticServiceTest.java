package com.oj.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.ai.model.DynamicAiModelFactory;
import com.oj.common.api.dto.ai.AiPlagiarismCheckDTO;
import com.oj.common.core.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlagiarismSemanticServiceTest {
    private final DynamicAiModelFactory models=mock(DynamicAiModelFactory.class);
    private final PlagiarismSemanticService service=new PlagiarismSemanticService(models,new ObjectMapper());
    private AiPlagiarismCheckDTO.CandidateDTO candidate() {
        var value=new AiPlagiarismCheckDTO.CandidateDTO(); value.setSubmissionId(9L); value.setUserId(3L); value.setCode("x=0\nx+=1\nprint(x)"); return value;
    }
    @Test void validModelOutputRetainsItsActualScoreReasonAndLineSegments() {
        var result=service.parse("{\"similarity\":0.9432,\"reason\":\"共同的冗余状态转移\",\"segments\":[{\"segName\":\"累加\",\"aFrom\":1,\"aTo\":2,\"bFrom\":1,\"bTo\":2}]}",candidate(),"a=0\na+=1\nprint(a)",.85);
        assertEquals(.9432,result.getSimilarity()); assertTrue(result.getSuspected()); assertNull(result.getAstSimilarity());
        assertEquals(2,result.getSegments().get(0).getATo()); assertEquals("共同的冗余状态转移",result.getReason());
    }
    @Test void hallucinatedLineRangesFailInsteadOfBeingClamped() {
        assertEquals(502,assertThrows(BusinessException.class,()->service.parse("{\"similarity\":0.99,\"reason\":\"same\",\"segments\":[{\"segName\":\"loop\",\"aFrom\":1,\"aTo\":900,\"bFrom\":1,\"bTo\":2}]}",candidate(),"a=0\na+=1",.85)).getCode());
    }
    @Test void invalidScoresAndHighScoresWithoutEvidenceFail() {
        for(String body:new String[]{"{\"similarity\":94,\"reason\":\"same\",\"segments\":[]}","{\"similarity\":0.99,\"reason\":\"same\",\"segments\":[]}","不是JSON"})
            assertEquals(502,assertThrows(BusinessException.class,()->service.parse(body,candidate(),"a=0\na+=1",.85)).getCode());
    }
    @Test void unavailableModelIsARealFailure() {
        var request=new AiPlagiarismCheckDTO(); request.setProblemId(1006L); request.setBaseSubmissionId(1L); request.setLanguage("python"); request.setBaseCode("a=1\nprint(a)"); request.setCandidates(java.util.List.of(candidate()));
        when(models.select(null)).thenThrow(BusinessException.badGateway("本地模型不可用"));
        assertEquals(502,assertThrows(BusinessException.class,()->service.check(request)).getCode());
        verify(models,never()).streaming(any());
    }
}
