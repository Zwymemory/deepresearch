package com.deepresearch.service;

import com.deepresearch.web.dto.AgentFeedbackRequest;
import com.deepresearch.web.dto.AgentMemoryRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class AgentStateServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch")
            .withUsername("deepresearch")
            .withPassword("deepresearch");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.ai.vectorstore.pgvector.index-type", () -> "NONE");
        registry.add("spring.ai.openai.api-key", () -> "integration-test-openai-key");
        registry.add("spring.ai.zhipuai.api-key", () -> "integration-test-zhipuai-key");
        registry.add("tavily.api-key", () -> "integration-test-tavily-key");
        registry.add("deepresearch.rerank.enabled", () -> "false");
        registry.add("deepresearch.memory.recent-messages", () -> "2");
        registry.add("deepresearch.memory.summary-trigger-messages", () -> "4");
        registry.add("deepresearch.security.jwt-secret",
                () -> "integration-test-jwt-secret-with-at-least-32-bytes");
    }

    @Autowired
    private AgentStateService stateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ConversationSummaryService conversationSummaryService;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE agent_feedback, agent_event, agent_step, agent_message,
                    agent_run, user_memory, agent_session RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void createsSessionForOwnerAndRejectsCrossTenantAccess() {
        AgentStateService.AgentContext context = stateService.prepareContext(null, "tenant-a:user-a", "第一问");

        assertThat(context.sessionId()).startsWith("sess-");
        assertThat(stateService.listSessions("tenant-a:user-a", 10)).singleElement()
                .satisfies(session -> assertThat(session.sessionId()).isEqualTo(context.sessionId()));
        assertThat(stateService.listSessions("tenant-b:user-b", 10)).isEmpty();
        assertThatThrownBy(() -> stateService.prepareContext(context.sessionId(), "tenant-b:user-b", "越权"))
                .hasMessageContaining("sessionId 不属于当前用户");
    }

    @Test
    void keepsOnlyRecentMessageWindow() {
        AgentStateService.AgentContext context = stateService.prepareContext(null, "user-1", "q1");
        stateService.storeRun("q1", response("run-1", context.sessionId(), "a1"), context.userId());
        stateService.storeRun("q2", response("run-2", context.sessionId(), "a2"), context.userId());

        AgentStateService.AgentContext next = stateService.prepareContext(context.sessionId(), "user-1", "q3");

        assertThat(next.recentConversation()).containsExactly("用户: q2", "助手: a2");
        assertThat(next.diagnostics().recentMessageCount()).isEqualTo(2);
    }

    @Test
    void compressesOlderConversationAfterThreshold() {
        when(conversationSummaryService.summarize(anyString(), anyList())).thenReturn("稳定会话摘要");
        AgentStateService.AgentContext context = stateService.prepareContext(null, "user-1", "q1");
        stateService.storeRun("q1", response("run-1", context.sessionId(), "a1"), context.userId());
        stateService.storeRun("q2", response("run-2", context.sessionId(), "a2"), context.userId());
        stateService.storeRun("q3", response("run-3", context.sessionId(), "a3"), context.userId());

        AgentStateService.AgentContext next = stateService.prepareContext(context.sessionId(), "user-1", "q4");

        assertThat(next.sessionSummary()).isEqualTo("稳定会话摘要");
        assertThat(next.diagnostics().summaryUsed()).isTrue();
        verify(conversationSummaryService).summarize(anyString(), anyList());
    }

    @Test
    void selectsRelevantLongTermMemoryForCurrentUserOnly() {
        stateService.createMemory(new AgentMemoryRequest(
                "user-1", "preference", "Spring AI 回答要简洁", "manual", 0.9));
        stateService.createMemory(new AgentMemoryRequest(
                "user-1", "preference", "旅行时喜欢靠窗座位", "manual", 0.9));
        stateService.createMemory(new AgentMemoryRequest(
                "user-2", "secret", "Spring AI 管理员口令", "manual", 1.0));

        AgentStateService.AgentContext context = stateService.prepareContext(null, "user-1", "Spring AI 怎么用");

        assertThat(context.memories()).anyMatch(row -> row.contains("Spring AI 回答要简洁"));
        assertThat(context.memories()).noneMatch(row -> row.contains("管理员口令"));
        assertThat(context.diagnostics().totalMemoryCount()).isEqualTo(2);
    }

    @Test
    void recordsFeedbackAndReturnsBadCase() {
        AgentStateService.AgentContext context = stateService.prepareContext(null, "user-1", "问题");
        stateService.storeRun("问题", response("run-bad", context.sessionId(), "错误答案"), context.userId());

        stateService.addFeedback("run-bad", "user-1", new AgentFeedbackRequest("DOWN", "NOT_GROUNDED", "缺少证据"));

        assertThat(stateService.listBadCases(10)).singleElement().satisfies(badCase -> {
            assertThat(badCase.runId()).isEqualTo("run-bad");
            assertThat(badCase.reason()).isEqualTo("NOT_GROUNDED");
        });
        assertThatThrownBy(() -> stateService.addFeedback(
                "run-bad", "user-2", new AgentFeedbackRequest("DOWN", null, null)))
                .hasMessageContaining("runId 不存在或不属于当前用户");
    }

    @Test
    void persistsOnlySafeDecisionSummaryForAgentSteps() {
        AgentStateService.AgentContext context = stateService.prepareContext(null, "user-1", "问题");
        AgentResearchResponse safe = new AgentResearchResponse(
                "run-safe-step", context.sessionId(), "answer", 1, true,
                context.toResponseMemoryContext(),
                List.of(new AgentResearchResponse.Step(
                        1, "kb_search", "模型根据 JSON Schema 选择受控工具", "TOOL_SUCCEEDED", "hash")),
                List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, "final")));

        stateService.storeRun("问题", safe, context.userId());

        var stored = jdbcTemplate.queryForMap(
                "SELECT thought, action_input, observation FROM agent_step WHERE run_id = ?",
                "run-safe-step");
        assertThat(stored.get("thought")).isNull();
        assertThat(stored.get("action_input")).isNull();
        assertThat(stored.get("observation").toString())
                .contains("受控工具", "TOOL_SUCCEEDED")
                .doesNotContain("问题", "answer");
    }

    @Test
    void rollsBackRunStepAndMessageWritesWhenAnEventInsertFails() {
        AgentStateService.AgentContext context = stateService.prepareContext(null, "user-1", "问题");
        AgentResearchResponse malformed = new AgentResearchResponse(
                "run-rollback",
                context.sessionId(),
                "answer",
                1,
                true,
                context.toResponseMemoryContext(),
                List.of(new AgentResearchResponse.Step(1, "final", "生成答案", "SUCCESS", null)),
                List.of(new AgentResearchResponse.Event(1, "DONE", null, 1, "final"))
        );

        assertThatThrownBy(() -> stateService.storeRun("问题", malformed, context.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("agent_run", "run_id", "run-rollback")).isZero();
        assertThat(count("agent_step", "run_id", "run-rollback")).isZero();
        assertThat(count("agent_message", "run_id", "run-rollback")).isZero();
    }

    private AgentResearchResponse response(String runId, String sessionId, String answer) {
        return new AgentResearchResponse(
                runId,
                sessionId,
                answer,
                1,
                true,
                new AgentResearchResponse.MemoryContext(
                        "", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")),
                List.of(new AgentResearchResponse.Step(1, "final", "生成答案", "SUCCESS", null)),
                List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, "final"))
        );
    }

    private int count(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value
        );
        return count == null ? 0 : count;
    }
}
