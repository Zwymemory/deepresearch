package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AgentCaseRunnerTest {

    @Test
    void keepsSetupInMemoryUsesIsolatedIdentityAndCountsItsUsage() {
        ReactAgentService manual = mock(ReactAgentService.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            AgentResearchRequest request = invocation.getArgument(0);
            List<String> history = invocation.getArgument(1);
            if (calls.getAndIncrement() == 0) {
                assertThat(history).isEmpty();
                assertThat(request.userId()).startsWith("eval:");
                return new AgentEvaluationRun(response("MCP-7788 是参数", 10), null);
            }
            assertThat(history).containsExactly("用户: MCP-7788 是什么？", "助手: MCP-7788 是参数");
            return new AgentEvaluationRun(response("MCP-7788 配置", 20), null);
        }).when(manual).evaluateRun(any(), any(), any(), any());
        AgentCaseRunner runner = new AgentCaseRunner(
                manual, mock(NativeToolCallingAgentService.class), new AgentAssertionEngine());
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "memory", "production-user", "它怎么配置？", List.of("MCP-7788 是什么？"),
                List.of(), List.of(), List.of("MCP-7788"), List.of(), List.of(), true, 2);

        var result = runner.run(testCase, "manual-react", "suite-1", 1);

        assertThat(result.passed()).isTrue();
        assertThat(result.setupUsage().totalTokens()).isEqualTo(10);
        assertThat(result.totalUsage().totalTokens()).isEqualTo(30);
        assertThat(calls).hasValue(2);
    }

    @Test
    void convertsOneCaseExceptionIntoSafeErrorResult() {
        ReactAgentService manual = mock(ReactAgentService.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("provider secret https://admin:pwd@internal");
        }).when(manual).evaluateRun(any(), any(), any(), any());
        AgentCaseRunner runner = new AgentCaseRunner(
                manual, mock(NativeToolCallingAgentService.class), new AgentAssertionEngine());
        AgentHarnessRequest.AgentHarnessCase testCase = new AgentHarnessRequest.AgentHarnessCase(
                "broken", "u", "q", null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, null);

        var result = runner.run(testCase, "manual-react", "suite-1", 1);

        assertThat(result.executionOutcome()).isEqualTo("ERROR");
        assertThat(result.executionErrorCode()).isEqualTo("EVALUATION_EXECUTION_FAILED");
        assertThat(result.executionErrorMessage()).isEqualTo("评测 case 执行失败")
                .doesNotContain("admin", "pwd", "internal");
    }

    private AgentResearchResponse response(String answer, int tokens) {
        return new AgentResearchResponse(
                "run", "session", answer, 1, true,
                new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")),
                List.of(), List.of(new AgentResearchResponse.Event(1, "DONE", "done", 1, null)),
                "SUCCESS", new AgentResearchResponse.Usage(
                tokens, 0, tokens, true, java.math.BigDecimal.ZERO, "CNY", 1, 1, 0));
    }
}
