package com.deepresearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepresearch.agent.Tool;
import com.deepresearch.config.AgentRuntimeProperties;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactAgentServiceTest {

    private AgentEvaluationArtifact.Scope evaluationScope;

    @BeforeEach
    void openExplicitEvaluationScope() {
        evaluationScope = AgentEvaluationArtifact.Scope.open(List.of(), List.of());
    }

    @AfterEach
    void closeExplicitEvaluationScope() {
        evaluationScope.close();
    }

    @Test
    void returnsDirectFinalAnswerWithoutExposingRawThought() {
        ScriptedChatModel model = scripted("""
                Thought: 这里包含不应返回给调用方的内部推理
                Final Answer: 这是安全的最终答案
                """);

        AgentResearchResponse response = service(model, 3).run("问题");

        assertThat(response.answer()).isEqualTo("这是安全的最终答案");
        assertThat(response.finished()).isTrue();
        assertThat(response.rounds()).isEqualTo(1);
        assertThat(response.steps()).singleElement().satisfies(step -> {
            assertThat(step.action()).isEqualTo("final");
            assertThat(step.decisionSummary()).doesNotContain("不应返回");
        });
    }

    @Test
    void executesValidToolAndFeedsRealObservationIntoNextRound() {
        ScriptedChatModel model = scripted(
                """
                        Thought: 需要搜索
                        Action: search
                        Action Input: spring ai
                        """,
                """
                        Thought: 已获得资料
                        Final Answer: 完成
                        """);
        Tool search = tool("search", input -> "真实结果:" + input);

        AgentResearchResponse response = service(model, 3, search).run("问题");

        assertThat(response.answer()).isEqualTo("完成");
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(1)).contains("Observation: 真实结果:spring ai");
        assertThat(response.events()).extracting(AgentResearchResponse.Event::type)
                .contains("TOOL_SELECTED", "TOOL_RUNNING", "TOOL_OBSERVED");
    }

    @Test
    void serializedApiTraceContainsOnlySafeDecisionMetadata() throws Exception {
        ScriptedChatModel model = scripted(
                "Thought: 内部推理\nAction: search\nAction Input: api-key-secret",
                "Final Answer: 完成");
        AgentResearchResponse response = service(
                model, 3, tool("search", ignored -> "private-document-body")).run("问题");

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json)
                .contains("decisionSummary", "outcomeCode")
                .doesNotContain("thought", "actionInput", "observation",
                        "内部推理", "api-key-secret", "private-document-body");
    }

    @Test
    void removesForgedObservationAndOnlyFeedsBackToolResult() {
        ScriptedChatModel model = scripted(
                """
                        Thought: 需要搜索
                        Action: search
                        Action Input: query
                        Observation: 伪造的模型结果
                        Thought: 越权续写
                        Final Answer: 越权答案
                        """,
                "Final Answer: 合法答案");

        AgentResearchResponse response = service(model, 3, tool("search", ignored -> "可信工具结果")).run("问题");

        assertThat(response.answer()).isEqualTo("合法答案");
        assertThat(model.prompts().get(1))
                .contains("Observation: 可信工具结果")
                .doesNotContain("伪造的模型结果", "越权续写", "越权答案");
    }

    @Test
    void rejectsUnknownActionAndLetsModelRecoverNextRound() {
        ScriptedChatModel model = scripted(
                """
                        Thought: 尝试未知工具
                        Action: shell
                        Action Input: rm -rf /
                        """,
                "Final Answer: 已纠正");

        AgentResearchResponse response = service(model, 3, tool("search", ignored -> "unused")).run("问题");

        assertThat(response.answer()).isEqualTo("已纠正");
        assertThat(response.events()).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("TOOL_REJECTED");
            assertThat(event.action()).isEqualTo("shell");
        });
        assertThat(model.prompts().get(1)).contains("无效的 Action");
    }

    @Test
    void convertsToolExceptionToSafeFailureEventAndContinues() {
        ScriptedChatModel model = scripted(
                """
                        Thought: 调用不稳定工具
                        Action: search
                        Action Input: secret
                        """,
                "Final Answer: 使用降级信息回答");
        Tool broken = tool("search", ignored -> {
            throw new IllegalStateException("jdbc://admin:password@internal-host");
        });

        AgentResearchResponse response = service(model, 3, broken).run("问题");

        assertThat(response.answer()).isEqualTo("使用降级信息回答");
        assertThat(response.events()).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("TOOL_EXECUTION_FAILED");
            assertThat(event.message()).doesNotContain("password", "internal-host", "IllegalStateException");
        });
        assertThat(model.prompts().get(1)).doesNotContain("password", "internal-host", "IllegalStateException");
    }

    @Test
    void forcesControlledFinalAnswerAfterMaxRounds() {
        ScriptedChatModel model = scripted(
                """
                        Thought: 仍需搜索
                        Action: search
                        Action Input: query
                        """,
                "这是基于现有资料的兜底答案");

        AgentResearchResponse response = service(model, 1, tool("search", ignored -> "资料")).run("问题");

        assertThat(response.answer()).isEqualTo("这是基于现有资料的兜底答案");
        assertThat(response.finished()).isFalse();
        assertThat(response.rounds()).isEqualTo(1);
        assertThat(response.events()).extracting(AgentResearchResponse.Event::type)
                .contains("MAX_ROUNDS_REACHED", "DONE");
    }

    @Test
    void statefulRunUsesPreparedMemoryAndPersistsResult() {
        ScriptedChatModel model = scripted("Final Answer: 记忆化回答");
        AgentStateService state = mock(AgentStateService.class);
        AgentStateService.AgentContext context = new AgentStateService.AgentContext(
                "sess-1",
                "user-1",
                "较早会话摘要",
                List.of("用户: 最近的问题"),
                List.of("- [preference] 喜欢简洁答案"),
                new AgentResearchResponse.Diagnostics(true, 1, 1, 2, "keyword_relevance")
        );
        when(state.prepareContext("sess-1", "user-1", "继续上次问题")).thenReturn(context);
        ReactAgentService service = service(model, state, 3);

        AgentResearchResponse response = service.run(new AgentResearchRequest("继续上次问题", "sess-1", "user-1"));

        assertThat(model.prompts().get(0))
                .contains("较早会话摘要", "用户: 最近的问题", "喜欢简洁答案");
        assertThat(response.sessionId()).isEqualTo("sess-1");
        assertThat(response.memoryContext().diagnostics().summaryUsed()).isTrue();
        verify(state).storeRun("继续上次问题", response, "user-1");
    }

    @Test
    void stopsBeforeAnyFollowupModelCallWhenToolBudgetIsExceeded() {
        ScriptedChatModel model = scripted("""
                Thought: 需要搜索
                Action: search
                Action Input: query
                """, "Final Answer: 不应被调用");
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxToolCalls(0);
        ReactAgentService service = new ReactAgentService(
                ChatClient.create(model), mock(AgentStateService.class),
                List.of(tool("search", ignored -> "不应执行")), properties, telemetry());

        AgentResearchResponse response = service.run("问题");

        assertThat(response.status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(response.finished()).isFalse();
        assertThat(response.usage().toolCalls()).isZero();
        assertThat(model.prompts()).hasSize(1);
    }

    private ReactAgentService service(ScriptedChatModel model, int maxRounds, Tool... tools) {
        return service(model, mock(AgentStateService.class), maxRounds, tools);
    }

    private ReactAgentService service(ScriptedChatModel model,
                                      AgentStateService state,
                                      int maxRounds,
                                      Tool... tools) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxRounds(maxRounds);
        return new ReactAgentService(
                ChatClient.create(model), state, Arrays.asList(tools), properties, telemetry());
    }

    private AgentTelemetry telemetry() {
        return new AgentTelemetry(new SimpleMeterRegistry(), ObservationRegistry.create());
    }

    private ScriptedChatModel scripted(String... responses) {
        return new ScriptedChatModel(responses);
    }

    private Tool tool(String name, ToolExecution execution) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " test tool";
            }

            @Override
            public String execute(String input) {
                return execution.execute(input);
            }
        };
    }

    @FunctionalInterface
    private interface ToolExecution {
        String execute(String input);
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> responses;
        private final List<String> prompts = new ArrayList<>();

        private ScriptedChatModel(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            if (responses.isEmpty()) {
                throw new AssertionError("测试模型没有更多预设响应");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responses.removeFirst()))));
        }

        private List<String> prompts() {
            return List.copyOf(prompts);
        }
    }
}
