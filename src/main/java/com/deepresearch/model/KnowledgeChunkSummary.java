package com.deepresearch.model;

public record KnowledgeChunkSummary(
        String chunkId,
        String title,
        String sectionPath,
        Integer pageNumber,
        Integer chunkIndex,
        Integer version,
        String preview
) {
}
