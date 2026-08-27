package com.deepresearch.web.dto;

import java.time.OffsetDateTime;

public record AgentMemoryResponse(
        String memoryId,
        String userId,
        String memoryType,
        String content,
        String source,
        double confidence,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
