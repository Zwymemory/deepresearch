package com.deepresearch.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPackingServiceTest {

    @Test
    void extractsQueryRelatedEvidenceAndDropsIrrelevantNoise() {
        ContextPackingService service = new ContextPackingService(true, 120, 180, 2);
        List<ContextPackingService.ChunkInput> chunks = List.of(
                chunk(1, "tech.md#配置 > MCP-7788#0",
                        "苹果、香蕉、晴天、咖啡。MCP-7788 是混合检索链路的测试参数，用于观察编号召回。无关句子继续填充。", false, 3.0),
                chunk(2, "tech.md#配置 > ZXQ-4499#1",
                        "ZXQ-4499 是另一个实验开关。这里不包含目标配置。", true, null)
        );

        ContextPackingService.PackedContext packed = service.pack("MCP-7788 怎么配置？", chunks);

        assertThat(packed.evidences()).isNotEmpty();
        assertThat(packed.evidences().get(0).evidenceText()).contains("MCP-7788");
        assertThat(packed.diagnostics().inputChunks()).isEqualTo(2);
        assertThat(packed.diagnostics().outputEvidences()).isLessThanOrEqualTo(2);
        assertThat(packed.diagnostics().compressionRatio()).isLessThan(1.0);
    }

    @Test
    void preservesStableMetadataInPackedEvidence() {
        ContextPackingService service = new ContextPackingService(true, 200, 300, 2);

        ContextPackingService.PackedContext packed = service.pack("年假是多少天？", List.of(
                chunk(1, "employee.md#员工制度 > 年假#3", "员工年假为 15 天。病假需要证明。", false, 2.5)));

        ContextPackingService.PackedEvidence evidence = packed.evidences().get(0);
        assertThat(evidence.filename()).isEqualTo("employee.md");
        assertThat(evidence.sectionPath()).isEqualTo("员工制度 > 年假");
        assertThat(evidence.chunkIndex()).isEqualTo(3);
        assertThat(evidence.chunkKey()).isEqualTo("employee.md#员工制度 > 年假#3");
    }

    private ContextPackingService.ChunkInput chunk(int index, String chunkKey, String text, boolean expanded, Double rerankScore) {
        String[] parts = chunkKey.split("#", -1);
        return new ContextPackingService.ChunkInput(
                index,
                "测试文档",
                "doc-test",
                "chunk-" + index,
                parts[0],
                parts[1],
                Integer.parseInt(parts[2]),
                null,
                chunkKey,
                expanded ? "expanded" : "vector#1, keyword#1",
                expanded ? null : 1,
                expanded ? null : 1,
                0.03,
                rerankScore,
                expanded,
                expanded ? "source-key" : "",
                text);
    }
}
