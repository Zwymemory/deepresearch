package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagflowDebugServiceTest {
    @Test
    void exposesOnlyFixedLowCardinalityStageTimings() {
        KnowledgeRetrievalGateway gateway = mock(KnowledgeRetrievalGateway.class);
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        when(rewrite.rewrite("where is ZXQ-4499?", List.of("history-id-123")))
                .thenReturn(new QueryRewriteService.RewriteResult(
                        "where is ZXQ-4499?", "ZXQ-4499", true, "rewritten"));
        RetrievedEvidence evidence = new RetrievedEvidence(
                "来源1", "[来源1]", "ragflow:ds:doc:chunk", "ds", "doc", "chunk",
                "Guide", "safe evidence", 0.9, "ragflow", true, null, null);
        when(gateway.retrieveWithDiagnostics("ZXQ-4499", 5)).thenReturn(
                new KnowledgeRetrievalGateway.RetrievalResult(List.of(evidence), Map.of(
                        "registry", 2L, "upstreamApi", 11L, "evidenceNormalization", 3L)));

        HybridDebugResponse response = new RagflowDebugService(gateway, rewrite)
                .debug("where is ZXQ-4499?", 5, List.of("history-id-123"));

        assertThat(response.stageTimingMs()).containsOnlyKeys(
                "queryRewrite", "registry", "upstreamApi", "evidenceNormalization",
                "responseAssembly", "total");
        assertThat(response.stageTimingMs()).allSatisfy((stage, millis) -> {
            assertThat(stage).doesNotContain("ZXQ-4499", "history-id-123");
            assertThat(millis).isGreaterThanOrEqualTo(0L);
        });
        assertThat(response.stageTimingMs()).containsEntry("registry", 2L)
                .containsEntry("upstreamApi", 11L)
                .containsEntry("evidenceNormalization", 3L);
    }

    @Test
    void omitsOptionalTimingsForExistingLegacyCallers() throws Exception {
        HybridDebugResponse legacy = new HybridDebugResponse(
                "question", 1, 1, "question", "question", false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 0, 0,
                new HybridDebugResponse.ContextPackingDiagnostics(false, 0, 0, 0, 0, 1.0, "disabled"),
                new HybridDebugResponse.RerankDiagnostics(false, "disabled", false, 0, 0, 0, false, "disabled"));

        assertThat(new ObjectMapper().writeValueAsString(legacy)).doesNotContain("stageTimingMs");
    }
}
