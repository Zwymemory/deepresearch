package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Supplier;

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
    void completeCheck(EvidenceAuthority.Grant grant, String checkId, String responseHash, String assessmentId, JsonNode result);
    record ReadState(String receiptId, String status, String fingerprint, JsonNode record) { }
    record CheckState(String checkId, String status, String requestHash, JsonNode request, String responseHash, JsonNode result) { }
}
