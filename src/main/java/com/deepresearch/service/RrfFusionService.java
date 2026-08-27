package com.deepresearch.service;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只负责稳定键去重与 Reciprocal Rank Fusion，不访问数据库或外部服务。
 * 输入顺序决定并列分数顺序，输出因此可重复测试。
 */
@Service
class RrfFusionService {

    private final int rrfK;

    RrfFusionService(@Value("${deepresearch.rrf-k:60}") int rrfK) {
        this.rrfK = rrfK;
    }

    List<HybridChunk> fuse(List<Document> vectorHits, List<Document> keywordHits, int limit) {
        Map<String, HybridChunk> chunks = new LinkedHashMap<>();
        addRanked(chunks, vectorHits, HybridChunk.Route.VECTOR);
        addRanked(chunks, keywordHits, HybridChunk.Route.KEYWORD);
        return chunks.values().stream()
                .sorted((a, b) -> Double.compare(b.rrfScore(), a.rrfScore()))
                .limit(limit)
                .toList();
    }

    List<Document> simpleMerge(List<Document> vectorHits, List<Document> keywordHits, int limit) {
        Map<String, Document> merged = new LinkedHashMap<>();
        vectorHits.forEach(document -> merged.putIfAbsent(HybridDocumentSupport.stableKey(document), document));
        keywordHits.forEach(document -> merged.putIfAbsent(HybridDocumentSupport.stableKey(document), document));
        return merged.values().stream().limit(limit).toList();
    }

    List<HybridChunk> dedupeByDoc(List<HybridChunk> chunks) {
        Map<String, HybridChunk> documents = new LinkedHashMap<>();
        chunks.forEach(chunk -> documents.putIfAbsent(HybridDocumentSupport.docKey(chunk.document()), chunk));
        return new ArrayList<>(documents.values());
    }

    private void addRanked(Map<String, HybridChunk> chunks, List<Document> documents, HybridChunk.Route route) {
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            HybridChunk chunk = chunks.computeIfAbsent(
                    HybridDocumentSupport.stableKey(document), ignored -> new HybridChunk(document));
            int rank = i + 1;
            chunk.add(route, rank, 1.0 / (rrfK + rank));
        }
    }
}
