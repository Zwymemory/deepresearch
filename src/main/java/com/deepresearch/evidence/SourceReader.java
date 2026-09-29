package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public interface SourceReader {
    Document read(EvidenceAuthority.Grant grant, EvidenceAuthority.Candidate candidate);
    /** original document text (extracted for HTML); never a search summary. */
    record Document(String text, String title, JsonNode locator, String snapshotKind, Instant observedAt,
                    String rawResponseHash, boolean truncated, boolean verifiedObservation) { }
}
