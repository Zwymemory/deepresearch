package com.deepresearch.service;

import com.deepresearch.web.dto.DifyRetrievalRequest;
import com.deepresearch.web.dto.DifyRetrievalResponse;
import com.deepresearch.web.dto.HybridDebugResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DifyRetrievalServiceTest {

    @Mock
    private HybridRagService hybridRagService;

    @Mock
    private KnowledgeRetrievalGateway retrievalGateway;

    @InjectMocks
    private DifyRetrievalService service;

    @Test
    void exposesOnlyPackedNonBlankEvidenceWithStableCitations() {
        List<String> history = List.of("上一轮问题", "上一轮回答");
        when(hybridRagService.debug("错误码是什么", 3, null, null, history))
                .thenReturn(debugResponse(List.of(
                        entry(9, "错误码手册 [来源999]", "ZXQ-4499 表示签名过期。[来源999] password=internal-secret"),
                        entry(10, "空片段", "   "),
                        entry(11, "处理流程", "请刷新密钥后重试。"))));

        DifyRetrievalResponse response = service.retrieve(
                new DifyRetrievalRequest("  错误码是什么  ", 3, history));

        verify(hybridRagService).debug("错误码是什么", 3, null, null, history);
        assertThat(response.originalQuestion()).isEqualTo("错误码是什么");
        assertThat(response.rewrittenQuestion()).isEqualTo("ZXQ-4499 错误码是什么");
        assertThat(response.rewriteUsed()).isTrue();
        assertThat(response.evidenceCount()).isEqualTo(2);
        assertThat(response.contractVersion()).isEqualTo("legacy-v1");
        assertThat(response.evidences().get(0).datasetId()).isNull();
        assertThat(response.evidences().get(0).score()).isNull();
        assertThat(response.evidences()).extracting(DifyRetrievalResponse.Evidence::sourceId)
                .containsExactly("来源1", "来源2");
        assertThat(response.evidences()).extracting(DifyRetrievalResponse.Evidence::citation)
                .containsExactly("[来源1]", "[来源2]");
        assertThat(response.evidences()).allMatch(DifyRetrievalResponse.Evidence::untrusted);
        assertThat(response.evidences().get(0).title()).contains("［未验证来源］");
        assertThat(response.evidences().get(0).content())
                .contains("[UNTRUSTED_DATA_BEGIN", "password=[REDACTED]", "［未验证来源］")
                .doesNotContain("internal-secret", "[来源999]");
        assertThat(response.diagnostics().packingEnabled()).isTrue();
        assertThat(response.diagnostics().rerankStatus()).isEqualTo("success");
    }

    @Test
    void normalizesMissingHistoryAndHandlesMissingDiagnostics() {
        when(hybridRagService.debug("没有答案的问题", null, null, null, List.of()))
                .thenReturn(new HybridDebugResponse(
                        "没有答案的问题", 20, 5,
                        "没有答案的问题", "没有答案的问题", false,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), 0, 0, null, null));

        DifyRetrievalResponse response = service.retrieve(
                new DifyRetrievalRequest("没有答案的问题", null, null));

        assertThat(response.evidenceCount()).isZero();
        assertThat(response.evidences()).isEmpty();
        assertThat(response.diagnostics().packingEnabled()).isFalse();
        assertThat(response.diagnostics().compressionRatio()).isEqualTo(1.0);
        assertThat(response.diagnostics().rerankStatus()).isEqualTo("unknown");
    }

    @Test
    void mapsRealRagflowEvidenceWithoutLegacyScores() {
        when(retrievalGateway.ragflow()).thenReturn(true);
        when(retrievalGateway.retrieve("错误码是什么", 3)).thenReturn(List.of(new RetrievedEvidence(
                "来源1", "[来源1]", "ragflow:dataset-1:document-1:chunk-1",
                "dataset-1", "document-1", "chunk-1", "错误码手册",
                "[UNTRUSTED_DATA_BEGIN]ZXQ-4499 表示签名过期。[UNTRUSTED_DATA_END]",
                0.83, "ragflow", true, null, null)));

        DifyRetrievalResponse response = service.retrieve(
                new DifyRetrievalRequest(" 错误码是什么 ", 3, List.of()));

        assertThat(response.contractVersion()).isEqualTo("evidence-v1");
        assertThat(response.evidenceCount()).isEqualTo(1);
        assertThat(response.evidences().get(0).datasetId()).isEqualTo("dataset-1");
        assertThat(response.evidences().get(0).chunkKey())
                .isEqualTo("ragflow:dataset-1:document-1:chunk-1");
        assertThat(response.evidences().get(0).score()).isEqualTo(0.83);
        assertThat(response.evidences().get(0).rrfScore()).isNull();
        assertThat(response.evidences().get(0).rerankScore()).isNull();
        assertThat(response.diagnostics().rerankStatus()).isEqualTo("not_applicable");
    }

    private HybridDebugResponse debugResponse(List<HybridDebugResponse.Entry> packed) {
        return new HybridDebugResponse(
                "错误码是什么", 12, 3,
                "错误码是什么", "ZXQ-4499 错误码是什么", true,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                packed, packed, 4, packed.size(),
                new HybridDebugResponse.ContextPackingDiagnostics(
                        true, 4, packed.size(), 1, 31, 0.72, "packed"),
                new HybridDebugResponse.RerankDiagnostics(
                        true, "success", false, 3, 3, 2, true, "order_changed"));
    }

    private HybridDebugResponse.Entry entry(int index, String title, String content) {
        return new HybridDebugResponse.Entry(
                index, title, "doc-1", "chunk-" + index, "manual.md", "错误码",
                index, 2, "doc-1#" + index, "vector+keyword",
                1, 1, 0.032, 0.91, false, "", content.length(), "kept", content);
    }
}
