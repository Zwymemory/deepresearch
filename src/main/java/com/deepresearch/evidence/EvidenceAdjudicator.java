package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Applies scope/integrity rules to budgeted semantic proposals; no majority voting. */
public final class EvidenceAdjudicator {
    public static final Set<String> ACTIONS = Set.of("search", "read_source", "recheck_version", "seek_counterevidence", "stop_with_gaps");
    public EvidenceDtos.RecordResult adjudicate(EvidenceAuthority.Grant grant, JsonNode request, JsonNode response,
                                                String assessment, Map<String, JsonNode> metadata) {
        keys(response, "claims", "follow_up_actions");
        if (!response.path("claims").isArray() || response.path("claims").size() != request.path("claims").size()
                || !response.path("follow_up_actions").isArray() || response.path("follow_up_actions").size() > 4)
            throw new EvidenceException("CHECK_RESPONSE_INVALID");
        var evidence = new LinkedHashMap<String, JsonNode>();
        request.path("evidence").forEach(e -> evidence.put(e.path("evidence_id").asText(), e));
        var proposals = new HashMap<String, JsonNode>();
        for (JsonNode proposal : response.path("claims")) {
            keys(proposal, "claim_id", "relations", "limitations");
            String identity = id(field(proposal, "claim_id"));
            if (proposals.put(identity, proposal) != null || !proposal.path("relations").isArray()
                    || proposal.path("relations").size() != evidence.size()) throw new EvidenceException("CHECK_RESPONSE_INVALID");
            strings(proposal.path("limitations"), 8, 1000);
        }
        var modelActions = new ArrayList<JsonNode>();
        for (JsonNode action : response.path("follow_up_actions")) {
            keys(action, "action", "query", "reason");
            if (!ACTIONS.contains(field(action, "action"))) throw new EvidenceException("CHECK_ACTION_INVALID");
            text(field(action, "query"), 600); text(field(action, "reason"), 1000); modelActions.add(action);
        }
        var records = new ArrayList<JsonNode>(); var actions = new ArrayList<JsonNode>();
        for (JsonNode original : request.path("claims")) {
            String claimId = original.path("claim_id").asText(); JsonNode proposal = proposals.remove(claimId);
            if (proposal == null) throw new EvidenceException("CHECK_CLAIM_BINDING_INVALID");
            var links = new ArrayList<JsonNode>(); var dismissed = new ArrayList<JsonNode>();
            var applicable = new LinkedHashMap<String, String>(); var verified = new LinkedHashMap<String, String>();
            var grouped = new HashMap<String, String>(); var seen = new HashSet<String>();
            var basis = new ArrayList<JsonNode>();
            var gaps = new ArrayList<String>();
            for (JsonNode relation : proposal.path("relations")) {
                keys(relation, "evidence_id", "relation", "quote", "reason");
                String evidenceId = id(field(relation, "evidence_id"));
                JsonNode source = evidence.get(evidenceId); String label = field(relation, "relation");
                if (source == null || !seen.add(evidenceId) || !Set.of("supports", "refutes", "insufficient").contains(label))
                    throw new EvidenceException("CHECK_EVIDENCE_BINDING_INVALID");
                text(field(relation, "reason"), 1000);
                basis.add(object("evidence_id", evidenceId, "reason", field(relation, "reason")));
                JsonNode boundQuote = bindQuote(source, relation.path("quote"));
                String assessmentRef = assessment;
                JsonNode prior = null;
                for (var row : request.path("prior_relations")) if (row.path("claim_id").asText().equals(claimId) && row.path("evidence_id").asText().equals(evidenceId)) { prior = row; break; }
                String clarification = prior == null ? null : scopeClarification(original, source, prior.path("quote"), evidence.values());
                if (prior != null) {
                    // Changing a model label or choosing a different paragraph is not new evidence.
                    boundQuote = prior.path("quote"); quote(source, boundQuote);
                    label = prior.path("relation").asText(); assessmentRef = prior.path("assessment_ref").asText();
                    basis.add(object("evidence_id", evidenceId, "prior_decision_id", prior.path("decision_id").asText(),
                            "continuity", clarification == null ? "Previously applicable relation retained" : clarification));
                }
                links.add(object("evidence_id", evidenceId, "relation", label, "quote", boundQuote,
                        "assessment_method", "model_proposal", "assessment_ref", assessmentRef));
                if (clarification != null) {
                    dismissed.add(object("evidence_id", evidenceId, "reason", clarification)); continue;
                }
                JsonNode targetVersion = original.path("applicability").path("version"), sourceVersion = source.path("applicability").path("version");
                boolean versionMatches = targetVersion.path("status").asText().equals(sourceVersion.path("status").asText())
                        && (!targetVersion.path("status").asText().equals("known") || targetVersion.path("value").asText().equals(sourceVersion.path("value").asText()));
                if (!versionMatches) {
                    String reason = "known".equals(sourceVersion.path("status").asText()) ? "Source declares a different applicable version" : "Source version is unknown; requested version is not established";
                    dismissed.add(object("evidence_id", evidenceId, "reason", reason));
                    gaps.add(reason); continue;
                }
                JsonNode targetTime = original.path("applicability").path("valid_at"), sourceTime = source.path("applicability").path("valid_at");
                if (targetTime.path("status").asText().equals("known") && (!sourceTime.path("status").asText().equals("known")
                        || !java.time.OffsetDateTime.parse(targetTime.path("value").asText()).toInstant().equals(java.time.OffsetDateTime.parse(sourceTime.path("value").asText()).toInstant()))) {
                    String reason = "Requested effective time is not established by this original evidence";
                    dismissed.add(object("evidence_id", evidenceId, "reason", reason)); gaps.add(reason); continue;
                }
                if (outsideDeclaredConditions(original, source)) {
                    dismissed.add(object("evidence_id", evidenceId, "reason", "Original document explicitly declares conditions outside the requested scope")); continue;
                }
                String previous = grouped.putIfAbsent(source.path("snapshot").path("sha256").asText(), label);
                if (previous != null && !previous.equals(label) && !previous.equals("insufficient") && !label.equals("insufficient"))
                    throw new EvidenceException("CHECK_IDENTICAL_SOURCE_CONTRADICTION");
                applicable.put(evidenceId, label);
                if (metadata.get(evidenceId).path("verified_observation").asBoolean(false)) verified.put(evidenceId, label);
            }
            boolean supports = applicable.containsValue("supports"), refutes = applicable.containsValue("refutes");
            boolean verifiedSupport = verified.containsValue("supports"), verifiedRefute = verified.containsValue("refutes");
            String status = supports && refutes ? "contested" : supports ? "supported" : refutes ? "refuted" : "insufficient";
            var adopted = new ArrayList<String>(); var unresolved = new ArrayList<String>();
            if (supports && refutes && verifiedSupport != verifiedRefute) {
                status = verifiedSupport ? "supported" : "refuted";
                String direction = verifiedSupport ? "supports" : "refutes";
                for (var entry : applicable.entrySet()) {
                    if (verified.containsKey(entry.getKey()) && direction.equals(entry.getValue())) adopted.add(entry.getKey());
                    else dismissed.add(object("evidence_id", entry.getKey(), "reason",
                            "A completed controlled observation contradicts this proposal; retain source and reproduction limits"));
                }
            } else if (status.equals("contested")) unresolved.addAll(applicable.keySet());
            else {
                String direction = status.equals("supported") ? "supports" : status.equals("refuted") ? "refutes" : "none";
                for (var entry : applicable.entrySet()) {
                    if (direction.equals(entry.getValue())) adopted.add(entry.getKey());
                    else dismissed.add(object("evidence_id", entry.getKey(), "reason", "Does not provide adequate support or counterevidence"));
                }
            }
            if (status.equals("contested")) gaps.add("Applicable sources disagree; no independently verified resolution is available");
            if (status.equals("insufficient")) gaps.add("No adequate original evidence establishes this claim in the requested scope");
            List<String> decisionGaps = status.equals("contested") || status.equals("insufficient") ? gaps.stream().distinct().toList() : List.of();
            var claim = scoped("Claim", grant, "claim_id", claimId, "run_id", grant.runId(), "text", original.path("text").asText(),
                    "kind", original.path("kind").asText(), "applicability", original.path("applicability"), "evidence_links", links,
                    "decision_status", status, "freshness", "fresh");
            String decisionId = "decision-" + claimId;
            String rationale = "Budgeted verifier proposal; original context/scope checked; observations limited to their recorded test. "
                    + canonical(object("public_basis", basis, "limitations", proposal.path("limitations")));
            if (rationale.codePointCount(0, rationale.length()) > 10000) throw new EvidenceException("CHECK_RATIONALE_TOO_LARGE");
            var decision = scoped("DecisionRecord", grant, "decision_id", decisionId, "claim_id", claimId, "run_id", grant.runId(),
                    "decision_status", status, "adopted_evidence_ids", adopted, "dismissed_evidence", dismissed,
                    "unresolved_evidence_ids", unresolved, "rationale", rationale,
                    "gaps", decisionGaps, "policy_version", "0.1.0", "recorded_at", Instant.now().toString(), "assessment_method", "model_proposal");
            records.add(claim); records.add(decision);
            if (!decisionGaps.isEmpty()) {
                int round = request.path("dispute_round").asInt();
                String query = original.path("text").asText(); query = query.substring(0, query.offsetByCodePoints(0, Math.min(180, query.codePointCount(0, query.length()))));
                List<JsonNode> planned = round == 2 ? List.of(object("action", "stop_with_gaps", "query", query, "reason", "Two supplement rounds completed; preserve unresolved gaps"))
                        : !modelActions.isEmpty() ? modelActions : List.of(object("action", evidence.isEmpty() ? "search" : gaps.stream().anyMatch(g -> g.contains("version")) ? "recheck_version" : "seek_counterevidence",
                                "query", query, "reason", decisionGaps.get(0)));
                actions.addAll(planned);
                records.add(scoped("Challenge", grant, "challenge_id", "challenge-" + claimId, "claim_id", claimId,
                        "run_id", grant.runId(), "task_id", grant.taskId(),
                        "kind", status.equals("contested") ? "direct_conflict" : gaps.stream().anyMatch(g -> g.contains("version")) ? "version_conflict" : "missing_support",
                        "status", round == 2 ? "unresolved" : "open", "evidence_ids", new ArrayList<>(evidence.keySet()),
                        "requested_actions", planned.stream().map(a -> a.path("action").asText()).distinct().toList(),
                        "response_decision_id", null, "remaining_gaps", decisionGaps, "recorded_at", Instant.now().toString()));
            }
        }
        if (!proposals.isEmpty()) throw new EvidenceException("CHECK_CLAIM_BINDING_INVALID");
        return new EvidenceDtos.RecordResult(records, actions.stream().distinct().limit(4).toList(), false);
    }

    private static boolean outsideDeclaredConditions(JsonNode claim, JsonNode evidence) {
        var declared = EvidenceService.declaredConditions(evidence.path("snapshot").path("text").asText());
        if (declared.equals(List.of("Limited to this original document or recorded observation")) || claim.path("applicability").path("conditions").isEmpty()) return false;
        // Unequal free-form language does not prove disjoint scope. Only a literal
        // categorical dimension (e.g. mode=legacy versus mode=general) can do so.
        var dimension = java.util.regex.Pattern.compile("([a-z][a-z0-9_]{0,31})=([A-Za-z0-9_.-]{1,64})");
        for (String condition : declared) {
            var sourceDimension = dimension.matcher(condition); if (!sourceDimension.matches()) continue;
            for (var requested : claim.path("applicability").path("conditions")) {
                var targetDimension = dimension.matcher(requested.asText());
                if (targetDimension.matches() && sourceDimension.group(1).equals(targetDimension.group(1))
                        && !sourceDimension.group(2).equals(targetDimension.group(2))) return true;
            }
        }
        return false;
    }
    /** Narrow, auditable scope clarification: same document, same version/time, exact old paragraph. */
    private static String scopeClarification(JsonNode claim, JsonNode old, JsonNode oldQuote, Collection<JsonNode> evidence) {
        if (!EvidenceService.declaredConditions(old.path("snapshot").path("text").asText()).equals(List.of("Limited to this original document or recorded observation"))) return null;
        for (var candidate : evidence) {
            if (candidate.path("evidence_id").equals(old.path("evidence_id")) || !sameDocument(old, candidate)
                    || !candidate.path("applicability").path("version").equals(old.path("applicability").path("version"))
                    || !candidate.path("applicability").path("valid_at").equals(old.path("applicability").path("valid_at"))
                    || !outsideDeclaredConditions(claim, candidate)) continue;
            // Scope is asserted only by the original; no model supplied locator or verified flag.
            try { bindQuote(candidate, oldQuote.path("text")); }
            catch (EvidenceException invalidContext) { continue; }
            String header = "Document conditions: " + EvidenceService.declaredConditions(candidate.path("snapshot").path("text").asText()).get(0);
            if (!candidate.path("snapshot").path("text").asText().contains(header)) continue;
            return "Scope clarification in completed evidence " + candidate.path("evidence_id").asText()
                    + " (snapshot " + candidate.path("snapshot").path("sha256").asText() + ") reproduces the original quote and declares "
                    + EvidenceJson.canonical(candidate.path("applicability").path("conditions")) + "; original decision and quote retained";
        }
        return null;
    }
    private static boolean sameDocument(JsonNode left, JsonNode right) {
        var a = left.path("source"); var b = right.path("source");
        if (!a.path("kind").equals(b.path("kind"))) return false;
        var x = a.path("locator"); var y = b.path("locator");
        return a.path("kind").asText().equals("knowledge")
                ? x.path("dataset_id").equals(y.path("dataset_id")) && x.path("document_id").equals(y.path("document_id"))
                : a.path("kind").asText().equals("web") && x.equals(y);
    }

    /** The model quotes text; trusted code computes the exact range and hash. */
    public static JsonNode bindQuote(JsonNode evidence, JsonNode proposal) {
        if (!proposal.isTextual()) { quote(evidence, proposal); return proposal; }
        String original = evidence.path("snapshot").path("text").asText();
        String quoted = text(proposal.asText(), 10000); int left = original.indexOf(quoted);
        if (left < 0 || original.indexOf(quoted, left + 1) >= 0) throw new EvidenceException("CHECK_QUOTE_BINDING_INVALID");
        JsonNode bound = object("start", original.codePointCount(0, left),
                "end", original.codePointCount(0, left + quoted.length()), "text", quoted, "sha256", sha(quoted));
        quote(evidence, bound); return bound;
    }
    public static void quote(JsonNode evidence, JsonNode quote) {
        keys(quote, "start", "end", "text", "sha256");
        if (!quote.path("start").isIntegralNumber() || !quote.path("end").isIntegralNumber()
                || !quote.path("start").canConvertToInt() || !quote.path("end").canConvertToInt()) throw new EvidenceException("CHECK_QUOTE_INVALID");
        String source = evidence.path("snapshot").path("text").asText();
        int start = quote.path("start").asInt(), end = quote.path("end").asInt();
        if (start < 0 || end <= start || end > source.codePointCount(0, source.length())) throw new EvidenceException("CHECK_QUOTE_INVALID");
        int left = source.offsetByCodePoints(0, start), right = source.offsetByCodePoints(0, end);
        String original = source.substring(left, right);
        if (!original.equals(field(quote, "text")) || !sha(original).equals(field(quote, "sha256"))) throw new EvidenceException("CHECK_QUOTE_BINDING_INVALID");
        int contentLeft = left, contentRight = right;
        while (contentLeft < right && QuoteWhitespace.space(source.codePointAt(contentLeft))) contentLeft += Character.charCount(source.codePointAt(contentLeft));
        while (contentRight > left && QuoteWhitespace.space(source.codePointBefore(contentRight))) contentRight -= Character.charCount(source.codePointBefore(contentRight));
        int paragraphStart = source.lastIndexOf('\n', Math.max(-1, contentLeft - 1)) + 1;
        int paragraphEnd = source.indexOf('\n', contentRight); if (paragraphEnd < 0) paragraphEnd = source.length();
        if (!QuoteWhitespace.blank(source.substring(paragraphStart, contentLeft)) || !QuoteWhitespace.blank(source.substring(contentRight, paragraphEnd)))
            throw new EvidenceException("CHECK_QUOTE_CONTEXT_INCOMPLETE");
    }
    public static void strings(JsonNode values, int count, int max) {
        if (!values.isArray() || values.size() > count) throw new EvidenceException("CHECK_RESPONSE_INVALID");
        for (JsonNode value : values) { if (!value.isTextual()) throw new EvidenceException("CHECK_RESPONSE_INVALID"); text(value.asText(), max); }
    }
}
