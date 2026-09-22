package com.deepresearch.service;

import com.deepresearch.model.IngestResult;
import com.deepresearch.model.IngestStatus;
import com.deepresearch.model.ParsedDocument;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable RAGFlow ingestion. No database transaction spans a remote request. */
@Service
@EnableScheduling
public class RagflowIngestionService {
    private static final Logger log = LoggerFactory.getLogger(RagflowIngestionService.class);
    private final JdbcTemplate db;
    private final RagflowClient client;
    private final TransactionTemplate localTransactions;

    public RagflowIngestionService(JdbcTemplate db, RagflowClient client) {
        this.db = db;
        this.client = client;
        this.localTransactions = db.getDataSource() == null ? null
                : new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    }

    public IngestResult ingest(ParsedDocument document, boolean force) {
        return ingest(document, force, null, null, null);
    }

    public IngestResult ingest(ParsedDocument document, boolean force, byte[] originalFile, String originalFilename) {
        return ingest(document, force, originalFile, originalFilename, null);
    }

    /** A legacy document can be copied while retrieval.provider remains legacy. */
    public IngestResult ingestExisting(String legacyDocId, ParsedDocument document) {
        return ingest(document, false, null, null, legacyDocId);
    }

    public IngestResult ingestExisting(String legacyDocId, ParsedDocument document,
                                       byte[] originalFile, String originalFilename) {
        return ingest(document, false, originalFile, originalFilename, legacyDocId);
    }

    public IngestResult reindexExisting(String legacyDocId, ParsedDocument document,
                                        byte[] originalFile, String originalFilename) {
        return ingest(document, true, originalFile, originalFilename, legacyDocId);
    }

    private IngestResult ingest(ParsedDocument document, boolean force, byte[] originalFile,
                                String originalFilename, String legacyDocId) {
        client.requireConfigured();
        String hash = originalFile == null ? StructuralChunker.sha256(document.rawContent()) : sha256(originalFile);
        Row old = findDocument(document, legacyDocId);
        if (legacyDocId != null && old == null) throw new IllegalArgumentException("文档不存在: " + legacyDocId);
        String id = old == null ? "doc-" + UUID.randomUUID() : old.id();
        String dataset = old != null && old.datasetId() != null ? old.datasetId() : client.datasets().get(0);
        if (!client.datasets().contains(dataset)) throw new IllegalStateException("RAGFlow dataset is no longer allowed");
        if (old != null && old.previousId() != null) cleanupPrevious(id);
        if (old != null && hash.equals(old.activeHash()) && "DONE".equals(old.syncStatus()) && !force)
            return new IngestResult(id, IngestStatus.UNCHANGED, old.version(), 0, "文档内容未变化");

        if (old == null) {
            db.update("""
                    INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,chunk_count,status,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,1,0,'PENDING',now(),now())
                    """, id, document.title(), document.sourceType().name(), document.filename(), document.rawContent(), hash);
        }
        int version = old == null ? 1 : old.version() + 1;
        String originalName = originalFile == null ? null :
                (originalFilename == null || originalFilename.isBlank() ? document.filename() : originalFilename);
        String remoteName = remoteName(id, version, hash, originalName);
        db.update("""
                INSERT INTO kb_ragflow_sync_job(legacy_doc_id,dataset_id,remote_name,content_hash,version,
                    title,source_type,filename,raw_content,original_file,original_filename,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'PREPARED') ON CONFLICT(legacy_doc_id) DO NOTHING
                """, id, dataset, remoteName, hash, version, document.title(), document.sourceType().name(),
                document.filename(), document.rawContent(), originalFile, originalName);
        Job job = job(id);
        if (job == null || !hash.equals(job.hash()))
            throw new IllegalStateException("该文档已有不同内容的 RAGFlow 同步任务，请先完成或删除它");
        if ("DELETING".equals(job.status())) throw new IllegalStateException("该文档正在删除");
        process(id, true);
        Job remaining = job(id);
        if (remaining == null) return new IngestResult(id, IngestStatus.DONE, version, 0, "RAGFlow 入库完成");
        if ("FAILED".equals(remaining.status()))
            return new IngestResult(id, IngestStatus.FAILED, version, 0, remaining.error());
        return new IngestResult(id, IngestStatus.PARSING, version, 0, "RAGFlow 解析中；轮询同步状态");
    }

    /** Poll a single document, or replay an interrupted upload/parse step. */
    public void reconcile(String id) {
        Job pending = job(id);
        if (pending != null) {
            if ("DELETING".equals(pending.status())) processDelete(id);
            else if (!"FAILED".equals(pending.status())) process(id, false);
            return;
        }
        List<String> deleting = db.query("SELECT legacy_doc_id FROM kb_ragflow_document WHERE legacy_doc_id=? AND sync_status='DELETING'",
                (rs, n) -> rs.getString(1), id);
        if (!deleting.isEmpty()) processDelete(id);
        else cleanupPrevious(id);
    }

    /** This is also used by the ADMIN status endpoint while legacy retrieval is active. */
    public Map<String, Object> status(String id) {
        Job pending = job(id);
        if (pending != null) return Map.of("docId", id, "status", pending.status(), "version", pending.version(),
                "error", pending.error() == null ? "" : pending.error());
        List<String> active = db.query("SELECT sync_status FROM kb_ragflow_document WHERE legacy_doc_id=?",
                (rs, n) -> rs.getString(1), id);
        return Map.of("docId", id, "status", active.isEmpty() ? "NOT_SYNCED" : active.get(0));
    }

    /** The local delete intent is committed before removing either remote version. */
    public void delete(String id) {
        if (localTransactions == null) throw new IllegalStateException("RAGFlow sync requires a DataSource");
        localTransactions.executeWithoutResult(ignored -> {
            int pending = db.update("""
                    UPDATE kb_ragflow_sync_job SET status='DELETING',error_message=NULL,updated_at=now()
                    WHERE legacy_doc_id=? AND (lock_token IS NULL OR lease_until<now())
                    """, id);
            if (pending == 0 && job(id) != null) throw new IllegalStateException("该文档正在同步，请稍后删除");
            db.update("UPDATE kb_ragflow_document SET sync_status='DELETING',updated_at=now() WHERE legacy_doc_id=?", id);
        });
        processDelete(id);
    }

    @Scheduled(fixedDelayString = "${deepresearch.ragflow.reconcile-delay-ms:30000}")
    public void recover() {
        try { client.requireConfigured(); }
        catch (IllegalStateException notConfigured) { return; }
        List<String> ids = db.query("""
                SELECT legacy_doc_id FROM kb_ragflow_sync_job
                WHERE (lock_token IS NULL OR lease_until<now()) AND status<>'FAILED'
                ORDER BY updated_at LIMIT 100
                """, (rs, n) -> rs.getString(1));
        for (String id : ids) recoverOne(id);
        List<String> cleanup = db.query("""
                SELECT legacy_doc_id FROM kb_ragflow_document
                WHERE previous_document_id IS NOT NULL OR sync_status='DELETING'
                ORDER BY updated_at LIMIT 100
                """, (rs, n) -> rs.getString(1));
        for (String id : cleanup) recoverOne(id);
    }

    private void recoverOne(String id) {
        try { reconcile(id); }
        catch (RuntimeException e) { log.warn("RAGFlow 对账待重试: docId={}, reason={}", id, e.getMessage()); }
    }

    private void process(String id, boolean retryFailed) {
        String token = claim(id);
        if (token == null) return; // Another request owns this durable attempt.
        try {
            Job current = job(id);
            if ("FAILED".equals(current.status())) {
                if (!retryFailed) return;
                String restart = current.remoteId() == null ? "PREPARED" : "UPLOADED";
                updateJob(id, token, "UPDATE kb_ragflow_sync_job SET status=?,error_message=NULL,updated_at=now() WHERE legacy_doc_id=? AND lock_token=?",
                        restart, id, token);
                current = job(id);
            }
            if ("PREPARED".equals(current.status())) {
                List<String> found = client.findDocumentsByName(current.dataset(), current.remoteName());
                if (found.size() > 1) throw new IllegalStateException("RAGFlow 中存在多个同名上传，需人工对账: " + current.remoteName());
                String remote = found.isEmpty() ? client.upload(current.dataset(), current.remoteName(),
                        current.originalFile() == null ? current.raw().getBytes(StandardCharsets.UTF_8) : current.originalFile())
                        : found.get(0);
                updateJob(id, token, """
                        UPDATE kb_ragflow_sync_job SET remote_document_id=?,status='UPLOADED',error_message=NULL,updated_at=now()
                        WHERE legacy_doc_id=? AND lock_token=?
                        """, remote, id, token);
                current = job(id);
            }
            if ("UPLOADED".equals(current.status())) {
                // A crash can occur after RAGFlow accepts parse and before this status is saved.
                JsonNode remote = client.document(current.dataset(), current.remoteId());
                String run = remote.path("run").asText("");
                if (List.of("", "UNSTART", "0", "FAIL", "4", "CANCEL", "2").contains(run))
                    client.parse(current.dataset(), current.remoteId());
                updateJob(id, token, """
                        UPDATE kb_ragflow_sync_job SET status='PARSING',error_message=NULL,updated_at=now()
                        WHERE legacy_doc_id=? AND lock_token=?
                        """, id, token);
                db.update("""
                        UPDATE kb_document SET status='PARSING',updated_at=now() WHERE doc_id=? AND status='PENDING'
                        AND NOT EXISTS(SELECT 1 FROM kb_ragflow_document WHERE legacy_doc_id=? AND sync_status='DONE')
                        """, id, id);
                return;
            }
            if ("PARSING".equals(current.status())) {
                JsonNode remote = client.document(current.dataset(), current.remoteId());
                String run = remote.path("run").asText("");
                if ("DONE".equals(run) || "3".equals(run)) promote(current, token, remote.path("chunk_count").asInt(0));
                else if (List.of("FAIL", "4", "CANCEL", "2").contains(run)) {
                    updateJob(id, token, """
                            UPDATE kb_ragflow_sync_job SET status='FAILED',error_message=?,updated_at=now()
                            WHERE legacy_doc_id=? AND lock_token=?
                            """, "RAGFlow 解析失败: " + remote.path("progress_msg").asText(run), id, token);
                    db.update("""
                            UPDATE kb_document SET status='FAILED',error_message=?,updated_at=now() WHERE doc_id=? AND status IN ('PENDING','PARSING')
                            AND NOT EXISTS(SELECT 1 FROM kb_ragflow_document WHERE legacy_doc_id=? AND sync_status='DONE')
                            """, "RAGFlow 解析失败", id, id);
                }
            }
        } catch (RuntimeException e) {
            db.update("UPDATE kb_ragflow_sync_job SET error_message=?,updated_at=now() WHERE legacy_doc_id=? AND lock_token=?",
                    e.getMessage(), id, token);
            throw e;
        } finally {
            db.update("UPDATE kb_ragflow_sync_job SET lock_token=NULL,lease_until=NULL WHERE legacy_doc_id=? AND lock_token=?", id, token);
        }
    }

    private void promote(Job job, String token, int chunks) {
        // An interrupted promotion is safe to replay. The old ID stays in previous_document_id until deleted.
        if (localTransactions == null) throw new IllegalStateException("RAGFlow sync requires a DataSource");
        localTransactions.executeWithoutResult(ignored -> {
            db.update("""
                INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,version,content_hash,
                    original_file,original_filename,sync_status,updated_at)
                VALUES(?,?,?,?,?,?,?,'DONE',now())
                ON CONFLICT(legacy_doc_id) DO UPDATE SET
                    previous_document_id=CASE
                        WHEN kb_ragflow_document.document_id IS DISTINCT FROM excluded.document_id
                        THEN coalesce(kb_ragflow_document.previous_document_id,kb_ragflow_document.document_id)
                        ELSE kb_ragflow_document.previous_document_id END,
                    dataset_id=excluded.dataset_id,document_id=excluded.document_id,version=excluded.version,
                    content_hash=excluded.content_hash,original_file=excluded.original_file,
                    original_filename=excluded.original_filename,sync_status='DONE',error_message=NULL,updated_at=now()
                    """, job.id(), job.dataset(), job.remoteId(), job.version(), job.hash(),
                    job.originalFile(), job.originalFilename());
            db.update("""
                UPDATE kb_document SET title=?,source_type=?,filename=?,raw_content=?,content_hash=?,version=?,
                    chunk_count=?,status='DONE',error_message=NULL,updated_at=now() WHERE doc_id=?
                    """, job.title(), job.sourceType(), job.filename(), job.raw(), job.hash(), job.version(), chunks, job.id());
            updateJob(job.id(), token, "DELETE FROM kb_ragflow_sync_job WHERE legacy_doc_id=? AND lock_token=?", job.id(), token);
        });
        try { cleanupPrevious(job.id()); }
        catch (RuntimeException e) { log.warn("RAGFlow 旧版本待删除: docId={}, reason={}", job.id(), e.getMessage()); }
    }

    private void cleanupPrevious(String id) {
        List<Mapping> mappings = mapping(id);
        if (mappings.isEmpty() || mappings.get(0).previousId() == null) return;
        Mapping mapping = mappings.get(0);
        client.delete(mapping.dataset(), mapping.previousId());
        db.update("UPDATE kb_ragflow_document SET previous_document_id=NULL,updated_at=now() WHERE legacy_doc_id=? AND previous_document_id=?",
                id, mapping.previousId());
    }

    private void processDelete(String id) {
        Job pending = job(id);
        if (pending != null) {
            if (!"DELETING".equals(pending.status())) return;
            String token = claim(id);
            if (token == null) throw new IllegalStateException("该文档的 RAGFlow 同步正在进行");
            try {
                java.util.Set<String> remoteIds = new java.util.LinkedHashSet<>(
                        client.findDocumentsByName(pending.dataset(), pending.remoteName()));
                if (pending.remoteId() != null) remoteIds.add(pending.remoteId());
                for (String remote : remoteIds) client.delete(pending.dataset(), remote);
            } finally {
                db.update("UPDATE kb_ragflow_sync_job SET lock_token=NULL,lease_until=NULL WHERE legacy_doc_id=? AND lock_token=?", id, token);
            }
        }
        for (Mapping mapping : mapping(id)) {
            if (mapping.documentId() != null) client.delete(mapping.dataset(), mapping.documentId());
            if (mapping.previousId() != null) client.delete(mapping.dataset(), mapping.previousId());
        }
        db.update("DELETE FROM kb_document WHERE doc_id=?", id);
    }

    private Row findDocument(ParsedDocument document, String id) {
        String where = id == null ? "d.title=? AND d.filename=?" : "d.doc_id=?";
        Object[] args = id == null ? new Object[]{document.title(), document.filename()} : new Object[]{id};
        List<Row> rows = db.query("""
                SELECT d.doc_id,d.version,m.content_hash,m.sync_status,m.dataset_id,m.previous_document_id
                FROM kb_document d LEFT JOIN kb_ragflow_document m ON m.legacy_doc_id=d.doc_id
                WHERE %s ORDER BY d.updated_at DESC LIMIT 1
                """.formatted(where), (rs, n) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Job job(String id) {
        List<Job> rows = db.query("""
                SELECT legacy_doc_id,dataset_id,remote_name,remote_document_id,content_hash,version,
                    title,source_type,filename,raw_content,original_file,original_filename,status,error_message
                FROM kb_ragflow_sync_job WHERE legacy_doc_id=?
                """, (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getBytes(11), rs.getString(12), rs.getString(13), rs.getString(14)), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Mapping> mapping(String id) {
        return db.query("SELECT dataset_id,document_id,previous_document_id FROM kb_ragflow_document WHERE legacy_doc_id=?",
                (rs, n) -> new Mapping(rs.getString(1), rs.getString(2), rs.getString(3)), id);
    }

    private String claim(String id) {
        String token = UUID.randomUUID().toString();
        int claimed = db.update("""
                UPDATE kb_ragflow_sync_job SET lock_token=?,lease_until=now()+interval '10 minutes'
                WHERE legacy_doc_id=? AND (lock_token IS NULL OR lease_until<now())
                """, token, id);
        return claimed == 1 ? token : null;
    }

    private void updateJob(String id, String token, String sql, Object... args) {
        if (db.update(sql, args) != 1) throw new IllegalStateException("RAGFlow sync lease lost for " + id);
    }

    private static String remoteName(String id, int version, String hash, String originalName) {
        String extension = ".txt";
        if (originalName != null) {
            String lower = originalName.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".pdf")) extension = ".pdf";
            else if (lower.endsWith(".md") || lower.endsWith(".markdown")) extension = ".md";
        }
        return "dr-" + id + "-v" + version + "-" + hash.substring(0, 12) + extension;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private record Row(String id, int version, String activeHash, String syncStatus, String datasetId, String previousId) {}
    private record Mapping(String dataset, String documentId, String previousId) {}
    private record Job(String id, String dataset, String remoteName, String remoteId, String hash, int version,
                       String title, String sourceType, String filename, String raw, byte[] originalFile,
                       String originalFilename, String status, String error) {}
}
