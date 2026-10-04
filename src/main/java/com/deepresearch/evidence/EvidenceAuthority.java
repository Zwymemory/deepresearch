package com.deepresearch.evidence;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Implemented by the control plane; hashes/model text are never capabilities. */
public interface EvidenceAuthority {
    Grant authorize(String authorization, String operation, EvidenceDtos.Identifiers identifiers);
    boolean active(Grant grant);
    default void assertCheckCallBinding(Grant grant,String fingerprint) { }
    default boolean obligationChecksRequired(Grant grant) { return false; }
    default JsonNode obligationContext(Grant grant,String manifest,List<EvidenceDtos.ClaimReference> refs) { throw EvidenceException.denied(); }
    Candidate candidate(Grant grant, String sourceId);
    /** Resolve the exact completed search receipt stored by read_source, never the newest hit. */
    default Candidate originalCandidate(Grant grant, String sourceId, String parentReceiptId) { throw EvidenceException.denied(); }
    /** Native control-plane goals; request/model bodies cannot declare their own completion. */
    default List<ReportGoal> reportGoals(Grant grant) { throw EvidenceException.denied(); }
    /** Current server-owned proof covering all research, including unbound and failed investigations. */
    default JsonNode reportState(Grant grant) { throw EvidenceException.denied(); }
    /** Joins B's database transaction: fence + complete the budgeted read_source receipt. */
    void commitRead(Grant grant, String sourceId, JsonNode evidence, String receiptId);
    /** Verify A's completed budgeted check call binds these exact request/response bytes. */
    String modelReceipt(Grant grant, String checkId, String requestHash, String modelCallId, String responseHash);
    default Observation controlledObservation(Grant grant, Candidate candidate) { throw EvidenceException.denied(); }
    /** Reserve a bounded live KB validation through A's ledger, or replay its completed hash. */
    default PublicationReadPermit publicationRead(Grant grant, JsonNode evidence) { throw EvidenceException.denied(); }
    default void completePublicationRead(Grant grant, PublicationReadPermit permit, String snapshotHash, String errorCode) { throw EvidenceException.denied(); }

    record Grant(AuthPrincipal principal, String projectId, String runId, String taskId,
                 String callId, String claimToken) { }
    record Candidate(String sourceId, String kind, String url, String datasetId, String documentId,
                     String chunkId, String title, String parentReceiptId) { }
    /** Trusted tool observation obtained by the port, never accepted from an HTTP/model body. */
    record Observation(String artifactId, String text, Instant observedAt) { }
    record PublicationReadPermit(String operationId, String completedSnapshotHash) { }
    record ReportCriterion(String criterionId, String text, String status,
                           List<String> checkIds, List<String> claimIds, List<String> gaps) { }
    record ReportGoal(String taskId, String text, String status, boolean completionVerified,
                      List<ReportCriterion> criteria, List<String> gaps) {
        /** Legacy status strings do not prove coverage of the native acceptance criteria. */
        public ReportGoal(String taskId, String text, String status) {
            this(taskId, text, status, false, List.of(), List.of("Legacy goal has no verified acceptance-criterion mapping"));
        }
    }

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
