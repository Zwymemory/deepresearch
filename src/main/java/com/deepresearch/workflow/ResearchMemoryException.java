package com.deepresearch.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Scalar failures only: no source body, locator or credential in the response. */
public final class ResearchMemoryException extends ResponseStatusException {
    private final String code;
    private final boolean requiresReselection;
    public ResearchMemoryException(String code,boolean requiresReselection) {
        super(switch(code) {
            case "RESEARCH_MEMORY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RESEARCH_MEMORY_INPUT_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.CONFLICT;
        },code);
        this.code=code;this.requiresReselection=requiresReselection;
    }
    public String code() { return code; }
    public boolean requiresReselection() { return requiresReselection; }
}
