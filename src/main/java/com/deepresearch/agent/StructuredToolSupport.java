package com.deepresearch.agent;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import com.deepresearch.service.AgentBudgetContext;
import com.deepresearch.service.AgentControlException;
import com.deepresearch.service.AgentRunBudget;
import com.deepresearch.service.AgentEvaluationArtifact;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** 原生工具共享的参数校验与安全失败转换。 */
final class StructuredToolSupport {

    private StructuredToolSupport() {
    }

    static StructuredToolResult validate(Validator validator, Object request) {
        if (request == null) {
            return StructuredToolResult.failure("INVALID_TOOL_INPUT", "工具参数不能为空");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        String message = violations.stream().map(ConstraintViolation::getMessage).sorted().findFirst()
                .orElse("工具参数不合法");
        return StructuredToolResult.failure("INVALID_TOOL_INPUT", message);
    }

    static StructuredToolResult execute(String toolName,
                                        NativeToolExecutionRecorder recorder,
                                        String rawArgument,
                                        Supplier<String> action) {
        return executeInternal(toolName, recorder, rawArgument, action, Function.identity());
    }

    /**
     * Runs the blocking retrieval operation under the timeout executor, then updates the
     * request-thread citation registry after the result returns. ThreadLocal state must not be
     * accessed inside AgentRunBudget's worker thread.
     */
    static StructuredToolResult executeCitationAware(String toolName,
                                                      NativeToolExecutionRecorder recorder,
                                                      String rawArgument,
                                                      Supplier<CitationAwareToolOutput> action) {
        return executeInternal(toolName, recorder, rawArgument, action, recorder::globalizeCitations);
    }

    private static <T> StructuredToolResult executeInternal(String toolName,
                                                             NativeToolExecutionRecorder recorder,
                                                             String rawArgument,
                                                             Supplier<T> action,
                                                             Function<T, String> render) {
        StructuredToolResult result;
        AgentEvaluationArtifact artifact = AgentEvaluationArtifact.Scope.current();
        String argumentFingerprint = ToolArgumentFingerprint.sha256(rawArgument);
        ToolExecutionPolicy.Decision productionDecision =
                ToolExecutionPolicy.authorize(toolName, argumentFingerprint);
        if (!productionDecision.permitted()) {
            result = StructuredToolResult.failure("POLICY_DENIED", "当前主体未获授该工具能力");
            recorder.record(toolName, argumentFingerprint, result);
            if (artifact != null) {
                artifact.capture(toolName, result.code(), argumentFingerprint, result.content());
            }
            return result;
        }
        if (artifact != null && !artifact.toolAllowed(toolName)) {
            result = StructuredToolResult.failure("POLICY_DENIED", "工具调用被评测策略拒绝");
            recorder.record(toolName, argumentFingerprint, result);
            artifact.capture(toolName, result.code(), argumentFingerprint, result.content());
            return result;
        }
        try {
            AgentRunBudget budget = AgentBudgetContext.current();
            T value = budget == null ? action.get() : budget.callTool(toolName, action::get);
            String output = render.apply(value);
            result = AgentEvaluationArtifact.successfulObservation(output)
                    ? StructuredToolResult.success(output)
                    : StructuredToolResult.failure("TOOL_RESULT_UNAVAILABLE", output);
        } catch (AgentControlException exception) {
            result = StructuredToolResult.failure(exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            result = StructuredToolResult.failure("TOOL_EXECUTION_FAILED", "工具执行失败，请稍后重试");
        }
        if (artifact != null) {
            String content = artifact.capture(
                    toolName, result.code(), argumentFingerprint, result.content());
            result = new StructuredToolResult(result.success(), result.code(), content);
        }
        recorder.record(toolName, argumentFingerprint, result);
        return result;
    }

    static StructuredToolResult reject(String toolName,
                                       NativeToolExecutionRecorder recorder,
                                       StructuredToolResult validation) {
        String fingerprint = ToolArgumentFingerprint.sha256("");
        AgentEvaluationArtifact artifact = AgentEvaluationArtifact.Scope.current();
        if (artifact != null) {
            artifact.capture(toolName, validation.code(), fingerprint, validation.content());
        }
        recorder.record(toolName, fingerprint, validation);
        return validation;
    }
}
