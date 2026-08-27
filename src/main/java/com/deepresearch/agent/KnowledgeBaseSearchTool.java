package com.deepresearch.agent;

import com.deepresearch.service.HybridRagService;
import com.deepresearch.web.dto.HybridDebugResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * W8：企业知识库检索工具。
 *
 * 把 W4-W7 已经完成的 hybrid retrieval / rerank / expansion / context packing
 * 封装成 Agent 可调用的 kb_search 工具。
 */
@Component
public class KnowledgeBaseSearchTool implements Tool {

    private static final int PREVIEW_LIMIT = 420;

    private final HybridRagService hybridRagService;
    private final int topK;
    private final int recallK;
    private final int candidateK;

    public KnowledgeBaseSearchTool(HybridRagService hybridRagService,
                                   @Value("${deepresearch.agent.kb-search-top-k:3}") int topK,
                                   @Value("${deepresearch.agent.kb-search-recall-k:20}") int recallK,
                                   @Value("${deepresearch.agent.kb-search-candidate-k:10}") int candidateK) {
        this.hybridRagService = hybridRagService;
        this.topK = topK;
        this.recallK = recallK;
        this.candidateK = candidateK;
    }

    @Override
    public String name() {
        return "kb_search";
    }

    @Override
    public String description() {
        return "企业知识库检索工具。输入一个知识库问题，返回经过向量检索、BM25、RRF、rerank、上下文扩展和证据压缩后的资料片段。"
                + "适合查询内部文档、配置项、编号、制度、技术手册。";
    }

    @Override
    public String execute(String input) {
        return executeWithCitations(input).content();
    }

    /** Returns typed chunk identifiers alongside locally numbered evidence text. */
    public CitationAwareToolOutput executeWithCitations(String input) {
        if (input == null || input.isBlank()) {
            return CitationAwareToolOutput.withoutSources("（知识库检索失败：查询为空）");
        }
        try {
            HybridDebugResponse debug = hybridRagService.debug(input.trim(), topK, recallK, candidateK, List.of());
            List<HybridDebugResponse.Entry> evidences = debug.compressedContext();
            if (evidences == null || evidences.isEmpty()) {
                return CitationAwareToolOutput.withoutSources("（知识库中没有检索到相关证据）");
            }

            StringBuilder sb = new StringBuilder();
            List<String> sourceIds = new ArrayList<>(evidences.size());
            sb.append("知识库检索 query: ")
                    .append(ToolOutputSanitizer.neutralizeCitationMarkers(debug.rewrittenQuestion()))
                    .append("\n")
                    .append("证据压缩: inputChunks=").append(debug.contextPackingDiagnostics().inputChunks())
                    .append(", outputEvidences=").append(debug.contextPackingDiagnostics().outputEvidences())
                    .append(", compressionRatio=").append(debug.contextPackingDiagnostics().compressionRatio())
                    .append("\n\n");

            for (int i = 0; i < evidences.size(); i++) {
                HybridDebugResponse.Entry entry = evidences.get(i);
                sourceIds.add(CitationSourceSupport.safeKnowledgeChunk(entry.chunkKey()));
                sb.append("[来源").append(i + 1).append("] ")
                        .append(ToolOutputSanitizer.neutralizeCitationMarkers(entry.title())).append("\n");
                if (entry.sectionPath() != null && !entry.sectionPath().isBlank()) {
                    sb.append("章节: ")
                            .append(ToolOutputSanitizer.neutralizeCitationMarkers(entry.sectionPath()))
                            .append("\n");
                }
                if (entry.chunkKey() != null && !entry.chunkKey().isBlank()) {
                    sb.append("chunkKey: ")
                            .append(ToolOutputSanitizer.neutralizeCitationMarkers(entry.chunkKey()))
                            .append("\n");
                }
                sb.append("召回路径: ").append(entry.route()).append("\n")
                        .append("证据: ")
                        .append(ToolOutputSanitizer.neutralizeCitationMarkers(truncate(entry.preview())))
                        .append("\n\n");
            }
            return new CitationAwareToolOutput(
                    ToolOutputSanitizer.markUntrusted("knowledge-base", sb.toString().trim()), sourceIds);
        } catch (RuntimeException e) {
            return CitationAwareToolOutput.withoutSources("（知识库检索失败：服务暂时不可用）");
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= PREVIEW_LIMIT ? normalized : normalized.substring(0, PREVIEW_LIMIT) + "…";
    }
}
