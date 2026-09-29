package com.deepresearch.workflow;

import com.deepresearch.service.KnowledgeRetrievalGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

class DifyCitationValidatorTest {
    private final KnowledgeRetrievalGateway gateway = mock(KnowledgeRetrievalGateway.class);
    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final DifyCitationValidator validator = new DifyCitationValidator(gateway, repository);

    @Test
    void checksEveryCurrentlyActiveSourceAndFailsClosedOnDeletedChunk() {
        String first = "kb:ragflow:dataset:doc:one";
        String deleted = "kb:ragflow:dataset:doc:deleted";
        when(gateway.citationExists(first)).thenReturn(true);
        when(gateway.citationExists(deleted)).thenReturn(false);
        try {
            assertThat(validator.available("wf-1", List.of(first, deleted))).isFalse();
            verify(gateway).citationExists(first);
            verify(gateway).citationExists(deleted);
        } finally {
            validator.shutdown();
        }
    }

    @Test
    void rejectsOversizedCitationSetWithoutNetworkCalls() {
        try {
            assertThat(validator.available("wf-1", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"))).isFalse();
            verify(gateway, never()).citationExists(anyString());
        } finally {
            validator.shutdown();
        }
    }

    @Test
    void mixedSourcesRecheckKbAndVerifyExactRunBoundWebSnapshotWithoutFetchingUrls() {
        String kb = "kb:ragflow:dataset:doc:one", url = "https://example.com/page";
        String id = DifyWebEvidence.id(url, "Page", "Actual typed search summary");
        when(gateway.citationExists(kb)).thenReturn(true);
        when(repository.difyWebSource("wf-1", id)).thenReturn(Optional.of(new WorkflowRepository.DifyWebSource(
                id, url, "Page", "Actual typed search summary", OffsetDateTime.now())));
        try {
            assertThat(validator.available("wf-1", List.of(kb, id))).isTrue();
            assertThat(validator.available("another-run", List.of(id))).isFalse();
            verify(gateway).citationExists(kb);
            verify(gateway, never()).citationExists(id);
            verify(gateway, never()).citationExists(url);
        } finally { validator.shutdown(); }
    }

    @Test
    void webSnapshotCannotSubstituteAnotherUrlOrSummaryUnderAnExistingId() {
        String id = DifyWebEvidence.id("https://example.com/real", "Page", "Original summary");
        when(repository.difyWebSource("wf-1", id)).thenReturn(Optional.of(new WorkflowRepository.DifyWebSource(
                id, "https://example.com/invented", "Page", "Original summary", OffsetDateTime.now())));
        try {
            assertThat(validator.available("wf-1", List.of(id))).isFalse();
            when(repository.difyWebSource("wf-1", id)).thenReturn(Optional.of(new WorkflowRepository.DifyWebSource(
                    id, "https://example.com/real", "Page", "Changed summary", OffsetDateTime.now())));
            assertThat(validator.available("wf-1", List.of(id))).isFalse();
        } finally { validator.shutdown(); }
    }
}
