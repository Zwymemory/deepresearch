package com.deepresearch.web;

import com.deepresearch.security.JwtTokenService;
import com.deepresearch.service.DifyRetrievalService;
import com.deepresearch.web.dto.DifyRetrievalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("unit-test")
class DifyIntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService tokenService;

    @MockitoBean
    private DifyRetrievalService retrievalService;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore vectorStore;

    @Test
    void authenticatedUserCanRetrieveEvidence() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(response());
        String authorization = tokenService.issue(
                "tenant-1", "dify-service", List.of("USER"), null).authorizationHeader();

        mockMvc.perform(post("/api/integrations/dify/retrieve")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"question\":\"ZXQ-4499 是什么？\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceCount").value(1))
                .andExpect(jsonPath("$.evidences[0].citation").value("[来源1]"))
                .andExpect(jsonPath("$.evidences[0].untrusted").value(true));

        verify(retrievalService).retrieve(any());
    }

    @Test
    void rejectsOutOfRangeTopKBeforeCallingService() throws Exception {
        String authorization = tokenService.issue(
                "tenant-1", "dify-service", List.of("USER"), null).authorizationHeader();

        mockMvc.perform(post("/api/integrations/dify/retrieve")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"question\":\"test\",\"topK\":11}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("topK 最大为 10"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        String authorization = tokenService.issue(
                "tenant-1", "dify-service", List.of("USER"), null).authorizationHeader();

        mockMvc.perform(post("/api/integrations/dify/retrieve")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    private DifyRetrievalResponse response() {
        return new DifyRetrievalResponse(
                "ZXQ-4499 是什么？", "ZXQ-4499 是什么？", false, 1,
                List.of(new DifyRetrievalResponse.Evidence(
                        "来源1", "[来源1]", "错误码手册", "doc-1", "chunk-1",
                        "manual.md", "错误码", 2, "doc-1#1", null, null, "vector+keyword",
                        0.032, 0.91, "ZXQ-4499 表示签名过期。", true)),
                new DifyRetrievalResponse.Diagnostics(
                        12, 3, 2, 1, true, 1, 18, 0.75, "packed",
                        true, "success", false, "order_changed"));
    }
}
