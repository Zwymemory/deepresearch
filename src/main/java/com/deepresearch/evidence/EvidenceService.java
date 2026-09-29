package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import static com.deepresearch.evidence.EvidenceJson.*;

public final class EvidenceService {
    private final EvidenceAuthority authority;
    private final EvidenceStore store;
    private final SourceReader reader;
    private final EvidenceAdjudicator adjudicator = new EvidenceAdjudicator();
    public EvidenceService(EvidenceAuthority authority, EvidenceStore store, SourceReader reader) {
        this.authority = authority; this.store = store; this.reader = reader;
    }
    private EvidenceAuthority.Grant grant(String authorization, String operation, EvidenceDtos.Identifiers ids) {
        if (ids == null) throw EvidenceException.denied();
        id(ids.project_id()); id(ids.run_id()); id(ids.task_id()); id(ids.call_id());
        try { UUID.fromString(ids.claim_token()); } catch (RuntimeException invalid) { throw EvidenceException.denied(); }
        var g = authority.authorize(authorization, operation, ids);
        if (g == null || g.principal() == null || !ids.project_id().equals(g.projectId()) || !ids.run_id().equals(g.runId())
                || !ids.task_id().equals(g.taskId()) || !ids.call_id().equals(g.callId()) || !ids.claim_token().equals(g.claimToken())) throw EvidenceException.denied();
        id(g.principal().tenantId()); id(g.principal().userId()); UUID.fromString(g.claimToken()); active(g); return g;
    }
    private void active(EvidenceAuthority.Grant g) { if (!authority.active(g)) throw EvidenceException.denied(); }
    private <T> T tx(EvidenceAuthority.Grant g, java.util.function.Supplier<T> work) {
        return store.transaction(g, () -> { active(g); return work.get(); });
    }
    public JsonNode read(String authorization, EvidenceDtos.ReadRequest request) {
        var g = grant(authorization, "read_source", request.identifiers());
        String sourceId = id(request.source_id()); var candidate = authority.candidate(g, sourceId);
        if (candidate == null || !sourceId.equals(candidate.sourceId()) || candidate.parentReceiptId() == null || candidate.parentReceiptId().isBlank()) throw EvidenceException.denied();
        String fingerprint = sha(canonical(object("source_id", sourceId, "candidate", candidate)));
        var begin = tx(g, () -> store.beginRead(g, sourceId, candidate.parentReceiptId(), fingerprint));
        if (!fingerprint.equals(begin.fingerprint())) throw new EvidenceException("READ_IDEMPOTENCY_CONFLICT");
        if (begin.status().equals("COMPLETED")) { verifiedEvidence(g, begin.record()); return begin.record(); }
        if (!begin.status().equals("NEW")) throw new EvidenceException("READ_RESULT_UNKNOWN");
        try {
            var document = reader.read(g, candidate); active(g);
            String original = text(document.text(), 10000);
            if (document.observedAt() == null || document.observedAt().isAfter(Instant.now().plusSeconds(5))) throw new EvidenceException("SOURCE_TIME_INVALID");
            String hash = sha(original); JsonNode version = declaredVersion(original);
            var source = object("source_id", sourceId, "kind", candidate.kind(), "title", candidate.title() == null || candidate.title().isBlank() ? "Untitled source" : text(candidate.title(), 10000),
                    "locator", document.locator(), "version", version, "published_at", unknown("Publication date was not independently established"),
                    "observed_at", document.observedAt().toString(), "source_group", known("content-" + hash),
                    "derivation", "unknown", "parent_source_id", null, "authority", "unassessed");
            var evidence = scoped("Evidence", g, "evidence_id", "evidence-" + UUID.randomUUID(), "version", 1,
                    "run_id", g.runId(), "task_id", g.taskId(), "receipt_id", begin.receiptId(), "source", source,
                    "snapshot", object("kind", document.snapshotKind(), "text", original, "sha256", hash, "encoding", "utf-8", "offset_unit", "unicode_codepoint"),
                    "applicability", object("subject", source.path("title").asText(), "version", version, "valid_at", unknown("Document validity period is unknown"), "conditions", List.of("Limited to this original document or recorded observation")),
                    "retrieval_score", unknown("Read operation does not measure retrieval relevance"), "freshness", "fresh", "availability", "available", "validity", "unassessed", "invalidation_reason", null);
            var metadata = object("source_id", sourceId, "snapshot_sha256", hash, "source_metadata_sha256", sha(canonical(source)),
                    "raw_response_sha256", document.rawResponseHash(), "truncated", document.truncated(), "verified_observation", document.verifiedObservation(),
                    "parent_receipt_id", candidate.parentReceiptId());
            return tx(g, () -> {
                authority.commitRead(g, sourceId, evidence, begin.receiptId());
                store.completeRead(g, begin.receiptId(), fingerprint, evidence, metadata); return evidence;
            });
        } catch (RuntimeException failure) {
            try { tx(g, () -> { store.failRead(g, begin.receiptId(), failure instanceof EvidenceException e ? e.code() : "SOURCE_READ_FAILED"); return null; }); }
            catch (RuntimeException stale) { /* A revoked the fence; EXECUTING stays explicitly ambiguous. */ }
            throw failure instanceof EvidenceException ? failure : new EvidenceException("SOURCE_READ_FAILED");
        }
    }
    static JsonNode declaredVersion(String original) {
        var matches = Pattern.compile("(?im)^\\s*(?:Document version|Version|版本)\\s*:\\s*([A-Za-z0-9_.-]{1,64})\\s*$").matcher(original);
        Set<String> values = new HashSet<>(); while (matches.find()) values.add(matches.group(1));
        return values.size() == 1 ? known(values.iterator().next()) : unknown(values.isEmpty() ? "No explicit document version declaration" : "Conflicting version declarations in original text");
    }
    private JsonNode verifiedEvidence(EvidenceAuthority.Grant g, JsonNode evidence) {
        if (!g.runId().equals(evidence.path("run_id").asText()) || !g.projectId().equals(evidence.path("project_id").asText())
                || !g.principal().tenantId().equals(evidence.path("tenant_id").asText()) || !g.principal().userId().equals(evidence.path("owner_id").asText())) throw EvidenceException.denied();
        JsonNode metadata = store.readMetadata(g, evidence.path("receipt_id").asText());
        if (!sha(evidence.path("snapshot").path("text").asText()).equals(evidence.path("snapshot").path("sha256").asText())
                || !metadata.path("snapshot_sha256").asText().equals(evidence.path("snapshot").path("sha256").asText())
                || !metadata.path("source_id").asText().equals(evidence.path("source").path("source_id").asText())
                || !sha(canonical(evidence.path("source"))).equals(metadata.path("source_metadata_sha256").asText())
                || !evidence.path("availability").asText().equals("available") || !evidence.path("validity").asText().equals("unassessed"))
            throw new EvidenceException("EVIDENCE_RECEIPT_BINDING_INVALID");
        return metadata;
    }
    public EvidenceDtos.PreparedCheck prepare(String authorization, EvidenceDtos.PrepareRequest command) {
        var g = grant(authorization, "check_claims", command.identifiers());
        if (command.claims() == null || command.claims().isEmpty() || command.claims().size() > 4 || command.evidence_ids() == null
                || command.evidence_ids().size() > 4 || new HashSet<>(command.evidence_ids()).size() != command.evidence_ids().size()
                || command.dispute_round() < 0 || command.dispute_round() > 2 || (command.dispute_round() == 0) != (command.parent_check_id() == null))
            throw new EvidenceException("CHECK_REQUEST_INVALID");
        for (var spec : command.claims()) {
            text(spec.text(), 4000); if (!Set.of("factual", "inference", "recommendation").contains(spec.kind())) throw new EvidenceException("CHECK_REQUEST_INVALID");
            applicability(spec.applicability());
        }
        String investigation = sha(canonical(JSON.valueToTree(command.claims())));
        String fingerprint = sha(canonical(object("claims", command.claims(), "evidence_ids", command.evidence_ids(), "dispute_round", command.dispute_round(), "parent_check_id", command.parent_check_id())));
        String checkId = "check-" + sha(g.runId() + ":" + g.projectId() + ":" + g.callId() + ":" + fingerprint).substring(0, 48);
        var evidence = new ArrayList<JsonNode>();
        for (String identity : command.evidence_ids()) { var record = store.get(g, "Evidence", id(identity)); verifiedEvidence(g, record); evidence.add(record); }
        var claims = new ArrayList<JsonNode>(); int index = 0;
        for (var spec : command.claims()) claims.add(object("claim_id", "claim-" + checkId + "-" + index++, "text", spec.text(), "kind", spec.kind(), "applicability", spec.applicability()));
        var request = object("protocol_version", "evidence-check/1", "check_id", checkId, "claims", claims, "evidence", evidence,
                "dispute_round", command.dispute_round(), "parent_check_id", command.parent_check_id());
        if (canonical(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) throw new EvidenceException("CHECK_REQUEST_TOO_LARGE");
        String requestHash = sha(canonical(request));
        var stored = tx(g, () -> store.prepare(g, checkId, investigation, command.dispute_round(), command.parent_check_id(), fingerprint, requestHash, request));
        return new EvidenceDtos.PreparedCheck(stored.checkId(), stored.requestHash(), stored.request(), !stored.status().equals("COMPLETED"), stored.result() == null ? null : result(stored.result()));
    }
    public EvidenceDtos.RecordResult complete(String authorization, EvidenceDtos.CompleteRequest command) {
        var g = grant(authorization, "check_claims", command.identifiers());
        var check = store.check(g, id(command.check_id()));
        if (command.response() == null || canonical(command.response()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) throw new EvidenceException("CHECK_RESPONSE_TOO_LARGE");
        String responseHash = sha(canonical(command.response()));
        if (check.status().equals("COMPLETED")) {
            if (!responseHash.equals(check.responseHash())) throw new EvidenceException("CHECK_IDEMPOTENCY_CONFLICT");
            return result(check.result());
        }
        String assessment = id(authority.modelReceipt(g, check.checkId(), check.requestHash(), id(command.model_call_id()), responseHash));
        var metadata = new HashMap<String, JsonNode>();
        for (var evidence : check.request().path("evidence")) {
            var current = store.get(g, "Evidence", evidence.path("evidence_id").asText());
            if (!canonical(current).equals(canonical(evidence))) throw new EvidenceException("CHECK_SNAPSHOT_CHANGED");
            metadata.put(evidence.path("evidence_id").asText(), verifiedEvidence(g, current));
        }
        var outcome = adjudicator.adjudicate(g, check.request(), command.response(), assessment, metadata);
        return tx(g, () -> {
            outcome.records().forEach(row -> store.put(g, row));
            store.completeCheck(g, check.checkId(), responseHash, assessment, JSON.valueToTree(outcome)); return outcome;
        });
    }
    public JsonNode packet(String authorization, EvidenceDtos.PacketRequest command) {
        var g = grant(authorization, "record_packet", command.identifiers());
        if (command.check_ids() == null || command.check_ids().isEmpty() || command.check_ids().size() > 4
                || new HashSet<>(command.check_ids()).size() != command.check_ids().size()) throw new EvidenceException("PACKET_INVALID");
        var claims = new LinkedHashSet<String>(); var decisions = new LinkedHashSet<String>();
        var evidence = new LinkedHashSet<String>(); var challenges = new LinkedHashSet<String>(); var gaps = new LinkedHashSet<String>();
        for (String checkId : command.check_ids()) {
            var checked = store.check(g, id(checkId)); if (!checked.status().equals("COMPLETED")) throw new EvidenceException("PACKET_CHECK_INCOMPLETE");
            for (JsonNode record : result(checked.result()).records()) {
                switch (record.path("record_type").asText()) {
                    case "Claim" -> { claims.add(record.path("claim_id").asText()); record.path("evidence_links").forEach(link -> evidence.add(link.path("evidence_id").asText())); }
                    case "DecisionRecord" -> { decisions.add(record.path("decision_id").asText()); record.path("gaps").forEach(gap -> gaps.add(gap.asText())); }
                    case "Challenge" -> challenges.add(record.path("challenge_id").asText());
                    default -> throw new EvidenceException("PACKET_INVALID");
                }
            }
        }
        String packetId = "packet-" + sha(g.runId() + ":" + canonical(JSON.valueToTree(command.check_ids()))).substring(0, 48);
        try { return store.get(g, "ResearchPacket", packetId); }
        catch (EvidenceException absent) { if (!absent.code().equals("EVIDENCE_ACCESS_DENIED")) throw absent; }
        var packet = scoped("ResearchPacket", g, "packet_id", packetId, "run_id", g.runId(), "task_id", g.taskId(), "context_id", null,
                "status", gaps.isEmpty() ? "complete" : "partial", "claim_ids", claims, "evidence_ids", evidence, "decision_ids", decisions, "challenge_ids", challenges,
                "limitations", List.of("Verifier relations are proposals, not a universal factual truth guarantee", "Source version declarations and test observations have their recorded scope only"),
                "gaps", gaps, "recorded_at", Instant.now().toString());
        return tx(g, () -> { store.put(g, packet); return packet; });
    }
    /** Returns quote-bound publication data; A remains the only answer publisher. */
    public JsonNode publish(String authorization, EvidenceDtos.PublishRequest command) {
        var g = grant(authorization, "publish_evidence", command.identifiers());
        var packet = store.get(g, "ResearchPacket", id(command.packet_id()));
        if (command.claim_ids() == null || command.claim_ids().isEmpty() || command.claim_ids().size() > 4
                || new HashSet<>(command.claim_ids()).size() != command.claim_ids().size()) throw new EvidenceException("PUBLICATION_INVALID");
        Set<String> available = new HashSet<>(); packet.path("claim_ids").forEach(c -> available.add(c.asText()));
        var published = new ArrayList<JsonNode>();
        for (String claimId : command.claim_ids()) {
            if (!available.contains(claimId)) throw new EvidenceException("PUBLICATION_INVALID");
            var claim = store.get(g, "Claim", claimId);
            if (!claim.path("decision_status").asText().equals("supported")) throw new EvidenceException("PUBLICATION_NOT_SUPPORTED");
            JsonNode decision = store.get(g, "DecisionRecord", "decision-" + claimId);
            if (!decision.path("decision_status").asText().equals("supported") || !decision.path("unresolved_evidence_ids").isEmpty()) throw new EvidenceException("PUBLICATION_NOT_SUPPORTED");
            var quotes = new ArrayList<JsonNode>();
            for (var adopted : decision.path("adopted_evidence_ids")) {
                var source = store.get(g, "Evidence", adopted.asText()); verifiedEvidence(g, source);
                var candidate = authority.candidate(g, source.path("source").path("source_id").asText());
                if (candidate == null) throw EvidenceException.denied();
                if (source.path("source").path("kind").asText().equals("knowledge")) {
                    var current = reader.read(g, candidate);
                    if (!sha(current.text()).equals(source.path("snapshot").path("sha256").asText())) throw new EvidenceException("PUBLICATION_SOURCE_CHANGED");
                }
                for (var link : claim.path("evidence_links")) if (link.path("evidence_id").asText().equals(adopted.asText())) {
                    EvidenceAdjudicator.quote(source, link.path("quote"));
                    quotes.add(object("evidence_id", adopted.asText(), "source", source.path("source"), "quote", link.path("quote"), "receipt_id", source.path("receipt_id").asText()));
                }
            }
            if (quotes.isEmpty()) throw new EvidenceException("PUBLICATION_INVALID");
            published.add(object("claim", claim, "decision", decision, "citations", quotes));
        }
        var references = new LinkedHashMap<String, JsonNode>(); StringBuilder answer = new StringBuilder();
        for (JsonNode item : published) {
            String claimText = com.deepresearch.agent.ToolOutputSanitizer.neutralizeCitationMarkers(item.path("claim").path("text").asText());
            answer.append(claimText);
            for (JsonNode citation : item.path("citations")) {
                String identity = citation.path("evidence_id").asText(); references.putIfAbsent(identity, citation);
                int number = new ArrayList<>(references.keySet()).indexOf(identity) + 1;
                answer.append(" [来源").append(number).append(']');
            }
            answer.append('\n');
        }
        active(g); return object("packet_id", command.packet_id(), "run_id", g.runId(), "claims", published,
                "answer", answer.toString().strip(), "answer_sha256", sha(answer.toString().strip()),
                "citations", references.values(), "semantic_truth_guaranteed", false);
    }
    private static EvidenceDtos.RecordResult result(JsonNode value) { return JSON.convertValue(value, EvidenceDtos.RecordResult.class); }
    private static void applicability(JsonNode value) {
        keys(value, "subject", "version", "valid_at", "conditions"); text(field(value, "subject"), 4000);
        tagged(value.path("version"), false); tagged(value.path("valid_at"), true); EvidenceAdjudicator.strings(value.path("conditions"), 20, 1000);
    }
    private static void tagged(JsonNode value, boolean time) {
        String state = value.path("status").asText();
        if (state.equals("unknown")) { keys(value, "status", "value", "reason"); if (!value.path("value").isNull()) throw new EvidenceException("CHECK_REQUEST_INVALID"); text(field(value, "reason"), 1000); }
        else if (state.equals("known")) { keys(value, "status", "value"); String known = field(value, "value"); if (time) {
            try { OffsetDateTime.parse(known); } catch (Exception invalid) { throw new EvidenceException("CHECK_REQUEST_INVALID"); }
        }} else throw new EvidenceException("CHECK_REQUEST_INVALID");
    }
}
