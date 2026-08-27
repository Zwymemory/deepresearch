package com.deepresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionServiceTest {

    private final RrfFusionService fusion = new RrfFusionService(60);

    @Test
    void fusesRanksDeduplicatesSameChunkAndKeepsStableOrder() {
        Document a = document("a", "doc-a", "a.txt");
        Document b = document("b", "doc-b", "b.txt");

        List<HybridChunk> result = fusion.fuse(List.of(a, b), List.of(b, a), 10);

        assertThat(result).extracting(chunk -> chunk.document().getId()).containsExactly("a", "b");
        assertThat(result).allSatisfy(chunk -> {
            assertThat(chunk.vectorRank()).isNotNull();
            assertThat(chunk.keywordRank()).isNotNull();
            assertThat(chunk.routeSummary()).contains("vector#", "keyword#");
        });
    }

    @Test
    void supportsVectorFirstComparisonAndDocumentLevelDeduplication() {
        Document first = document("a-1", "doc-a", "a.txt");
        Document sibling = document("a-2", "doc-a", "a.txt");
        Document other = document("b-1", "doc-b", "b.txt");

        assertThat(fusion.simpleMerge(List.of(first, other), List.of(sibling), 10))
                .extracting(Document::getId)
                .containsExactly("a-1", "b-1", "a-2");

        List<HybridChunk> fused = fusion.fuse(List.of(first, sibling, other), List.of(), 10);
        assertThat(fusion.dedupeByDoc(fused))
                .extracting(chunk -> chunk.document().getId())
                .containsExactly("a-1", "b-1");
    }

    private Document document(String id, String docId, String filename) {
        return new Document(id, "content-" + id, Map.of(
                "title", id,
                "docId", docId,
                "filename", filename,
                "chunkIndex", 1
        ));
    }
}
