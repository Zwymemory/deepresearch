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

/**
 * 协调 cross-encoder 精排并在禁用、空结果或异常时回退到 RRF 顺序。
 * 失败诊断只返回稳定错误分类，不把底层地址或凭据带到调试 API。
 */
@Service
class RerankCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RerankCoordinator.class);
    private final RerankService rerankService;

    RerankCoordinator(RerankService rerankService) {
        this.rerankService = rerankService;
    }

    Outcome rerank(String question, List<HybridChunk> candidates) {
        if (!rerankService.enabled() || candidates.isEmpty()) {
            return new Outcome(candidates, diagnostics(
                    rerankService.enabled(), candidates.isEmpty() ? "skipped" : "disabled", false,
                    candidates.size(), 0, 0, false,
                    candidates.isEmpty() ? "empty_candidates" : "rerank_disabled"));
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
                return new Outcome(candidates, diagnostics(true, "fallback", true,
                        candidates.size(), 0, 0, false, "empty_result"));
            }
            Map<String, HybridChunk> remaining = new LinkedHashMap<>();
            candidates.forEach(chunk -> remaining.put(HybridDocumentSupport.stableKey(chunk.document()), chunk));
            List<HybridChunk> reranked = new ArrayList<>();
            for (RerankResult result : results) {
                HybridChunk chunk = remaining.remove(result.id());
                if (chunk != null) {
                    chunk.setRerankScore(result.score());
                    reranked.add(chunk);
                }
            }
            reranked.addAll(remaining.values());
            int changed = changedCount(candidates, reranked);
            boolean scores = reranked.stream().anyMatch(chunk -> chunk.rerankScore() != null);
            return new Outcome(reranked, diagnostics(true, "success", false,
                    candidates.size(), results.size(), changed, scores,
                    changed == 0 ? "order_unchanged" : "order_changed"));
        } catch (RuntimeException exception) {
            log.warn("reranker 调用失败，使用 RRF 排序兜底: {}", exception.getClass().getSimpleName());
            return new Outcome(candidates, diagnostics(true, "fallback", true,
                    candidates.size(), 0, 0, false, "execution_failed"));
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
