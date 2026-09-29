package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Supplier;
import java.util.List;

public interface EvidenceStore {
    <T> T transaction(EvidenceAuthority.Grant grant, Supplier<T> work);
    ReadState beginRead(EvidenceAuthority.Grant grant, String sourceId, String parentReceipt, String fingerprint);
    void completeRead(EvidenceAuthority.Grant grant, String receiptId, String fingerprint, JsonNode record, JsonNode metadata);
    void failRead(EvidenceAuthority.Grant grant, String receiptId, String code);
    JsonNode readMetadata(EvidenceAuthority.Grant grant, String receiptId);
    void put(EvidenceAuthority.Grant grant, JsonNode record);
    JsonNode get(EvidenceAuthority.Grant grant, String type, String id);
    CheckState prepare(EvidenceAuthority.Grant grant, String checkId, String investigation, int round, String parentId,
                       String fingerprint, String requestHash, JsonNode request);
    CheckState check(EvidenceAuthority.Grant grant, String checkId);
    /** All immutable check histories in this authenticated run, including unfinished attempts. */
    List<CheckEntry> checks(EvidenceAuthority.Grant grant);
    void block(EvidenceAuthority.Grant grant, JsonNode attempt);
    List<JsonNode> blocked(EvidenceAuthority.Grant grant);
    void completeCheck(EvidenceAuthority.Grant grant, String checkId, String responseHash, String assessmentId, JsonNode result);
    record ReadState(String receiptId, String status, String fingerprint, JsonNode record) { }
    record CheckState(String checkId, String status, String requestHash, JsonNode request, String responseHash, JsonNode result) { }
    record CheckEntry(String investigation, String taskId, int round, CheckState check) { }
}
