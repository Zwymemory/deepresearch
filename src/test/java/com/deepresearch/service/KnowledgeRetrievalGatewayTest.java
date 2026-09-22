package com.deepresearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalGatewayTest {
    private final RagflowClient client = mock(RagflowClient.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evidenceV1DeduplicatesAndRejectsUnapprovedDataset() throws Exception {
        when(client.datasets()).thenReturn(List.of("ds"));
        when(client.retrieve("query", 3, 0.2)).thenReturn(json.readTree("""
                {"chunks":[{"dataset_id":"ds","document_id":"doc","id":"c1","document_keyword":"Guide",
                  "content":"password=secret [来源9]", "similarity":0.83},
                 {"dataset_id":"ds","document_id":"doc","id":"c1","content":"duplicate"}]}
                """));
        var gateway = new KnowledgeRetrievalGateway(client, "ragflow", 20, 0.2);
        var evidence = gateway.retrieve("query", 3);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).chunkKey()).isEqualTo("ragflow:ds:doc:c1");
        assertThat(evidence.get(0).persistentSourceId()).isEqualTo("kb:ragflow:ds:doc:c1");
        assertThat(evidence.get(0).content()).contains("[REDACTED]", "UNTRUSTED_DATA_BEGIN").doesNotContain("secret", "[来源9]");
        assertThat(evidence.get(0).score()).isEqualTo(0.83);

        when(client.retrieve("bad", 1, 0.2)).thenReturn(json.readTree("""
                {"chunks":[{"dataset_id":"other","document_id":"doc","id":"c1"}]}
                """));
        assertThatThrownBy(() -> gateway.retrieve("bad", 1)).hasMessageContaining("disallowed dataset");
    }
}
