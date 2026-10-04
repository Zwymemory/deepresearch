package com.deepresearch.evidence.publicview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.deepresearch.evidence.EvidenceAdjudicator;
import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.WorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static com.deepresearch.evidence.publicview.EvidenceViewDtos.*;
import static com.deepresearch.evidence.publicview.EvidenceViewQuery.*;

@ConditionalOnProperty(name = {"deepresearch.workflow.enabled", "deepresearch.agent.evidence.enabled"}, havingValue = "true")
@Service
public class EvidenceViewService {
    private static final Map<String, String> ID_FIELDS = Map.of("Evidence", "evidence_id", "Claim", "claim_id",
            "DecisionRecord", "decision_id", "Challenge", "challenge_id", "ResearchPacket", "packet_id");
    private static final Set<String> STATUSES = Set.of("supported", "refuted", "contested", "insufficient");
    private static final List<String> LIMITATIONS = List.of("RECORDED_OBSERVATIONS_ONLY", "EMPTY_DOES_NOT_PROVE_NO_CONFLICT",
            "MODEL_RELATIONS_ARE_NOT_TRUTH_GUARANTEES", "PUBLICATION_REQUIRES_EXISTING_SEAL_AND_FINALIZATION",
            "NO_SOURCE_REFRESH", "NO_RAW_SNAPSHOTS_OR_MODEL_RATIONALES");
    private final WorkflowService workflows;
    private final EvidenceViewQuery query;
    private final TransactionTemplate tx;
    private final ObjectMapper publicJson;
    public EvidenceViewService(WorkflowService workflows, EvidenceViewQuery query, PlatformTransactionManager manager, ObjectMapper publicJson) {
        this.workflows = workflows; this.query = query; this.publicJson = publicJson;
        tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public View read(String runId, AuthPrincipal principal) {
        return tx.execute(ignored -> {
            var run = workflows.ownedRun(runId, principal.storageUserId());
            if (!"/api/research/agents".equals(run.endpoint())) return empty(runId, run.status(), "UNSUPPORTED_MODE", true);
            var scope = query.scope(runId, principal.tenantId(), principal.userId());
            if (scope == null) throw new IntegrityFailure();
            try {
                return project(scope, run.status(), run.finalResponseJson());
            } catch (CapacityFailure cap) {
                return empty(runId, run.status(), "BOUNDED_OUT", false);
            } catch (IntegrityFailure invalid) { throw invalid;
            } catch (RuntimeException invalid) { throw new IntegrityFailure(); }
        });
    }
    private View project(Scope s, String runStatus, String finalResponse) {
        var rows = query.records(s); var checks = query.checks(s); var reads = query.reads(s); var blocked = query.blocked(s);
        // Bound total decoding/verification work as well as the final public serialization.
        long bytes = 0;
        for (var group : List.of(rows, checks, reads, blocked)) for (var row : group) for (var value : row.values())
            if (value instanceof String text) bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 2097152) throw new CapacityFailure();
        var records = new TreeMap<String, Stored>();
        for (var row : rows) {
            JsonNode node = parse(row, "payload"); String type = string(row, "record_type"), recordId = string(row, "record_id");
            int version = ((Number) row.get("version")).intValue();
            require(ID_FIELDS.containsKey(type)); scope(s, node);
            require(type.equals(node.path("record_type").asText()) && "0.1.0".equals(node.path("schema_version").asText())
                    && recordId.equals(node.path(ID_FIELDS.get(type)).asText()) && version == node.path("version").asInt(1));
            digest(node, string(row, "payload_sha256"));
            var stored = new Stored(node, new Identity(type, recordId, version, string(row, "payload_sha256"), timestamp(row, "created_at")), row);
            require(records.put(key(type, recordId, version), stored) == null);
        }
        var receipts = new HashMap<String, Map<String, Object>>();
        for (var receipt : reads) receipts.put(string(receipt, "receipt_id"), receipt);
        var refs = new ArrayList<EvidenceRef>();
        for (var record : records.values()) if (record.identity.recordType().equals("Evidence")) {
            var node = record.node; var source = node.path("source");
            var receipt = receipts.get(node.path("receipt_id").asText());
            require(receipt != null && "COMPLETED".equals(receipt.get("status"))
                    && node.path("receipt_id").asText().equals(record.row.get("read_receipt_id")));
            var metadata = parse(receipt, "metadata");
            require(canonical(node).equals(canonical(parse(receipt, "record_json"))));
            String snapshotHash = required(node.path("snapshot"), "sha256", 64);
            require(sha(required(node.path("snapshot"), "text", 10000)).equals(snapshotHash)
                    && snapshotHash.equals(metadata.path("snapshot_sha256").asText())
                    && sha(canonical(source)).equals(metadata.path("source_metadata_sha256").asText())
                    && record.identity.payloadSha256().equals(metadata.path("evidence_sha256").asText())
                    && source.path("source_id").asText().equals(metadata.path("source_id").asText())
                    && source.path("source_id").asText().equals(receipt.get("source_id"))
                    && source.path("source_id").asText().equals(metadata.path("requested_candidate").path("sourceId").asText())
                    && source.path("kind").asText().equals(metadata.path("requested_candidate").path("kind").asText())
                    && Objects.equals(receipt.get("parent_receipt_id"), metadata.path("parent_receipt_id").asText())
                    && "available".equals(node.path("availability").asText()) && "unassessed".equals(node.path("validity").asText()));
            refs.add(new EvidenceRef(record.identity, required(source, "source_id", 128),
                    choice(source, "kind", Set.of("knowledge", "web")), optional(source, "title", 256),
                    safeUrl(source), tagged(source.path("published_at"), true), optional(source, "observed_at", 64),
                    snapshotHash, applicability(node.path("applicability"))));
        }
        var checkMap = new LinkedHashMap<String, Map<String, Object>>(); var latest = new HashMap<String, Integer>();
        for (var check : checks) {
            String id = string(check, "check_id"); int round = ((Number) check.get("dispute_round")).intValue();
            require(checkMap.put(id, check) == null && round >= 0 && round <= 2);
            latest.merge(string(check, "investigation"), round, Math::max);
        }
        var claimChecks = new HashMap<String, String>(); var checkedRecords = new HashMap<String, String>(); var checkDtos = new ArrayList<Check>();
        for (var check : checks) {
            String checkId = string(check, "check_id"), investigation = string(check, "investigation");
            int round = ((Number) check.get("dispute_round")).intValue();
            var request = parse(check, "request"); digest(request, string(check, "request_sha256"));
            require(checkId.equals(request.path("check_id").asText()) && investigation.equals(request.path("investigation_id").asText())
                    && round == request.path("dispute_round").asInt(-1) && Set.of("evidence-check/2","evidence-check/3").contains(request.path("protocol_version").asText()));
            String parentId = (String) check.get("parent_check_id");
            require(Objects.equals(parentId, request.path("parent_check_id").isNull() ? null : request.path("parent_check_id").asText()));
            if (round == 0) require(parentId == null);
            else {
                var parent = checkMap.get(parentId);
                require(parent != null && investigation.equals(parent.get("investigation"))
                        && ((Number) parent.get("dispute_round")).intValue() == round - 1 && "COMPLETED".equals(parent.get("status")));
            }
            var evidenceIds = new ArrayList<String>();
            require(request.path("evidence").isArray() && request.path("evidence").size() <= 4);
            for (var evidence : request.path("evidence")) {
                var stored = get(records, "Evidence", evidence.path("evidence_id").asText(), evidence.path("version").asInt(1));
                require(canonical(evidence).equals(canonical(stored.node))); evidenceIds.add(stored.identity.recordId());
            }
            var claimIds = new ArrayList<String>(); var requestedClaims = new HashMap<String, JsonNode>();
            require(request.path("claims").isArray() && request.path("claims").size() > 0 && request.path("claims").size() <= 4);
            for (var claim : request.path("claims")) {
                String id = required(claim, "claim_id", 128); require(requestedClaims.put(id, claim) == null); claimIds.add(id);
            }
            require(query.obligationProof(s,check,request,check.get("result")==null?JSON.nullNode():parse(check,"result")));
            String status = string(check, "status"); require(Set.of("AWAITING_MODEL", "COMPLETED").contains(status));
            if (status.equals("COMPLETED")) {
                var result = parse(check, "result"); require(result.path("records").isArray() && result.path("records").size() <= 12);
                var returnedClaims = new HashSet<String>();
                for (var record : result.path("records")) {
                    String type = record.path("record_type").asText(); require(Set.of("Claim", "DecisionRecord", "Challenge").contains(type));
                    var stored = get(records, type, record.path(ID_FIELDS.get(type)).asText(), record.path("version").asInt(1));
                    require(canonical(stored.node).equals(canonical(record)));
                    require(checkedRecords.put(key(type, stored.identity.recordId(), stored.identity.version()), checkId) == null);
                    if (!type.equals("Claim")) require(requestedClaims.containsKey(record.path("claim_id").asText()));
                    if (type.equals("Claim")) {
                        String id = stored.identity.recordId(); var original = requestedClaims.get(id);
                        require(original != null && returnedClaims.add(id) && Objects.equals(original.get("text"), record.get("text"))
                                && Objects.equals(original.get("kind"), record.get("kind")) && Objects.equals(original.get("applicability"), record.get("applicability")));
                        require(claimChecks.put(id, checkId) == null);
                        for (var link : record.path("evidence_links")) require(evidenceIds.contains(link.path("evidence_id").asText()));
                    }
                }
                require(returnedClaims.equals(requestedClaims.keySet()));
            } else require(check.get("result") == null);
            checkDtos.add(new Check(checkId, investigation, round, parentId, status, string(check, "request_sha256"),
                    timestamp(check, "created_at"), timestamp(check, "completed_at"), round == latest.get(investigation), claimIds, evidenceIds));
        }
        PublicationMembership publication = publishedClaims(s, runStatus, finalResponse, records);
        var claims = new ArrayList<Claim>(); var decisions = new ArrayList<Decision>(); var disagreements = new ArrayList<Disagreement>();
        for (var stored : records.values()) if (stored.identity.recordType().equals("Claim")) {
            var node = stored.node; String claimId = stored.identity.recordId(), checkId = claimChecks.get(claimId);
            require(checkId != null);
            var decision = get(records, "DecisionRecord", "decision-" + claimId, 1);
            require(checkId.equals(checkedRecords.get(key("DecisionRecord", decision.identity.recordId(), 1))));
            require(claimId.equals(decision.node.path("claim_id").asText()));
            String status = choice(node, "decision_status", STATUSES);
            require(status.equals(decision.node.path("decision_status").asText()));
            var adopted = ids(decision.node.path("adopted_evidence_ids")); var unresolved = ids(decision.node.path("unresolved_evidence_ids"));
            var dismissed = new ArrayList<Dismissed>(); var dispositions = new HashMap<String, String>();
            adopted.forEach(id -> require(dispositions.put(id, "adopted") == null));
            unresolved.forEach(id -> require(dispositions.put(id, "unresolved") == null));
            require(decision.node.path("dismissed_evidence").isArray() && decision.node.path("dismissed_evidence").size() <= 4);
            for (var item : decision.node.path("dismissed_evidence")) {
                String id = required(item, "evidence_id", 128); require(dispositions.put(id, "dismissed") == null); dismissed.add(new Dismissed(id));
            }
            var snapshotHashes = new HashMap<String, String>();
            var links = new ArrayList<Link>(); var supports = new ArrayList<String>(); var refutes = new ArrayList<String>(); var seen = new HashSet<String>();
            require(node.path("evidence_links").isArray() && node.path("evidence_links").size() <= 4);
            for (var link : node.path("evidence_links")) {
                String id = required(link, "evidence_id", 128); require(seen.add(id) && dispositions.containsKey(id));
                var boundEvidence = parse(checkMap.get(checkId), "request").path("evidence");
                JsonNode bound = null; for (var item : boundEvidence) if (id.equals(item.path("evidence_id").asText())) bound = item;
                require(bound != null);
                var evidence = get(records, "Evidence", id, bound.path("version").asInt(1)); EvidenceAdjudicator.quote(evidence.node, link.path("quote"));
                snapshotHashes.put(id, evidence.node.path("snapshot").path("sha256").asText());
                String relation = choice(link, "relation", Set.of("supports", "refutes", "insufficient"));
                String disposition = dispositions.get(id);
                if (disposition.equals("adopted")) require(relation.equals(status.equals("supported") ? "supports" : status.equals("refuted") ? "refutes" : "invalid"));
                if (disposition.equals("unresolved") && relation.equals("supports")) supports.add(id);
                if (disposition.equals("unresolved") && relation.equals("refutes")) refutes.add(id);
                var quote = link.path("quote"); String text = quote.path("text").asText(); boolean small = text.codePointCount(0, text.length()) <= 1000;
                links.add(new Link(id, evidence.identity.version(), relation, disposition,
                        new Quote(quote.path("start").asInt(), quote.path("end").asInt(), quote.path("sha256").asText(), small ? text : null, small ? "AVAILABLE" : "OMITTED_SIZE_LIMIT")));
            }
            require(seen.equals(dispositions.keySet()));
            if (status.equals("contested")) {
                require(!supports.isEmpty() && !refutes.isEmpty() && adopted.isEmpty());
                for (var support : supports) for (var refute : refutes) require(!snapshotHashes.get(support).equals(snapshotHashes.get(refute)));
                disagreements.add(new Disagreement(claimId, decision.identity.recordId(), checkId, supports, refutes));
            } else require(unresolved.isEmpty());
            if (Set.of("supported", "refuted").contains(status)) require(!adopted.isEmpty());
            if (status.equals("insufficient")) require(adopted.isEmpty());
            var check = checkMap.get(checkId); boolean current = ((Number) check.get("dispute_round")).intValue() == latest.get(string(check, "investigation"));
            claims.add(new Claim(stored.identity, required(node, "text", 4000), required(node, "kind", 64), applicability(node.path("applicability")),
                    status, checkId, current, publication.claimIds().contains(claimId) ? "IN_FINALIZED_REPORT" : "RECORDED_ONLY", links));
            decisions.add(new Decision(decision.identity, claimId, status, required(decision.node, "policy_version", 64), adopted, unresolved, dismissed,
                    status.equals("contested") ? List.of("RECORDED_DISAGREEMENT") : status.equals("insufficient") ? List.of("INSUFFICIENT_EVIDENCE") : List.of()));
        }
        // Orphan summaries cannot be silently treated as verified decisions.
        for (var record : records.values()) if (record.identity.recordType().equals("DecisionRecord"))
            require(claimChecks.containsKey(record.node.path("claim_id").asText()));
        var blockedDtos = new ArrayList<Blocked>(); boolean unresolvedBlock = false;
        for (var row : blocked) {
            var node = parse(row, "payload"); scope(s, node); digest(node, string(row, "payload_sha256"));
            String attemptId = string(row, "attempt_id"), investigation = string(row, "investigation");
            require(attemptId.equals(node.path("attempt_id").asText()) && investigation.equals(node.path("investigation_id").asText()));
            int round = node.path("dispute_round").asInt(-1); require(round >= 0 && round <= 2);
            String error = choice(node, "error_code", Set.of("EVIDENCE_CAPACITY_EXCEEDED", "CHECK_REQUEST_TOO_LARGE"));
            var requiredIds = ids(node.path("evidence_ids"), 64);
            requiredIds.forEach(id -> latest(records, "Evidence", id));
            boolean resolved = checkDtos.stream().anyMatch(c -> c.investigationId().equals(investigation) && c.disputeRound() >= round
                    && c.status().equals("COMPLETED") && c.evidenceIds().containsAll(requiredIds));
            unresolvedBlock |= !resolved;
            blockedDtos.add(new Blocked(attemptId, investigation, round, error, string(row, "payload_sha256"), timestamp(row, "created_at"), resolved));
        }
        boolean empty = rows.isEmpty() && checks.isEmpty() && reads.isEmpty() && blocked.isEmpty();
        boolean incomplete = unresolvedBlock || reads.stream().anyMatch(r -> !"COMPLETED".equals(r.get("status")))
                || checkDtos.stream().anyMatch(c -> !c.status().equals("COMPLETED")) || claims.isEmpty();
        var view = new View("evidence-view/1", s.run(), runStatus, empty ? "NO_RECORDS_YET" : incomplete ? "RECORDED_INCOMPLETE" : "AVAILABLE",
                publication.finalized() ? "FINALIZED_REPORT" : "RECORDED_ONLY", limits(true), LIMITATIONS, refs, claims, decisions, checkDtos, disagreements, blockedDtos);
        try {
            if (publicJson.writeValueAsBytes(view).length > RESPONSE_BYTES) throw new CapacityFailure();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IntegrityFailure(); }
        return view;
    }
    private PublicationMembership publishedClaims(Scope s, String status, String finalJson, Map<String, Stored> records) {
        if (!Set.of("SUCCEEDED", "INSUFFICIENT_EVIDENCE").contains(status) || finalJson == null) return new PublicationMembership(false, Set.of());
        if (finalJson.getBytes(StandardCharsets.UTF_8).length > RESPONSE_BYTES) throw new CapacityFailure();
        var finalResult = parse(finalJson); var seals = query.publications(s);
        long bytes = 0; for (var seal : seals) for (var value : seal.values()) if (value instanceof String text) bytes += text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 2097152) throw new CapacityFailure();
        if (!finalResult.path("claims").isArray()) return new PublicationMembership(false, Set.of());
        // A terminal response containing report claims must have an exact existing publication seal.
        for (var seal : seals) {
            var result = parse(seal, "result"); var proof = parse(seal, "proof");
            if (!Objects.equals(result.get("answer"), finalResult.get("answer")) || !status.equals(result.path("terminal_status").asText())
                    || !Objects.equals(result.get("claims"), finalResult.get("claims"))
                    || !Objects.equals(parse(seal, "citations"), finalResult.get("citations"))) continue;
            require(s.run().equals(result.path("run_id").asText()) && s.run().equals(proof.path("run_id").asText())
                    && result.path("approved").asBoolean(false) && status.equals(proof.path("terminal_status").asText())
                    && Objects.equals(result.get("citations"), parse(seal, "citations"))
                    && seal.get("answer_hash").equals(result.path("answer_sha256").asText())
                    && sha(result.path("answer").asText()).equals(seal.get("answer_hash"))
                    && seal.get("answer_hash").equals(proof.path("answer_sha256").asText())
                    && Objects.equals(result.get("claims"), proof.get("claims")) && proof.path("approved").asBoolean(false));
            var ids = new HashSet<String>();
            for (var item : result.path("claims")) {
                var claim = item.path("claim"); String id = required(claim, "claim_id", 128);
                require(canonical(get(records, "Claim", id, claim.path("version").asInt(1)).node).equals(canonical(claim))
                        && canonical(get(records, "DecisionRecord", "decision-" + id, 1).node).equals(canonical(item.path("decision"))));
                ids.add(id);
            }
            return new PublicationMembership(true, Set.copyOf(ids));
        }
        throw new IntegrityFailure();
    }
    private record PublicationMembership(boolean finalized, Set<String> claimIds) { }
    private record Stored(JsonNode node, Identity identity, Map<String, Object> row) { }
    static final class IntegrityFailure extends RuntimeException { }
    private static void require(boolean valid) { if (!valid) throw new IntegrityFailure(); }
    private static JsonNode parse(Map<String, Object> row, String field) { return parse((String) row.get(field)); }
    private static JsonNode parse(String value) {
        try { require(value != null); return JSON.readTree(value); } catch (Exception invalid) { throw new IntegrityFailure(); }
    }
    private static String string(Map<String, Object> row, String field) { return (String) row.get(field); }
    private static String timestamp(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp t) return t.toInstant().toString();
        if (value instanceof java.time.OffsetDateTime t) return t.toInstant().toString();
        throw new IntegrityFailure();
    }
    private static void digest(JsonNode node, String hash) { require(hash != null && hash.matches("[a-f0-9]{64}") && sha(canonical(node)).equals(hash)); }
    private static void scope(Scope s, JsonNode node) {
        require(s.tenant().equals(node.path("tenant_id").asText()) && s.owner().equals(node.path("owner_id").asText())
                && s.project().equals(node.path("project_id").asText()) && s.run().equals(node.path("run_id").asText()));
    }
    private static String key(String type, String id, int version) { return type + ":" + id + ":" + version; }
    private static Stored get(Map<String, Stored> rows, String type, String id, int version) {
        var row = rows.get(key(type, id, version)); require(row != null); return row;
    }
    private static Stored latest(Map<String, Stored> rows, String type, String id) {
        return rows.values().stream().filter(r -> r.identity.recordType().equals(type) && r.identity.recordId().equals(id))
                .max(Comparator.comparingInt(r -> r.identity.version())).orElseThrow(IntegrityFailure::new);
    }
    private static String required(JsonNode node, String field, int max) {
        var value = node.path(field); require(value.isTextual()); String text = value.asText();
        require(!text.isBlank() && text.codePointCount(0, text.length()) <= max); return text;
    }
    private static String optional(JsonNode node, String field, int max) {
        var value = node.path(field); if (!value.isTextual()) return null;
        String text = value.asText(); return text.isBlank() || text.codePointCount(0, text.length()) > max ? null : text;
    }
    private static String choice(JsonNode node, String field, Set<String> values) { String value = required(node, field, 128); require(values.contains(value)); return value; }
    private static List<String> ids(JsonNode node) { return ids(node, 4); }
    private static List<String> ids(JsonNode node, int max) {
        require(node.isArray() && node.size() <= max); var ids = new ArrayList<String>();
        for (var item : node) { require(item.isTextual() && item.asText().matches("\\S{1,128}") && !ids.contains(item.asText())); ids.add(item.asText()); }
        return List.copyOf(ids);
    }
    private static Tagged tagged(JsonNode node, boolean time) {
        String status = choice(node, "status", Set.of("known", "unknown"));
        if (status.equals("unknown")) { require(node.path("value").isNull()); return new Tagged("unknown", null); }
        String value = required(node, "value", 128);
        if (time) try { java.time.OffsetDateTime.parse(value); } catch (Exception invalid) { throw new IntegrityFailure(); }
        return new Tagged("known", value);
    }
    private static Applicability applicability(JsonNode node) {
        EvidenceAdjudicator.strings(node.path("conditions"), 20, 1000);
        var conditions = new ArrayList<String>();
        for (var condition : node.path("conditions")) conditions.add(condition.asText());
        return new Applicability(tagged(node.path("version"), false), tagged(node.path("valid_at"), true), List.copyOf(conditions));
    }
    private static String safeUrl(JsonNode source) {
        if (!source.path("kind").asText().equals("web")) return null;
        String raw = optional(source.path("locator"), "uri", 2048); if (raw == null) return null;
        try {
            URI uri = URI.create(raw); String host = uri.getHost();
            if (host != null) host = host.toLowerCase(Locale.ROOT);
            if (!Set.of("https", "http").contains(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() != -1
                    || host.equalsIgnoreCase("localhost") || !host.contains(".") || host.matches("[0-9.]+")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost") || host.contains(":")) return null;
            return uri.toASCIIString();
        } catch (Exception invalid) { return null; }
    }
    private static Limits limits(boolean complete) { return new Limits(RECORDS, CHECKS, READS, BLOCKED, RESPONSE_BYTES, complete); }
    private static View empty(String run, String status, String availability, boolean complete) {
        var limitations = new ArrayList<>(LIMITATIONS); if (!complete) limitations.add("PROJECTION_CAPACITY_EXCEEDED_NO_PARTIAL_DATA");
        return new View("evidence-view/1", run, status, availability, "NOT_ASSESSED", limits(complete), List.copyOf(limitations),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
