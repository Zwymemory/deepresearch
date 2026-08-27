package com.deepresearch.web.dto;

import java.time.OffsetDateTime;

public record AgentFeedbackResponse(
        String feedbackId,
        String runId,
        String rating,
        String reason,
        String comment,
        OffsetDateTime createdAt
) {
}
