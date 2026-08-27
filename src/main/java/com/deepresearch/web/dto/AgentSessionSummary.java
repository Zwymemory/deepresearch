package com.deepresearch.web.dto;

import java.time.OffsetDateTime;

public record AgentSessionSummary(
        String sessionId,
        String userId,
        String title,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
