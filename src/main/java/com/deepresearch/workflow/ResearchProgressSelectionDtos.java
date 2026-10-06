package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class ResearchProgressSelectionDtos {
    private ResearchProgressSelectionDtos() {}
    public record SelectionRequest(AuthPrincipal principal,String projectId,String targetSessionId) {}
    public record Selection(JsonNode priorProgress,String projectionSha256,long canonicalBytes) {
        public Selection { priorProgress=priorProgress.deepCopy(); }
        @Override public JsonNode priorProgress() { return priorProgress.deepCopy(); }
    }
    public record ValidationRequest(String runId,UUID claimToken,String projectionSha256) {}
    public record ValidationResult(String projectId,String projectionSha256,Instant checkedAt) {}
}
