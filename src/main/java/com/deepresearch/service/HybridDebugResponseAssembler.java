package com.deepresearch.service;

import com.deepresearch.web.dto.HybridDebugResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将检索流水线的内部结果映射成调试 DTO。
 * 该类只做展示映射，不执行召回、重排、扩展或上下文压缩。
 */
@Service
class HybridDebugResponseAssembler {

    HybridDebugResponse assemble(String question,
                                 int recallK,
                                 int finalK,
                                 QueryRewriteService.RewriteResult rewrite,
                                 HybridRetrievalOrchestrator.RetrievalResult retrieval,
                                 List<Document> simpleMerge,
                                 List<HybridChunk> fused,
                                 RerankCoordinator.Outcome rerank,
                                 List<HybridChunk> direct,
                                 List<HybridChunk> fusedByDoc,
                                 List<HybridChunk> rerankedByDoc,
                                 List<HybridChunk> expanded,
                                 ContextPackingService.PackedContext packed) {
        return new HybridDebugResponse(
                question,
                recallK,
                finalK,
                rewrite.originalQuestion(),
                rewrite.rewrittenQuestion(),
                rewrite.used(),
                rankEntries(retrieval.vectorHits(), HybridChunk.Route.VECTOR),
                rankEntries(retrieval.keywordHits(), HybridChunk.Route.KEYWORD),
                simpleEntries(simpleMerge),
                fusedEntries(fused.stream().limit(finalK).toList()),
                fusedEntries(fusedByDoc),
                fusedEntries(direct),
                fusedEntries(rerankedByDoc),
                fusedEntries(expanded),
                packedEntries(packed.evidences()),
                expanded.size(),
                packed.evidences().size(),
                packingDiagnostics(packed.diagnostics()),
                rerank.diagnostics()
        );
    }

    private List<HybridDebugResponse.Entry> rankEntries(List<Document> documents, HybridChunk.Route route) {
        List<HybridDebugResponse.Entry> entries = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            int rank = i + 1;
            entries.add(entry(
                    rank, document,
                    route.name().toLowerCase(Locale.ROOT) + "#" + rank,
                    route == HybridChunk.Route.VECTOR ? rank : null,
                    route == HybridChunk.Route.KEYWORD ? rank : null,
                    null, null, false, "", null, "", HybridDocumentSupport.preview(document)));
        }
        return entries;
    }

    private List<HybridDebugResponse.Entry> simpleEntries(List<Document> documents) {
        List<HybridDebugResponse.Entry> entries = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            entries.add(entry(i + 1, documents.get(i), "vector-first-merge",
                    null, null, null, null, false, "", null, "",
                    HybridDocumentSupport.preview(documents.get(i))));
        }
        return entries;
    }

    private List<HybridDebugResponse.Entry> fusedEntries(List<HybridChunk> chunks) {
        List<HybridDebugResponse.Entry> entries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            HybridChunk chunk = chunks.get(i);
            entries.add(entry(
                    i + 1,
                    chunk.document(),
                    chunk.routeSummary(),
                    chunk.vectorRank(),
                    chunk.keywordRank(),
                    Math.round(chunk.rrfScore() * 1_000_000.0) / 1_000_000.0,
                    chunk.rerankScore(),
                    chunk.expanded(),
                    chunk.expandedFromChunkKey(),
                    null,
                    "",
                    HybridDocumentSupport.preview(chunk.document())));
        }
        return entries;
    }

    private HybridDebugResponse.Entry entry(int index,
                                            Document document,
                                            String route,
                                            Integer vectorRank,
                                            Integer keywordRank,
                                            Double rrfScore,
                                            Double rerankScore,
                                            boolean expanded,
                                            String expandedFrom,
                                            Integer evidenceChars,
                                            String packReason,
                                            String preview) {
        return new HybridDebugResponse.Entry(
                index,
                HybridDocumentSupport.title(document),
                HybridDocumentSupport.stringMeta(document, "docId"),
                HybridDocumentSupport.stringMeta(document, "chunkId"),
                HybridDocumentSupport.stringMeta(document, "filename"),
                HybridDocumentSupport.stringMeta(document, "sectionPath"),
                HybridDocumentSupport.intMeta(document, "chunkIndex"),
                HybridDocumentSupport.intMeta(document, "pageNumber"),
                HybridDocumentSupport.chunkKey(document),
                route,
                vectorRank,
                keywordRank,
                rrfScore,
                rerankScore,
                expanded,
                expandedFrom,
                evidenceChars,
                packReason,
                preview
        );
    }

    private List<HybridDebugResponse.Entry> packedEntries(List<ContextPackingService.PackedEvidence> evidences) {
        List<HybridDebugResponse.Entry> entries = new ArrayList<>();
        for (int i = 0; i < evidences.size(); i++) {
            ContextPackingService.PackedEvidence evidence = evidences.get(i);
            entries.add(new HybridDebugResponse.Entry(
                    i + 1,
                    evidence.title(),
                    evidence.docId(),
                    evidence.chunkId(),
                    evidence.filename(),
                    evidence.sectionPath(),
                    evidence.chunkIndex(),
                    evidence.pageNumber(),
                    evidence.chunkKey(),
                    evidence.route(),
                    evidence.vectorRank(),
                    evidence.keywordRank(),
                    evidence.rrfScore(),
                    evidence.rerankScore(),
                    evidence.expanded(),
                    evidence.expandedFromChunkKey(),
                    evidence.evidenceChars(),
                    evidence.packReason(),
                    evidence.evidenceText()));
        }
        return entries;
    }

    private HybridDebugResponse.ContextPackingDiagnostics packingDiagnostics(
            ContextPackingService.PackingDiagnostics diagnostics) {
        return new HybridDebugResponse.ContextPackingDiagnostics(
                diagnostics.enabled(),
                diagnostics.inputChunks(),
                diagnostics.outputEvidences(),
                diagnostics.droppedChunks(),
                diagnostics.totalEvidenceChars(),
                diagnostics.compressionRatio(),
                diagnostics.reason());
    }
}
