package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagflowDebugService {
    private final KnowledgeRetrievalGateway gateway;
    private final QueryRewriteService queryRewrite;
    public RagflowDebugService(KnowledgeRetrievalGateway gateway, QueryRewriteService queryRewrite) {
        this.gateway = gateway;
        this.queryRewrite = queryRewrite;
    }

    public HybridDebugResponse debug(String question, Integer topK, List<String> history) {
        long totalStarted = System.nanoTime();
        long rewriteStarted = System.nanoTime();
        QueryRewriteService.RewriteResult rewrite = queryRewrite.rewrite(question, history == null ? List.of() : history);
        long rewriteMs = elapsedMs(rewriteStarted);
        KnowledgeRetrievalGateway.RetrievalResult retrieval =
                gateway.retrieveWithDiagnostics(rewrite.rewrittenQuestion(), topK);
        List<RetrievedEvidence> evidence = retrieval.evidence();

        long assemblyStarted = System.nanoTime();
        List<HybridDebugResponse.Entry> entries = evidence.stream().map(e -> new HybridDebugResponse.Entry(
                Integer.parseInt(e.sourceId().substring(2)), e.title(), e.docId(), e.chunkId(), "",
                e.sectionPath(), null, e.pageNumber(), e.chunkKey(), "ragflow", null, null,
                null, null, false, null, e.content().length(), "ragflow", e.content())).toList();
        int chars = evidence.stream().mapToInt(e -> e.content().length()).sum();
        Map<String, Double> scores = evidence.stream().filter(e -> e.score() != null)
                .collect(Collectors.toMap(RetrievedEvidence::chunkKey, RetrievedEvidence::score));
        HybridDebugResponse.ContextPackingDiagnostics packing =
                new HybridDebugResponse.ContextPackingDiagnostics(false, entries.size(), entries.size(), 0,
                        chars, 1.0, "provider=ragflow; no local expansion");
        HybridDebugResponse.RerankDiagnostics rerank =
                new HybridDebugResponse.RerankDiagnostics(false, "RAGFLOW", false, entries.size(), entries.size(),
                        0, false, "Legacy RRF and rerank scores are absent");
        long assemblyMs = elapsedMs(assemblyStarted);
        LinkedHashMap<String, Long> timings = new LinkedHashMap<>();
        timings.put("queryRewrite", rewriteMs);
        timings.put("registry", retrieval.stageTimingMs().getOrDefault("registry", 0L));
        timings.put("upstreamApi", retrieval.stageTimingMs().getOrDefault("upstreamApi", 0L));
        timings.put("evidenceNormalization", retrieval.stageTimingMs().getOrDefault("evidenceNormalization", 0L));
        timings.put("responseAssembly", assemblyMs);
        timings.put("total", elapsedMs(totalStarted));
        return new HybridDebugResponse(question, evidence.size(), evidence.size(), question, rewrite.rewrittenQuestion(), rewrite.used(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                entries, entries, entries.size(), entries.size(),
                packing, rerank, "ragflow", scores, timings);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
