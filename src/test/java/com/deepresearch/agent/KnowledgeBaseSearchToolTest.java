package com.deepresearch.agent;

import com.deepresearch.service.HybridRagService;
import com.deepresearch.web.dto.HybridDebugResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseSearchToolTest {

    private final HybridRagService ragService = mock(HybridRagService.class);
    private final KnowledgeBaseSearchTool tool = new KnowledgeBaseSearchTool(ragService, 3, 20, 10);

    @Test
    void formatsEvidenceTruncatesPreviewAndRedactsSecrets() {
        when(ragService.debug("query", 3, 20, 10, List.of())).thenReturn(response(List.of(entry(
                "password=internal-value " + "证据".repeat(300)))));

        String result = tool.execute(" query ");

        assertThat(result)
                .contains("[来源1] title", "chunkKey: doc:1", "password=[REDACTED]", "…")
                .doesNotContain("internal-value");
    }

    @Test
    void handlesBlankNoEvidenceAndFailureWithoutLeakingDetails() {
        when(ragService.debug("empty", 3, 20, 10, List.of())).thenReturn(response(List.of()));
        when(ragService.debug("broken", 3, 20, 10, List.of()))
                .thenThrow(new IllegalStateException("jdbc://admin:password@internal"));

        assertThat(tool.execute(" ")).contains("查询为空");
        assertThat(tool.execute("empty")).contains("没有检索到相关证据");
        assertThat(tool.execute("broken"))
                .contains("服务暂时不可用")
                .doesNotContain("admin", "password", "internal");
    }

    private HybridDebugResponse response(List<HybridDebugResponse.Entry> evidence) {
        return new HybridDebugResponse(
                "query", 20, 3, "query", "query", false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), evidence,
                evidence.size(), evidence.size(),
                new HybridDebugResponse.ContextPackingDiagnostics(
                        true, evidence.size(), evidence.size(), 0, 100, 1.0, "test"),
                new HybridDebugResponse.RerankDiagnostics(
                        false, "disabled", false, 0, 0, 0, false, "test")
        );
    }

    private HybridDebugResponse.Entry entry(String preview) {
        return new HybridDebugResponse.Entry(
                1, "title", "doc", "chunk", "file.txt", "section", 1, null,
                "doc:1", "hybrid", 1, 1, 0.1, 0.9,
                false, null, preview.length(), "selected", preview
        );
    }
}
