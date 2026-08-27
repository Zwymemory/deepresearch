package com.deepresearch.service;

import com.deepresearch.model.KeywordSearchHit;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 关键词检索抽象。
 *
 * HybridRagService 只依赖这个接口，不依赖 Elasticsearch 的具体 API。
 * 后续要换 OpenSearch、Lucene、商业搜索服务，只需要替换实现。
 */
public interface KeywordSearchService {

    void index(List<Document> chunks);

    List<KeywordSearchHit> search(String question, int limit);

    void deleteByDocId(String docId);

    void clear();
}
