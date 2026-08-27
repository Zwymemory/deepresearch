package com.deepresearch.model;

import java.util.List;

public record KnowledgeDocumentDetail(
        KnowledgeDocumentSummary document,
        List<KnowledgeChunkSummary> chunks
) {
}
