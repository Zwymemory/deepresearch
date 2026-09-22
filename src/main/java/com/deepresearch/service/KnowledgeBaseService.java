package com.deepresearch.service;

import com.deepresearch.model.IngestResult;
import com.deepresearch.model.IngestStatus;
import com.deepresearch.model.KnowledgeChunk;
import com.deepresearch.model.KnowledgeChunkSummary;
import com.deepresearch.model.KnowledgeDocumentDetail;
import com.deepresearch.model.KnowledgeDocumentSummary;
import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * W5：生产级知识库入库编排。
 *
 * 核心职责：解析文档、结构化切分、版本/去重、pgvector + Elasticsearch 双写、文档生命周期管理。
 * Spring AI 的 vector_store 仍由 VectorStore 管理，W5 只通过 metadata 建立 docId/chunkId/version 等关联。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final KeywordSearchService keywordSearchService;
    private final DocumentParserService parserService;
    private final StructuralChunker chunker;
    private final KnowledgeRetrievalGateway retrievalGateway;
    private final RagflowIngestionService ragflowIngestion;
    private final TransactionTemplate transactions;

    public KnowledgeBaseService(VectorStore vectorStore,
                                JdbcTemplate jdbcTemplate,
                                KeywordSearchService keywordSearchService,
                                DocumentParserService parserService,
                                StructuralChunker chunker,
                                KnowledgeRetrievalGateway retrievalGateway,
                                RagflowIngestionService ragflowIngestion,
                                ObjectProvider<PlatformTransactionManager> transactions) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.keywordSearchService = keywordSearchService;
        this.parserService = parserService;
        this.chunker = chunker;
        this.retrievalGateway = retrievalGateway;
        this.ragflowIngestion = ragflowIngestion;
        PlatformTransactionManager manager = transactions.getIfAvailable();
        this.transactions = manager == null ? null : new TransactionTemplate(manager);
    }

    public IngestResult ingest(String title, String text) {
        ParsedDocument document = parserService.parseText(title, text);
        return ingestParsed(document);
    }

    public IngestResult ingestFile(String title, String filename, byte[] bytes) {
        ParsedDocument document = parserService.parseFile(title, filename, bytes);
        if (retrievalGateway.ragflow()) return ragflowIngestion.ingest(document, false, bytes, filename);
        return ingestParsed(document);
    }

    public IngestResult ingestParsed(ParsedDocument document) {
        if (retrievalGateway.ragflow()) return ragflowIngestion.ingest(document, false);
        return transactions == null ? ingestParsed(document, false) : transactions.execute(status -> ingestParsed(document, false));
    }

    private IngestResult ingestParsed(ParsedDocument document, boolean forceReindex) {
        String contentHash = StructuralChunker.sha256(document.rawContent());
        Optional<KnowledgeDocumentSummary> existing = findByTitleAndFilename(document.title(), document.filename());
        if (existing.isPresent()
                && existing.get().contentHash().equals(contentHash)
                && existing.get().status() == IngestStatus.DONE
                && !forceReindex) {
            KnowledgeDocumentSummary old = existing.get();
            return new IngestResult(old.docId(), IngestStatus.UNCHANGED, old.version(), old.chunkCount(),
                    "文档内容未变化，跳过 embedding 和索引重建");
        }

        String docId = existing.map(KnowledgeDocumentSummary::docId).orElseGet(() -> "doc-" + UUID.randomUUID());
        int version = existing.map(summary -> summary.version() + 1).orElse(1);
        upsertDocument(docId, document, contentHash, version, 0, IngestStatus.PENDING, null);
        String jobId = createJob(docId, IngestStatus.PARSING, "PARSING");

        try {
            updateJob(jobId, IngestStatus.CHUNKING, "CHUNKING", null, false);
            List<KnowledgeChunk> chunks = chunker.chunk(docId, document);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文档没有可入库的有效内容");
            }

            deleteChunks(docId);

            updateDocumentStatus(docId, IngestStatus.EMBEDDING, null);
            updateJob(jobId, IngestStatus.EMBEDDING, "EMBEDDING", null, false);
            List<Document> vectorDocuments = toVectorDocuments(docId, document, contentHash, version, chunks);
            vectorStore.add(vectorDocuments);

            updateDocumentStatus(docId, IngestStatus.INDEXING, null);
            updateJob(jobId, IngestStatus.INDEXING, "INDEXING", null, false);
            keywordSearchService.index(vectorDocuments);

            updateDocumentDone(docId, chunks.size());
            updateJob(jobId, IngestStatus.DONE, "DONE", null, true);
            log.debug("W5 入库完成：docId={}, title={}, version={}, chunks={}",
                    docId, document.title(), version, chunks.size());
            return new IngestResult(docId, IngestStatus.DONE, version, chunks.size(), "入库完成");
        } catch (RuntimeException e) {
            updateDocumentStatus(docId, IngestStatus.FAILED, e.getMessage());
            updateJob(jobId, IngestStatus.FAILED, "FAILED", e.getMessage(), true);
            throw e;
        }
    }

    public List<KnowledgeDocumentSummary> listDocuments() {
        return jdbcTemplate.query("""
                SELECT doc_id, title, source_type, filename, content_hash, version, chunk_count,
                       status, error_message, created_at, updated_at
                FROM kb_document
                ORDER BY updated_at DESC
                """, (rs, rowNum) -> mapDocument(rs));
    }

    public KnowledgeDocumentDetail getDocument(String docId) {
        if (retrievalGateway.ragflow()) ragflowIngestion.reconcile(docId);
        KnowledgeDocumentSummary summary = findByDocId(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
        List<KnowledgeChunkSummary> chunks = retrievalGateway.ragflow() ? List.of() : jdbcTemplate.query("""
                SELECT id::text AS chunk_id,
                       content,
                       coalesce(metadata ->> 'title', '') AS title,
                       coalesce(metadata ->> 'sectionPath', '') AS section_path,
                       NULLIF(metadata ->> 'pageNumber', '')::int AS page_number,
                       NULLIF(metadata ->> 'chunkIndex', '')::int AS chunk_index,
                       NULLIF(metadata ->> 'version', '')::int AS version
                FROM vector_store
                WHERE metadata ->> 'docId' = ?
                ORDER BY NULLIF(metadata ->> 'chunkIndex', '')::int NULLS LAST
                """, (rs, rowNum) -> new KnowledgeChunkSummary(
                rs.getString("chunk_id"),
                rs.getString("title"),
                rs.getString("section_path"),
                (Integer) rs.getObject("page_number"),
                (Integer) rs.getObject("chunk_index"),
                (Integer) rs.getObject("version"),
                preview(rs.getString("content"))), docId);
        return new KnowledgeDocumentDetail(summary, chunks);
    }

    public IngestResult reindexDocument(String docId) {
        DocumentRow row = readDocumentRow(docId);
        ParsedDocument document = parserService.parseStored(row.title(), row.sourceType(), row.filename(), row.rawContent());
        if (retrievalGateway.ragflow()) {
            List<byte[]> files = jdbcTemplate.query("SELECT original_file FROM kb_ragflow_document WHERE legacy_doc_id=?",
                    (rs, n) -> rs.getBytes(1), docId);
            if (!files.isEmpty() && files.get(0) != null)
                return ragflowIngestion.reindexExisting(docId, document, files.get(0), row.filename());
            return ragflowIngestion.reindexExisting(docId, document, null, null);
        }
        return transactions == null ? ingestParsed(document, true) : transactions.execute(status -> ingestParsed(document, true));
    }

    /** Explicit migration of a legacy document, addressed by its stable Java ID. */
    public IngestResult syncRagflowDocument(String docId) {
        DocumentRow row = readDocumentRow(docId);
        ParsedDocument document = parserService.parseStored(row.title(), row.sourceType(), row.filename(), row.rawContent());
        List<byte[]> files = jdbcTemplate.query("SELECT original_file FROM kb_ragflow_document WHERE legacy_doc_id=?",
                (rs, n) -> rs.getBytes(1), docId);
        if (!files.isEmpty() && files.get(0) != null)
            return ragflowIngestion.ingestExisting(docId, document, files.get(0), row.filename());
        return ragflowIngestion.ingestExisting(docId, document);
    }

    public Map<String, Object> ragflowSyncStatus(String docId) {
        ragflowIngestion.reconcile(docId);
        return ragflowIngestion.status(docId);
    }

    public IngestResult deleteDocument(String docId) {
        KnowledgeDocumentSummary summary = findByDocId(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
        if (retrievalGateway.ragflow()) {
            ragflowIngestion.delete(docId);
            return new IngestResult(docId, IngestStatus.DELETED, summary.version(), 0, "文档已删除");
        }
        java.util.function.Supplier<IngestResult> legacyDelete = () -> {
            deleteChunks(docId);
            jdbcTemplate.update("DELETE FROM kb_document WHERE doc_id = ?", docId);
            return new IngestResult(docId, IngestStatus.DELETED, summary.version(), 0, "文档已删除");
        };
        return transactions == null ? legacyDelete.get() : transactions.execute(status -> legacyDelete.get());
    }

    public long count() {
        if (retrievalGateway.ragflow()) {
            Long total = jdbcTemplate.queryForObject("SELECT coalesce(sum(chunk_count),0) FROM kb_document WHERE status='DONE'", Long.class);
            return total == null ? 0 : total;
        }
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        return n == null ? 0 : n;
    }

    @Transactional
    public void clear() {
        if (retrievalGateway.ragflow()) throw new IllegalStateException("RAGFlow 模式不支持批量清空；请逐个删除文档");
        jdbcTemplate.update("DELETE FROM vector_store");
        jdbcTemplate.update("DELETE FROM kb_document");
        keywordSearchService.clear();
        log.debug("知识库已清空");
    }

    public int reindexKeywordIndex() {
        if (retrievalGateway.ragflow()) throw new IllegalStateException("关键词索引重建仅适用于 legacy 模式");
        List<Document> chunks = jdbcTemplate.query("""
                SELECT id::text AS id,
                       content,
                       coalesce(metadata ->> 'title', '') AS title,
                       coalesce(metadata ->> 'docId', '') AS doc_id,
                       coalesce(metadata ->> 'chunkId', '') AS chunk_id,
                       coalesce(metadata ->> 'filename', '') AS filename,
                       coalesce(metadata ->> 'sectionPath', '') AS section_path,
                       NULLIF(metadata ->> 'chunkIndex', '')::int AS chunk_index,
                       NULLIF(metadata ->> 'pageNumber', '')::int AS page_number,
                       NULLIF(metadata ->> 'version', '')::int AS version,
                       coalesce(metadata ->> 'sourceType', '') AS source_type
                FROM vector_store
                """, (rs, rowNum) -> new Document(
                rs.getString("id"),
                rs.getString("content"),
                Map.of(
                        "title", rs.getString("title"),
                        "docId", rs.getString("doc_id"),
                        "chunkId", rs.getString("chunk_id"),
                        "filename", rs.getString("filename"),
                        "sectionPath", rs.getString("section_path"),
                        "chunkIndex", rs.getObject("chunk_index") == null ? "" : rs.getInt("chunk_index"),
                        "pageNumber", rs.getObject("page_number") == null ? "" : rs.getInt("page_number"),
                        "version", rs.getObject("version") == null ? "" : rs.getInt("version"),
                        "sourceType", rs.getString("source_type"))));
        keywordSearchService.clear();
        keywordSearchService.index(chunks);
        log.debug("Elasticsearch 关键词索引重建完成，共 {} 个分片", chunks.size());
        return chunks.size();
    }

    private List<Document> toVectorDocuments(String docId, ParsedDocument document, String docHash,
                                             int version, List<KnowledgeChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new Document(chunk.chunkId(), chunk.content(), chunkMetadata(docId, document, docHash, version, chunk)))
                .toList();
    }

    private Map<String, Object> chunkMetadata(String docId, ParsedDocument document, String docHash,
                                              int version, KnowledgeChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", docId);
        metadata.put("chunkId", chunk.chunkId());
        metadata.put("title", document.title());
        metadata.put("sectionPath", chunk.sectionPath() == null ? "" : chunk.sectionPath());
        metadata.put("pageNumber", chunk.pageNumber() == null ? "" : chunk.pageNumber());
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("contentHash", chunk.contentHash());
        metadata.put("docContentHash", docHash);
        metadata.put("version", version);
        metadata.put("sourceType", document.sourceType().name());
        metadata.put("filename", document.filename());
        return metadata;
    }

    private void deleteChunks(String docId) {
        jdbcTemplate.update("DELETE FROM vector_store WHERE metadata ->> 'docId' = ?", docId);
        keywordSearchService.deleteByDocId(docId);
    }

    private void upsertDocument(String docId, ParsedDocument document, String contentHash, int version,
                                int chunkCount, IngestStatus status, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO kb_document
                    (doc_id, title, source_type, filename, raw_content, content_hash, version,
                     chunk_count, status, error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (doc_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    source_type = EXCLUDED.source_type,
                    filename = EXCLUDED.filename,
                    raw_content = EXCLUDED.raw_content,
                    content_hash = EXCLUDED.content_hash,
                    version = EXCLUDED.version,
                    chunk_count = EXCLUDED.chunk_count,
                    status = EXCLUDED.status,
                    error_message = EXCLUDED.error_message,
                    updated_at = now()
                """, docId, document.title(), document.sourceType().name(), document.filename(), document.rawContent(),
                contentHash, version, chunkCount, status.name(), errorMessage);
    }

    private String createJob(String docId, IngestStatus status, String stage) {
        String jobId = "job-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO kb_ingest_job (job_id, doc_id, status, stage, started_at)
                VALUES (?, ?, ?, ?, now())
                """, jobId, docId, status.name(), stage);
        return jobId;
    }

    private void updateDocumentStatus(String docId, IngestStatus status, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE kb_document
                SET status = ?, error_message = ?, updated_at = now()
                WHERE doc_id = ?
                """, status.name(), errorMessage, docId);
    }

    private void updateDocumentDone(String docId, int chunkCount) {
        jdbcTemplate.update("""
                UPDATE kb_document
                SET status = ?, chunk_count = ?, error_message = NULL, updated_at = now()
                WHERE doc_id = ?
                """, IngestStatus.DONE.name(), chunkCount, docId);
    }

    private void updateJob(String jobId, IngestStatus status, String stage, String errorMessage, boolean finished) {
        jdbcTemplate.update("""
                UPDATE kb_ingest_job
                SET status = ?, stage = ?, error_message = ?, finished_at = CASE WHEN ? THEN now() ELSE finished_at END
                WHERE job_id = ?
                """, status.name(), stage, errorMessage, finished, jobId);
    }

    private Optional<KnowledgeDocumentSummary> findByTitleAndFilename(String title, String filename) {
        List<KnowledgeDocumentSummary> rows = jdbcTemplate.query("""
                SELECT doc_id, title, source_type, filename, content_hash, version, chunk_count,
                       status, error_message, created_at, updated_at
                FROM kb_document
                WHERE title = ? AND filename = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """, (rs, rowNum) -> mapDocument(rs), title, filename);
        return rows.stream().findFirst();
    }

    private Optional<KnowledgeDocumentSummary> findByDocId(String docId) {
        List<KnowledgeDocumentSummary> rows = jdbcTemplate.query("""
                SELECT doc_id, title, source_type, filename, content_hash, version, chunk_count,
                       status, error_message, created_at, updated_at
                FROM kb_document
                WHERE doc_id = ?
                """, (rs, rowNum) -> mapDocument(rs), docId);
        return rows.stream().findFirst();
    }

    private DocumentRow readDocumentRow(String docId) {
        List<DocumentRow> rows = jdbcTemplate.query("""
                SELECT title, source_type, filename, raw_content
                FROM kb_document
                WHERE doc_id = ?
                """, (rs, rowNum) -> new DocumentRow(
                rs.getString("title"),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getString("filename"),
                rs.getString("raw_content")), docId);
        return rows.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
    }

    private KnowledgeDocumentSummary mapDocument(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeDocumentSummary(
                rs.getString("doc_id"),
                rs.getString("title"),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getString("filename"),
                rs.getString("content_hash"),
                rs.getInt("version"),
                rs.getInt("chunk_count"),
                IngestStatus.valueOf(rs.getString("status")),
                rs.getString("error_message"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String preview(String content) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return text.length() <= 180 ? text : text.substring(0, 180) + "...";
    }

    private record DocumentRow(
            String title,
            SourceType sourceType,
            String filename,
            String rawContent
    ) {
    }
}
