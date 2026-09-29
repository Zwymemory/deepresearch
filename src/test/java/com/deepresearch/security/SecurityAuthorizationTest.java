package com.deepresearch.security;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("unit-test")
class SecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService tokenService;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private VectorStore vectorStore;

    @Test
    void unauthenticatedStatefulAgentRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/research/agent")
                        .contentType("application/json")
                        .content("{\"question\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"需要登录\"}"));

        mockMvc.perform(post("/api/research/workflows")
                        .header("Idempotency-Key", "workflow-key-0001")
                        .contentType("application/json")
                        .content("{\"question\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserCannotAccessAdminEndpoint() throws Exception {
        String authorization = tokenService.issue(
                "tenant-1", "user-1", List.of("USER"), null).authorizationHeader();

        mockMvc.perform(get("/api/agent/bad-cases")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"权限不足\"}"));

        mockMvc.perform(get("/api/kb/count")
                        .header("Authorization", authorization))
                .andExpect(status().isForbidden());
    }

    @Test
    void privateResearchAndKnowledgeEndpointsFailClosed() throws Exception {
        mockMvc.perform(post("/api/research/hybrid")
                        .contentType("application/json")
                        .content("{\"question\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/integrations/dify/retrieve")
                        .contentType("application/json")
                        .content("{\"question\":\"test\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/kb/count"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousDevTokenCannotMintAdminRole() throws Exception {
        mockMvc.perform(post("/api/auth/dev-token")
                        .contentType("application/json")
                        .content("{\"tenantId\":\"tenant-1\",\"userId\":\"attacker\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsEndpointRequiresAdminRole() throws Exception {
        String user = tokenService.issue(
                "tenant-1", "user-1", List.of("USER"), null).authorizationHeader();
        String admin = tokenService.issue(
                "tenant-1", "admin-1", List.of("ADMIN"), null).authorizationHeader();

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", user))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", admin))
                .andExpect(status().isOk());
    }
}
