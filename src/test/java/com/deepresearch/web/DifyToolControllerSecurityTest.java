package com.deepresearch.web;

import com.deepresearch.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("unit-test")
class DifyToolControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenService userTokens;
    @MockitoBean private JdbcTemplate jdbcTemplate;
    @MockitoBean private VectorStore vectorStore;

    @Test
    void internalToolDoesNotAcceptAnonymousOrUserJwt() throws Exception {
        String body = "{\"runId\":\"wf-7e65e2c8-4251-41ed-94aa-123147661234\","
                + "\"callId\":\"wf-7e65e2c8-4251-41ed-94aa-123147661234:initial:1\","
                + "\"input\":\"1+1\"}";
        mockMvc.perform(post("/internal/dify/tools/calculator")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        String userJwt = userTokens.issue("tenant-1", "user-1", List.of("USER"), null)
                .authorizationHeader();
        mockMvc.perform(post("/internal/dify/tools/calculator")
                        .header("Authorization", userJwt)
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/dify/tools/unapproved")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }
}
