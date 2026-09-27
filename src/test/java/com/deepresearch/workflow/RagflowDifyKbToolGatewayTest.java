package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.KnowledgeRetrievalGateway;
import com.deepresearch.service.RetrievedEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagflowDifyKbToolGatewayTest {
    private final KnowledgeRetrievalGateway retrieval = mock(KnowledgeRetrievalGateway.class);
    private final RagflowDifyKbToolGateway gateway = new RagflowDifyKbToolGateway(retrieval);
    private final AuthPrincipal owner = new AuthPrincipal("tenant-1", "user-1", List.of("USER"));

    @Test
    void returnsStableRagflowCitationsForAuthorizedRunOwner() {
        when(retrieval.ragflow()).thenReturn(true);
        when(retrieval.retrieve("What is the policy?", 5)).thenReturn(List.of(new RetrievedEvidence(
                "来源1", "[来源1]", "ragflow:dataset-1:document-1:chunk-1",
                "dataset-1", "document-1", "chunk-1", "Policy", "Required checks",
                0.8, "ragflow", true, null, null)));

        assertThat(gateway.search(owner, " What is the policy? "))
                .containsExactly(new DifyKbToolGateway.Evidence(
                        "kb:ragflow:dataset-1:document-1:chunk-1", "Policy", "Required checks"));
        verify(retrieval).retrieve("What is the policy?", 5);
    }

    @Test
    void rejectsMissingOwnerAndDisabledRagflow() {
        assertThatThrownBy(() -> gateway.search(null, "query"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.search(owner, "query"))
                .isInstanceOf(IllegalStateException.class);
    }
}
