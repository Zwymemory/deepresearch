package com.deepresearch.service;

import com.deepresearch.config.AgentRuntimeProperties;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 单次 Agent run 的 deadline、模型 token/cost 和工具调用预算账本。 */
public final class AgentRunBudget {

    private static final ExecutorService CALL_EXECUTOR = Executors.newCachedThreadPool(new DaemonThreadFactory());
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final AgentRuntimeProperties policy;
    private final AgentTelemetry telemetry;
    private final long startedNanos = System.nanoTime();
    private final long deadlineNanos;
    private int inputTokens;
    private int outputTokens;
    private int modelCalls;
    private int toolCalls;
    private boolean estimated;
    private String terminalCode;

    public AgentRunBudget(AgentRuntimeProperties policy) {
        this(policy, null);
    }

    public AgentRunBudget(AgentRuntimeProperties policy, AgentTelemetry telemetry) {
        this.policy = policy;
        this.telemetry = telemetry;
        this.deadlineNanos = startedNanos + policy.getMaxDuration().toNanos();
    }

    public ChatResponse callModel(String prompt, Supplier<ChatResponse> action) {
        throwIfTerminated();
        ensureDeadline("MODEL_TIMEOUT");
        int projectedInput = estimateTokens(prompt);
        if (inputTokens + projectedInput > policy.getMaxInputTokens()
                || totalTokens() + projectedInput > policy.getMaxTotalTokens()) {
            terminate("BUDGET_EXCEEDED", "模型输入将超过 token 预算");
        }
        long operationStarted = System.nanoTime();
        boolean success = false;
        ChatResponse response;
        try {
            response = runTimed(
                    () -> AgentBudgetContext.with(this, () -> telemetry == null
                            ? action.get() : telemetry.observeModel(action)),
                    policy.getModelTimeout(), "MODEL_TIMEOUT", "模型调用超时");
            success = true;
        } finally {
            if (telemetry != null) {
                telemetry.recordModel(Duration.ofNanos(System.nanoTime() - operationStarted),
                        success, prompt == null ? 0 : prompt.length());
            }
        }
        modelCalls++;
        recordUsage(prompt, response);
        checkTokenAndCostLimits();
        return response;
    }

    public <T> T callTool(Callable<T> action) {
        return callTool("unknown", action);
    }

    public <T> T callTool(String toolName, Callable<T> action) {
        throwIfTerminated();
        ensureDeadline("TOOL_TIMEOUT");
        if (toolCalls >= policy.getMaxToolCalls()) {
            terminate("BUDGET_EXCEEDED", "工具调用次数已达到预算上限");
        }
        toolCalls++;
        long operationStarted = System.nanoTime();
        boolean success = false;
        try {
            T result = runTimed(() -> telemetry == null
                            ? action.call()
                            : telemetry.observeTool(toolName, () -> callUnchecked(action)),
                    policy.getToolTimeout(), "TOOL_TIMEOUT", "工具调用超时");
            success = true;
            return result;
        } finally {
            if (telemetry != null) {
                telemetry.recordTool(toolName, Duration.ofNanos(System.nanoTime() - operationStarted), success);
            }
        }
    }

    public void throwIfTerminated() {
        if (terminalCode != null) {
            throw new AgentControlException(terminalCode, safeMessage(terminalCode));
        }
        ensureDeadline("MODEL_TIMEOUT");
    }

    public AgentResearchResponse.Usage snapshot() {
        return new AgentResearchResponse.Usage(
                inputTokens,
                outputTokens,
                totalTokens(),
                estimated,
                estimatedCost(),
                "CNY",
                elapsedMillis(),
                modelCalls,
                toolCalls);
    }

    private <T> T runTimed(Callable<T> action, Duration operationTimeout,
                           String timeoutCode, String timeoutMessage) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        long timeoutNanos = Math.min(operationTimeout.toNanos(), remainingNanos);
        if (timeoutNanos <= 0) {
            terminate(timeoutCode, timeoutMessage);
        }
        Future<T> future = CALL_EXECUTOR.submit(action);
        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            terminate(timeoutCode, timeoutMessage);
            throw new AssertionError("unreachable");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            terminate("CANCELLED", "运行已取消");
            throw new AssertionError("unreachable");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Agent operation failed", cause);
        }
    }

    private <T> T callUnchecked(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Tool operation failed", failure);
        }
    }

    private void recordUsage(String prompt, ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        Integer exactInput = usage == null ? null : usage.getPromptTokens();
        Integer exactOutput = usage == null ? null : usage.getCompletionTokens();
        if (!(usage instanceof EmptyUsage)
                && exactInput != null && exactOutput != null && exactInput >= 0 && exactOutput >= 0) {
            inputTokens += exactInput;
            outputTokens += exactOutput;
            return;
        }
        estimated = true;
        inputTokens += estimateTokens(prompt);
        String content = response == null || response.getResult() == null
                ? "" : response.getResult().getOutput().getText();
        outputTokens += estimateTokens(content);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double estimate = text.codePoints()
                .mapToDouble(codePoint -> isCjk(codePoint) ? 0.6 : 0.3)
                .sum();
        return Math.max(1, (int) Math.ceil(estimate));
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private void checkTokenAndCostLimits() {
        if (inputTokens > policy.getMaxInputTokens()
                || outputTokens > policy.getMaxOutputTokens()
                || totalTokens() > policy.getMaxTotalTokens()
                || estimatedCost().compareTo(policy.getMaxEstimatedCost()) > 0) {
            terminate("BUDGET_EXCEEDED", "本次运行已达到预算上限");
        }
    }

    private BigDecimal estimatedCost() {
        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(policy.getInputCostPerMillion()).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(policy.getOutputCostPerMillion()).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).setScale(8, RoundingMode.HALF_UP);
    }

    private int totalTokens() {
        return inputTokens + outputTokens;
    }

    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private void ensureDeadline(String code) {
        if (System.nanoTime() >= deadlineNanos) {
            terminate(code, "本次运行已超过 deadline");
        }
    }

    private void terminate(String code, String message) {
        terminalCode = code;
        throw new AgentControlException(code, message);
    }

    private String safeMessage(String code) {
        return switch (code) {
            case "BUDGET_EXCEEDED" -> "本次运行已达到预算上限";
            case "MODEL_TIMEOUT" -> "模型调用超时";
            case "TOOL_TIMEOUT" -> "工具调用超时";
            case "CANCELLED" -> "运行已取消";
            default -> "运行已终止";
        };
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-call-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
