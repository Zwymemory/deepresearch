package com.deepresearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.deepresearch.model.KeywordSearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Elasticsearch 关键词检索实现。
 *
 * ES 默认相关性排序就是 BM25，比 PostgreSQL ts_rank_cd 更贴近真实生产里的关键词召回分支。
 * 当前 mapping 使用内置 standard analyzer；生产中文场景可进一步接 IK / smartcn / 自定义 analyzer。
 */
@Service
public class ElasticsearchKeywordSearchService implements KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchKeywordSearchService.class);
    private final ElasticsearchClient client;
    private final String indexName;

    public ElasticsearchKeywordSearchService(
            ElasticsearchClient client,
            @Value("${deepresearch.elasticsearch.index-name:deepresearch_chunks}") String indexName) {
        this.client = client;
        this.indexName = indexName;
    }

    @Override
    public void index(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        ensureIndex();
        try {
            BulkResponse response = client.bulk(b -> {
                for (Document chunk : chunks) {
                    IndexedChunk doc = new IndexedChunk(
                            chunk.getId(),
                            titleOf(chunk),
                            chunk.getText(),
                            cleanBody(chunk.getText()),
                            stringMeta(chunk, "docId"),
                            stringMeta(chunk, "chunkId"),
                            stringMeta(chunk, "filename"),
                            stringMeta(chunk, "sectionPath"),
                            intMeta(chunk, "chunkIndex"),
                            intMeta(chunk, "pageNumber"),
                            intMeta(chunk, "version"),
                            stringMeta(chunk, "sourceType"));
                    b.operations(op -> op.index(i -> i
                            .index(indexName)
                            .id(doc.getId())
                            .document(doc)));
                }
                return b;
            });
            if (response.errors()) {
                response.items().stream()
                        .filter(item -> item.error() != null)
                        .findFirst()
                        .ifPresent(item -> log.warn("Elasticsearch bulk index 部分失败: {}", item.error().reason()));
            }
            log.debug("Elasticsearch 已索引 {} 个知识库分片", chunks.size());
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("写入 Elasticsearch 关键词索引失败", e);
        }
    }

    @Override
    public List<KeywordSearchHit> search(String question, int limit) {
        ensureIndex();
        try {
            SearchResponse<IndexedChunk> response = searchCombined(question, limit);

            return response.hits().hits().stream()
                    .map(this::toHit)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("查询 Elasticsearch 关键词索引失败", e);
        }
    }

    private SearchResponse<IndexedChunk> searchCombined(String question, int limit) throws IOException {
        List<String> exactTerms = extractExactIdentifiers(question);
        return client.search(s -> s
                        .index(indexName)
                        .size(limit)
                        .query(q -> q.bool(b -> {
                            b.should(sh -> sh.multiMatch(mm -> mm
                                    .query(question)
                                    .fields("title^4", "sectionPath^2", "body^2", "content")));
                            b.should(sh -> sh.matchPhrase(mp -> mp
                                    .field("title")
                                    .query(question)
                                    .boost(6.0f)));
                            b.should(sh -> sh.matchPhrase(mp -> mp
                                    .field("body")
                                    .query(question)
                                    .boost(3.0f)));
                            b.should(sh -> sh.matchPhrase(mp -> mp
                                    .field("content")
                                    .query(question)
                                    .boost(1.5f)));
                            b.should(sh -> sh.matchPhrase(mp -> mp
                                    .field("sectionPath")
                                    .query(question)
                                    .boost(3.0f)));
                            for (String term : exactTerms) {
                                String wildcard = "*" + term.toLowerCase() + "*";
                                b.should(sh -> sh.wildcard(w -> w
                                        .field("title.keyword")
                                        .caseInsensitive(true)
                                        .value(wildcard)
                                        .boost(10.0f)));
                                b.should(sh -> sh.wildcard(w -> w
                                        .field("body.keyword")
                                        .caseInsensitive(true)
                                        .value(wildcard)
                                        .boost(6.0f)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("body")
                                        .query(term)
                                        .boost(6.0f)));
                                b.should(sh -> sh.matchPhrase(mp -> mp
                                        .field("content")
                                        .query(term)
                                        .boost(3.0f)));
                            }
                            return b.minimumShouldMatch("1");
                        })),
                IndexedChunk.class);
    }

    @Override
    public void deleteByDocId(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        ensureIndex();
        try {
            client.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.term(t -> t
                            .field("docId")
                            .value(docId))));
            client.indices().refresh(r -> r.index(indexName));
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("按 docId 删除 Elasticsearch 关键词索引失败: " + docId, e);
        }
    }

    @Override
    public void clear() {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                client.deleteByQuery(d -> d
                        .index(indexName)
                        .query(q -> q.matchAll(m -> m)));
                client.indices().refresh(r -> r.index(indexName));
            }
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("清空 Elasticsearch 关键词索引失败", e);
        }
    }

    private KeywordSearchHit toHit(Hit<IndexedChunk> hit) {
        IndexedChunk source = hit.source();
        if (source == null) {
            return null;
        }
        return new KeywordSearchHit(
                hit.id(),
                source.getTitle(),
                source.getContent(),
                source.getDocId(),
                source.getChunkId(),
                source.getFilename(),
                source.getSectionPath(),
                source.getChunkIndex(),
                source.getPageNumber(),
                hit.score() == null ? 0.0 : hit.score());
    }

    private void ensureIndex() {
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                return;
            }
            client.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m
                            .properties("id", p -> p.keyword(k -> k))
                            .properties("docId", p -> p.keyword(k -> k))
                            .properties("chunkId", p -> p.keyword(k -> k))
                            .properties("filename", p -> p.keyword(k -> k))
                            .properties("title", p -> p.text(t -> t
                                    .analyzer("standard")
                                    .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256)))))
                            .properties("sectionPath", p -> p.text(t -> t
                                    .analyzer("standard")
                                    .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(512)))))
                            .properties("content", p -> p.text(t -> t.analyzer("standard")))
                            .properties("body", p -> p.text(t -> t
                                    .analyzer("standard")
                                    .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(4096)))))
                            .properties("chunkIndex", p -> p.integer(i -> i))
                            .properties("pageNumber", p -> p.integer(i -> i))
                            .properties("version", p -> p.integer(i -> i))
                            .properties("sourceType", p -> p.keyword(k -> k))));
            log.info("Elasticsearch index 已创建: {}", indexName);
        } catch (IOException | ElasticsearchException e) {
            throw new IllegalStateException("初始化 Elasticsearch index 失败: " + indexName, e);
        }
    }

    private List<String> extractExactIdentifiers(String question) {
        return ExactIdentifierSupport.extractSearchTerms(question);
    }

    private String titleOf(Document chunk) {
        return String.valueOf(chunk.getMetadata().getOrDefault("title", "知识库片段"));
    }

    private String cleanBody(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int bodyStart = content.indexOf("正文：");
        if (bodyStart >= 0) {
            return content.substring(bodyStart + "正文：".length()).trim();
        }
        return content.trim();
    }

    private String stringMeta(Document chunk, String key) {
        Object value = chunk.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Integer intMeta(Document chunk, String key) {
        Object value = chunk.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * Elasticsearch Java Client 反序列化需要无参构造器 + getter/setter。
     */
    public static class IndexedChunk {
        private String id;
        private String title;
        private String content;
        private String body;
        private String docId;
        private String chunkId;
        private String filename;
        private String sectionPath;
        private Integer chunkIndex;
        private Integer pageNumber;
        private Integer version;
        private String sourceType;

        public IndexedChunk() {
        }

        public IndexedChunk(String id, String title, String content, String body, String docId, String chunkId, String filename,
                            String sectionPath, Integer chunkIndex, Integer pageNumber,
                            Integer version, String sourceType) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.body = body;
            this.docId = docId;
            this.chunkId = chunkId;
            this.filename = filename;
            this.sectionPath = sectionPath;
            this.chunkIndex = chunkIndex;
            this.pageNumber = pageNumber;
            this.version = version;
            this.sourceType = sourceType;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        public String getDocId() {
            return docId;
        }

        public void setDocId(String docId) {
            this.docId = docId;
        }

        public String getChunkId() {
            return chunkId;
        }

        public void setChunkId(String chunkId) {
            this.chunkId = chunkId;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public String getSectionPath() {
            return sectionPath;
        }

        public void setSectionPath(String sectionPath) {
            this.sectionPath = sectionPath;
        }

        public Integer getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(Integer chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public Integer getPageNumber() {
            return pageNumber;
        }

        public void setPageNumber(Integer pageNumber) {
            this.pageNumber = pageNumber;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }
    }
}
