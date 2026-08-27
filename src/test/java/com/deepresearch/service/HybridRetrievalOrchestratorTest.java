package com.deepresearch.service;

import com.deepresearch.model.KeywordSearchHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalOrchestratorTest {

    @Test
    void fallsBackToKeywordRouteWhenEmbeddingProviderFails() {
        VectorStore vectorStore = mock(VectorStore.class);
        KeywordSearchService keyword = mock(KeywordSearchService.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("private provider response"));
        when(keyword.search(anyString(), anyInt())).thenReturn(List.of(keywordHit()));

        HybridRetrievalOrchestrator.RetrievalResult result =
                orchestrator(vectorStore, keyword).retrieve("checkpoint recovery", 10);

        assertThat(result.vectorHits()).isEmpty();
        assertThat(result.keywordHits()).singleElement()
                .satisfies(hit -> assertThat(hit.getMetadata().get("filename"))
                        .isEqualTo("recovery.md"));
    }

    @Test
    void fallsBackToVectorRouteWhenKeywordIndexFails() {
        VectorStore vectorStore = mock(VectorStore.class);
        KeywordSearchService keyword = mock(KeywordSearchService.class);
        Document vectorHit = new Document("chunk-1", "checkpoint recovery",
                Map.of("title", "Recovery"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorHit));
        when(keyword.search(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("private index response"));

        HybridRetrievalOrchestrator.RetrievalResult result =
                orchestrator(vectorStore, keyword).retrieve("checkpoint recovery", 10);

        assertThat(result.vectorHits()).containsExactly(vectorHit);
        assertThat(result.keywordHits()).isEmpty();
    }

    @Test
    void failsClosedWhenBothRetrievalRoutesFail() {
        VectorStore vectorStore = mock(VectorStore.class);
        KeywordSearchService keyword = mock(KeywordSearchService.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("private vector failure"));
        when(keyword.search(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("private keyword failure"));

        assertThatThrownBy(() -> orchestrator(vectorStore, keyword)
                .retrieve("checkpoint recovery", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("all retrieval routes failed")
                .hasNoCause();
    }

    private HybridRetrievalOrchestrator orchestrator(VectorStore vectorStore,
                                                      KeywordSearchService keyword) {
        AgentTelemetry telemetry = new AgentTelemetry(
                new SimpleMeterRegistry(), ObservationRegistry.create());
        return new HybridRetrievalOrchestrator(vectorStore, keyword, telemetry);
    }

    private KeywordSearchHit keywordHit() {
        return new KeywordSearchHit(
                "chunk-kb", "Recovery", "checkpoint recovery", "doc-1", "chunk-kb",
                "recovery.md", "Recovery", 0, null, 2.0);
    }
}
