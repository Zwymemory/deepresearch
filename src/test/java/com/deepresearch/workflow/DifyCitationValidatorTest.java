package com.deepresearch.workflow;

import com.deepresearch.service.KnowledgeRetrievalGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

class DifyCitationValidatorTest {
    private final KnowledgeRetrievalGateway gateway = mock(KnowledgeRetrievalGateway.class);
    private final DifyCitationValidator validator = new DifyCitationValidator(gateway);

    @Test
    void checksEveryCurrentlyActiveSourceAndFailsClosedOnDeletedChunk() {
        String first = "kb:ragflow:dataset:doc:one";
        String deleted = "kb:ragflow:dataset:doc:deleted";
        when(gateway.citationExists(first)).thenReturn(true);
        when(gateway.citationExists(deleted)).thenReturn(false);
        try {
            assertThat(validator.available(List.of(first, deleted))).isFalse();
            verify(gateway).citationExists(first);
            verify(gateway).citationExists(deleted);
        } finally {
            validator.shutdown();
        }
    }

    @Test
    void rejectsOversizedCitationSetWithoutNetworkCalls() {
        try {
            assertThat(validator.available(List.of("a", "b", "c", "d", "e", "f"))).isFalse();
            verify(gateway, never()).citationExists(anyString());
        } finally {
            validator.shutdown();
        }
    }
}
