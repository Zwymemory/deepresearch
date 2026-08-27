package com.deepresearch.web.dto;

import java.time.OffsetDateTime;

public record AgentBadCaseResponse(
        String feedbackId,
        String runId,
        String sessionId,
        String userId,
        String question,
        String answer,
        String rating,
        String reason,
        String comment,
        OffsetDateTime createdAt
) {
}
