package com.deepresearch.evidence;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Safe scalar failures; never log a URL, source body, token or model response. */
public final class EvidenceException extends ResponseStatusException {
    private final String code;
    public EvidenceException(String code) { this(code, HttpStatus.UNPROCESSABLE_ENTITY); }
    public EvidenceException(String code, HttpStatus status) { super(status, code); this.code = code; }
    public String code() { return code; }
    public static EvidenceException denied() { return new EvidenceException("EVIDENCE_ACCESS_DENIED", HttpStatus.NOT_FOUND); }
}
