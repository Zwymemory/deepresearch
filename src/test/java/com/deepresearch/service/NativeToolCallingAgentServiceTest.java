package com.deepresearch.service;

import com.deepresearch.agent.CalculatorTool;
import com.deepresearch.agent.CalculatorTools;
import com.deepresearch.agent.CitationAwareToolOutput;
import com.deepresearch.agent.CitationDetail;
import com.deepresearch.agent.FileReadTool;
import com.deepresearch.agent.FileResourceTools;
import com.deepresearch.agent.KnowledgeBaseSearchTool;
import com.deepresearch.agent.KnowledgeSearchTools;
import com.deepresearch.agent.NativeToolExecutionRecorder;
import com.deepresearch.agent.WebSearchTool;
import com.deepresearch.agent.WebSearchTools;
import com.deepresearch.config.AgentRuntimeProperties;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeToolCallingAgentServiceTest {

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
    void executesFrameworkToolCallPersistsStateAndReturnsSafeTrace() {
        ScriptedToolCallingModel model = new ScriptedToolCallingModel(
                toolCall("call-1", "calculate", "{\"request\":{\"expression\":\"2 + 3\"}}"),
                text("结果是 5。"));
        AgentStateService state = mock(AgentStateService.class);
        AgentStateService.AgentContext context = new AgentStateService.AgentContext(
                "sess-1", "tenant:user", "summary", List.of("用户: earlier"), List.of(),
                new AgentResearchResponse.Diagnostics(true, 1, 0, 0, "no_memory"));
        when(state.prepareContext("sess-1", "tenant:user", "计算 2+3")).thenReturn(context);
        NativeToolCallingAgentService service = service(model, state);
        AgentResearchRequest request = new AgentResearchRequest("计算 2+3", "sess-1", "tenant:user");

        AgentResearchResponse response = service.run(request);

        assertThat(response.answer()).isEqualTo("结果是 5。");
        assertThat(response.steps()).extracting(AgentResearchResponse.Step::action)
                .containsExactly("calculate", "final");
        assertThat(response.steps()).allSatisfy(step ->
                assertThat(step.decisionSummary()).doesNotContain("2 + 3"));
        assertThat(response.events()).extracting(AgentResearchResponse.Event::type)
                .contains("TOOL_SELECTED", "TOOL_OBSERVED", "FINAL_ANSWER", "DONE");
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(1).getInstructions())
                .anyMatch(ToolResponseMessage.class::isInstance);
        verify(state).storeRun("计算 2+3", response, "tenant:user");
    }

    @Test
    void stopsNativeLoopBeforeFollowupModelCallWhenToolBudgetIsExceeded() {
        ScriptedToolCallingModel model = new ScriptedToolCallingModel(
                toolCall("call-1", "calculate", "{\"request\":{\"expression\":\"2 + 3\"}}"),
                text("不应被调用"));
        AgentStateService state = mock(AgentStateService.class);
        when(state.prepareContext(null, null, "计算")).thenReturn(new AgentStateService.AgentContext(
                null, "default", "", List.of(), List.of(),
                new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "stateless")));
        AgentRuntimeProperties policy = new AgentRuntimeProperties();
        policy.setMaxToolCalls(0);
        NativeToolCallingAgentService service = service(model, state, policy);

        AgentResearchResponse response = service.run(new AgentResearchRequest("计算", null, null));

        assertThat(response.status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(response.finished()).isFalse();
        assertThat(model.prompts()).hasSize(1);
    }

    @Test
    void globalizesRetrievalMarkersAndReturnsOnlyCitationsUsedByFinalAnswer() {
        WebSearchTool web = mock(WebSearchTool.class);
        when(web.executeWithCitations("first")).thenReturn(new CitationAwareToolOutput(
                "[UNTRUSTED_DATA_BEGIN source=web]\n[来源1] A\nURL: https://a.example/doc\n"
                        + "摘要: alpha\n[UNTRUSTED_DATA_END source=web]",
                List.of("https://a.example/doc"), List.of(CitationDetail.web(
                        "https://a.example/doc", "A", "https://a.example/doc", "alpha"))));
        when(web.executeWithCitations("second")).thenReturn(new CitationAwareToolOutput(
                "[UNTRUSTED_DATA_BEGIN source=web]\n[来源1] B\nURL: https://b.example/doc\n"
                        + "摘要: beta\n[UNTRUSTED_DATA_END source=web]",
                List.of("https://b.example/doc"), List.of(CitationDetail.web(
                        "https://b.example/doc", "B", "https://b.example/doc", "beta"))));
        ScriptedToolCallingModel model = new ScriptedToolCallingModel(
                toolCall("call-1", "searchWeb", "{\"request\":{\"query\":\"first\"}}"),
                toolCall("call-2", "searchWeb", "{\"request\":{\"query\":\"second\"}}"),
                text("先引用第二次检索 [来源2]，再引用第一次检索 [来源1]。"));
        AgentStateService state = mock(AgentStateService.class);
        when(state.prepareContext(null, null, "研究问题")).thenReturn(new AgentStateService.AgentContext(
                null, "default", "", List.of(), List.of(),
                new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "stateless")));

        AgentResearchResponse response = service(
                model, state, new AgentRuntimeProperties(), mock(KnowledgeBaseSearchTool.class), web)
                .run(new AgentResearchRequest("研究问题", null, null));

        verify(web).executeWithCitations("first");
        verify(web).executeWithCitations("second");
        assertThat(response.finished())
                .as("status=%s answer=%s citations=%s events=%s", response.status(), response.answer(),
                        response.citations(), response.events())
                .isTrue();
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.answer())
                .isEqualTo("先引用第二次检索 [来源1]，再引用第一次检索 [来源2]。");
        assertThat(response.citations())
                .containsExactly("https://b.example/doc", "https://a.example/doc");
        assertThat(response.citationContract()).isEqualTo("INDEXED_V1");
        assertThat(response.citationDetails()).extracting(CitationDetail::title).containsExactly("B", "A");
        assertThat(model.prompts()).hasSize(3);
        assertThat(toolResponseData(model.prompts().get(1))).contains("[来源1]");
        assertThat(toolResponseData(model.prompts().get(2))).contains("[来源2]");
    }

    @Test
    void rejectsFinalAnswerThatInventsOutOfRangeCitationNumber() {
        WebSearchTool web = mock(WebSearchTool.class);
        when(web.executeWithCitations("first")).thenReturn(new CitationAwareToolOutput(
                "[来源1] A\nURL: https://a.example/doc\n摘要: alpha",
                List.of("https://a.example/doc")));
        ScriptedToolCallingModel model = new ScriptedToolCallingModel(
                toolCall("call-1", "searchWeb", "{\"request\":{\"query\":\"first\"}}"),
                text("模型自行编造了来源 [来源2]。"));
        AgentStateService state = mock(AgentStateService.class);
        when(state.prepareContext(null, null, "研究问题")).thenReturn(new AgentStateService.AgentContext(
                null, "default", "", List.of(), List.of(),
                new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "stateless")));

        AgentResearchResponse response = service(
                model, state, new AgentRuntimeProperties(), mock(KnowledgeBaseSearchTool.class), web)
                .run(new AgentResearchRequest("研究问题", null, null));

        assertThat(response.finished()).isFalse();
        assertThat(response.status()).isEqualTo("CITATION_VALIDATION_FAILED");
        assertThat(response.citations()).isEmpty();
        assertThat(response.citationContract()).isEqualTo("NONE");
        assertThat(response.answer()).contains("不作为已核验结论");
        assertThat(response.events()).extracting(AgentResearchResponse.Event::type)
                .contains("CITATION_VALIDATION_FAILED", "DONE")
                .doesNotContain("FINAL_ANSWER");
    }

    private NativeToolCallingAgentService service(ScriptedToolCallingModel model, AgentStateService state) {
        return service(model, state, new AgentRuntimeProperties());
    }

    private NativeToolCallingAgentService service(ScriptedToolCallingModel model,
                                                   AgentStateService state,
                                                   AgentRuntimeProperties policy) {
        return service(model, state, policy,
                mock(KnowledgeBaseSearchTool.class), mock(WebSearchTool.class));
    }

    private NativeToolCallingAgentService service(ScriptedToolCallingModel model,
                                                   AgentStateService state,
                                                   AgentRuntimeProperties policy,
                                                   KnowledgeBaseSearchTool knowledge,
                                                   WebSearchTool web) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        NativeToolExecutionRecorder recorder = new NativeToolExecutionRecorder();
        return new NativeToolCallingAgentService(
                model,
                state,
                recorder,
                new CalculatorTools(new CalculatorTool(), validator, recorder),
                new KnowledgeSearchTools(knowledge, validator, recorder),
                new WebSearchTools(web, validator, recorder),
                new FileResourceTools(mock(FileReadTool.class), validator, recorder),
                policy,
                new AgentTelemetry(new SimpleMeterRegistry(), ObservationRegistry.create()));
    }

    private ChatResponse toolCall(String id, String name, String arguments) {
        AssistantMessage message = new AssistantMessage(
                "", Map.of(), List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)));
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private String toolResponseData(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static final class ScriptedToolCallingModel implements ChatModel {
        private final Deque<ChatResponse> responses;
        private final List<Prompt> prompts = new ArrayList<>();

        private ScriptedToolCallingModel(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (responses.isEmpty()) {
                throw new AssertionError("测试模型没有更多预设响应");
            }
            ChatResponse response = responses.removeFirst();
            return response;
        }

        private List<Prompt> prompts() {
            return List.copyOf(prompts);
        }
    }
}
