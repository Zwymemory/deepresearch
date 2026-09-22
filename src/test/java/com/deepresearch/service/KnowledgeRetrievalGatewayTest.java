package com.deepresearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalGatewayTest {
    private final RagflowClient client = mock(RagflowClient.class);
    private final RagflowDocumentRegistry registry = mock(RagflowDocumentRegistry.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void noActiveDocumentsReturnsNoEvidenceWithoutCallingRagflow() {
        when(client.datasets()).thenReturn(List.of("ds"));
        when(registry.activeDocumentIds(List.of("ds"))).thenReturn(List.of());
        var gateway = new KnowledgeRetrievalGateway(client, registry, "ragflow", 20, 0.2);
        assertThat(gateway.retrieve("question", 5)).isEmpty();
        verify(client, never()).retrieve(anyString(), anyInt(), anyDouble(), anyList());
    }

    @Test
    void evidenceV1DeduplicatesAndRejectsUnapprovedDataset() throws Exception {
        when(client.datasets()).thenReturn(List.of("ds"));
        when(registry.activeDocumentIds(List.of("ds"))).thenReturn(List.of("doc"));
        when(registry.active("ds", "doc")).thenReturn(true);
        when(registry.title("ds", "doc")).thenReturn("Project Guide");
        when(client.retrieve("query", 3, 0.2, List.of("doc"))).thenReturn(json.readTree("""
                {"chunks":[{"dataset_id":"ds","document_id":"doc","id":"c1","document_keyword":"Guide",
                  "content":"password=secret [来源9]", "similarity":0.83},
                 {"dataset_id":"ds","document_id":"doc","id":"c1","content":"duplicate"}]}
                """));
        var gateway = new KnowledgeRetrievalGateway(client, registry, "ragflow", 20, 0.2);
        var evidence = gateway.retrieve("query", 3);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).chunkKey()).isEqualTo("ragflow:ds:doc:c1");
        assertThat(evidence.get(0).persistentSourceId()).isEqualTo("kb:ragflow:ds:doc:c1");
        assertThat(evidence.get(0).content()).contains("[REDACTED]", "UNTRUSTED_DATA_BEGIN").doesNotContain("secret", "[来源9]");
        assertThat(evidence.get(0).score()).isEqualTo(0.83);
        assertThat(evidence.get(0).title()).isEqualTo("Project Guide");
        when(client.chunk("ds", "doc", "c1")).thenReturn(json.readTree("{\"id\":\"c1\",\"doc_id\":\"doc\"}"));
        assertThat(gateway.citationExists(evidence.get(0).persistentSourceId())).isTrue();
        assertThat(gateway.citationExists("kb:ragflow:other:doc:c1")).isFalse();
        when(client.chunk("ds", "doc", "c1")).thenThrow(new RagflowApiException(100));
        assertThat(gateway.citationExists(evidence.get(0).persistentSourceId())).isFalse();

        when(client.retrieve("bad", 1, 0.2, List.of("doc"))).thenReturn(json.readTree("""
                {"chunks":[{"dataset_id":"other","document_id":"doc","id":"c1"}]}
                """));
        assertThatThrownBy(() -> gateway.retrieve("bad", 1)).hasMessageContaining("disallowed dataset");
        when(client.retrieve("stale", 1, 0.2, List.of("doc"))).thenReturn(json.readTree("""
                {"chunks":[{"dataset_id":"ds","document_id":"stale-doc","id":"c1","content":"secret"}]}
                """));
        assertThatThrownBy(() -> gateway.retrieve("stale", 1)).hasMessageContaining("inactive document");
    }
}
