package com.deepresearch.evidence.publicview;

import java.util.List;

/** Deliberately contains no JsonNode/Map fields or internal execution credentials. */
public final class EvidenceViewDtos {
    private EvidenceViewDtos() { }
    public record View(String schemaVersion, String runId, String runStatus, String availability,
                       String publicationState, Limits limits, List<String> limitations,
                       List<EvidenceRef> evidence, List<Claim> claims, List<Decision> decisions,
                       List<Check> checks, List<Disagreement> disagreements, List<Blocked> blockedAttempts) { }
    public record Limits(int records, int checks, int sourceReads, int blockedAttempts, int responseBytes,
                         boolean completeProjection) { }
    public record Identity(String recordType, String recordId, int version, String payloadSha256, String recordedAt) { }
    public record Tagged(String status, String value) { }
    public record Applicability(Tagged version, Tagged validAt, List<String> conditions) { }
    public record EvidenceRef(Identity identity, String sourceId, String kind, String title, String url,
                              Tagged publishedAt, String observedAt, String snapshotSha256,
                              Applicability applicability) { }
    public record Quote(int start, int end, String sha256, String text, String textAvailability) { }
    public record Link(String evidenceId, int evidenceVersion, String relation, String disposition, Quote quote) { }
    public record Claim(Identity identity, String text, String kind, Applicability applicability,
                        String decisionStatus, String checkId, boolean latestRecordedRound,
                        String publicationState, List<Link> evidenceLinks) { }
    public record Dismissed(String evidenceId) { }
    public record Decision(Identity identity, String claimId, String decisionStatus, String policyVersion,
                           List<String> adoptedEvidenceIds, List<String> unresolvedEvidenceIds,
                           List<Dismissed> dismissedEvidence, List<String> gapCodes) { }
    public record Check(String checkId, String investigationId, int disputeRound, String parentCheckId,
                        String status, String requestSha256, String recordedAt, String completedAt,
                        boolean latestRecordedRound, List<String> claimIds, List<String> evidenceIds) { }
    public record Disagreement(String claimId, String decisionId, String checkId,
                               List<String> supportingEvidenceIds, List<String> refutingEvidenceIds) { }
    public record Blocked(String attemptId, String investigationId, int disputeRound, String errorCode,
                          String payloadSha256, String recordedAt, boolean resolvedByLaterCheck) { }
}
