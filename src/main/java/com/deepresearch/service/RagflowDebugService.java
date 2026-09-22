package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagflowDebugService {
    private final KnowledgeRetrievalGateway gateway;
    public RagflowDebugService(KnowledgeRetrievalGateway gateway) { this.gateway = gateway; }

    public HybridDebugResponse debug(String question, Integer topK) {
        List<RetrievedEvidence> evidence = gateway.retrieve(question, topK);
        List<HybridDebugResponse.Entry> entries = evidence.stream().map(e -> new HybridDebugResponse.Entry(
                Integer.parseInt(e.sourceId().substring(2)), e.title(), e.docId(), e.chunkId(), "",
                e.sectionPath(), null, e.pageNumber(), e.chunkKey(), "ragflow", null, null,
                null, null, false, null, e.content().length(), "ragflow", e.content())).toList();
        int chars = evidence.stream().mapToInt(e -> e.content().length()).sum();
        return new HybridDebugResponse(question, evidence.size(), evidence.size(), question, question, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                entries, entries, entries.size(), entries.size(),
                new HybridDebugResponse.ContextPackingDiagnostics(false, entries.size(), entries.size(), 0, chars, 1.0, "provider=ragflow; no local expansion"),
                new HybridDebugResponse.RerankDiagnostics(false, "RAGFLOW", false, entries.size(), entries.size(), 0, false,
                        "RAGFlow similarity is available through the evidence gateway; legacy RRF and rerank scores are absent"));
    }
}
