package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import com.deepresearch.web.dto.RetrievalEvalRequest;
import com.deepresearch.web.dto.RetrievalEvalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线检索评测服务。
 *
 * 这里不评估 LLM 生成质量，只评估检索排序本身：
 * vectorOnly / keywordOnly / noRrfMerge / rrfFusion / rerankResult
 * 各自在 topK 内是否能召回标准答案片段。
 */
@Service
public class RetrievalEvalService {

    private static final int DEFAULT_TOP_K = 3;
    private static final List<String> ROUTES = List.of(
            "vectorOnly",
            "keywordOnly",
            "noRrfMerge",
            "rrfFusion",
            "rrfDocFusion",
            "rerankResult",
            "rerankDocResult",
            "expandedContextResult",
            "compressedContextResult"
    );

    private final HybridRagService hybridRagService;
    private final ObjectMapper objectMapper;

    public RetrievalEvalService(HybridRagService hybridRagService, ObjectMapper objectMapper) {
        this.hybridRagService = hybridRagService;
        this.objectMapper = objectMapper;
    }

    public RetrievalEvalResponse evaluate(RetrievalEvalRequest request) {
        int topK = request != null && request.topK() != null && request.topK() > 0
                ? request.topK()
                : DEFAULT_TOP_K;
        String dataset = normalizeDataset(request == null ? null : request.dataset());
        List<RetrievalEvalRequest.Case> cases = request != null
                && request.cases() != null
                && !request.cases().isEmpty()
                ? request.cases()
                : loadDataset(dataset);

        Map<String, List<RetrievalEvalResponse.RouteResult>> resultsByRoute = new LinkedHashMap<>();
        for (String route : ROUTES) {
            resultsByRoute.put(route, new ArrayList<>());
        }

        List<RetrievalEvalResponse.CaseResult> caseResults = new ArrayList<>();
        RerankAccumulator rerankAccumulator = new RerankAccumulator();
        for (RetrievalEvalRequest.Case evalCase : cases) {
            HybridDebugResponse debug = hybridRagService.debug(evalCase.question(), topK, request == null ? null : request.recallK(), request == null ? null : request.candidateK());
            rerankAccumulator.add(debug.rerankDiagnostics());
            Map<String, RetrievalEvalResponse.RouteResult> routes = new LinkedHashMap<>();
            routes.put("vectorOnly", firstRelevant(debug.vectorOnly(), evalCase, topK));
            routes.put("keywordOnly", firstRelevant(debug.keywordOnly(), evalCase, topK));
            routes.put("noRrfMerge", firstRelevant(debug.noRrfMerge(), evalCase, topK));
            routes.put("rrfFusion", firstRelevant(debug.rrfFusion(), evalCase, topK));
            routes.put("rrfDocFusion", firstRelevant(debug.rrfDocFusion(), evalCase, topK));
            routes.put("rerankResult", firstRelevant(debug.rerankResult(), evalCase, topK));
            routes.put("rerankDocResult", firstRelevant(debug.rerankDocResult(), evalCase, topK));
            // 所有带 @K 的 route 指标统一使用请求 topK，避免 context route 把 rank>K 的结果
            // 也算作 HitRate@K/Recall@K。完整上下文覆盖率应作为单独、不带 @K 的指标设计。
            routes.put("expandedContextResult", firstRelevant(debug.expandedContext(), evalCase, topK));
            routes.put("compressedContextResult", firstRelevant(debug.compressedContext(), evalCase, topK));

            for (String route : ROUTES) {
                resultsByRoute.get(route).add(routes.get(route));
            }

            caseResults.add(new RetrievalEvalResponse.CaseResult(
                    evalCase.id(),
                    evalCase.question(),
                    evalCase.expectedTitle(),
                    evalCase.expectedTerms() == null ? List.of() : evalCase.expectedTerms(),
                    evalCase.expectedChunkKeys() == null ? List.of() : evalCase.expectedChunkKeys(),
                    evalCase.expectedDocKeys() == null ? List.of() : evalCase.expectedDocKeys(),
                    evalCase.type(),
                    evalCase.difficulty(),
                    routes
            ));
        }

        Map<String, RetrievalEvalResponse.Metrics> metrics = new LinkedHashMap<>();
        for (String route : ROUTES) {
            metrics.put(route, metrics(resultsByRoute.get(route), topK));
        }

        return new RetrievalEvalResponse(
                dataset,
                topK,
                cases.size(),
                metrics,
                diagnostics(caseResults),
                rerankAccumulator.summary(),
                caseResults);
    }

    private Map<String, RetrievalEvalResponse.RouteDiagnostics> diagnostics(List<RetrievalEvalResponse.CaseResult> caseResults) {
        Map<String, RetrievalEvalResponse.RouteDiagnostics> diagnostics = new LinkedHashMap<>();
        for (String route : ROUTES) {
            if ("rrfFusion".equals(route)) {
                continue;
            }
            List<RetrievalEvalResponse.CaseDelta> routeHitRrfMiss = new ArrayList<>();
            List<RetrievalEvalResponse.CaseDelta> rrfHitRouteMiss = new ArrayList<>();
            for (RetrievalEvalResponse.CaseResult caseResult : caseResults) {
                RetrievalEvalResponse.RouteResult routeResult = caseResult.routes().get(route);
                RetrievalEvalResponse.RouteResult rrfResult = caseResult.routes().get("rrfFusion");
                boolean routeHit = hit(routeResult);
                boolean rrfHit = hit(rrfResult);
                if (routeHit && !rrfHit) {
                    routeHitRrfMiss.add(delta(caseResult));
                }
                if (rrfHit && !routeHit) {
                    rrfHitRouteMiss.add(delta(caseResult));
                }
            }
            diagnostics.put(route, new RetrievalEvalResponse.RouteDiagnostics(
                    routeHitRrfMiss.size(),
                    routeHitRrfMiss.stream().limit(20).toList(),
                    rrfHitRouteMiss.size(),
                    rrfHitRouteMiss.stream().limit(20).toList()));
        }
        return diagnostics;
    }

    private boolean hit(RetrievalEvalResponse.RouteResult result) {
        return result != null && result.rank() != null && !"miss".equals(result.matchMode());
    }

    private RetrievalEvalResponse.CaseDelta delta(RetrievalEvalResponse.CaseResult caseResult) {
        return new RetrievalEvalResponse.CaseDelta(
                caseResult.id(),
                caseResult.question(),
                caseResult.expectedDocKeys(),
                caseResult.expectedChunkKeys());
    }

    private RetrievalEvalResponse.Metrics metrics(List<RetrievalEvalResponse.RouteResult> results, int topK) {
        int total = results.size();
        int hitCount = 0;
        double recallSum = 0.0;
        double reciprocalRankSum = 0.0;
        double ndcgSum = 0.0;
        for (RetrievalEvalResponse.RouteResult result : results) {
            Integer rank = result == null ? null : result.rank();
            if (rank != null && rank > 0 && rank <= topK) {
                hitCount++;
                reciprocalRankSum += 1.0 / rank;
            }
            int relevantTotal = result == null ? 0 : result.relevantTotal();
            int relevantRetrieved = result == null ? 0 : result.relevantRetrieved();
            recallSum += relevantTotal == 0 ? 0.0 : (double) relevantRetrieved / relevantTotal;

            List<Integer> relevantRanks = result == null || result.relevantRanks() == null
                    ? List.of() : result.relevantRanks();
            double dcg = relevantRanks.stream()
                    .filter(relevantRank -> relevantRank != null && relevantRank > 0 && relevantRank <= topK)
                    .mapToDouble(relevantRank -> 1.0 / log2(relevantRank + 1.0))
                    .sum();
            int idealHits = Math.min(relevantTotal, topK);
            double idcg = 0.0;
            for (int idealRank = 1; idealRank <= idealHits; idealRank++) {
                idcg += 1.0 / log2(idealRank + 1.0);
            }
            ndcgSum += idcg == 0.0 ? 0.0 : dcg / idcg;
        }
        if (total == 0) {
            return new RetrievalEvalResponse.Metrics(0, 0.0, 0.0, 0.0, 0.0);
        }
        return new RetrievalEvalResponse.Metrics(
                hitCount,
                round((double) hitCount / total),
                round(recallSum / total),
                round(reciprocalRankSum / total),
                round(ndcgSum / total)
        );
    }

    private RetrievalEvalResponse.RouteResult firstRelevant(List<HybridDebugResponse.Entry> entries,
                                                            RetrievalEvalRequest.Case evalCase,
                                                            int topK) {
        int relevantTotal = relevantTotal(evalCase);
        if (entries == null || entries.isEmpty()) {
            return miss(relevantTotal);
        }
        Integer firstRank = null;
        HybridDebugResponse.Entry firstEntry = null;
        String firstMatchMode = "miss";
        List<Integer> relevantRanks = new ArrayList<>();
        LinkedHashSet<String> matchedIdentities = new LinkedHashSet<>();
        for (int i = 0; i < entries.size() && i < topK; i++) {
            HybridDebugResponse.Entry entry = entries.get(i);
            String matchMode = matchMode(entry, evalCase);
            if (!"miss".equals(matchMode)) {
                String identity = relevantIdentity(entry, evalCase, matchMode);
                if (matchedIdentities.add(identity)) {
                    relevantRanks.add(i + 1);
                }
                if (firstRank == null) {
                    firstRank = i + 1;
                    firstEntry = entry;
                    firstMatchMode = matchMode;
                }
            }
        }
        if (firstEntry == null) {
            return miss(relevantTotal);
        }
        return new RetrievalEvalResponse.RouteResult(
                firstRank,
                firstEntry.chunkKey(),
                docKey(firstEntry),
                firstEntry.title(),
                firstMatchMode,
                List.copyOf(relevantRanks),
                matchedIdentities.size(),
                relevantTotal);
    }

    private RetrievalEvalResponse.RouteResult miss(int relevantTotal) {
        return new RetrievalEvalResponse.RouteResult(
                null, null, null, null, "miss", List.of(), 0, relevantTotal);
    }

    private int relevantTotal(RetrievalEvalRequest.Case evalCase) {
        List<String> expectedChunkKeys = evalCase.expectedChunkKeys() == null
                ? List.of() : evalCase.expectedChunkKeys();
        if (!expectedChunkKeys.isEmpty()) {
            return (int) expectedChunkKeys.stream().map(this::normalize).distinct().count();
        }
        List<String> expectedDocKeys = evalCase.expectedDocKeys() == null
                ? List.of() : evalCase.expectedDocKeys();
        if (!expectedDocKeys.isEmpty()) {
            return (int) expectedDocKeys.stream().map(this::normalize).distinct().count();
        }
        return 1;
    }

    private String relevantIdentity(HybridDebugResponse.Entry entry,
                                    RetrievalEvalRequest.Case evalCase,
                                    String matchMode) {
        if ("strict_chunk_key".equals(matchMode)) {
            return "chunk:" + normalize(entry.chunkKey());
        }
        if ("strict_doc_key".equals(matchMode)) {
            return "doc:" + normalize(docKey(entry));
        }
        return "fallback:" + normalize(evalCase.id());
    }

    private String matchMode(HybridDebugResponse.Entry entry, RetrievalEvalRequest.Case evalCase) {
        List<String> expectedChunkKeys = evalCase.expectedChunkKeys() == null ? List.of() : evalCase.expectedChunkKeys();
        if (!expectedChunkKeys.isEmpty()) {
            return expectedChunkKeys.stream().anyMatch(expected -> normalize(expected).equals(normalize(entry.chunkKey())))
                    ? "strict_chunk_key"
                    : "miss";
        }

        List<String> expectedDocKeys = evalCase.expectedDocKeys() == null ? List.of() : evalCase.expectedDocKeys();
        if (!expectedDocKeys.isEmpty()) {
            String docKey = normalize(docKey(entry));
            return expectedDocKeys.stream().anyMatch(expected -> normalize(expected).equals(docKey))
                    ? "strict_doc_key"
                    : "miss";
        }

        String title = normalize(entry.title());
        String expectedTitle = normalize(evalCase.expectedTitle());
        if (!expectedTitle.isBlank() && !title.contains(expectedTitle)) {
            return "miss";
        }

        List<String> expectedTerms = evalCase.expectedTerms() == null ? List.of() : evalCase.expectedTerms();
        if (expectedTerms.isEmpty()) {
            return "fallback_title";
        }

        String searchable = normalize(entry.title() + " " + entry.preview());
        for (String term : expectedTerms) {
            if (!searchable.contains(normalize(term))) {
                return "miss";
            }
        }
        return "fallback_terms";
    }

    private String normalizeDataset(String dataset) {
        if (dataset == null || dataset.isBlank()) {
            return "demo";
        }
        String normalized = dataset.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "mini", "mini-benchmark" -> "mini";
            case "context", "context-engineering", "w7" -> "context";
            case "open-scifact", "scifact", "beir-scifact" -> "open-scifact";
            case "demo", "smoke" -> "demo";
            default -> throw new IllegalArgumentException("未知评测集: " + dataset);
        };
    }

    private List<RetrievalEvalRequest.Case> loadDataset(String dataset) {
        Path path = switch (dataset) {
            case "mini" -> Path.of("testdata", "eval", "mini-benchmark.jsonl");
            case "context" -> Path.of("testdata", "eval", "context-engineering-cases.jsonl");
            case "open-scifact" -> Path.of("testdata", "eval", "open-scifact.jsonl");
            default -> Path.of("testdata", "eval", "demo-cases.jsonl");
        };
        if (!Files.exists(path) && "demo".equals(dataset)) {
            return defaultCases();
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("评测集文件不存在: " + path);
        }
        try {
            List<RetrievalEvalRequest.Case> cases = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (!trimmed.isBlank() && !trimmed.startsWith("#")) {
                    cases.add(objectMapper.readValue(trimmed, RetrievalEvalRequest.Case.class));
                }
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取评测集失败: " + path, e);
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String docKey(HybridDebugResponse.Entry entry) {
        if (entry.filename() != null && !entry.filename().isBlank()) {
            return entry.filename();
        }
        return entry.docId() == null ? "" : entry.docId();
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private List<RetrievalEvalRequest.Case> defaultCases() {
        return List.of(
                new RetrievalEvalRequest.Case("demo-001", "员工年假是多少天？", "员工手册", List.of("年假", "15"), List.of(), List.of(), "policy", "easy"),
                new RetrievalEvalRequest.Case("demo-002", "员工请病假需要什么材料？", "员工手册", List.of("病假", "证明"), List.of(), List.of(), "policy", "easy"),
                new RetrievalEvalRequest.Case("demo-003", "MCP-7788 是什么？", "技术配置手册", List.of("MCP-7788"), List.of(), List.of(), "identifier", "easy"),
                new RetrievalEvalRequest.Case("demo-004", "deepresearch.rrf-k 是什么？", "技术配置手册", List.of("deepresearch.rrf-k", "RRF"), List.of(), List.of(), "config", "easy"),
                new RetrievalEvalRequest.Case("demo-005", "ReAct 中 Action 和 Observation 是什么？", "AI Agent", List.of("Action", "Observation"), List.of(), List.of(), "concept", "easy"),
                new RetrievalEvalRequest.Case("demo-006", "RAG 和 ReAct 有什么区别？", "AI Agent", List.of("RAG", "ReAct"), List.of(), List.of(), "concept", "easy")
        );
    }

    private static class RerankAccumulator {
        private int total;
        private int success;
        private int fallback;
        private int changed;
        private int unchanged;
        private int scoresPresent;
        private String lastStatus = "";
        private String lastReason = "";

        void add(HybridDebugResponse.RerankDiagnostics diagnostics) {
            if (diagnostics == null) {
                return;
            }
            total++;
            lastStatus = diagnostics.status();
            lastReason = diagnostics.reason();
            if ("success".equals(diagnostics.status())) {
                success++;
                if (diagnostics.changedCount() > 0) {
                    changed++;
                } else {
                    unchanged++;
                }
            }
            if (diagnostics.fallback()) {
                fallback++;
            }
            if (diagnostics.scoresPresent()) {
                scoresPresent++;
            }
        }

        RetrievalEvalResponse.RerankSummary summary() {
            return new RetrievalEvalResponse.RerankSummary(
                    total,
                    success,
                    fallback,
                    changed,
                    unchanged,
                    scoresPresent,
                    lastStatus,
                    lastReason);
        }
    }
}
