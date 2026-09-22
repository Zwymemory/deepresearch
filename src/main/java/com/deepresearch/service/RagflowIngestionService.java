package com.deepresearch.service;

import com.deepresearch.model.IngestResult;
import com.deepresearch.model.IngestStatus;
import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Persistent upload state: remote calls are deliberately outside DB transactions. */
@Service
public class RagflowIngestionService {
    private final JdbcTemplate db;
    private final RagflowClient client;

    public RagflowIngestionService(JdbcTemplate db, RagflowClient client) { this.db = db; this.client = client; }

    public IngestResult ingest(ParsedDocument document, boolean force) {
        return ingest(document, force, null, null);
    }

    public IngestResult ingest(ParsedDocument document, boolean force, byte[] originalFile, String originalFilename) {
        client.requireConfigured();
        String hash = originalFile == null ? StructuralChunker.sha256(document.rawContent())
                : sha256(originalFile);
        String dataset = client.datasets().get(0);
        List<Row> matches = db.query("""
                SELECT d.doc_id, d.version, d.content_hash, m.document_id, m.sync_status
                FROM kb_document d LEFT JOIN kb_ragflow_document m ON m.legacy_doc_id=d.doc_id
                WHERE d.title=? AND d.filename=? ORDER BY d.updated_at DESC LIMIT 1
                """, (rs, n) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                document.title(), document.filename());
        Row old = matches.isEmpty() ? null : matches.get(0);
        if (old != null && hash.equals(old.hash) && "DONE".equals(old.status) && !force)
            return new IngestResult(old.id, IngestStatus.UNCHANGED, old.version, 0, "文档内容未变化");
        String id = old == null ? "doc-" + UUID.randomUUID() : old.id;
        int version = old == null ? 1 : old.version + 1;
        db.update("""
                INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,chunk_count,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,0,'PENDING',now(),now())
                ON CONFLICT(doc_id) DO UPDATE SET title=excluded.title,source_type=excluded.source_type,
                filename=excluded.filename,raw_content=excluded.raw_content,content_hash=excluded.content_hash,
                version=excluded.version,status='PENDING',updated_at=now()
                """, id, document.title(), document.sourceType().name(), document.filename(), document.rawContent(), hash, version);
        db.update("""
                INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,version,content_hash,original_file,original_filename,sync_status)
                VALUES(?,?,?,?,?,?,'PENDING') ON CONFLICT(legacy_doc_id) DO UPDATE SET version=excluded.version,
                content_hash=excluded.content_hash,original_file=excluded.original_file,original_filename=excluded.original_filename,
                sync_status='PENDING',error_message=NULL,updated_at=now()
                """, id, dataset, version, hash, originalFile, originalFilename);
        try {
            String filename = originalFile == null ? id + ".txt" : originalFilename;
            String remote = client.upload(dataset, filename, originalFile == null
                    ? document.rawContent().getBytes(StandardCharsets.UTF_8) : originalFile);
            db.update("UPDATE kb_ragflow_document SET previous_document_id=document_id,document_id=?,sync_status='UPLOADED',updated_at=now() WHERE legacy_doc_id=?", remote, id);
            client.parse(dataset, remote);
            db.update("UPDATE kb_ragflow_document SET sync_status='PARSING',updated_at=now() WHERE legacy_doc_id=?", id);
            db.update("UPDATE kb_document SET status='PARSING',updated_at=now() WHERE doc_id=?", id);
            return new IngestResult(id, IngestStatus.PARSING, version, 0, "RAGFlow 解析中；轮询文档详情");
        } catch (RuntimeException failure) {
            db.update("UPDATE kb_ragflow_document SET sync_status='FAILED',error_message=?,updated_at=now() WHERE legacy_doc_id=?", failure.getMessage(), id);
            db.update("UPDATE kb_document SET status='FAILED',error_message=?,updated_at=now() WHERE doc_id=?", failure.getMessage(), id);
            throw failure;
        }
    }

    public void reconcile(String id) {
        List<Mapping> mappings = db.query("SELECT dataset_id,document_id,previous_document_id FROM kb_ragflow_document WHERE legacy_doc_id=?",
                (rs, n) -> new Mapping(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        if (mappings.isEmpty() || mappings.get(0).documentId == null) return;
        Mapping mapping = mappings.get(0);
        JsonNode remote = client.document(mapping.datasetId, mapping.documentId);
        String run = remote.path("run").asText("");
        String status = switch (run) { case "DONE", "3" -> "DONE"; case "FAIL", "4", "CANCEL", "2" -> "FAILED"; default -> "PARSING"; };
        int chunks = remote.path("chunk_count").asInt(0);
        db.update("UPDATE kb_ragflow_document SET sync_status=?,updated_at=now() WHERE legacy_doc_id=?", status, id);
        db.update("UPDATE kb_document SET status=?,chunk_count=?,updated_at=now() WHERE doc_id=?", status, chunks, id);
        if ("DONE".equals(status) && mapping.previousDocumentId != null) {
            client.delete(mapping.datasetId, mapping.previousDocumentId);
            db.update("UPDATE kb_ragflow_document SET previous_document_id=NULL,updated_at=now() WHERE legacy_doc_id=?", id);
        }
    }

    public void delete(String id) {
        List<Mapping> mappings = db.query("SELECT dataset_id,document_id,previous_document_id FROM kb_ragflow_document WHERE legacy_doc_id=?",
                (rs, n) -> new Mapping(rs.getString(1), rs.getString(2), rs.getString(3)), id);
        if (!mappings.isEmpty() && mappings.get(0).documentId != null) client.delete(mappings.get(0).datasetId, mappings.get(0).documentId);
        if (!mappings.isEmpty() && mappings.get(0).previousDocumentId != null) client.delete(mappings.get(0).datasetId, mappings.get(0).previousDocumentId);
        db.update("DELETE FROM kb_document WHERE doc_id=?", id);
    }

    private record Row(String id, int version, String hash, String remoteId, String status) {}
    private record Mapping(String datasetId, String documentId, String previousDocumentId) {}

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
