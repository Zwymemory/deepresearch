package com.deepresearch.service;

import com.deepresearch.web.dto.AgentHarnessRequest;
import com.deepresearch.web.dto.AgentHarnessResponse;
import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行一个隔离的 case × mode × trial。
 * Harness 不调用生产有状态入口，setup/history 只存在当前方法内存中。
 */
@Service
class AgentCaseRunner {

    private final ReactAgentService reactAgentService;
    private final NativeToolCallingAgentService nativeAgentService;
    private final AgentAssertionEngine assertionEngine;
    private final WorkflowHarnessAdapter workflowHarnessAdapter;

    @Autowired
    AgentCaseRunner(ReactAgentService reactAgentService,
                    NativeToolCallingAgentService nativeAgentService,
                    AgentAssertionEngine assertionEngine,
                    WorkflowHarnessAdapter workflowHarnessAdapter) {
        this.reactAgentService = reactAgentService;
        this.nativeAgentService = nativeAgentService;
        this.assertionEngine = assertionEngine;
        this.workflowHarnessAdapter = workflowHarnessAdapter;
    }

    AgentCaseRunner(ReactAgentService reactAgentService,
                    NativeToolCallingAgentService nativeAgentService,
                    AgentAssertionEngine assertionEngine) {
        this(reactAgentService, nativeAgentService, assertionEngine, null);
    }

    AgentHarnessResponse.CaseResult run(AgentHarnessRequest.AgentHarnessCase testCase, String mode) {
        return run(testCase, mode, "eval-local", 1);
    }

    AgentHarnessResponse.CaseResult run(AgentHarnessRequest.AgentHarnessCase testCase,
                                        String mode,
                                        String evaluationId,
                                        int trial) {
        String identity = evaluationIdentity(evaluationId, testCase.id(), mode, trial);
        String sessionId = identity + ":session";
        List<String> conversation = new ArrayList<>();
        AgentResearchResponse.Usage setupUsage = AgentResearchResponse.Usage.empty();
        long setupStarted = System.nanoTime();
        try {
            for (String setup : safe(testCase.sessionSetup())) {
                if (setup == null || setup.isBlank()) {
                    continue;
                }
                AgentEvaluationRun setupRun = runAgent(mode,
                        new AgentResearchRequest(setup, sessionId, identity), conversation, testCase);
                setupUsage = plus(setupUsage, setupRun.response().usage());
                if (!"SUCCESS".equals(setupRun.response().status()) || !setupRun.response().finished()) {
                    throw new IllegalStateException("evaluation setup did not finish successfully");
                }
                conversation.add("用户: " + setup);
                conversation.add("助手: " + setupRun.response().answer());
            }
        } catch (RuntimeException failure) {
            long setupLatency = millisSince(setupStarted);
            AgentEvaluationException classified = AgentEvaluationException.classify(failure);
            return assertionEngine.executionError(testCase, mode, trial, setupLatency,
                    setupLatency, setupUsage, classified, true);
        }
        long setupLatency = millisSince(setupStarted);

        long targetStarted = System.nanoTime();
        try {
            AgentEvaluationRun run = runAgent(mode,
                    new AgentResearchRequest(testCase.question(), sessionId, identity), conversation, testCase);
            long targetLatency = millisSince(targetStarted);
            return assertionEngine.evaluate(testCase, run, mode, targetLatency,
                    trial, setupLatency, setupUsage);
        } catch (RuntimeException failure) {
            long targetLatency = millisSince(targetStarted);
            AgentEvaluationException classified = AgentEvaluationException.classify(failure);
            return assertionEngine.executionError(testCase, mode, trial, targetLatency,
                    setupLatency, setupUsage, classified, false);
        }
    }

    private AgentEvaluationRun runAgent(String mode,
                                        AgentResearchRequest request,
                                        List<String> recentConversation,
                                        AgentHarnessRequest.AgentHarnessCase testCase) {
        return switch (mode) {
            case "manual-react" -> reactAgentService.evaluateRun(
                    request, recentConversation, testCase.allowedTools(), testCase.forbiddenTools());
            case "native-tool-calling" -> nativeAgentService.evaluateRun(
                    request, recentConversation, testCase.allowedTools(), testCase.forbiddenTools());
            case "langgraph-pwrs" -> {
                if (workflowHarnessAdapter == null) {
                    throw new IllegalStateException("workflow harness adapter is unavailable");
                }
                yield workflowHarnessAdapter.run(request, testCase.allowedTools(), request.userId());
            }
            default -> throw new IllegalArgumentException(
                    "Agent Harness mode 只能是 manual-react、native-tool-calling 或 langgraph-pwrs");
        };
    }

    private String evaluationIdentity(String evaluationId, String caseId, String mode, int trial) {
        String safeEvaluation = safeId(evaluationId);
        String safeCase = safeId(caseId);
        String safeMode = safeId(mode);
        return "eval:" + safeEvaluation + ":" + safeCase + ":" + safeMode + ":" + trial;
    }

    private String safeId(String value) {
        String normalized = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_-]", "-");
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60);
    }

    private long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private AgentResearchResponse.Usage plus(AgentResearchResponse.Usage a,
                                             AgentResearchResponse.Usage b) {
        if (a.estimatedCost().signum() != 0 && b.estimatedCost().signum() != 0
                && !a.costCurrency().equals(b.costCurrency())) {
            throw new IllegalArgumentException("评测 setup usage 币种不一致");
        }
        String currency = a.estimatedCost().signum() == 0 ? b.costCurrency() : a.costCurrency();
        return new AgentResearchResponse.Usage(
                a.inputTokens() + b.inputTokens(), a.outputTokens() + b.outputTokens(),
                a.totalTokens() + b.totalTokens(), a.estimated() || b.estimated(),
                a.estimatedCost().add(b.estimatedCost()), currency,
                a.durationMs() + b.durationMs(), a.modelCalls() + b.modelCalls(),
                a.toolCalls() + b.toolCalls());
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
