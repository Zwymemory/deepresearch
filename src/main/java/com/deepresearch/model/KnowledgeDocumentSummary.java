package com.deepresearch.model;

import java.time.Instant;

public record KnowledgeDocumentSummary(
        String docId,
        String title,
        SourceType sourceType,
        String filename,
        String contentHash,
        int version,
        int chunkCount,
        IngestStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
