package com.deepresearch.model;

public enum IngestStatus {
    PENDING,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    DONE,
    FAILED,
    UNCHANGED,
    DELETED
}
