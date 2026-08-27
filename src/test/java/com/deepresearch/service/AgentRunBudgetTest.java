package com.deepresearch.service;

import com.deepresearch.config.AgentRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunBudgetTest {

    @Test
    void recordsProviderUsageAsExactAndCalculatesConfiguredCost() {
        AgentRunBudget budget = new AgentRunBudget(policy());
        ChatResponse response = response("answer", 100, 25);

        budget.callModel("prompt", () -> response);

        assertThat(budget.snapshot().inputTokens()).isEqualTo(100);
        assertThat(budget.snapshot().outputTokens()).isEqualTo(25);
        assertThat(budget.snapshot().estimated()).isFalse();
        assertThat(budget.snapshot().estimatedCost()).isEqualByComparingTo(new BigDecimal("0.00015000"));
    }

    @Test
    void marksUsageAsEstimatedWhenProviderDoesNotReturnUsage() {
        AgentRunBudget budget = new AgentRunBudget(policy());

        budget.callModel("中文 prompt", () -> new ChatResponse(
                List.of(new Generation(new AssistantMessage("中文 answer")))));

        assertThat(budget.snapshot().estimated()).isTrue();
        assertThat(budget.snapshot().totalTokens()).isPositive();
    }

    @Test
    void rejectsProjectedInputBeforeCallingModel() {
        AgentRuntimeProperties policy = policy();
        policy.setMaxInputTokens(1);
        policy.setMaxTotalTokens(1);
        AgentRunBudget budget = new AgentRunBudget(policy);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> budget.callModel("这是明显超过预算的输入", () -> {
            calls.incrementAndGet();
            return response("unused", 1, 1);
        })).isInstanceOfSatisfying(AgentControlException.class,
                failure -> assertThat(failure.code()).isEqualTo("BUDGET_EXCEEDED"));
        assertThat(calls).hasValue(0);
    }

    @Test
    void enforcesModelTimeoutAndToolCallLimit() {
        AgentRuntimeProperties timeoutPolicy = policy();
        timeoutPolicy.setModelTimeout(Duration.ofMillis(20));
        AgentRunBudget timed = new AgentRunBudget(timeoutPolicy);

        assertThatThrownBy(() -> timed.callModel("prompt", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return response("late", 1, 1);
        })).isInstanceOfSatisfying(AgentControlException.class,
                failure -> assertThat(failure.code()).isEqualTo("MODEL_TIMEOUT"));

        AgentRuntimeProperties toolPolicy = policy();
        toolPolicy.setMaxToolCalls(1);
        AgentRunBudget tools = new AgentRunBudget(toolPolicy);
        assertThat(tools.callTool(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> tools.callTool(() -> "must-not-run"))
                .isInstanceOfSatisfying(AgentControlException.class,
                        failure -> assertThat(failure.code()).isEqualTo("BUDGET_EXCEEDED"));
    }

    private AgentRuntimeProperties policy() {
        AgentRuntimeProperties policy = new AgentRuntimeProperties();
        policy.setInputCostPerMillion(BigDecimal.ONE);
        policy.setOutputCostPerMillion(BigDecimal.valueOf(2));
        return policy;
    }

    private ChatResponse response(String content, int inputTokens, int outputTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(inputTokens, outputTokens))
                        .build());
    }
}
