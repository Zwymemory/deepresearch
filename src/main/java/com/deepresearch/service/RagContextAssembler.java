package com.deepresearch.service;

import com.deepresearch.web.dto.ResearchAnswer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 把排序后的内部 chunk 转成 ContextPacking 输入、模型上下文与公开来源列表。
 * 压缩策略本身仍由 ContextPackingService 负责，本类不重复评分或裁剪算法。
 */
@Service
class RagContextAssembler {

    private final ContextPackingService contextPackingService;

    RagContextAssembler(ContextPackingService contextPackingService) {
        this.contextPackingService = contextPackingService;
    }

    ContextPackingService.PackedContext pack(String question, List<HybridChunk> chunks) {
        return contextPackingService.pack(question, toInputs(chunks));
    }

    String buildModelContext(ContextPackingService.PackedContext packedContext) {
        StringBuilder context = new StringBuilder();
        List<ContextPackingService.PackedEvidence> evidences = packedContext.evidences();
        for (int i = 0; i < evidences.size(); i++) {
            ContextPackingService.PackedEvidence evidence = evidences.get(i);
            context.append("[来源").append(i + 1).append("] ").append(evidence.title()).append("\n");
            if (evidence.sectionPath() != null && !evidence.sectionPath().isBlank()) {
                context.append("章节: ").append(evidence.sectionPath()).append("\n");
            }
            if (evidence.pageNumber() != null) {
                context.append("页码: ").append(evidence.pageNumber()).append("\n");
            }
            context.append("召回路径: ").append(evidence.route()).append("\n")
                    .append("证据: ").append(evidence.evidenceText()).append("\n\n");
        }
        return context.toString().trim();
    }

    List<ResearchAnswer.Source> sources(ContextPackingService.PackedContext packedContext) {
        return IntStream.range(0, packedContext.evidences().size())
                .mapToObj(i -> new ResearchAnswer.Source(i + 1, packedContext.evidences().get(i).title(), ""))
                .toList();
    }

    private List<ContextPackingService.ChunkInput> toInputs(List<HybridChunk> chunks) {
        List<ContextPackingService.ChunkInput> inputs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            HybridChunk chunk = chunks.get(i);
            inputs.add(new ContextPackingService.ChunkInput(
                    i + 1,
                    chunk.title(),
                    HybridDocumentSupport.stringMeta(chunk.document(), "docId"),
                    HybridDocumentSupport.stringMeta(chunk.document(), "chunkId"),
                    HybridDocumentSupport.stringMeta(chunk.document(), "filename"),
                    HybridDocumentSupport.stringMeta(chunk.document(), "sectionPath"),
                    HybridDocumentSupport.intMeta(chunk.document(), "chunkIndex"),
                    HybridDocumentSupport.intMeta(chunk.document(), "pageNumber"),
                    HybridDocumentSupport.chunkKey(chunk.document()),
                    chunk.routeSummary(),
                    chunk.vectorRank(),
                    chunk.keywordRank(),
                    Math.round(chunk.rrfScore() * 1_000_000.0) / 1_000_000.0,
                    chunk.rerankScore(),
                    chunk.expanded(),
                    chunk.expandedFromChunkKey(),
                    HybridDocumentSupport.cleanText(chunk.document())
            ));
        }
        return inputs;
    }
}
