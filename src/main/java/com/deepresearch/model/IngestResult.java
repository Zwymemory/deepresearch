package com.deepresearch.model;

public record IngestResult(
        String docId,
        IngestStatus status,
        int version,
        int chunks,
        String message
) {
}
