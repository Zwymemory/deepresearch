package com.deepresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LexicalAnchorGuardTest {

    private final LexicalAnchorGuard guard = new LexicalAnchorGuard();

    @Test
    void keepsExactKeywordAnchorThroughCandidateAndFinalTopKCutsWhenRerankerIsDisabled() {
        Document semanticA = document("semantic-a", "general checkpoint overview");
        Document semanticB = document("semantic-b", "crash recovery overview");
        Document anchored = document("anchored", "graph.ainvoke uses durability=\"sync\" at node boundaries");
        Optional<LexicalAnchorGuard.Anchor> selected = guard.select(
                "durability=sync 在当前项目里保证什么？", List.of(semanticA, anchored));

        List<HybridChunk> candidates = guard.ensureCandidate(
                List.of(chunk(semanticA), chunk(semanticB)), 2, selected);
        assertThat(candidates).extracting(chunk -> chunk.document().getId())
                .containsExactly("semantic-a", "anchored");

        RerankService disabled = mock(RerankService.class);
        when(disabled.enabled()).thenReturn(false);
        List<HybridChunk> fallbackOrder = new RerankCoordinator(disabled)
                .rerank("durability=sync", candidates)
                .chunks();
        List<HybridChunk> direct = guard.ensureTopK(fallbackOrder, 1, selected);

        assertThat(direct).extracting(chunk -> chunk.document().getId()).containsExactly("anchored");
        assertThat(direct.get(0).routeSummary()).contains("keyword#2");
    }

    @Test
    void promotesAnchorAfterRerankerPlacesItBelowFinalTopK() {
        Document first = document("first", "unrelated first");
        Document second = document("second", "unrelated second");
        Document anchored = document("anchored", "failure code MODEL_SCHEMA_INVALID is stable");
        Optional<LexicalAnchorGuard.Anchor> selected = guard.select(
                "解释 MODEL_SCHEMA_INVALID", List.of(anchored));

        List<HybridChunk> reranked = List.of(chunk(first), chunk(second), chunk(anchored));
        List<HybridChunk> direct = guard.ensureTopK(reranked, 2, selected);

        assertThat(direct).extracting(chunk -> chunk.document().getId())
                .containsExactly("first", "anchored");
    }

    @Test
    void leavesRankingUntouchedWhenNoKeywordDocumentContainsExactAnchor() {
        Document first = document("first", "durability and sync are discussed separately");
        Document second = document("second", "general checkpoint guidance");
        Optional<LexicalAnchorGuard.Anchor> selected = guard.select(
                "durability=sync 的边界", List.of(first, second));
        List<HybridChunk> ranked = List.of(chunk(first), chunk(second));

        assertThat(selected).isEmpty();
        assertThat(guard.ensureCandidate(ranked, 2, selected)).isSameAs(ranked);
        assertThat(guard.ensureTopK(ranked, 1, selected))
                .extracting(chunk -> chunk.document().getId())
                .containsExactly("first");
    }

    @Test
    void appendsAnchorWithoutDroppingCandidateWhenCandidateBudgetHasRoom() {
        Document first = document("first", "unrelated");
        Document anchored = document("anchored", "endpoint /api/research/workflows is available");
        Optional<LexicalAnchorGuard.Anchor> selected = guard.select(
                "调用 /api/research/workflows", List.of(anchored));

        assertThat(guard.ensureCandidate(List.of(chunk(first)), 2, selected))
                .extracting(chunk -> chunk.document().getId())
                .containsExactly("first", "anchored");
    }

    private HybridChunk chunk(Document document) {
        return new HybridChunk(document);
    }

    private Document document(String id, String content) {
        return new Document(id, content, Map.of(
                "title", "title-" + id,
                "filename", id + ".md",
                "sectionPath", "facts",
                "chunkIndex", 1));
    }
}
