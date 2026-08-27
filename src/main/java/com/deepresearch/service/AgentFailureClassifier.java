package com.deepresearch.service;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** 将 provider/网络异常收敛为稳定错误码，响应中不暴露底层消息。 */
final class AgentFailureClassifier {

    private AgentFailureClassifier() {
    }

    static AgentControlException modelFailure(Throwable failure) {
        Throwable root = rootCause(failure);
        if (root instanceof InterruptedException || root instanceof CancellationException
                || Thread.currentThread().isInterrupted()) {
            return new AgentControlException("CANCELLED", "运行已取消");
        }
        if (root instanceof TimeoutException || contains(root, "timeout") || contains(root, "timed out")) {
            return new AgentControlException("MODEL_TIMEOUT", "模型调用超时");
        }
        if (contains(root, "429") || contains(root, "rate limit") || contains(root, "too many requests")) {
            return new AgentControlException("MODEL_RATE_LIMITED", "模型服务当前限流，请稍后重试");
        }
        return new AgentControlException("MODEL_EXECUTION_FAILED", "模型调用失败，请稍后重试");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean contains(Throwable failure, String text) {
        String message = failure.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(text);
    }
}
