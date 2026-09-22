package com.deepresearch.service;

/** A successful HTTP response carrying a RAGFlow business error. */
public class RagflowApiException extends IllegalStateException {
    private final int code;
    public RagflowApiException(int code) {
        super("RAGFlow response code " + code);
        this.code = code;
    }
    public int code() { return code; }
}
