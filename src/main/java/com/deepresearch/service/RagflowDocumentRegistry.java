package com.deepresearch.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only Java-managed, successfully parsed documents may appear in evidence. */
@Component
public class RagflowDocumentRegistry {
    private final JdbcTemplate db;
    public RagflowDocumentRegistry(JdbcTemplate db) { this.db = db; }

    public Snapshot snapshot(List<String> datasetIds) {
        if (datasetIds.isEmpty()) return Snapshot.empty();
        List<RegisteredDocument> rows = db.query("""
                SELECT m.dataset_id, m.document_id, COALESCE(d.title, '') AS title
                FROM kb_ragflow_document m
                JOIN kb_document d ON d.doc_id=m.legacy_doc_id
                WHERE m.sync_status='DONE' AND d.status='DONE'
                  AND m.document_id IS NOT NULL AND m.dataset_id = ANY(?)
                ORDER BY m.dataset_id, m.document_id
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", datasetIds.toArray(String[]::new))),
                (rs, n) -> new RegisteredDocument(
                        rs.getString("dataset_id"), rs.getString("document_id"), rs.getString("title")));
        return Snapshot.of(rows);
    }

    public boolean active(String datasetId, String documentId) {
        Long count = db.queryForObject("""
                SELECT count(*) FROM kb_ragflow_document m
                JOIN kb_document d ON d.doc_id=m.legacy_doc_id
                WHERE m.dataset_id=? AND m.document_id=? AND m.sync_status='DONE' AND d.status='DONE'
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

    public record DocumentKey(String datasetId, String documentId) { }

    public record RegisteredDocument(String datasetId, String documentId, String title) {
        DocumentKey key() { return new DocumentKey(datasetId, documentId); }
    }

    /** Immutable point-in-time allowlist used throughout one retrieval request. */
    public static final class Snapshot {
        private final Map<DocumentKey, RegisteredDocument> documents;
        private final Set<DocumentKey> activeKeys;
        private final Set<String> documentIds;

        private Snapshot(Map<DocumentKey, RegisteredDocument> documents) {
            this.documents = Collections.unmodifiableMap(new LinkedHashMap<>(documents));
            this.activeKeys = Collections.unmodifiableSet(new LinkedHashSet<>(documents.keySet()));
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            documents.values().forEach(document -> ids.add(document.documentId()));
            this.documentIds = Collections.unmodifiableSet(ids);
        }

        public static Snapshot empty() { return new Snapshot(Map.of()); }

        public static Snapshot of(List<RegisteredDocument> documents) {
            LinkedHashMap<DocumentKey, RegisteredDocument> byKey = new LinkedHashMap<>();
            for (RegisteredDocument document : documents) byKey.put(document.key(), document);
            return new Snapshot(byKey);
        }

        public Set<DocumentKey> activeKeys() { return activeKeys; }
        public Set<String> documentIds() { return documentIds; }

        public String title(DocumentKey key) {
            RegisteredDocument document = documents.get(key);
            return document == null || document.title() == null ? "" : document.title();
        }
    }
}
