package com.deepresearch.service;

/** Evidence v1. IDs come from RAGFlow; sourceId is scoped to one response. */
public record RetrievedEvidence(String sourceId, String citation, String chunkKey,
        String datasetId, String docId, String chunkId, String title, String content,
        Double score, String route, boolean untrusted, Integer pageNumber, String sectionPath) {
    public String persistentSourceId() { return "kb:" + chunkKey; }
}
