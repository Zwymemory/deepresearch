package com.deepresearch.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * W4.2 混合检索调试响应。
 *
 * 不调用 LLM，只展示检索和融合排序结果，方便对比：
 * 向量召回、关键词召回、不加 RRF 的简单合并、加 RRF 后的融合排序、Cross-encoder 精排结果。
 */
public record HybridDebugResponse(
        String question,
        int recallK,
        int finalK,
        String originalQuestion,
        String rewrittenQuestion,
        boolean rewriteUsed,
        List<Entry> vectorOnly,
        List<Entry> keywordOnly,
        List<Entry> noRrfMerge,
        List<Entry> rrfFusion,
        List<Entry> rrfDocFusion,
        List<Entry> rerankResult,
        List<Entry> rerankDocResult,
        List<Entry> expandedContext,
        List<Entry> compressedContext,
        int contextChunkCount,
        int compressedContextCount,
        ContextPackingDiagnostics contextPackingDiagnostics,
        RerankDiagnostics rerankDiagnostics,
        String provider,
        Map<String, Double> similarityScores,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Long> stageTimingMs
) {
    public HybridDebugResponse {
        stageTimingMs = stageTimingMs == null ? Map.of() : Map.copyOf(stageTimingMs);
    }

    /** Compatibility constructor for callers created before stage timings were exposed. */
    public HybridDebugResponse(String question, int recallK, int finalK, String originalQuestion,
                               String rewrittenQuestion, boolean rewriteUsed, List<Entry> vectorOnly,
                               List<Entry> keywordOnly, List<Entry> noRrfMerge, List<Entry> rrfFusion,
                               List<Entry> rrfDocFusion, List<Entry> rerankResult, List<Entry> rerankDocResult,
                               List<Entry> expandedContext, List<Entry> compressedContext, int contextChunkCount,
                               int compressedContextCount, ContextPackingDiagnostics contextPackingDiagnostics,
                               RerankDiagnostics rerankDiagnostics, String provider,
                               Map<String, Double> similarityScores) {
        this(question, recallK, finalK, originalQuestion, rewrittenQuestion, rewriteUsed, vectorOnly,
                keywordOnly, noRrfMerge, rrfFusion, rrfDocFusion, rerankResult, rerankDocResult,
                expandedContext, compressedContext, contextChunkCount, compressedContextCount,
                contextPackingDiagnostics, rerankDiagnostics, provider, similarityScores, Map.of());
    }

    public HybridDebugResponse(String question, int recallK, int finalK, String originalQuestion,
                               String rewrittenQuestion, boolean rewriteUsed, List<Entry> vectorOnly,
                               List<Entry> keywordOnly, List<Entry> noRrfMerge, List<Entry> rrfFusion,
                               List<Entry> rrfDocFusion, List<Entry> rerankResult, List<Entry> rerankDocResult,
                               List<Entry> expandedContext, List<Entry> compressedContext, int contextChunkCount,
                               int compressedContextCount, ContextPackingDiagnostics contextPackingDiagnostics,
                               RerankDiagnostics rerankDiagnostics) {
        this(question, recallK, finalK, originalQuestion, rewrittenQuestion, rewriteUsed, vectorOnly,
                keywordOnly, noRrfMerge, rrfFusion, rrfDocFusion, rerankResult, rerankDocResult,
                expandedContext, compressedContext, contextChunkCount, compressedContextCount,
                contextPackingDiagnostics, rerankDiagnostics, "legacy", Map.of(), Map.of());
    }

    public record Entry(
            int index,
            String title,
            String docId,
            String chunkId,
            String filename,
            String sectionPath,
            Integer chunkIndex,
            Integer pageNumber,
            String chunkKey,
            String route,
            Integer vectorRank,
            Integer keywordRank,
            Double rrfScore,
            Double rerankScore,
            boolean expanded,
            String expandedFromChunkKey,
            Integer evidenceChars,
            String packReason,
            String preview
    ) {
    }

    public record ContextPackingDiagnostics(
            boolean enabled,
            int inputChunks,
            int outputEvidences,
            int droppedChunks,
            int totalEvidenceChars,
            double compressionRatio,
            String reason
    ) {
    }

    public record RerankDiagnostics(
            boolean enabled,
            String status,
            boolean fallback,
            int candidateCount,
            int returnedCount,
            int changedCount,
            boolean scoresPresent,
            String reason
    ) {
    }
}
