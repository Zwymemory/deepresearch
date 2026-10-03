package com.deepresearch.evidence.publicview;

import com.deepresearch.evidence.EvidenceAdjudicator;
import com.deepresearch.evidence.EvidenceAuthority;
import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.security.JwtTokenService;
import com.deepresearch.service.RagflowClient;
import com.deepresearch.workflow.WorkflowTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Actual public JWT filter/controller/service/JDBC/Flyway; isolated synthetic stored receipts only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "deepresearch.workflow.enabled=true", "deepresearch.agent.evidence.enabled=true",
    "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
    "spring.ai.openai.api-key=isolated-http-model-key", "spring.ai.zhipuai.api-key=isolated-http-embedding-key",
    "deepresearch.elasticsearch.url=http://127.0.0.1:1", "deepresearch.rerank.enabled=false",
    "server.shutdown=immediate", "spring.lifecycle.timeout-per-shutdown-phase=1s", "spring.config.import="
})
@ActiveProfiles("integration-test")
@Testcontainers
class EvidenceViewHttpIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
            .withInitScript("init-workflow-role.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", PG::getJdbcUrl); p.add("spring.datasource.username", PG::getUsername); p.add("spring.datasource.password", PG::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenService tokens;
    @Autowired WorkflowTokenService services;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean RagflowClient ragflow;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    record Run(String id, String project, String tenant, String owner, String session) { }
    record Reply(int status, JsonNode body, String raw, String cache) { }
    record Fixture(Run run, String checkId, JsonNode request, List<JsonNode> originals, JsonNode outcome) { }
    String token(Run r) { return token(r.tenant, r.owner); }
    String token(String tenant, String owner) { return tokens.issue(tenant, owner, List.of("USER"), 60L).authorizationHeader(); }
    Reply http(String method, String path, String auth, JsonNode body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json");
        if (method.equals("POST")) b.header("Idempotency-Key", "evidence-view-" + UUID.randomUUID());
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(canonical(body)));
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body().isBlank() ? object() : JSON.readTree(r.body()), r.body(), r.headers().firstValue("Cache-Control").orElse(""));
    }
    Reply get(Run r) throws Exception { return http("GET", path(r), token(r), null); }
    String path(Run r) { return "/api/research/workflows/" + r.id + "/evidence"; }
    Run run() throws Exception { return run("tenant-view", "owner-" + UUID.randomUUID(), null, true); }
    Run run(String tenant, String owner, String session, boolean agent) throws Exception {
        var body = object("question", "Investigate the synthetic API", "requestedTools", List.of("kb_search"));
        if (session != null) body.put("sessionId", session);
        var reply = http("POST", agent ? "/api/research/agents" : "/api/research/workflows", token(tenant, owner), body);
        assertThat(reply.status).withFailMessage(reply.raw).isEqualTo(202);
        String id = reply.body.path("runId").asText();
        return new Run(id, agent ? db.queryForObject("SELECT project_id FROM agent_research_run WHERE run_id=?", String.class, id) : null,
                tenant, owner, reply.body.path("sessionId").asText());
    }
    EvidenceAuthority.Grant grant(Run r) { return new EvidenceAuthority.Grant(new AuthPrincipal(r.tenant, r.owner, List.of("USER")), r.project, r.id, "task-main", "call-synthetic", UUID.randomUUID().toString()); }
    JsonNode applicability() { return object("subject", "API limits", "version", known("2.0"), "valid_at", unknown("Unestablished"), "conditions", List.of()); }
    JsonNode evidence(Run r, int index, String url) {
        String text = "Source " + index + " states the API limit.\nUNRELATED_SNAPSHOT_SECRET";
        var source = object("source_id", "source-" + index, "kind", url == null ? "knowledge" : "web", "title", "Synthetic source " + index,
                "locator", url == null ? object("dataset_id", "PRIVATE_DATASET", "document_id", "PRIVATE_DOC", "chunk_id", "PRIVATE_CHUNK", "path", "/private/fixture") : object("uri", url),
                "version", known("2.0"), "published_at", unknown("Unestablished"), "observed_at", "2026-10-01T00:00:00Z");
        return scoped("Evidence", grant(r), "evidence_id", "evidence-" + index, "version", 1, "run_id", r.id, "receipt_id", "receipt-" + r.id + "-" + index,
                "source", source, "snapshot", object("text", text, "sha256", sha(text)), "applicability", applicability(), "availability", "available", "validity", "unassessed",
                "prompt", "PROMPT_SECRET", "raw_response", "RAW_MODEL_SECRET", "claim_token", "CLAIM_TOKEN_SECRET");
    }
    void insertEvidence(Run r, JsonNode evidence, String corruption) {
        String receipt = evidence.path("receipt_id").asText(); var source = evidence.path("source");
        var metadata = object("source_id", source.path("source_id"), "snapshot_sha256", evidence.path("snapshot").path("sha256"),
                "source_metadata_sha256", sha(canonical(source)), "evidence_sha256", sha(canonical(evidence)), "parent_receipt_id", "search-original",
                "requested_candidate", object("sourceId", source.path("source_id"), "kind", source.path("kind")), "verified_observation", false);
        if (corruption.equals("receipt")) metadata.put("evidence_sha256", sha("different"));
        if (corruption.equals("source")) metadata.put("source_id", "foreign-source");
        if (corruption.equals("candidate")) ((ObjectNode) metadata.path("requested_candidate")).put("sourceId", "foreign-source");
        db.update("""
            INSERT INTO agent_evidence_read_receipt(receipt_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,claim_token,
                source_id,parent_receipt_id,request_fingerprint,status,metadata,record_json,completed_at)
            VALUES (?,?,?,?,?,'task-main',?,?::uuid,?,'search-original',?,'COMPLETED',?::jsonb,?::jsonb,now())
            """, receipt, r.tenant, r.owner, r.project, r.id, "call-" + receipt, UUID.randomUUID().toString(), source.path("source_id").asText(), sha(receipt), canonical(metadata), canonical(evidence));
        insertRecord(r, evidence, corruption.equals("hash"));
    }
    void insertRecord(Run r, JsonNode record, boolean badHash) {
        String type = record.path("record_type").asText();
        String field = Map.of("Evidence", "evidence_id", "Claim", "claim_id", "DecisionRecord", "decision_id", "Challenge", "challenge_id", "ResearchPacket", "packet_id").get(type);
        db.update("""
            INSERT INTO agent_evidence_record(tenant_id,owner_id,project_id,record_type,record_id,version,run_id,read_receipt_id,payload,payload_sha256)
            VALUES (?,?,?,?,?,?,?, ?,?::jsonb,?)
            """, r.tenant, r.owner, r.project, type, record.path(field).asText(), record.path("version").asInt(1), r.id,
                type.equals("Evidence") ? record.path("receipt_id").asText() : null, canonical(record), sha(badHash ? "incorrect" : canonical(record)));
    }
    Fixture fixture(Run r, List<String> relations, String url, String corruption, boolean pending) {
        var originals = new ArrayList<JsonNode>(); var proposals = new ArrayList<JsonNode>(); var metadata = new HashMap<String, JsonNode>();
        for (int i = 0; i < relations.size(); i++) {
            var e = evidence(r, i, url);
            if (corruption.equals("version")) ((ObjectNode) e.path("applicability")).set("version", known("3.0"));
            if (corruption.equals("large-quote")) {
                String text = "Q".repeat(1200) + "\nUNRELATED_SNAPSHOT_SECRET";
                ((ObjectNode) e.path("snapshot")).put("text", text).put("sha256", sha(text));
            }
            insertEvidence(r, e, corruption); originals.add(e);
            proposals.add(object("evidence_id", e.path("evidence_id"), "relation", relations.get(i), "quote", corruption.equals("large-quote") ? "Q".repeat(1200) : "Source " + i + " states the API limit.", "reason", "PRIVATE_MODEL_RATIONALE"));
            metadata.put(e.path("evidence_id").asText(), object("verified_observation", false));
        }
        String check = "check-" + r.id;
        var request = object("protocol_version", "evidence-check/2", "check_id", check, "claims", List.of(object("claim_id", "claim-" + r.id, "text", "API limit hypothesis", "kind", "factual", "applicability", applicability())),
                "evidence", originals, "investigation_id", sha(r.id), "dispute_round", 0, "parent_check_id", null, "prior_relations", List.of(), "prompt", "CHECK_PROMPT_SECRET");
        var response = object("claims", List.of(object("claim_id", "claim-" + r.id, "relations", proposals, "limitations", List.of("PRIVATE_MODEL_LIMITATION"))), "follow_up_actions", List.of());
        var outcome = JSON.valueToTree(new EvidenceAdjudicator().adjudicate(grant(r), request, response, "assessment-synthetic", metadata));
        if (corruption.equals("quote")) {
            var link = (ObjectNode) outcome.path("records").get(0).path("evidence_links").get(0); ((ObjectNode) link.path("quote")).put("sha256", sha("bad-quote"));
        }
        if (corruption.equals("link")) ((ObjectNode) outcome.path("records").get(1)).set("adopted_evidence_ids", JSON.valueToTree(List.of("foreign-evidence")));
        if (!pending) for (var record : outcome.path("records")) insertRecord(r, record, false);
        JsonNode storedOutcome = outcome;
        if (corruption.equals("result")) { storedOutcome = outcome.deepCopy(); ((ObjectNode) storedOutcome.path("records").get(0)).put("text", "Different result"); }
        db.update("""
            INSERT INTO agent_evidence_check(check_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,investigation,
                dispute_round,request_fingerprint,request_sha256,request,status,response_sha256,assessment_id,result,completed_at)
            VALUES (?,?,?,?,?,'task-main',?,?,0,?,?,?::jsonb,?,?,?,?::jsonb,?)
            """, check, r.tenant, r.owner, r.project, r.id, "call-" + check, sha(r.id), sha(check),
                sha(corruption.equals("request") ? "bad-request" : canonical(request)), canonical(request), pending ? "AWAITING_MODEL" : "COMPLETED",
                pending ? null : sha(canonical(response)), pending ? null : "assessment-synthetic", pending ? null : canonical(storedOutcome), pending ? null : java.time.OffsetDateTime.now());
        return new Fixture(r, check, request, originals, outcome);
    }
    Fixture fixture(Run r, List<String> relations) { return fixture(r, relations, null, "", false); }

    @Test void publicAuthenticationRejectsAnonymousInternalDelegationAndSpoofedHeaders() throws Exception {
        var r = run();
        assertThat(http("GET", path(r), null, null).status).isEqualTo(401);
        assertThat(http("GET", path(r), "Bearer invalid", null).status).isEqualTo(401);
        assertThat(http("GET", path(r), "Bearer " + services.issueServiceToken("fixture", 60).token(), null).status).isEqualTo(401);
        String delegation = services.issueDelegation(r.tenant, r.owner, r.id, "grant-fixture", "task-main", UUID.randomUUID().toString(), List.of("kb_search"), 60).token();
        assertThat(http("GET", path(r), "Bearer " + delegation, null).status).isEqualTo(401);
        var raw = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path(r))).header("X-User-Id", r.owner).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(raw.statusCode()).isEqualTo(401);
    }
    @Test void fullScopeIsolationIncludesOtherRunsInSameProjectAndBrowserParametersCannotOverrideScope() throws Exception {
        var r = run(); fixture(r, List.of("supports"));
        assertThat(http("GET", path(r), token(r.tenant, "another-user"), null).status).isEqualTo(404);
        assertThat(http("GET", path(r), token("another-tenant", r.owner), null).status).isEqualTo(404);
        assertThat(http("GET", "/api/research/workflows/missing/evidence", token(r), null).status).isEqualTo(404);
        var other = run(r.tenant, r.owner, r.session, true);
        assertThat(other.project).isEqualTo(r.project);
        assertThat(get(other).body.path("availability").asText()).isEqualTo("NO_RECORDS_YET");
        var reply = http("GET", path(other) + "?tenantId=another-tenant&ownerId=another-user&projectId=" + r.project + "&runId=" + r.id, token(other), null);
        assertThat(reply.body.path("evidence")).isEmpty();
    }
    @Test void ordinaryWorkflowIsExplicitlyUnsupportedAndAnAgentWithNoRowsIsNotAConflictConclusion() throws Exception {
        var ordinary = run("tenant-view", "ordinary-" + UUID.randomUUID(), null, false);
        assertThat(get(ordinary).body.path("availability").asText()).isEqualTo("UNSUPPORTED_MODE");
        var r = run(); var reply = get(r);
        assertThat(reply.status).isEqualTo(200); assertThat(reply.cache).contains("no-store");
        assertThat(reply.body.path("schemaVersion").asText()).isEqualTo("evidence-view/1");
        assertThat(reply.body.path("availability").asText()).isEqualTo("NO_RECORDS_YET");
        assertThat(reply.body.path("limitations").toString()).contains("EMPTY_DOES_NOT_PROVE_NO_CONFLICT");
    }
    @Test void sourceOnlyAndAwaitingCheckRemainRecordedIncomplete() throws Exception {
        var only = run(); insertEvidence(only, evidence(only, 0, null), "");
        assertThat(get(only).body.path("availability").asText()).isEqualTo("RECORDED_INCOMPLETE");
        var pending = run(); fixture(pending, List.of("supports"), null, "", true);
        var view = get(pending).body;
        assertThat(view.path("availability").asText()).isEqualTo("RECORDED_INCOMPLETE");
        assertThat(view.path("checks").get(0).path("status").asText()).isEqualTo("AWAITING_MODEL"); assertThat(view.path("claims")).isEmpty();
    }
    @Test void supportedProjectionKeepsIdentityAndQuoteButOmitsUnrelatedAndSensitiveMaterial() throws Exception {
        var r = run(); var f = fixture(r, List.of("supports")); var reply = get(r);
        assertThat(reply.status).withFailMessage(reply.raw).isEqualTo(200);
        var view = reply.body; assertThat(view.path("availability").asText()).isEqualTo("AVAILABLE");
        var evidence = view.path("evidence").get(0); var claim = view.path("claims").get(0);
        assertThat(evidence.path("identity").path("payloadSha256").asText()).isEqualTo(sha(canonical(f.originals.get(0))));
        assertThat(evidence.path("url").isNull()).isTrue(); assertThat(evidence.path("publishedAt").path("status").asText()).isEqualTo("unknown");
        assertThat(evidence.path("applicability").path("validAt").path("status").asText()).isEqualTo("unknown");
        assertThat(claim.path("decisionStatus").asText()).isEqualTo("supported");
        assertThat(claim.path("publicationState").asText()).isEqualTo("RECORDED_ONLY");
        assertThat(claim.path("evidenceLinks").get(0).path("quote").path("text").asText()).isEqualTo("Source 0 states the API limit.");
        assertThat(reply.raw).doesNotContain("SECRET", "PRIVATE_", "claim_token", "rationale", "prompt", "locator", "snapshot\"", "tenant-view", "/private/fixture");
        verifyNoInteractions(ragflow, vectorStore);
    }
    @Test void actualConflictRequiresBothBoundOpposingRelationsAndInsufficientIsSeparate() throws Exception {
        var conflict = run(); fixture(conflict, List.of("supports", "refutes")); var view = get(conflict).body;
        assertThat(view.path("claims").get(0).path("decisionStatus").asText()).isEqualTo("contested");
        assertThat(view.path("disagreements")).hasSize(1);
        assertThat(view.path("disagreements").get(0).path("supportingEvidenceIds").toString()).contains("evidence-0");
        assertThat(view.path("disagreements").get(0).path("refutingEvidenceIds").toString()).contains("evidence-1");
        var insufficient = run(); fixture(insufficient, List.of("insufficient")); var sparse = get(insufficient).body;
        assertThat(sparse.path("claims").get(0).path("decisionStatus").asText()).isEqualTo("insufficient");
        assertThat(sparse.path("disagreements")).isEmpty();
        assertThat(sparse.path("decisions").get(0).path("gapCodes").toString()).contains("INSUFFICIENT_EVIDENCE");
    }
    @ParameterizedTest @ValueSource(strings={"hash", "receipt", "source", "candidate", "request", "quote", "link", "result"})
    void corruptionFailsClosedWithoutLeakingRawRecords(String corruption) throws Exception {
        var r = run(); fixture(r, List.of("supports"), null, corruption, false); var reply = get(r);
        assertThat(reply.status).withFailMessage(reply.raw).isEqualTo(409);
        assertThat(reply.body).isEqualTo(object("errorCode", "EVIDENCE_VIEW_INTEGRITY_INVALID"));
    }
    @ParameterizedTest @ValueSource(strings={"https://user:password@example.org/page", "http://127.0.0.1/private", "https://service.internal/secret", "https://example.org/page?token=private", "file:///private/data", "https://example.org/page#secret"})
    void unsafeOrCredentialBearingUrlsAreUnavailable(String url) throws Exception {
        var r = run(); fixture(r, List.of("supports"), url, "", false); var reply = get(r);
        assertThat(reply.status).withFailMessage(reply.raw).isEqualTo(200); assertThat(reply.body.path("evidence").get(0).path("url").isNull()).isTrue();
        assertThat(reply.raw).doesNotContain(url);
    }
    @Test void safePublicUrlIsProjected() throws Exception {
        var r = run(); fixture(r, List.of("supports"), "https://example.org/source", "", false);
        assertThat(get(r).body.path("evidence").get(0).path("url").asText()).isEqualTo("https://example.org/source");
    }
    @Test void refutedAndDismissedScopeMismatchesStayDistinctFromDisagreement() throws Exception {
        var r = run(); fixture(r, List.of("refutes")); var refuted = get(r).body;
        assertThat(refuted.path("claims").get(0).path("decisionStatus").asText()).isEqualTo("refuted");
        assertThat(refuted.path("claims").get(0).path("evidenceLinks").get(0).path("disposition").asText()).isEqualTo("adopted");
        var other = run(); fixture(other, List.of("supports"), null, "version", false); var rejected = get(other).body;
        assertThat(rejected.path("claims").get(0).path("decisionStatus").asText()).isEqualTo("insufficient");
        assertThat(rejected.path("claims").get(0).path("evidenceLinks").get(0).path("disposition").asText()).isEqualTo("dismissed");
        assertThat(rejected.path("disagreements")).isEmpty();
    }
    @Test void terminalClaimsWithoutSealFailClosedAndCannotPublishCandidateProse() throws Exception {
        var r = run(); var f = fixture(r, List.of("supports"));
        var response = object("answer", "PRIVATE_CANDIDATE_ANSWER", "claims", List.of(object("claim", f.outcome.path("records").get(0), "decision", f.outcome.path("records").get(1))));
        db.update("UPDATE agent_workflow_run SET status='SUCCEEDED',final_response=?::jsonb WHERE run_id=?", canonical(response), r.id);
        var reply = get(r); assertThat(reply.status).isEqualTo(409); assertThat(reply.raw).doesNotContain("PRIVATE_CANDIDATE_ANSWER");
    }
    @Test void capacityReturnsNoMisleadingPartialRecords() throws Exception {
        var r = run();
        for (int i = 0; i <= 256; i++) insertRecord(r, scoped("ResearchPacket", grant(r), "run_id", r.id, "packet_id", "packet-" + i), false);
        var reply = get(r); assertThat(reply.status).isEqualTo(200);
        assertThat(reply.body.path("availability").asText()).isEqualTo("BOUNDED_OUT");
        assertThat(reply.body.path("limits").path("completeProjection").asBoolean()).isFalse();
        assertThat(reply.body.path("evidence")).isEmpty(); assertThat(reply.body.path("claims")).isEmpty();
    }
    @Test void oversizedQuoteIsOmittedWithExactHashAndCodepointRange() throws Exception {
        var r = run(); fixture(r, List.of("supports"), null, "large-quote", false); var reply = get(r);
        assertThat(reply.status).withFailMessage(reply.raw).isEqualTo(200);
        var quote = reply.body.path("claims").get(0).path("evidenceLinks").get(0).path("quote");
        assertThat(quote.path("text").isNull()).isTrue(); assertThat(quote.path("textAvailability").asText()).isEqualTo("OMITTED_SIZE_LIMIT");
        assertThat(quote.path("sha256").asText()).isEqualTo(sha("Q".repeat(1200))); assertThat(quote.path("end").asInt()).isEqualTo(1200);
        assertThat(reply.raw).doesNotContain("UNRELATED_SNAPSHOT_SECRET");
    }
    @ParameterizedTest @ValueSource(strings={"reads", "checks", "bytes"})
    void inputCapsApplyToEveryLedgerWithoutReturningAPartialConclusion(String ledger) throws Exception {
        var r = run();
        if (ledger.equals("reads")) for (int i = 0; i < 129; i++) insertEvidence(r, evidence(r, i, null), "");
        else if (ledger.equals("checks")) for (int i = 0; i < 129; i++) {
            var request = object("check_id", "check-cap-" + i);
            db.update("""
                INSERT INTO agent_evidence_check(check_id,tenant_id,owner_id,project_id,run_id,task_id,call_id,investigation,
                    dispute_round,request_fingerprint,request_sha256,request,status)
                VALUES (?,?,?,?,?,'task-main',?,?,0,?,?,?::jsonb,'AWAITING_MODEL')
                """, "check-cap-" + r.id + "-" + i, r.tenant, r.owner, r.project, r.id, "call-" + i, sha("investigation-" + i), sha("call-" + i), sha(canonical(request)), canonical(request));
        } else for (int i = 0; i < 27; i++) insertRecord(r, scoped("ResearchPacket", grant(r), "run_id", r.id, "packet_id", "packet-" + i, "raw_private", "X".repeat(80000)), false);
        var reply = get(r); assertThat(reply.status).isEqualTo(200); assertThat(reply.body.path("availability").asText()).isEqualTo("BOUNDED_OUT");
        assertThat(reply.body.path("limits").path("completeProjection").asBoolean()).isFalse(); assertThat(reply.body.path("checks")).isEmpty();
    }
    @Test void recordedBlockedCapacityGapIsIncompleteAndNotADisagreement() throws Exception {
        var r = run(); insertEvidence(r, evidence(r, 0, null), "");
        var blocked = object("attempt_id", "blocked-" + r.id, "tenant_id", r.tenant, "owner_id", r.owner, "project_id", r.project, "run_id", r.id,
                "investigation_id", sha(r.id), "dispute_round", 0, "error_code", "EVIDENCE_CAPACITY_EXCEEDED", "evidence_ids", List.of("evidence-0"), "claim_texts", List.of("SECRET_CANDIDATE"));
        db.update("INSERT INTO agent_evidence_blocked_attempt(attempt_id,tenant_id,owner_id,project_id,run_id,investigation,payload,payload_sha256) VALUES (?,?,?,?,?,?,?::jsonb,?)",
                "blocked-" + r.id, r.tenant, r.owner, r.project, r.id, sha(r.id), canonical(blocked), sha(canonical(blocked)));
        var reply = get(r); assertThat(reply.body.path("availability").asText()).isEqualTo("RECORDED_INCOMPLETE");
        assertThat(reply.body.path("blockedAttempts")).hasSize(1); assertThat(reply.body.path("disagreements")).isEmpty(); assertThat(reply.raw).doesNotContain("SECRET_CANDIDATE");
    }
    @Test void terminalReadIsStableAndPublicationMembershipRequiresExactSealWithoutReturningAnswer() throws Exception {
        var r = run(); var f = fixture(r, List.of("supports"));
        var items = List.of(object("claim", f.outcome.path("records").get(0), "decision", f.outcome.path("records").get(1)));
        var result = object("run_id", r.id, "approved", true, "answer_sha256", sha("UNPUBLISHED_PROSE_SECRET"), "answer", "UNPUBLISHED_PROSE_SECRET", "terminal_status", "SUCCEEDED", "claims", items, "citations", List.of("source-0"));
        var proof = object("run_id", r.id, "terminal_status", "SUCCEEDED", "approved", true, "answer_sha256", sha("UNPUBLISHED_PROSE_SECRET"), "claims", items);
        db.update("INSERT INTO agent_research_publication(run_id,call_id,request_hash,status,answer_hash,citations,result,proof,completed_at) VALUES (?,'publish-fixture',?,'COMPLETED',?,?::jsonb,?::jsonb,?::jsonb,now())",
                r.id, sha("fixture"), sha("UNPUBLISHED_PROSE_SECRET"), canonical(result.path("citations")), canonical(result), canonical(proof));
        db.update("UPDATE agent_workflow_run SET status='SUCCEEDED',stage='SUCCEEDED',claim_token=NULL,lease_until=NULL,final_response=?::jsonb WHERE run_id=?", canonical(result), r.id);
        var before = databaseState(r); var first = get(r); var second = get(r);
        assertThat(first.status).withFailMessage(first.raw).isEqualTo(200); assertThat(second.raw).isEqualTo(first.raw);
        assertThat(first.body.path("publicationState").asText()).isEqualTo("FINALIZED_REPORT");
        assertThat(first.body.path("claims").get(0).path("publicationState").asText()).isEqualTo("IN_FINALIZED_REPORT");
        assertThat(first.raw).doesNotContain("UNPUBLISHED_PROSE_SECRET", "answer\""); assertThat(databaseState(r)).isEqualTo(before);
        verifyNoInteractions(ragflow, vectorStore);
    }
    @Test void anEmptyFinalizedInsufficientReportHasPublicationStateButNeverClaimsOrProse() throws Exception {
        var r = run();
        var result = object("run_id", r.id, "approved", true, "answer", "PRIVATE_EMPTY_REPORT", "answer_sha256", sha("PRIVATE_EMPTY_REPORT"),
                "terminal_status", "INSUFFICIENT_EVIDENCE", "claims", List.of(), "citations", List.of());
        db.update("INSERT INTO agent_research_publication(run_id,call_id,request_hash,status,answer_hash,citations,result,proof,completed_at) VALUES (?,'publish-empty',?,'COMPLETED',?,'[]',?::jsonb,?::jsonb,now())",
                r.id, sha("empty"), sha("PRIVATE_EMPTY_REPORT"), canonical(result), canonical(result));
        var interim = get(r); assertThat(interim.body.path("publicationState").asText()).isEqualTo("RECORDED_ONLY");
        db.update("UPDATE agent_workflow_run SET status='INSUFFICIENT_EVIDENCE',final_response=?::jsonb WHERE run_id=?", canonical(result), r.id);
        var reply = get(r); assertThat(reply.status).withFailMessage(reply.raw).isEqualTo(200);
        assertThat(reply.body.path("publicationState").asText()).isEqualTo("FINALIZED_REPORT");
        assertThat(reply.body.path("claims")).isEmpty(); assertThat(reply.raw).doesNotContain("PRIVATE_EMPTY_REPORT");
    }
    Map<String, Object> databaseState(Run r) {
        return db.queryForMap("""
            SELECT status,version,updated_at,final_response::text,
                (SELECT count(*) FROM agent_evidence_record WHERE run_id=?) AS records,
                (SELECT count(*) FROM agent_evidence_check WHERE run_id=?) AS checks,
                (SELECT count(*) FROM agent_research_operation WHERE run_id=?) AS operations,
                (SELECT count(*) FROM agent_workflow_event WHERE run_id=?) AS events
            FROM agent_workflow_run WHERE run_id=?
            """, r.id, r.id, r.id, r.id, r.id);
    }
}
