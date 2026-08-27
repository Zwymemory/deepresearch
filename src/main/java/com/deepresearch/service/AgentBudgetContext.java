package com.deepresearch.service;

import java.util.function.Supplier;

/** 让由 Spring AI 回调的 @Tool 访问当前 run 的预算，不把预算参数污染到工具 Schema。 */
public final class AgentBudgetContext {

    private static final ThreadLocal<AgentRunBudget> CURRENT = new ThreadLocal<>();

    private AgentBudgetContext() {
    }

    public static AgentRunBudget current() {
        return CURRENT.get();
    }

    public static <T> T with(AgentRunBudget budget, Supplier<T> action) {
        AgentRunBudget previous = CURRENT.get();
        CURRENT.set(budget);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
