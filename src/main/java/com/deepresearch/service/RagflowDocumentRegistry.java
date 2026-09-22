package com.deepresearch.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Only Java-managed, successfully parsed documents may appear in evidence. */
@Component
public class RagflowDocumentRegistry {
    private final JdbcTemplate db;
    public RagflowDocumentRegistry(JdbcTemplate db) { this.db = db; }

    public List<String> activeDocumentIds(List<String> datasetIds) {
        if (datasetIds.isEmpty()) return List.of();
        return db.query("""
                SELECT document_id FROM kb_ragflow_document
                WHERE sync_status='DONE' AND document_id IS NOT NULL AND dataset_id = ANY(?)
                ORDER BY document_id
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", datasetIds.toArray(String[]::new))),
                (rs, n) -> rs.getString(1));
    }

    public boolean active(String datasetId, String documentId) {
        Long count = db.queryForObject("""
                SELECT count(*) FROM kb_ragflow_document
                WHERE dataset_id=? AND document_id=? AND sync_status='DONE'
                """, Long.class, datasetId, documentId);
        return count != null && count > 0;
    }

    public String title(String datasetId, String documentId) {
        List<String> titles = db.query("""
                SELECT d.title FROM kb_document d
                JOIN kb_ragflow_document m ON m.legacy_doc_id=d.doc_id
                WHERE m.dataset_id=? AND m.document_id=? AND m.sync_status='DONE'
                """, (rs, n) -> rs.getString(1), datasetId, documentId);
        return titles.isEmpty() ? "" : titles.get(0);
    }
}
