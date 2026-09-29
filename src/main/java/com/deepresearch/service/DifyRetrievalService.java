package com.deepresearch.service;

import com.deepresearch.agent.ToolOutputSanitizer;
import com.deepresearch.web.dto.DifyRetrievalRequest;
import com.deepresearch.web.dto.DifyRetrievalResponse;
import com.deepresearch.web.dto.HybridDebugResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 将现有混合检索流水线适配为 Dify 可消费的稳定 Facade。
 */
@Service
public class DifyRetrievalService {

    private final HybridRagService hybridRagService;
    private final KnowledgeRetrievalGateway retrievalGateway;

    public DifyRetrievalService(HybridRagService hybridRagService,
                                KnowledgeRetrievalGateway retrievalGateway) {
        this.hybridRagService = hybridRagService;
        this.retrievalGateway = retrievalGateway;
    }

    public DifyRetrievalResponse retrieve(DifyRetrievalRequest request) {
        if (retrievalGateway.ragflow()) return retrieveRagflow(request);
        List<String> history = request.history() == null ? List.of() : request.history();
        HybridDebugResponse debug = hybridRagService.debug(
                request.question().trim(), request.topK(), null, null, history);

        List<DifyRetrievalResponse.Evidence> evidences = mapEvidences(debug.compressedContext());
        return new DifyRetrievalResponse(
                debug.originalQuestion(),
                debug.rewrittenQuestion(),
                debug.rewriteUsed(),
                evidences.size(),
                evidences,
                diagnostics(debug));
    }

    private DifyRetrievalResponse retrieveRagflow(DifyRetrievalRequest request) {
        String question = request.question().trim();
        int topK = request.topK() == null ? 5 : request.topK();
        List<DifyRetrievalResponse.Evidence> evidences = retrievalGateway.retrieve(question, topK).stream()
                .map(item -> new DifyRetrievalResponse.Evidence(
                        item.sourceId(), item.citation(), item.title(), item.docId(), item.chunkId(),
                        "", item.sectionPath(), item.pageNumber(), item.chunkKey(), item.datasetId(),
                        item.score(), item.route(), null, null, item.content(), item.untrusted()))
                .toList();
        int contentChars = evidences.stream().mapToInt(item -> item.content().length()).sum();
        DifyRetrievalResponse.Diagnostics diagnostics = new DifyRetrievalResponse.Diagnostics(
                evidences.size(), topK, evidences.size(), evidences.size(), false, 0,
                contentChars, 1.0, "ragflow_gateway", false, "not_applicable", false, "ragflow_gateway");
        return new DifyRetrievalResponse(question, question, false, evidences.size(), evidences,
                diagnostics, "evidence-v1");
    }

    private List<DifyRetrievalResponse.Evidence> mapEvidences(
            List<HybridDebugResponse.Entry> packedEntries) {
        if (packedEntries == null || packedEntries.isEmpty()) {
            return List.of();
        }
        List<DifyRetrievalResponse.Evidence> evidences = new ArrayList<>();
        for (HybridDebugResponse.Entry entry : packedEntries) {
            if (entry == null || !StringUtils.hasText(entry.preview())) {
                continue;
            }
            String sourceId = "来源" + (evidences.size() + 1);
            String content = ToolOutputSanitizer.markUntrusted(
                    "knowledge-base",
                    safeText(entry.preview().trim()));
            evidences.add(new DifyRetrievalResponse.Evidence(
                    sourceId,
                    "[" + sourceId + "]",
                    safeText(entry.title()),
                    safeText(entry.docId()),
                    safeText(entry.chunkId()),
                    safeText(entry.filename()),
                    safeText(entry.sectionPath()),
                    entry.pageNumber(),
                    safeText(entry.chunkKey()),
                    null,
                    null,
                    safeText(entry.route()),
                    entry.rrfScore(),
                    entry.rerankScore(),
                    content,
                    true));
        }
        return List.copyOf(evidences);
    }

    private DifyRetrievalResponse.Diagnostics diagnostics(HybridDebugResponse debug) {
        HybridDebugResponse.ContextPackingDiagnostics packing = debug.contextPackingDiagnostics();
        HybridDebugResponse.RerankDiagnostics rerank = debug.rerankDiagnostics();
        return new DifyRetrievalResponse.Diagnostics(
                debug.recallK(),
                debug.finalK(),
                debug.contextChunkCount(),
                debug.compressedContextCount(),
                packing != null && packing.enabled(),
                packing == null ? 0 : packing.droppedChunks(),
                packing == null ? 0 : packing.totalEvidenceChars(),
                packing == null ? 1.0 : packing.compressionRatio(),
                packing == null ? "unknown" : packing.reason(),
                rerank != null && rerank.enabled(),
                rerank == null ? "unknown" : rerank.status(),
                rerank != null && rerank.fallback(),
                rerank == null ? "unknown" : rerank.reason());
    }

    private String safeText(String value) {
        return ToolOutputSanitizer.neutralizeCitationMarkers(
                ToolOutputSanitizer.redactSecrets(value));
    }
}
