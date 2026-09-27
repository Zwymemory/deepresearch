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
import java.util.Set;

/** Cross-encoder ranking and answerability admission; unavailable checks fail closed. */
@Service
class RerankCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RerankCoordinator.class);
    private final RerankService rerankService;
    private final LegacyEvidenceVerifier evidenceVerifier;

    RerankCoordinator(RerankService rerankService, LegacyEvidenceVerifier evidenceVerifier) {
        this.rerankService = rerankService;
        this.evidenceVerifier = evidenceVerifier;
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
                    reranked.add(chunk);
                }
            }
            if (reranked.isEmpty()) {
                return new Outcome(List.of(), diagnostics(true, "success", false,
                        candidates.size(), results.size(), candidates.size(), false, "no_scored_candidates"));
            }
            Set<String> verified = evidenceVerifier.verify(question, reranked);
            List<HybridChunk> admitted = reranked.stream()
                    .filter(chunk -> verified.contains(HybridDocumentSupport.stableKey(chunk.document())))
                    .toList();
            int changed = changedCount(candidates, admitted);
            return new Outcome(admitted, diagnostics(true, "success", false,
                    candidates.size(), results.size(), changed, true,
                    admitted.isEmpty() ? "no_verified_relevance"
                            : admitted.size() < candidates.size() ? "relevance_filtered"
                            : changed == 0 ? "order_unchanged" : "order_changed"));
        } catch (RuntimeException exception) {
            log.warn("旧检索重排或证据核验失败，证据按无依据处理: {}", exception.getClass().getSimpleName());
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
