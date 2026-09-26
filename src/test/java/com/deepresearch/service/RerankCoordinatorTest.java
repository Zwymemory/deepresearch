package com.deepresearch.service;

import com.deepresearch.model.RerankResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankCoordinatorTest {

    private final RerankService rerankService = mock(RerankService.class);
    private final RerankCoordinator coordinator = new RerankCoordinator(
            rerankService, new LegacyRelevanceGate(-5.0, -6.5, 1.0 / 3));

    @Test
    void appliesReturnedOrderScoresAndDiagnostics() {
        when(rerankService.enabled()).thenReturn(true);
        when(rerankService.rerank(eq("body"), anyList())).thenReturn(List.of(
                new RerankResult("b", 0.95),
                new RerankResult("a", 0.70)
        ));
        List<HybridChunk> input = List.of(chunk("a"), chunk("b"));

        RerankCoordinator.Outcome outcome = coordinator.rerank("body", input);

        assertThat(outcome.chunks()).extracting(chunk -> chunk.document().getId()).containsExactly("b", "a");
        assertThat(outcome.chunks().get(0).rerankScore()).isEqualTo(0.95);
        assertThat(outcome.diagnostics().status()).isEqualTo("success");
        assertThat(outcome.diagnostics().changedCount()).isEqualTo(2);
    }

    @Test
    void returnsNoEvidenceWithoutLeakingRerankerException() {
        when(rerankService.enabled()).thenReturn(true);
        when(rerankService.rerank(eq("query"), anyList()))
                .thenThrow(new IllegalStateException("https://admin:password@reranker.internal"));
        List<HybridChunk> input = List.of(chunk("a"));

        RerankCoordinator.Outcome outcome = coordinator.rerank("query", input);

        assertThat(outcome.chunks()).isEmpty();
        assertThat(outcome.diagnostics().fallback()).isTrue();
        assertThat(outcome.diagnostics().reason())
                .isEqualTo("execution_failed_no_evidence")
                .doesNotContain("password", "internal");
    }

    @Test
    void skipsExternalCallWhenDisabledOrEmpty() {
        when(rerankService.enabled()).thenReturn(false);
        RerankCoordinator.Outcome disabled = coordinator.rerank("query", List.of(chunk("a")));
        assertThat(disabled.chunks()).isEmpty();
        assertThat(disabled.diagnostics().status()).isEqualTo("disabled");

        when(rerankService.enabled()).thenReturn(true);
        assertThat(coordinator.rerank("query", List.of()).diagnostics().status()).isEqualTo("skipped");
    }

    @Test
    void sendsTitleOnlyInDedicatedFieldInsteadOfDuplicatingItInContent() {
        when(rerankService.enabled()).thenReturn(true);
        when(rerankService.rerank(eq("query"), anyList())).thenReturn(List.of());
        HybridChunk input = new HybridChunk(new Document(
                "a",
                "body",
                Map.of("title", "A title", "sectionPath", "Chapter 1", "body", "Useful body")));

        coordinator.rerank("query", List.of(input));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.deepresearch.model.RerankCandidate>> candidates =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(rerankService).rerank(eq("query"), candidates.capture());
        assertThat(candidates.getValue().get(0).title()).isEqualTo("A title");
        assertThat(candidates.getValue().get(0).content())
                .isEqualTo("Chapter 1\nUseful body")
                .doesNotContain("A title");
    }

    @Test
    void rejectsUnscoredAndUnrelatedCandidatesEvenWhenRerankerReturnsThem() {
        when(rerankService.enabled()).thenReturn(true);
        when(rerankService.rerank(eq("dinner menu on train 59264"), anyList())).thenReturn(List.of(
                new RerankResult("number-only", -4.8),
                new RerankResult("unscored", Double.NaN)
        ));
        List<HybridChunk> input = List.of(
                new HybridChunk(new Document("number-only", "Run 59264 is reserved for replay", Map.of())),
                chunk("unscored"));

        RerankCoordinator.Outcome outcome = coordinator.rerank("dinner menu on train 59264", input);

        assertThat(outcome.chunks()).isEmpty();
        assertThat(outcome.diagnostics().reason()).isEqualTo("no_verified_relevance");
    }

    private HybridChunk chunk(String id) {
        return new HybridChunk(new Document(id, "body", Map.of("title", id)));
    }
}
