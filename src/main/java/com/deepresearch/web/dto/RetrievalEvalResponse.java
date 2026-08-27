package com.deepresearch.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 检索评测响应。
 *
 * HitRate@K：有至少一个相关结果进入 topK 的 query 比例。
 * Recall@K：每个 query 召回的去重相关项数 / 该 query 的相关项总数，再做宏平均。
 * MRR：第一个相关结果排名的倒数，越靠前越高。
 * NDCG@K：对 topK 内全部二元相关结果计算归一化折损收益。
 */
public record RetrievalEvalResponse(
        String dataset,
        int topK,
        int totalCases,
        Map<String, Metrics> metrics,
        Map<String, RouteDiagnostics> diagnostics,
        RerankSummary rerankSummary,
        List<CaseResult> cases
) {
    public record Metrics(
            int hitCount,
            double hitRateAtK,
            double recallAtK,
            double mrr,
            double ndcgAtK
    ) {
    }

    public record CaseResult(
            String id,
            String question,
            String expectedTitle,
            List<String> expectedTerms,
            List<String> expectedChunkKeys,
            List<String> expectedDocKeys,
            String type,
            String difficulty,
            Map<String, RouteResult> routes
    ) {
    }

    public record RouteResult(
            Integer rank,
            String matchedChunkKey,
            String matchedDocKey,
            String matchedTitle,
            String matchMode,
            List<Integer> relevantRanks,
            int relevantRetrieved,
            int relevantTotal
    ) {
    }

    public record RouteDiagnostics(
            int routeHitRrfMissCount,
            List<CaseDelta> routeHitRrfMiss,
            int rrfHitRouteMissCount,
            List<CaseDelta> rrfHitRouteMiss
    ) {
    }

    public record CaseDelta(
            String id,
            String question,
            List<String> expectedDocKeys,
            List<String> expectedChunkKeys
    ) {
    }

    public record RerankSummary(
            int totalCases,
            int successCount,
            int fallbackCount,
            int changedCount,
            int unchangedCount,
            int scoresPresentCount,
            String lastStatus,
            String lastReason
    ) {
    }
}
