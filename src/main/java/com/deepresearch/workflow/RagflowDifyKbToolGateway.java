package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.KnowledgeRetrievalGateway;
import com.deepresearch.service.RetrievedEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** Connects authorized Dify tool calls to the shared, allowlisted RAGFlow corpus. */
@Component
@ConditionalOnProperty(prefix = "deepresearch.retrieval", name = "provider", havingValue = "ragflow")
public class RagflowDifyKbToolGateway implements DifyKbToolGateway {
    private static final int TOOL_TOP_K = 5;
    private final KnowledgeRetrievalGateway retrieval;

    public RagflowDifyKbToolGateway(KnowledgeRetrievalGateway retrieval) {
        this.retrieval = retrieval;
    }

    @Override
    public List<Evidence> search(AuthPrincipal owner, String query) {
        if (owner == null || owner.tenantId() == null || owner.tenantId().isBlank()
                || owner.userId() == null || owner.userId().isBlank()) {
            throw new IllegalArgumentException("Dify run owner is required");
        }
        if (query == null || query.isBlank()) return List.of();
        if (!retrieval.ragflow()) throw new IllegalStateException("RAGFlow retrieval is disabled");
        return retrieval.retrieve(query.trim(), TOOL_TOP_K).stream()
                .map(RagflowDifyKbToolGateway::evidence).toList();
    }

    private static Evidence evidence(RetrievedEvidence item) {
        return new Evidence(item.persistentSourceId(), item.title(), item.content());
    }
}
