package com.deepresearch.evidence;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Implemented by the control plane; hashes/model text are never capabilities. */
public interface EvidenceAuthority {
    Grant authorize(String authorization, String operation, EvidenceDtos.Identifiers identifiers);
    boolean active(Grant grant);
    Candidate candidate(Grant grant, String sourceId);
    /** Joins B's database transaction: fence + complete the budgeted read_source receipt. */
    void commitRead(Grant grant, String sourceId, JsonNode evidence, String receiptId);
    /** Verify A's completed budgeted check call binds these exact request/response bytes. */
    String modelReceipt(Grant grant, String checkId, String requestHash, String modelCallId, String responseHash);
    default Observation controlledObservation(Grant grant, Candidate candidate) { throw EvidenceException.denied(); }

    record Grant(AuthPrincipal principal, String projectId, String runId, String taskId,
                 String callId, String claimToken) { }
    record Candidate(String sourceId, String kind, String url, String datasetId, String documentId,
                     String chunkId, String title, String parentReceiptId) { }
    /** Trusted tool observation obtained by the port, never accepted from an HTTP/model body. */
    record Observation(String artifactId, String text, Instant observedAt) { }

    static EvidenceAuthority denyAll() {
        return new EvidenceAuthority() {
            public Grant authorize(String a, String o, EvidenceDtos.Identifiers i) { throw EvidenceException.denied(); }
            public boolean active(Grant g) { return false; }
            public Candidate candidate(Grant g, String s) { throw EvidenceException.denied(); }
            public void commitRead(Grant g, String s, JsonNode e, String r) { throw EvidenceException.denied(); }
            public String modelReceipt(Grant g, String c, String q, String m, String r) { throw EvidenceException.denied(); }
        };
    }
}
