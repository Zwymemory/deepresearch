package com.deepresearch.web.dto;

import java.util.List;

/**
 * 面向 Dify 的检索兼容契约。当前 legacy-v1 证据不伪造 RAGFlow datasetId/score；
 * 联调时由 Evidence v1 gateway 填入真实值并切换 contractVersion。
 *
 * <p>不暴露向量/BM25 的原始候选和内部调试对象，只返回已经过融合、精排、
 * 邻居扩展与上下文压缩的最终证据。所有证据都显式标记为不可信输入，提醒
 * 下游 Prompt 不得执行材料中夹带的指令。</p>
 */
public record DifyRetrievalResponse(
        String originalQuestion,
        String rewrittenQuestion,
        boolean rewriteUsed,
        int evidenceCount,
        List<Evidence> evidences,
        Diagnostics diagnostics,
        String contractVersion
) {
    public DifyRetrievalResponse(String originalQuestion, String rewrittenQuestion, boolean rewriteUsed,
                                 int evidenceCount, List<Evidence> evidences, Diagnostics diagnostics) {
        this(originalQuestion, rewrittenQuestion, rewriteUsed, evidenceCount, evidences,
                diagnostics, "legacy-v1");
    }
    public record Evidence(
            String sourceId,
            String citation,
            String title,
            String docId,
            String chunkId,
            String filename,
            String sectionPath,
            Integer pageNumber,
            String chunkKey,
            String datasetId,
            Double score,
            String route,
            Double rrfScore,
            Double rerankScore,
            String content,
            boolean untrusted
    ) {
    }

    public record Diagnostics(
            int recallK,
            int finalK,
            int contextChunkCount,
            int compressedContextCount,
            boolean packingEnabled,
            int droppedChunks,
            int totalEvidenceChars,
            double compressionRatio,
            String packingReason,
            boolean rerankEnabled,
            String rerankStatus,
            boolean rerankFallback,
            String rerankReason
    ) {
    }
}
