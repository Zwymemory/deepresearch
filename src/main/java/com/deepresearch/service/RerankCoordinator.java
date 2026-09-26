package com.deepresearch.service;

import com.deepresearch.model.RerankCandidate;
import com.deepresearch.model.RerankResult;
import com.deepresearch.web.dto.HybridDebugResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cross-encoder ranking and evidence admission; unavailable scores fail closed. */
@Service
class RerankCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RerankCoordinator.class);
    private final RerankService rerankService;
    private final LegacyRelevanceGate relevanceGate;

    RerankCoordinator(RerankService rerankService, LegacyRelevanceGate relevanceGate) {
        this.rerankService = rerankService;
        this.relevanceGate = relevanceGate;
    }

    Outcome rerank(String question, List<HybridChunk> candidates) {
        if (candidates.isEmpty()) {
            return new Outcome(List.of(), diagnostics(rerankService.enabled(), "skipped", false,
                    0, 0, 0, false, "empty_candidates"));
        }
        if (!rerankService.enabled()) {
            return new Outcome(List.of(), diagnostics(false, "disabled", false,
                    candidates.size(), 0, 0, false, "rerank_disabled_no_evidence"));
        }
        try {
            List<RerankCandidate> inputs = candidates.stream()
                    .map(chunk -> new RerankCandidate(
                            HybridDocumentSupport.stableKey(chunk.document()),
                            chunk.title(),
                            HybridDocumentSupport.rerankText(chunk.document())))
                    .toList();
            List<RerankResult> results = rerankService.rerank(question, inputs);
            if (results.isEmpty()) {
                return new Outcome(List.of(), diagnostics(true, "fallback", true,
                        candidates.size(), 0, candidates.size(), false, "empty_result_no_evidence"));
            }
            Map<String, HybridChunk> remaining = new LinkedHashMap<>();
            candidates.forEach(chunk -> remaining.put(HybridDocumentSupport.stableKey(chunk.document()), chunk));
            List<HybridChunk> reranked = new ArrayList<>();
            for (RerankResult result : results) {
                HybridChunk chunk = remaining.remove(result.id());
                if (chunk != null && Double.isFinite(result.score())) {
                    chunk.setRerankScore(result.score());
                    if (relevanceGate.accepts(question, chunk)) reranked.add(chunk);
                }
            }
            int changed = changedCount(candidates, reranked);
            return new Outcome(reranked, diagnostics(true, "success", false,
                    candidates.size(), results.size(), changed, true,
                    reranked.isEmpty() ? "no_verified_relevance"
                            : reranked.size() < candidates.size() ? "relevance_filtered"
                            : changed == 0 ? "order_unchanged" : "order_changed"));
        } catch (RuntimeException exception) {
            log.warn("reranker 调用失败，证据按无依据处理: {}", exception.getClass().getSimpleName());
            return new Outcome(List.of(), diagnostics(true, "fallback", true,
                    candidates.size(), 0, candidates.size(), false, "execution_failed_no_evidence"));
        }
    }

    private int changedCount(List<HybridChunk> before, List<HybridChunk> after) {
        int changed = Math.abs(before.size() - after.size());
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            if (!HybridDocumentSupport.stableKey(before.get(i).document())
                    .equals(HybridDocumentSupport.stableKey(after.get(i).document()))) {
                changed++;
            }
        }
        return changed;
    }

    private HybridDebugResponse.RerankDiagnostics diagnostics(boolean enabled, String status, boolean fallback,
                                                               int candidates, int returned, int changed,
                                                               boolean scores, String reason) {
        return new HybridDebugResponse.RerankDiagnostics(
                enabled, status, fallback, candidates, returned, changed, scores, reason);
    }

    record Outcome(List<HybridChunk> chunks, HybridDebugResponse.RerankDiagnostics diagnostics) {
    }
}
