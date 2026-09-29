package com.deepresearch.web.dto;

import java.util.List;
import java.math.BigDecimal;

/**
 * ReAct Agent 研究响应（Week3）。
 *
 * 除了最终回答，还把 Agent 每一轮的安全运行轨迹一并返回。
 * 原始模型推理只用于当前运行内部，不通过 API 返回或持久化。
 *
 * @param runId     单次 Agent 运行 ID
 * @param sessionId W9 会话 ID。用于自动读取最近对话记忆和追踪 run
 * @param answer        最终回答（关键论断带 [来源N] 引用）
 * @param rounds        实际推理的轮数
 * @param finished      是否在最大轮数内自然给出答案（false 表示是达到上限后被强制收尾的）
 * @param memoryContext W9.5 本轮选入的上下文；不能据此认定模型实际使用记忆
 * @param steps         每一轮的推理轨迹
 * @param events        Agent 运行事件流，用于普通响应和 SSE 进度展示
 */
public record AgentResearchResponse(
        String runId,
        String sessionId,
        String answer,
        int rounds,
        boolean finished,
        MemoryContext memoryContext,
        List<Step> steps,
        List<Event> events,
        String status,
        Usage usage,
        List<String> citations,
        String citationContract
) {
    public AgentResearchResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        citationContract = citationContract == null || citationContract.isBlank()
                ? "NONE" : citationContract;
    }

    /** Source-compatible constructor for callers created before structured citations were added. */
    public AgentResearchResponse(String runId, String sessionId, String answer, int rounds,
                                 boolean finished, MemoryContext memoryContext,
                                 List<Step> steps, List<Event> events, String status, Usage usage) {
        this(runId, sessionId, answer, rounds, finished, memoryContext, steps, events,
                status, usage, List.of(), "NONE");
    }

    /** 兼容已有调用点；新运行时应尽量传入显式状态和 usage。 */
    public AgentResearchResponse(String runId, String sessionId, String answer, int rounds,
                                 boolean finished, MemoryContext memoryContext,
                                 List<Step> steps, List<Event> events) {
        this(runId, sessionId, answer, rounds, finished, memoryContext, steps, events,
                finished ? "SUCCESS" : "MAX_ROUNDS_REACHED", Usage.empty(), List.of(), "NONE");
    }

    public record Usage(
            int inputTokens,
            int outputTokens,
            int totalTokens,
            boolean estimated,
            BigDecimal estimatedCost,
            String costCurrency,
            long durationMs,
            int modelCalls,
            int toolCalls
    ) {
        public static Usage empty() {
            return new Usage(0, 0, 0, true, BigDecimal.ZERO, "CNY", 0, 0, 0);
        }
    }
    /**
     * 一轮 ReAct 步骤。
     *
     * @param round       第几轮
     * @param action          选择的工具名（最终回答这一步为 "final"）
     * @param decisionSummary 系统生成的安全决策摘要，不是模型原始推理
     * @param outcomeCode     稳定结果码，不包含工具参数或文档正文
     */
    public record Step(int round, String action, String decisionSummary, String outcomeCode,
                       String argumentFingerprint) {
    }

    /**
     * W8：Agent Runtime 事件。
     *
     * @param seq     事件序号
     * @param type    事件类型，如 STARTED / TOOL_SELECTED / TOOL_OBSERVED / DONE
     * @param message 人类可读说明
     * @param round   所属轮次
     * @param action  当前工具名
     */
    public record Event(int seq, String type, String message, Integer round, String action) {
    }

    public record MemoryContext(
            String sessionSummary,
            List<String> recentConversation,
            List<String> memories,
            Diagnostics diagnostics
    ) {
    }

    public record Diagnostics(
            boolean summarySelected,
            int recentMessageCount,
            int selectedMemoryCount,
            int totalMemoryCount,
            String reason
    ) {
        /** Legacy Java accessor meant selection; it is no longer emitted as a usage claim. */
        @Deprecated
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean summaryUsed() {
            return summarySelected;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("modelUseVerification")
        public String modelUseVerification() {
            return "unknown";
        }
    }
}
