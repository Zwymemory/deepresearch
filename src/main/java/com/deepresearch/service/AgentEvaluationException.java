package com.deepresearch.service;

import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 将单个 Agent 评测 case 的基础设施异常收敛为稳定、安全的错误码。
 *
 * <p>该异常只表示 case 无法正常执行，不表示断言失败。调用方可以把 {@link #code()}
 * 和安全消息写入评测结果，但不得把底层异常消息返回给报告或 API。</p>
 */
final class AgentEvaluationException extends RuntimeException {

    private static final int MAX_CAUSE_DEPTH = 32;

    private final String code;

    private AgentEvaluationException(String code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }

    String code() {
        return code;
    }

    static AgentEvaluationException classify(Throwable failure) {
        if (failure instanceof AgentEvaluationException evaluationFailure) {
            return evaluationFailure;
        }
        if (findCause(failure, CancellationException.class) != null
                || findCause(failure, InterruptedException.class) != null) {
            return error("EVALUATION_CANCELLED", "评测 case 已取消", failure);
        }

        AgentControlException controlled = findCause(failure, AgentControlException.class);
        if (controlled != null) {
            return switch (controlled.code()) {
                case "CANCELLED" -> error("EVALUATION_CANCELLED", "评测 case 已取消", failure);
                case "MODEL_TIMEOUT", "TOOL_TIMEOUT" ->
                        error("EVALUATION_TIMEOUT", "评测 case 执行超时", failure);
                case "BUDGET_EXCEEDED" -> error(
                        "EVALUATION_BUDGET_EXCEEDED", "评测 case 已达到运行预算上限", failure);
                default -> executionFailure(failure);
            };
        }
        if (findCause(failure, TimeoutException.class) != null) {
            return error("EVALUATION_TIMEOUT", "评测 case 执行超时", failure);
        }

        ResponseStatusException status = findCause(failure, ResponseStatusException.class);
        if (status != null) {
            if (status.getStatusCode().value() == 401 || status.getStatusCode().value() == 403) {
                return error("EVALUATION_ACCESS_DENIED", "评测 case 无权访问所需状态", failure);
            }
            if (status.getStatusCode().is4xxClientError()) {
                return error("EVALUATION_INVALID_CASE", "评测 case 配置不合法", failure);
            }
        }
        if (findCause(failure, DataAccessException.class) != null) {
            return error("EVALUATION_STATE_UNAVAILABLE", "评测状态暂时不可用", failure);
        }
        if (findCause(failure, IllegalArgumentException.class) != null) {
            return error("EVALUATION_INVALID_CASE", "评测 case 配置不合法", failure);
        }
        return executionFailure(failure);
    }

    private static AgentEvaluationException executionFailure(Throwable failure) {
        return error("EVALUATION_EXECUTION_FAILED", "评测 case 执行失败", failure);
    }

    private static AgentEvaluationException error(String code, String message, Throwable failure) {
        return new AgentEvaluationException(code, message, failure);
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH && visited.add(current)) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
