package com.deepresearch.service;

import com.deepresearch.model.RerankResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

class LexicalAnchorGuardTest {

    private final LexicalAnchorGuard guard = new LexicalAnchorGuard();

    @Test
    void keepsExactKeywordAnchorThroughCandidateAndFinalTopKCutsWhenRerankerConfirmsIt() {
        Document semanticA = document("semantic-a", "general checkpoint overview");
        Document semanticB = document("semantic-b", "crash recovery overview");
        Document anchored = document("anchored", "graph.ainvoke uses durability=\"sync\" at node boundaries");
        Optional<LexicalAnchorGuard.Anchor> selected = guard.select(
                "durability=sync 在当前项目里保证什么？", List.of(semanticA, anchored));

        List<HybridChunk> candidates = guard.ensureCandidate(
                List.of(chunk(semanticA), chunk(semanticB)), 2, selected);
        assertThat(candidates).extracting(chunk -> chunk.document().getId())
                .containsExactly("semantic-a", "anchored");

        RerankService reranker = mock(RerankService.class);
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank(eq("durability=sync"), anyList())).thenReturn(List.of(
                new RerankResult("anchored", 0.5), new RerankResult("semantic-a", -9.0)));
        LegacyEvidenceVerifier verifier = mock(LegacyEvidenceVerifier.class);
        when(verifier.verify(eq("durability=sync"), anyList())).thenReturn(Set.of("anchored"));
        List<HybridChunk> verifiedOrder = new RerankCoordinator(
                reranker, verifier)
                .rerank("durability=sync", candidates)
                .chunks();
        List<HybridChunk> direct = guard.ensureTopK(verifiedOrder, 1, selected);

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

    @Test
    void doesNotReinsertAnAnchorRemovedByRelevanceGate() {
        Document unrelated = document("unrelated", "A verified unrelated fact");
        Document anchor = document("anchor", "ERR-P403 is a recorded failure code");
        Optional<LexicalAnchorGuard.Anchor> selected = guard.select("ERR-P403 payroll owner", List.of(anchor));
        assertThat(selected).isPresent();

        List<HybridChunk> direct = guard.ensureTopK(List.of(chunk(unrelated)), 1, selected);

        assertThat(direct).extracting(value -> value.document().getId()).containsExactly("unrelated");
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
