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
                    "applicability", object("subject", source.path("title").asText(), "version", version, "valid_at", declaredTime(original), "conditions", declaredConditions(original)),
                    "retrieval_score", unknown("Read operation does not measure retrieval relevance"), "freshness", "fresh", "availability", "available", "validity", "unassessed", "invalidation_reason", null);
            var metadata = object("source_id", sourceId, "snapshot_sha256", hash, "source_metadata_sha256", sha(canonical(source)),
                    "evidence_sha256", sha(canonical(evidence)), "requested_candidate", candidate,
                    "raw_response_sha256", document.rawResponseHash(), "truncated", document.truncated(), "verified_observation", document.verifiedObservation(),
                    "condition_declaration_status", conditionDeclaration(original).state().name(),
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
    static JsonNode declaredTime(String original) {
        var matches = Pattern.compile("(?im)^\\s*(?:Valid at|有效时间)\\s*:\\s*(\\S+)\\s*$").matcher(original);
        Set<String> values = new HashSet<>();
        while (matches.find()) {
            try { values.add(OffsetDateTime.parse(matches.group(1)).toInstant().toString()); }
            catch (RuntimeException invalid) { return unknown("Invalid effective time declaration"); }
        }
        return values.size() == 1 ? known(values.iterator().next()) : unknown("No unambiguous effective time declaration in original text");
    }
    enum ConditionState { MISSING, DECLARED, AMBIGUOUS }
    record ConditionDeclaration(ConditionState state, List<String> values) { }
    static ConditionDeclaration conditionDeclaration(String original) {
        var matcher = Pattern.compile("(?im)^Document conditions:[ \\t]*([^\\r\\n]*)$").matcher(original);
        var values = new LinkedHashSet<String>(); boolean malformed = false;
        while (matcher.find()) {
            String value = matcher.group(1).strip();
            if (value.isEmpty() || value.codePointCount(0, value.length()) > 1000) malformed = true;
            else values.add(value);
        }
        ConditionState state = malformed || values.size() > 1 ? ConditionState.AMBIGUOUS
                : values.isEmpty() ? ConditionState.MISSING : ConditionState.DECLARED;
        return new ConditionDeclaration(state, List.copyOf(values));
    }
    static List<String> declaredConditions(String original) {
        var declaration = conditionDeclaration(original);
        return switch (declaration.state()) {
            case DECLARED -> declaration.values();
            case AMBIGUOUS -> List.of("Conflicting or invalid document conditions; scope is not established");
            case MISSING -> List.of("Limited to this original document or recorded observation");
        };
    }
    private JsonNode verifiedEvidence(EvidenceAuthority.Grant g, JsonNode evidence) {
        if (!g.runId().equals(evidence.path("run_id").asText()) || !g.projectId().equals(evidence.path("project_id").asText())
                || !g.principal().tenantId().equals(evidence.path("tenant_id").asText()) || !g.principal().userId().equals(evidence.path("owner_id").asText())) throw EvidenceException.denied();
        JsonNode metadata = store.readMetadata(g, evidence.path("receipt_id").asText());
        if (!sha(evidence.path("snapshot").path("text").asText()).equals(evidence.path("snapshot").path("sha256").asText())
                || !metadata.path("snapshot_sha256").asText().equals(evidence.path("snapshot").path("sha256").asText())
                || !metadata.path("source_id").asText().equals(evidence.path("source").path("source_id").asText())
                || !sha(canonical(evidence.path("source"))).equals(metadata.path("source_metadata_sha256").asText())
                || !sha(canonical(evidence)).equals(metadata.path("evidence_sha256").asText())
                || !evidence.path("availability").asText().equals("available") || !evidence.path("validity").asText().equals("unassessed"))
            throw new EvidenceException("EVIDENCE_RECEIPT_BINDING_INVALID");
        return metadata;
    }
    public EvidenceDtos.PreparedCheck prepare(String authorization, EvidenceDtos.PrepareRequest command) {
        var g = grant(authorization, "check_claims", command.identifiers());
        if (command.claims() == null || command.claims().isEmpty() || command.claims().size() > 4 || command.evidence_ids() == null
                || new HashSet<>(command.evidence_ids()).size() != command.evidence_ids().size()
                || command.dispute_round() < 0 || command.dispute_round() > 2 || (command.dispute_round() == 0) != (command.parent_check_id() == null))
            throw new EvidenceException("CHECK_REQUEST_INVALID");
        if (command.evidence_ids().size() > 64) throw new EvidenceException("CHECK_REQUEST_INVALID");
        for (var spec : command.claims()) {
            if (spec == null || spec.kind() == null) throw new EvidenceException("CHECK_REQUEST_INVALID");
            text(spec.text(), 4000); if (!Set.of("factual", "inference", "recommendation").contains(spec.kind())) throw new EvidenceException("CHECK_REQUEST_INVALID");
            applicability(spec.applicability());
        }
        var specs = command.claims().stream().map(s -> claimSpec(JSON.valueToTree(s))).sorted().toList();
        if (new HashSet<>(specs).size() != specs.size()) throw new EvidenceException("CHECK_REQUEST_INVALID");
        String investigation = sha(canonical(JSON.valueToTree(specs)));
        if (command.investigation_id() != null && !investigation.equals(command.investigation_id())) throw new EvidenceException("CLAIM_SCOPE_CHANGED");
        command.evidence_ids().forEach(EvidenceJson::id);
        String fingerprint = sha(canonical(object("claims", specs, "evidence_ids", command.evidence_ids().stream().sorted().toList(), "dispute_round", command.dispute_round(), "parent_check_id", command.parent_check_id())));
        String checkId = "check-" + sha(g.runId() + ":" + g.projectId() + ":" + g.callId() + ":" + fingerprint).substring(0, 48);
        PrepareAttempt attempt = tx(g, () -> {
            var history = store.checks(g).stream().filter(e -> investigation.equals(e.investigation())).toList();
            for (var entry : history) if (checkId.equals(entry.check().checkId())) return new PrepareAttempt(prepared(entry.check(), investigation), null);
            // Validate supplied originals before reporting a conflicting root; never accept corrupt snapshots.
            for (String identity : command.evidence_ids()) verifiedEvidence(g, store.get(g, "Evidence", identity));
            EvidenceStore.CheckState previous = null;
            if (command.dispute_round() == 0) {
                if (!history.isEmpty()) throw new EvidenceException("CHECK_IDEMPOTENCY_CONFLICT");
            } else {
                var parent = history.stream().filter(e -> e.check().checkId().equals(command.parent_check_id())).findFirst().orElseThrow(() -> new EvidenceException("DISPUTE_PARENT_INVALID"));
                if (!parent.check().status().equals("COMPLETED") || parent.round() != command.dispute_round() - 1
                        || history.stream().anyMatch(e -> e.round() > parent.round())) throw new EvidenceException("DISPUTE_PARENT_INVALID");
                previous = parent.check();
            }
            var required = new TreeSet<>(command.evidence_ids());
            if (previous != null) required.addAll(retainedEvidence(previous));
            for (var blocked : store.blocked(g)) if (blocked.path("investigation_id").asText().equals(investigation) && !blockResolved(blocked, history))
                blocked.path("evidence_ids").forEach(e -> required.add(e.asText()));
            if (required.size() > 4) return blockedAttempt(g, command, checkId, investigation, required, "EVIDENCE_CAPACITY_EXCEEDED");
            var evidence = new ArrayList<JsonNode>();
            for (String identity : required) { var record = store.get(g, "Evidence", identity); verifiedEvidence(g, record); evidence.add(record); }
            var claims = new ArrayList<JsonNode>(); int index = 0;
            for (var spec : command.claims()) claims.add(object("claim_id", "claim-" + checkId + "-" + index++, "text", spec.text(), "kind", spec.kind(), "applicability", spec.applicability()));
            var priors = new ArrayList<JsonNode>();
            if (previous != null) for (var currentClaim : claims) for (var oldClaim : result(previous.result()).records()) {
                if (!oldClaim.path("record_type").asText().equals("Claim") || !claimSpec(currentClaim).equals(claimSpec(oldClaim))) continue;
                var decision = store.get(g, "DecisionRecord", "decision-" + oldClaim.path("claim_id").asText());
                var liveIds = new HashSet<String>();
                decision.path("adopted_evidence_ids").forEach(e -> liveIds.add(e.asText()));
                decision.path("unresolved_evidence_ids").forEach(e -> liveIds.add(e.asText()));
                for (var link : oldClaim.path("evidence_links")) if (liveIds.contains(link.path("evidence_id").asText()) && Set.of("supports", "refutes").contains(link.path("relation").asText())) {
                    priors.add(object("claim_id", currentClaim.path("claim_id").asText(), "evidence_id", link.path("evidence_id").asText(),
                            "relation", link.path("relation").asText(), "quote", link.path("quote"), "decision_id", decision.path("decision_id").asText(), "assessment_ref", link.path("assessment_ref").asText()));
                }
            }
            var request = object("protocol_version", "evidence-check/2", "check_id", checkId, "claims", claims, "evidence", evidence,
                    "dispute_round", command.dispute_round(), "parent_check_id", command.parent_check_id(), "investigation_id", investigation, "prior_relations", priors);
            if (canonical(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) return blockedAttempt(g, command, checkId, investigation, required, "CHECK_REQUEST_TOO_LARGE");
            return new PrepareAttempt(prepared(store.prepare(g, checkId, investigation, command.dispute_round(), command.parent_check_id(), fingerprint, sha(canonical(request)), request), investigation), null);
        });
        if (attempt.error() != null) throw new EvidenceException(attempt.error());
        return attempt.prepared();
    }
    private record PrepareAttempt(EvidenceDtos.PreparedCheck prepared, String error) { }
    private PrepareAttempt blockedAttempt(EvidenceAuthority.Grant g, EvidenceDtos.PrepareRequest command, String checkId, String investigation, Set<String> required, String code) {
        var row = object("attempt_id", "blocked-"+checkId, "tenant_id", g.principal().tenantId(), "owner_id", g.principal().userId(), "project_id", g.projectId(), "run_id", g.runId(),
                "task_id", g.taskId(), "investigation_id", investigation, "dispute_round", command.dispute_round(), "parent_check_id", command.parent_check_id(),
                "claim_texts", command.claims().stream().map(EvidenceDtos.ClaimSpec::text).toList(), "evidence_ids", required, "error_code", code);
        store.block(g, row); return new PrepareAttempt(null, code);
    }
    private static boolean blockResolved(JsonNode blocked, List<EvidenceStore.CheckEntry> history) {
        for (var entry : history) if (entry.round() >= blocked.path("dispute_round").asInt() && entry.check().status().equals("COMPLETED")) {
            if (!entry.investigation().equals(blocked.path("investigation_id").asText())) continue;
            var seen = new HashSet<String>(); entry.check().request().path("evidence").forEach(e -> seen.add(e.path("evidence_id").asText()));
            boolean covered = true; for (var e : blocked.path("evidence_ids")) if (!seen.contains(e.asText())) covered = false;
            if (covered) return true;
        }
        return false;
    }
    private static String claimSpec(JsonNode claim) {
        var scope = (com.fasterxml.jackson.databind.node.ObjectNode)claim.path("applicability").deepCopy();
        var conditions = new TreeSet<String>(); scope.path("conditions").forEach(c -> conditions.add(c.asText())); scope.set("conditions", JSON.valueToTree(conditions));
        return canonical(object("text", claim.path("text"), "kind", claim.path("kind"), "applicability", scope));
    }
    private static EvidenceDtos.PreparedCheck prepared(EvidenceStore.CheckState state, String investigation) {
        var required = new ArrayList<String>(); state.request().path("evidence").forEach(e -> required.add(e.path("evidence_id").asText()));
        return new EvidenceDtos.PreparedCheck(state.checkId(), state.requestHash(), state.request(), !state.status().equals("COMPLETED"),
                state.result() == null ? null : result(state.result()), investigation, required);
    }
    private static Set<String> retainedEvidence(EvidenceStore.CheckState check) {
        var retained = new LinkedHashSet<String>();
        for (var row : result(check.result()).records()) if (row.path("record_type").asText().equals("DecisionRecord")) {
            row.path("adopted_evidence_ids").forEach(e -> retained.add(e.asText()));
            row.path("unresolved_evidence_ids").forEach(e -> retained.add(e.asText()));
        }
        return retained;
    }
    public JsonNode investigation(String authorization, EvidenceDtos.InvestigationRequest command) {
        var g = grant(authorization, "check_claims", command.identifiers());
        return tx(g, () -> {
            var history = store.checks(g).stream().filter(e -> id(command.investigation_id()).equals(e.investigation())).toList();
            var blocked = store.blocked(g).stream().filter(b -> b.path("investigation_id").asText().equals(command.investigation_id()) && !blockResolved(b, history)).toList();
            if (history.isEmpty()) {
                if (blocked.isEmpty()) throw EvidenceException.denied();
                var required = new TreeSet<String>(); blocked.forEach(b -> b.path("evidence_ids").forEach(e -> required.add(e.asText())));
                return object("investigation_id", command.investigation_id(), "claim_specs", List.of(), "claim_texts", blocked.get(0).path("claim_texts"),
                        "check_id", null, "dispute_round", 0, "required_evidence_ids", required, "blocked_attempts", blocked,
                        "records", List.of(), "gaps", blocked.stream().map(b -> b.path("error_code").asText()).distinct().toList(), "check_ids", List.of(), "status", "CAPACITY_BLOCKED");
            }
            var latest = history.stream().filter(e -> e.check().status().equals("COMPLETED")).reduce((a,b) -> b).orElse(history.get(0));
            var specs = new ArrayList<JsonNode>(); latest.check().request().path("claims").forEach(c -> specs.add(object("text", c.path("text"), "kind", c.path("kind"), "applicability", c.path("applicability"))));
            var records = latest.check().result() == null ? List.<JsonNode>of() : result(latest.check().result()).records();
            var gaps = new LinkedHashSet<String>(); records.stream().filter(r -> r.path("record_type").asText().equals("DecisionRecord")).forEach(r -> r.path("gaps").forEach(x -> gaps.add(x.asText())));
            blocked.forEach(b -> gaps.add(b.path("error_code").asText()));
            var required = new TreeSet<String>(latest.check().result() == null ? Set.of() : retainedEvidence(latest.check()));
            blocked.forEach(b -> b.path("evidence_ids").forEach(e -> required.add(e.asText())));
            return object("investigation_id", command.investigation_id(), "claim_specs", specs, "check_id", latest.check().checkId(),
                    "dispute_round", latest.round(), "required_evidence_ids", required, "blocked_attempts", blocked,
                    "records", records, "gaps", gaps, "check_ids", history.stream().map(e -> e.check().checkId()).toList(), "status", latest.check().status());
        });
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
        var all = tx(g, () -> store.checks(g));
        var blocked = tx(g, () -> store.blocked(g)).stream().filter(b -> !blockResolved(b, all)).toList();
        var investigations = new TreeSet<String>();
        for (String checkId : command.check_ids()) {
            var selected = all.stream().filter(e -> e.check().checkId().equals(id(checkId))).findFirst().orElseThrow(EvidenceException::denied);
            if (!selected.check().status().equals("COMPLETED")) throw new EvidenceException("PACKET_CHECK_INCOMPLETE");
            investigations.add(selected.investigation());
        }
        var activeChecks = new ArrayList<String>();
        for (var investigation : investigations) {
            var history = all.stream().filter(e -> e.investigation().equals(investigation)).toList();
            var current = history.stream().filter(e -> e.check().status().equals("COMPLETED")).reduce((a,b) -> b).orElseThrow(EvidenceException::denied);
            var checked = current.check(); activeChecks.add(checked.checkId());
            if (history.stream().anyMatch(e -> !e.check().status().equals("COMPLETED"))) gaps.add("Supplement check is not completed: " + investigation);
            blocked.stream().filter(b -> b.path("investigation_id").asText().equals(investigation)).forEach(b -> gaps.add(b.path("error_code").asText()));
            for (JsonNode record : result(checked.result()).records()) {
                switch (record.path("record_type").asText()) {
                    case "Claim" -> { claims.add(record.path("claim_id").asText()); record.path("evidence_links").forEach(link -> evidence.add(link.path("evidence_id").asText())); }
                    case "DecisionRecord" -> { decisions.add(record.path("decision_id").asText()); record.path("gaps").forEach(gap -> gaps.add(gap.asText())); }
                    case "Challenge" -> challenges.add(record.path("challenge_id").asText());
                    default -> throw new EvidenceException("PACKET_INVALID");
                }
            }
        }
        String packetId = "packet-" + sha(g.runId() + ":" + canonical(object("checks", activeChecks, "gaps", gaps))).substring(0, 48);
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
        store.get(g, "ResearchPacket", id(command.packet_id()));
        // Compatibility selectors authorize no exclusion from the complete run report.
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) report(g);
        report.put("packet_id", command.packet_id()); return report;
    }
    public JsonNode report(String authorization, EvidenceDtos.ReportRequest command) {
        return report(grant(authorization, "publish_evidence", command.identifiers()));
    }
    private record ReportInput(List<EvidenceStore.CheckEntry> checks,List<JsonNode> blocked,
                               List<EvidenceAuthority.ReportGoal> goals,JsonNode state) { }
    private JsonNode report(EvidenceAuthority.Grant g) {
        var input=tx(g,()->new ReportInput(store.checks(g),store.blocked(g),authority.reportGoals(g),authority.reportState(g)));
        var all = input.checks();
        var blocked = input.blocked();
        var pending = blocked.stream().filter(b -> !blockResolved(b, all)).toList();
        var goals = input.goals();
        var researchState=input.state();
        if (researchState==null || researchState.path("version").asInt()!=1
                || !researchState.path("sha256").asText().matches("[a-f0-9]{64}")
                || !researchState.path("investigation_attempts").isArray()) throw new EvidenceException("REPORT_STATE_INVALID");
        if (goals == null || goals.size() > 64) throw new EvidenceException("REPORT_CAPACITY_EXCEEDED");
        var goalIds = new HashSet<String>();
        var criterionIds = new HashSet<String>();
        for (var goal : goals) {
            if (goal == null || !goalIds.add(id(goal.taskId())) || goal.status() == null || goal.text() == null
                    || goal.criteria() == null || goal.criteria().size() > 32 || goal.gaps() == null) throw EvidenceException.denied();
            EvidenceAdjudicator.strings(JSON.valueToTree(goal.gaps()),64,1000);
            for (var criterion : goal.criteria()) {
                if (criterion == null || !criterionIds.add(id(criterion.criterionId())) || criterion.text() == null || criterion.status() == null
                        || !Set.of("resolved","uncovered","blocked","stale").contains(criterion.status())
                        || criterion.checkIds() == null || criterion.claimIds() == null || criterion.gaps() == null) throw EvidenceException.denied();
                criterion.checkIds().forEach(EvidenceJson::id); criterion.claimIds().forEach(EvidenceJson::id);
                EvidenceAdjudicator.strings(JSON.valueToTree(criterion.gaps()),64,1000);
            }
        }
        var current = new TreeMap<String, EvidenceStore.CheckEntry>();
        for (var entry : all) if (entry.check().status().equals("COMPLETED")) current.put(entry.investigation(), entry);
        var claimIds = new ArrayList<String>();
        for (var entry : current.values()) for (var row : result(entry.check().result()).records())
            if (row.path("record_type").asText().equals("Claim")) claimIds.add(row.path("claim_id").asText());
        var unfinished = new ArrayList<JsonNode>();
        for (var gap : pending) unfinished.add(object("task_id", gap.path("task_id").asText(), "investigation_id", gap.path("investigation_id").asText(),
                "attempt_id", gap.path("attempt_id").asText(), "text", "核查未完成：" + canonical(gap.path("claim_texts")), "error_code", gap.path("error_code").asText(),
                "reason", "材料或请求超出本轮核查容量，保留缺口（" + gap.path("error_code").asText() + "）"));
        for (var entry : all) if (!entry.check().status().equals("COMPLETED"))
            unfinished.add(object("task_id", entry.taskId(), "investigation_id", entry.investigation(), "check_id", entry.check().checkId(), "reason", "Check has no completed, budget-attested assessment"));
        for (var attempt:researchState.path("investigation_attempts")) if (!attempt.path("status").asText().equals("completed"))
            unfinished.add(object("investigation_id",attempt.path("investigation_id"),"call_id",attempt.path("call_id"),
                "status",attempt.path("status"),"text","调查当前尝试","reason",attempt.path("reason")));
        for (var goal : goals) {
            // A independently verifies original criteria, current checks and prerequisites.
            // Stored done and legacy fixtures are not proof of complete coverage.
            boolean unresolved = false;
            for (var criterion : goal.criteria()) if (!criterion.status().equals("resolved") || !criterion.gaps().isEmpty()) {
                unresolved = true;
                unfinished.add(object("task_id",goal.taskId(),"criterion_id",criterion.criterionId(),"text",criterion.text(),
                        "status",criterion.status(),"check_ids",criterion.checkIds(),"claim_ids",criterion.claimIds(),"gaps",criterion.gaps(),
                        "reason","Acceptance criterion remains " + criterion.status() + (criterion.gaps().isEmpty() ? "" : ": " + canonical(JSON.valueToTree(criterion.gaps())))));
            }
            if (!goal.status().equals("done") || !goal.completionVerified() || goal.criteria().isEmpty() || unresolved || !goal.gaps().isEmpty()) {
                var reasons = new ArrayList<String>();
                if (!goal.status().equals("done")) reasons.add("Research goal remains unfinished");
                if (!goal.completionVerified()) reasons.add("Native completion proof is not verified");
                if (goal.criteria().isEmpty()) reasons.add("No acceptance-criterion coverage is available");
                if (unresolved) reasons.add("Acceptance criteria remain unresolved");
                reasons.addAll(goal.gaps());
                unfinished.add(object("task_id",goal.taskId(),"text",goal.text(),"status",goal.status(),"completion_verified",goal.completionVerified(),
                        "gaps",goal.gaps(),"reason",String.join("; ",reasons)));
            }
        }
        if (goals.isEmpty()) unfinished.add(object("task_id",g.taskId(),"text","Native research goals",
                "reason","No native acceptance-criterion completion proof is available"));
        var published = new ArrayList<JsonNode>();
        var validatedLive = new HashSet<String>(); var validationReceipts = new ArrayList<String>();
        var resolvedCount = 0;
        for (String claimId : claimIds) {
            var claim = store.get(g, "Claim", claimId);
            JsonNode decision = store.get(g, "DecisionRecord", "decision-" + claimId);
            String status = claim.path("decision_status").asText();
            if (!status.equals(decision.path("decision_status").asText()) || !Set.of("supported", "refuted", "contested", "insufficient").contains(status)) throw new EvidenceException("PUBLICATION_INVALID");
            if (Set.of("supported", "refuted").contains(status)) {
                if (!decision.path("unresolved_evidence_ids").isEmpty() || decision.path("adopted_evidence_ids").isEmpty()) throw new EvidenceException("PUBLICATION_INVALID");
                resolvedCount++;
            }
            var quotes = new ArrayList<JsonNode>();
            var cited = new LinkedHashSet<String>();
            decision.path("adopted_evidence_ids").forEach(e -> cited.add(e.asText()));
            decision.path("unresolved_evidence_ids").forEach(e -> cited.add(e.asText()));
            // Keep different-scope and insufficient material visible beside adopted quotes.
            claim.path("evidence_links").forEach(e -> cited.add(e.path("evidence_id").asText()));
            for (var identity : cited) {
                var adopted = JSON.valueToTree(identity);
                var source = store.get(g, "Evidence", adopted.asText()); var sourceMetadata = verifiedEvidence(g, source);
                var candidate = authority.originalCandidate(g, source.path("source").path("source_id").asText(), sourceMetadata.path("parent_receipt_id").asText());
                if (candidate == null) throw EvidenceException.denied();
                if (!canonical(JSON.valueToTree(candidate)).equals(canonical(sourceMetadata.path("requested_candidate"))))
                    throw new EvidenceException("PUBLICATION_SOURCE_IDENTITY_CHANGED");
                if (source.path("source").path("kind").asText().equals("knowledge") && validatedLive.add(adopted.asText())) {
                    var permit = authority.publicationRead(g, source);
                    if (permit == null) throw EvidenceException.denied(); id(permit.operationId());
                    String currentHash = permit.completedSnapshotHash();
                    if (currentHash == null) {
                        try {
                            var reread = reader.read(g, candidate); currentHash = sha(reread.text()); active(g);
                        } catch (RuntimeException failure) {
                            authority.completePublicationRead(g, permit, null, failure instanceof EvidenceException e ? e.code() : "SOURCE_READ_FAILED");
                            throw failure;
                        }
                        authority.completePublicationRead(g, permit, currentHash, null);
                    }
                    validationReceipts.add(permit.operationId());
                    if (!currentHash.equals(source.path("snapshot").path("sha256").asText())) throw new EvidenceException("PUBLICATION_SOURCE_CHANGED");
                }
                for (var link : claim.path("evidence_links")) if (link.path("evidence_id").asText().equals(adopted.asText())) {
                    EvidenceAdjudicator.quote(source, link.path("quote"));
                    String disposition = "dismissed"; String reason = "";
                    for (var v : decision.path("adopted_evidence_ids")) if (v.asText().equals(identity)) disposition = "adopted";
                    for (var v : decision.path("unresolved_evidence_ids")) if (v.asText().equals(identity)) disposition = "unresolved";
                    for (var v : decision.path("dismissed_evidence")) if (v.path("evidence_id").asText().equals(identity)) reason = v.path("reason").asText();
                    quotes.add(object("evidence_id", adopted.asText(), "source", source.path("source"), "quote", link.path("quote"), "receipt_id", source.path("receipt_id").asText(),
                            "relation", link.path("relation").asText(), "disposition", disposition, "reason", reason));
                }
            }
            if (quotes.isEmpty() && !status.equals("insufficient")) throw new EvidenceException("PUBLICATION_INVALID");
            published.add(object("claim", claim, "decision", decision, "citations", quotes));
        }
        var references = new LinkedHashMap<String, JsonNode>(); StringBuilder answer = new StringBuilder();
        boolean complete = !published.isEmpty() && resolvedCount == published.size() && unfinished.isEmpty();
        String reportStatus = complete ? "complete" : resolvedCount > 0 ? "partial" : "insufficient";
        answer.append("研究报告（").append(complete ? "已完成" : resolvedCount > 0 ? "部分完成" : "证据不足").append("）\n");
        var labels = Map.of("supported", "已支持", "refuted", "被反驳的主张", "contested", "仍有争议", "insufficient", "证据不足");
        for (String section : List.of("supported", "refuted", "contested", "insufficient")) {
          boolean heading = false;
          for (JsonNode item : published) {
            if (!item.path("claim").path("decision_status").asText().equals(section)) continue;
            if (!heading) { answer.append('\n').append(labels.get(section)).append("：\n"); heading = true; }
            String claimText = com.deepresearch.agent.ToolOutputSanitizer.neutralizeCitationMarkers(item.path("claim").path("text").asText());
            answer.append(claimText);
            var scope = item.path("claim").path("applicability");
            String version = scope.path("version").path("status").asText().equals("known") ? scope.path("version").path("value").asText() : "未确定，仅描述引用快照";
            answer.append("（适用版本：").append(com.deepresearch.agent.ToolOutputSanitizer.neutralizeCitationMarkers(version));
            if (scope.path("valid_at").path("status").asText().equals("known")) answer.append("；有效时间：").append(scope.path("valid_at").path("value").asText());
            else answer.append("；有效时间未确定");
            if (!scope.path("conditions").isEmpty()) answer.append("；条件：").append(com.deepresearch.agent.ToolOutputSanitizer.neutralizeCitationMarkers(canonical(scope.path("conditions"))));
            answer.append('）');
            for (JsonNode citation : item.path("citations")) {
                String identity = citationIdentity(citation.path("source"));
                if (!references.containsKey(identity)) {
                    var reference = (com.fasterxml.jackson.databind.node.ObjectNode) citation.deepCopy();
                    reference.put("citation_uri", identity); reference.set("occurrences", JSON.createArrayNode()); references.put(identity, reference);
                }
                ((com.fasterxml.jackson.databind.node.ArrayNode)references.get(identity).path("occurrences")).add(citation);
                int number = new ArrayList<>(references.keySet()).indexOf(identity) + 1;
                answer.append(" [来源").append(number).append(']');
            }
            if (!item.path("decision").path("gaps").isEmpty()) answer.append("；缺口：").append(neutral(canonical(item.path("decision").path("gaps"))));
            for (var dismissed : item.path("decision").path("dismissed_evidence"))
                if (dismissed.path("reason").asText().startsWith("Scope clarification")) answer.append("；争议解决依据：").append(neutral(dismissed.path("reason").asText()));
            answer.append('\n');
          }
        }
        if (!unfinished.isEmpty()) {
            answer.append("\n未完成目标：\n");
            for (var goal : unfinished) answer.append(neutral(goal.has("text") ? goal.path("text").asText() : goal.path("task_id").asText())).append("：").append(neutral(goal.path("reason").asText())).append('\n');
        }
        if (published.isEmpty()) answer.append("\n尚无完成核查的主张，不能据此给出事实结论。\n");
        answer.append("\n范围说明：上述裁决绑定所列原文、版本与条件；结构和回执校验不保证模型语义判断正确。");
        if (references.size() > 32) throw new EvidenceException("REPORT_CAPACITY_EXCEEDED");
        if (answer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768) throw new EvidenceException("PUBLICATION_TOO_LARGE");
        tx(g, () -> {
            if (!canonical(JSON.valueToTree(store.checks(g))).equals(canonical(JSON.valueToTree(all)))
                    || !canonical(JSON.valueToTree(store.blocked(g))).equals(canonical(JSON.valueToTree(blocked)))
                    || !canonical(JSON.valueToTree(authority.reportGoals(g))).equals(canonical(JSON.valueToTree(goals)))
                    || !canonical(authority.reportState(g)).equals(canonical(researchState))) throw new EvidenceException("REPORT_STATE_CHANGED");
            return null;
        });
        var investigations = all.stream().map(EvidenceStore.CheckEntry::investigation).distinct().map(identity -> object("investigation_id", identity,
                "check_ids", all.stream().filter(e -> e.investigation().equals(identity)).map(e -> e.check().checkId()).toList(),
                "current_check_id", current.containsKey(identity) ? current.get(identity).check().checkId() : null)).toList();
        var report = object("approved", true, "report_status", reportStatus, "terminal_status", complete ? "SUCCEEDED" : "INSUFFICIENT_EVIDENCE", "investigations", investigations,
                "run_id", g.runId(), "goals", goals, "research_state",researchState,"claims", published, "unfinished_goals", unfinished, "answer", answer.toString(), "answer_sha256", sha(answer.toString()),
                "citations", references.values(), "validation_receipts", validationReceipts, "semantic_truth_guaranteed", false);
        if (canonical(report).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 120000) throw new EvidenceException("REPORT_CAPACITY_EXCEEDED");
        return report;
    }
    private static String neutral(String text) { return com.deepresearch.agent.ToolOutputSanitizer.neutralizeCitationMarkers(text); }
    private static String citationIdentity(JsonNode source) {
        var locator = source.path("locator");
        if (source.path("kind").asText().equals("controlled_test")) return "test:" + locator.path("artifact_id").asText();
        return source.path("kind").asText().equals("knowledge") ? "kb:ragflow:" + locator.path("dataset_id").asText() + ":" + locator.path("document_id").asText() + ":" + locator.path("chunk_id").asText()
                : locator.path("uri").asText();
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
            try {
                if (!known.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})")) throw new IllegalArgumentException();
                OffsetDateTime.parse(known);
            } catch (Exception invalid) { throw new EvidenceException("CHECK_REQUEST_INVALID"); }
        }} else throw new EvidenceException("CHECK_REQUEST_INVALID");
    }
}
