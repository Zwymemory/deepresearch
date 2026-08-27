package com.deepresearch.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ReAct Agent 研究请求（Week3）。
 *
 * @param question  用户的研究问题（必填）
 * @param sessionId 会话 ID。为空时 W9 会自动创建新会话
 * @param userId    用户 ID。为空时使用 default
 */
public record AgentResearchRequest(
        @NotBlank(message = "question 不能为空") String question,
        String sessionId,
        String userId
) {
}
