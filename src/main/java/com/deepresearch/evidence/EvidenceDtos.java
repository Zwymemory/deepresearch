package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Request bodies select objects, never supply an authenticated owner/tenant. */
public final class EvidenceDtos {
    private EvidenceDtos() { }
    public record Identifiers(String project_id, String run_id, String task_id, String call_id, String claim_token) { }
    public record ReadRequest(Identifiers identifiers, String source_id) { }
    public record ClaimSpec(String text, String kind, JsonNode applicability) { }
    public record PrepareRequest(Identifiers identifiers, List<ClaimSpec> claims,
                                 List<String> evidence_ids, int dispute_round, String parent_check_id) { }
    public record CompleteRequest(Identifiers identifiers, String check_id, String model_call_id,
                                  JsonNode response) { }
    public record PacketRequest(Identifiers identifiers, List<String> check_ids) { }
    public record PublishRequest(Identifiers identifiers, String packet_id, List<String> claim_ids) { }
    public record PreparedCheck(String check_id, String request_sha256, JsonNode request,
                                boolean requires_model, RecordResult immediate_result) { }
    public record RecordResult(List<JsonNode> records, List<JsonNode> follow_up_actions,
                               boolean semantic_truth_guaranteed) { }
}
