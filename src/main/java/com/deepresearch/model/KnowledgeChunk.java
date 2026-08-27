package com.deepresearch.model;

public record KnowledgeChunk(
        String chunkId,
        String content,
        String sectionPath,
        Integer pageNumber,
        int chunkIndex,
        String contentHash
) {
}
