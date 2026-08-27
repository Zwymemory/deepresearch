package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import com.deepresearch.web.dto.RetrievalEvalRequest;
import com.deepresearch.web.dto.RetrievalEvalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalEvalServiceTest {

    @Test
    void matchesStrictChunkKeyBeforeFallbackTerms() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "wrong.md#错误章节#0", "错误内容"),
                entry(2, "policy.md#员工制度 > 年假#3", "年假 15 天"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "demo",
                3,
                List.of(new RetrievalEvalRequest.Case(
                        "case-1",
                        "员工年假是多少天？",
                        "员工手册",
                        List.of("年假", "15"),
                        List.of("policy.md#员工制度 > 年假#3"),
                        List.of(),
                        "policy",
                        "easy"))));

        RetrievalEvalResponse.RouteResult result = response.cases().get(0).routes().get("vectorOnly");
        assertThat(result.rank()).isEqualTo(2);
        assertThat(result.matchedChunkKey()).isEqualTo("policy.md#员工制度 > 年假#3");
        assertThat(result.matchMode()).isEqualTo("strict_chunk_key");
        assertThat(response.metrics().get("vectorOnly").recallAtK()).isEqualTo(1.0);
        assertThat(response.metrics().get("vectorOnly").mrr()).isEqualTo(0.5);
    }


    @Test
    void usesFallbackTermsWhenNoExpectedChunkKeys() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "employee.md#员工手册 > 休假制度 > 年假#0", "员工年假 15 天"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "demo",
                3,
                List.of(new RetrievalEvalRequest.Case(
                        "case-1",
                        "员工年假是多少天？",
                        "员工手册",
                        List.of("年假", "15"),
                        List.of(),
                        List.of(),
                        "policy",
                        "easy"))));

        RetrievalEvalResponse.RouteResult result = response.cases().get(0).routes().get("vectorOnly");
        assertThat(result.rank()).isEqualTo(1);
        assertThat(result.matchMode()).isEqualTo("fallback_terms");
    }

    @Test
    void recordsMissWhenNoResultMatches() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "finance.md#采购制度 > 预算#0", "预算审批"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "demo",
                3,
                List.of(new RetrievalEvalRequest.Case(
                        "case-1",
                        "MCP-7788 是什么？",
                        "",
                        List.of(),
                        List.of("tech.md#技术配置 > MCP-7788#0"),
                        List.of(),
                        "identifier",
                        "easy"))));

        RetrievalEvalResponse.RouteResult result = response.cases().get(0).routes().get("vectorOnly");
        assertThat(result.rank()).isNull();
        assertThat(result.matchMode()).isEqualTo("miss");
        assertThat(response.metrics().get("vectorOnly").recallAtK()).isEqualTo(0.0);
    }

    @Test
    void loadsMiniBenchmarkJsonlDataset() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "none.md#无关#0", "无关内容"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest("mini", 3, null));

        assertThat(response.dataset()).isEqualTo("mini");
        assertThat(response.totalCases()).isEqualTo(50);
        assertThat(response.cases()).allSatisfy(result ->
                assertThat(result.expectedChunkKeys()).isNotEmpty());
    }

    @Test
    void loadsContextEngineeringJsonlDataset() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "context-engineering-long-manual.md#DeepResearch W6/W7 长文档验证手册 > 五、MCP-7788 混合检索参数 > 5.1 MCP-7788 的基本定义#10", "MCP-7788 是测试参数"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest("context", 3, null));

        assertThat(response.dataset()).isEqualTo("context");
        assertThat(response.totalCases()).isEqualTo(8);
        assertThat(response.cases()).allSatisfy(result ->
                assertThat(result.expectedChunkKeys()).isNotEmpty());
    }

    @Test
    void matchesOpenDatasetByDocumentKey() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "scifact-999.md#BEIR SciFact 999 > Abstract#0", "unrelated"),
                entry(2, "scifact-123.md#BEIR SciFact 123 > Abstract#0", "relevant"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "open-scifact",
                3,
                List.of(new RetrievalEvalRequest.Case(
                        "scifact-test",
                        "Does the claim match the abstract?",
                        "",
                        List.of(),
                        List.of(),
                        List.of("scifact-123.md"),
                        "open_scifact",
                        "open"))));

        RetrievalEvalResponse.RouteResult result = response.cases().get(0).routes().get("vectorOnly");
        assertThat(result.rank()).isEqualTo(2);
        assertThat(result.matchedDocKey()).isEqualTo("scifact-123.md");
        assertThat(result.matchMode()).isEqualTo("strict_doc_key");
    }

    @Test
    void separatesQueryHitRateFromStandardMultiRelevantRecallAndNdcg() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "scifact-123.md#Abstract#0", "relevant one"),
                entry(2, "scifact-999.md#Abstract#0", "unrelated"),
                entry(3, "scifact-456.md#Abstract#0", "relevant two"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "open-scifact",
                3,
                List.of(new RetrievalEvalRequest.Case(
                        "multi-relevant",
                        "claim",
                        "",
                        List.of(),
                        List.of(),
                        List.of("scifact-123.md", "scifact-456.md"),
                        "open_scifact",
                        "open"))));

        RetrievalEvalResponse.Metrics metrics = response.metrics().get("vectorOnly");
        RetrievalEvalResponse.RouteResult route = response.cases().get(0).routes().get("vectorOnly");
        assertThat(metrics.hitRateAtK()).isEqualTo(1.0);
        assertThat(metrics.recallAtK()).isEqualTo(1.0);
        assertThat(metrics.mrr()).isEqualTo(1.0);
        assertThat(metrics.ndcgAtK()).isEqualTo(0.9197);
        assertThat(route.relevantRanks()).containsExactly(1, 3);
        assertThat(route.relevantRetrieved()).isEqualTo(2);
        assertThat(route.relevantTotal()).isEqualTo(2);
    }

    @Test
    void penalizesMissingRelevantDocumentsEvenWhenFirstHitRanksFirst() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(debug(List.of(
                entry(1, "scifact-123.md#Abstract#0", "relevant one"),
                entry(2, "scifact-999.md#Abstract#0", "unrelated"),
                entry(3, "scifact-888.md#Abstract#0", "unrelated"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "open-scifact",
                3,
                List.of(new RetrievalEvalRequest.Case(
                        "multi-relevant",
                        "claim",
                        "",
                        List.of(),
                        List.of(),
                        List.of("scifact-123.md", "scifact-456.md", "scifact-789.md"),
                        "open_scifact",
                        "open"))));

        RetrievalEvalResponse.Metrics metrics = response.metrics().get("vectorOnly");
        RetrievalEvalResponse.RouteResult route = response.cases().get(0).routes().get("vectorOnly");
        assertThat(metrics.hitRateAtK()).isEqualTo(1.0);
        assertThat(metrics.recallAtK()).isEqualTo(0.3333);
        assertThat(metrics.mrr()).isEqualTo(1.0);
        assertThat(metrics.ndcgAtK()).isEqualTo(0.4693);
        assertThat(route.relevantRanks()).containsExactly(1);
        assertThat(route.relevantRetrieved()).isEqualTo(1);
        assertThat(route.relevantTotal()).isEqualTo(3);
    }

    @Test
    void averagesRecallPerQueryInsteadOfPoolingRelevantDocuments() {
        HybridRagService hybrid = mock(HybridRagService.class);
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(
                debug(List.of(
                        entry(1, "scifact-123.md#Abstract#0", "relevant one"),
                        entry(2, "scifact-456.md#Abstract#0", "relevant two"),
                        entry(3, "scifact-999.md#Abstract#0", "unrelated"))),
                debug(List.of(
                        entry(1, "scifact-789.md#Abstract#0", "relevant three"),
                        entry(2, "scifact-888.md#Abstract#0", "unrelated"),
                        entry(3, "scifact-999.md#Abstract#0", "unrelated"))));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "open-scifact",
                3,
                List.of(
                        new RetrievalEvalRequest.Case(
                                "all-relevant-retrieved",
                                "claim A",
                                "",
                                List.of(),
                                List.of(),
                                List.of("scifact-123.md", "scifact-456.md"),
                                "open_scifact",
                                "open"),
                        new RetrievalEvalRequest.Case(
                                "one-of-three-retrieved",
                                "claim B",
                                "",
                                List.of(),
                                List.of(),
                                List.of("scifact-789.md", "scifact-456.md", "scifact-321.md"),
                                "open_scifact",
                                "open"))));

        RetrievalEvalResponse.Metrics metrics = response.metrics().get("vectorOnly");
        RetrievalEvalResponse.RouteResult firstRoute = response.cases().get(0).routes().get("vectorOnly");
        RetrievalEvalResponse.RouteResult secondRoute = response.cases().get(1).routes().get("vectorOnly");
        assertThat(firstRoute.relevantRetrieved()).isEqualTo(2);
        assertThat(firstRoute.relevantTotal()).isEqualTo(2);
        assertThat(secondRoute.relevantRetrieved()).isEqualTo(1);
        assertThat(secondRoute.relevantTotal()).isEqualTo(3);
        assertThat(metrics.recallAtK()).isEqualTo(0.6667);
        assertThat(metrics.recallAtK()).isNotEqualTo(0.6);
    }

    @Test
    void reportsDocRoutesDiagnosticsAndRerankSummary() {
        HybridRagService hybrid = mock(HybridRagService.class);
        HybridDebugResponse.Entry expected = entry(1, "scifact-123.md#BEIR SciFact 123 > Abstract#0", "relevant");
        HybridDebugResponse.Entry wrong = entry(1, "scifact-999.md#BEIR SciFact 999 > Abstract#0", "unrelated");
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(new HybridDebugResponse(
                "question",
                80,
                10,
                "question",
                "question",
                false,
                List.of(wrong),
                List.of(expected),
                List.of(wrong),
                List.of(wrong),
                List.of(expected),
                List.of(wrong),
                List.of(expected),
                List.of(expected),
                List.of(expected),
                1,
                1,
                packingDiagnostics(1, 1),
                new HybridDebugResponse.RerankDiagnostics(true, "success", false, 2, 2, 1, true, "order_changed")));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "open-scifact",
                10,
                80,
                60,
                List.of(new RetrievalEvalRequest.Case(
                        "scifact-test",
                        "claim",
                        "",
                        List.of(),
                        List.of(),
                        List.of("scifact-123.md"),
                        "open_scifact",
                        "open"))));

        assertThat(response.metrics()).containsKeys("rrfDocFusion", "rerankDocResult");
        assertThat(response.metrics().get("rrfDocFusion").recallAtK()).isEqualTo(1.0);
        assertThat(response.diagnostics().get("keywordOnly").routeHitRrfMissCount()).isEqualTo(1);
        assertThat(response.rerankSummary().changedCount()).isEqualTo(1);
        assertThat(response.rerankSummary().scoresPresentCount()).isEqualTo(1);
    }

    @Test
    void doesNotCountExpandedEvidenceBeyondRequestedTopK() {
        HybridRagService hybrid = mock(HybridRagService.class);
        HybridDebugResponse.Entry wrong = entry(1, "manual.md#部署手册 > 摘要#0", "配置概览");
        HybridDebugResponse.Entry expanded = expandedEntry(2, "manual.md#部署手册 > 参数表#1", "timeout 参数为 30 秒", "manual.md#部署手册 > 摘要#0");
        when(hybrid.debug(anyString(), any(), any(), any())).thenReturn(new HybridDebugResponse(
                "timeout 怎么配置？",
                20,
                1,
                "timeout 怎么配置？",
                "timeout 怎么配置？",
                false,
                List.of(wrong),
                List.of(wrong),
                List.of(wrong),
                List.of(wrong),
                List.of(wrong),
                List.of(wrong),
                List.of(wrong),
                List.of(wrong, expanded),
                List.of(expanded),
                2,
                1,
                packingDiagnostics(2, 1),
                new HybridDebugResponse.RerankDiagnostics(true, "success", false, 1, 1, 0, true, "order_unchanged")));
        RetrievalEvalService service = new RetrievalEvalService(hybrid, new ObjectMapper());

        RetrievalEvalResponse response = service.evaluate(new RetrievalEvalRequest(
                "demo",
                1,
                List.of(new RetrievalEvalRequest.Case(
                        "expanded-hit",
                        "timeout 怎么配置？",
                        "",
                        List.of(),
                        List.of("manual.md#部署手册 > 参数表#1"),
                        List.of(),
                        "context",
                        "medium"))));

        assertThat(response.metrics()).containsKey("expandedContextResult");
        assertThat(response.metrics().get("rerankDocResult").recallAtK()).isEqualTo(0.0);
        assertThat(response.metrics().get("expandedContextResult").recallAtK()).isEqualTo(0.0);
        assertThat(response.metrics().get("compressedContextResult").recallAtK()).isEqualTo(1.0);
        assertThat(response.cases().get(0).routes().get("expandedContextResult").matchMode())
                .isEqualTo("miss");
    }

    private HybridDebugResponse debug(List<HybridDebugResponse.Entry> entries) {
        return new HybridDebugResponse(
                "question",
                20,
                3,
                "question",
                "question",
                false,
                entries,
                entries,
                entries,
                entries,
                entries,
                entries,
                entries,
                entries,
                entries,
                entries.size(),
                entries.size(),
                packingDiagnostics(entries.size(), entries.size()),
                new HybridDebugResponse.RerankDiagnostics(true, "success", false, entries.size(), entries.size(), 0, true, "order_unchanged"));
    }

    private HybridDebugResponse.ContextPackingDiagnostics packingDiagnostics(int inputChunks, int outputEvidences) {
        return new HybridDebugResponse.ContextPackingDiagnostics(
                true,
                inputChunks,
                outputEvidences,
                Math.max(0, inputChunks - outputEvidences),
                120,
                0.5,
                "test");
    }

    private HybridDebugResponse.Entry entry(int index, String chunkKey, String preview) {
        String[] parts = chunkKey.split("#", -1);
        String filename = parts.length > 0 ? parts[0] : "";
        String sectionPath = parts.length > 1 ? parts[1] : "";
        Integer chunkIndex = parts.length > 2 && !parts[2].isBlank() ? Integer.parseInt(parts[2]) : null;
        return new HybridDebugResponse.Entry(
                index,
                sectionPath.contains("员工") ? "员工手册" : "知识库片段",
                "doc-test",
                "chunk-test",
                filename,
                sectionPath,
                chunkIndex,
                null,
                chunkKey,
                "vector#" + index,
                index,
                null,
                null,
                null,
                false,
                "",
                null,
                "",
                preview);
    }

    private HybridDebugResponse.Entry expandedEntry(int index, String chunkKey, String preview, String expandedFromChunkKey) {
        String[] parts = chunkKey.split("#", -1);
        String filename = parts.length > 0 ? parts[0] : "";
        String sectionPath = parts.length > 1 ? parts[1] : "";
        Integer chunkIndex = parts.length > 2 && !parts[2].isBlank() ? Integer.parseInt(parts[2]) : null;
        return new HybridDebugResponse.Entry(
                index,
                "知识库片段",
                "doc-test",
                "chunk-test-" + index,
                filename,
                sectionPath,
                chunkIndex,
                null,
                chunkKey,
                "expanded",
                null,
                null,
                null,
                null,
                true,
                expandedFromChunkKey,
                preview.length(),
                "test",
                preview);
    }
}
