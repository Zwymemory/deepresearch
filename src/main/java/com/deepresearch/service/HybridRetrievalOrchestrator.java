package com.deepresearch.service;

import com.deepresearch.model.KeywordSearchHit;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 执行向量与 BM25 两路召回并统一为 Spring AI Document。
 * 单路瞬时失败时保留另一条可用召回路线；两路都失败才终止请求。
 */
@Service
class HybridRetrievalOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalOrchestrator.class);

    private final VectorStore vectorStore;
    private final KeywordSearchService keywordSearchService;
    private final AgentTelemetry telemetry;

    HybridRetrievalOrchestrator(VectorStore vectorStore,
                                KeywordSearchService keywordSearchService,
                                AgentTelemetry telemetry) {
        this.vectorStore = vectorStore;
        this.keywordSearchService = keywordSearchService;
        this.telemetry = telemetry;
    }

    RetrievalResult retrieve(String question, int limit) {
        List<Document> safeVector = List.of();
        List<Document> keyword = List.of();
        boolean vectorFailed = false;
        boolean keywordFailed = false;
        try {
            List<Document> vector = vectorStore.similaritySearch(
                    SearchRequest.builder().query(question).topK(limit).build());
            safeVector = vector == null ? List.of() : vector;
        } catch (RuntimeException failure) {
            vectorFailed = true;
            log.warn("hybrid_retrieval_route_failed route=vector failure_type={}",
                    failure.getClass().getSimpleName());
        }
        try {
            keyword = keywordSearchService.search(question, limit).stream()
                    .map(this::toDocument)
                    .toList();
        } catch (RuntimeException failure) {
            keywordFailed = true;
            log.warn("hybrid_retrieval_route_failed route=keyword failure_type={}",
                    failure.getClass().getSimpleName());
        }
        if (vectorFailed && keywordFailed) {
            throw new IllegalStateException("all retrieval routes failed");
        }
        telemetry.recordRetrievalHits(safeVector.size() + keyword.size());
        return new RetrievalResult(safeVector, keyword);
    }

    private Document toDocument(KeywordSearchHit hit) {
        return new Document(hit.id(), hit.content() == null ? "" : hit.content(), Map.of(
                "title", hit.title() == null ? "" : hit.title(),
                "docId", hit.docId() == null ? "" : hit.docId(),
                "chunkId", hit.chunkId() == null ? "" : hit.chunkId(),
                "filename", hit.filename() == null ? "" : hit.filename(),
                "sectionPath", hit.sectionPath() == null ? "" : hit.sectionPath(),
                "chunkIndex", hit.chunkIndex() == null ? "" : hit.chunkIndex(),
                "pageNumber", hit.pageNumber() == null ? "" : hit.pageNumber(),
                "keywordScore", hit.score()));
    }

    record RetrievalResult(List<Document> vectorHits, List<Document> keywordHits) {
    }
}
