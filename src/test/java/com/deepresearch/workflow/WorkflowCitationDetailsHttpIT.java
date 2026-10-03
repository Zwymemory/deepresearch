package com.deepresearch.workflow;

import com.deepresearch.agent.CitationDetail;
import com.deepresearch.mcp.McpKnowledgeTools.Evidence;
import com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse;
import com.deepresearch.security.JwtTokenService;
import com.deepresearch.service.RagflowClient;
import com.deepresearch.tool.TavilySearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real JWT/HTTP/finalization/JSONB/query path; all provider transports are offline fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "deepresearch.workflow.enabled=true", "deepresearch.workflow.engine=langgraph",
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
        "spring.ai.openai.api-key=isolated-http-model-key", "spring.ai.zhipuai.api-key=isolated-http-embedding-key",
        "deepresearch.elasticsearch.url=http://127.0.0.1:1", "deepresearch.rerank.enabled=false",
        "server.shutdown=immediate", "spring.lifecycle.timeout-per-shutdown-phase=1s", "spring.config.import="
})
@ActiveProfiles("integration-test")
@Testcontainers
class WorkflowCitationDetailsHttpIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch")
            .withInitScript("init-workflow-role.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", PG::getJdbcUrl);
        properties.add("spring.datasource.username", PG::getUsername);
        properties.add("spring.datasource.password", PG::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenService users;
    @Autowired WorkflowTokenService services;
    @Autowired WorkflowRepository repository;
    @Autowired ObjectMapper mapper;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean RagflowClient ragflow;
    @MockitoBean TavilySearchClient webSearch;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    record Run(String id, String claim, String tenant, String owner) {}
    record Reply(int status, JsonNode body) {}
    String user(String tenant, String owner) { return users.issue(tenant, owner, List.of("USER"), 60L).authorizationHeader(); }
    String service() { return "Bearer " + services.issueServiceToken("workflow-sidecar", 60).token(); }
    Reply request(String method, String path, String token, JsonNode body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", token);
        if (body != null) builder.header("Content-Type", "application/json");
        if (path.equals("/api/research/workflows")) builder.header("Idempotency-Key", "source-http-" + UUID.randomUUID());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(canonical(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body().isBlank() ? object() : mapper.readTree(response.body()));
    }
    Run create(String tenant, String owner) throws Exception {
        var reply = request("POST", "/api/research/workflows", user(tenant, owner),
                object("question", "Synthetic source question", "requestedTools", List.of("kb_search", "web_search")));
        assertThat(reply.status()).withFailMessage(reply.body().toString()).isEqualTo(202);
        String id = reply.body().path("runId").asText(), claim = UUID.randomUUID().toString();
        db.update("UPDATE agent_workflow_run SET status='FINALIZING',stage='FINALIZING',claim_token=?::uuid,lease_until=now()+interval '120 seconds' WHERE run_id=?", claim, id);
        return new Run(id, claim, tenant, owner);
    }
    void receipt(Run run, String call, String tool, List<CitationDetail> snapshots) throws Exception {
        var evidence = snapshots.stream().map(detail -> new Evidence(detail.sourceId(), tool,
                "Untrusted parsed title", "", "parsed content", sha("parsed content"))).toList();
        var trusted = new McpToolResponse(true, "OK", tool, evidence, snapshots);
        // Completed Java snapshots may belong to the preceding claim after crash recovery.
        String executionClaim = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,
                    status,safe_result,completed_at,claim_token,mcp_execution_status,mcp_safe_result,
                    mcp_claim_token,mcp_started_at,mcp_completed_at)
                VALUES (?,?,'task-main',?,?,'COMPLETED',?::jsonb,now(),?::uuid,'COMPLETED',?::jsonb,?::uuid,now(),now())
                """, run.id(), call, tool, sha(call), canonical(object("sourceSnapshots", List.of(
                        CitationDetail.web("https://forged.example.org", "sidecar forged", "https://forged.example.org", "forged")))),
                run.claim(), mapper.writeValueAsString(trusted), executionClaim);
    }
    JsonNode finalizeBody(Run run, List<String> citations) {
        String answer = java.util.stream.IntStream.range(0, citations.size())
                .mapToObj(i -> "Synthetic claim [来源" + (i + 1) + "]").collect(java.util.stream.Collectors.joining(" "));
        return object("claimToken", run.claim(), "status", "SUCCEEDED", "answer", answer, "citations", citations);
    }
    Reply finalize(Run run, JsonNode body) throws Exception {
        return request("POST", "/internal/research/workflows/" + run.id() + "/finalize", service(), body);
    }
    Reply get(Run run) throws Exception { return request("GET", "/api/research/workflows/" + run.id(), user(run.tenant(), run.owner()), null); }

    @Test void persistedDetailsUseExactRunAndOwnerAndGetNeverFetchesOrMutates() throws Exception {
        Run run = create("source-tenant", "owner"), foreign = create("other-tenant", "owner");
        var web = CitationDetail.web("https://example.org/source", "Public title", "https://example.org/source", "Public excerpt");
        var kb = CitationDetail.knowledge("ragflow:dataset:document:chunk", "Managed title", "Managed excerpt");
        var foreignSource = CitationDetail.web("https://example.org/foreign", "Private foreign title", "https://example.org/foreign", "Foreign text");
        receipt(run, "web-first", "web_search", List.of(web));
        receipt(run, "kb-second", "kb_search", List.of(kb));
        receipt(run, "web-duplicate", "web_search", List.of(web));
        receipt(foreign, "foreign-only", "web_search", List.of(foreignSource));
        assertThat(repository.completedSourceReceipts(run.id(), "other-tenant:owner")).isEmpty();
        var citations = List.of(kb.sourceId(), web.sourceId(), foreignSource.sourceId());
        assertThat(finalize(run, finalizeBody(run, citations)).status()).isEqualTo(200);
        String path = "/api/research/workflows/" + run.id();
        assertThat(request("GET", path, null, null).status()).isEqualTo(401);
        assertThat(request("GET", path, user(run.tenant(), "different-owner"), null).status()).isEqualTo(404);
        assertThat(request("GET", path, user("other-tenant", run.owner()), null).status()).isEqualTo(404);
        String stored = db.queryForObject("SELECT final_response::text FROM agent_workflow_run WHERE run_id=?", String.class, run.id());
        Long version = db.queryForObject("SELECT version FROM agent_workflow_run WHERE run_id=?", Long.class, run.id());
        clearInvocations(ragflow, webSearch, vectorStore);
        var view = get(run);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().path("finalResponse").path("citations")).isEqualTo(mapper.valueToTree(citations));
        var details = view.body().path("finalResponse").path("citationDetails");
        assertThat(details.get(0).path("sourceId").asText()).isEqualTo(kb.sourceId());
        assertThat(details.get(0).get("url").isNull()).isTrue();
        assertThat(details.get(1).path("title").asText()).isEqualTo("Public title");
        assertThat(details.get(2).path("metadataStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(view.body().toString()).doesNotContain("Private foreign", "Foreign text", "Untrusted parsed", "sidecar forged");
        assertThat(get(run).body().path("finalResponse")).isEqualTo(view.body().path("finalResponse"));
        assertThat(db.queryForObject("SELECT final_response::text FROM agent_workflow_run WHERE run_id=?", String.class, run.id())).isEqualTo(stored);
        assertThat(db.queryForObject("SELECT version FROM agent_workflow_run WHERE run_id=?", Long.class, run.id())).isEqualTo(version);
        verifyNoInteractions(ragflow, webSearch, vectorStore);
    }

    @Test void jsonbReplayUsesFrozenMetadataEvenIfAnotherVariantLaterAppears() throws Exception {
        Run run = create("replay-tenant", "owner");
        var detail = CitationDetail.web("https://example.org/source", "First title", "https://example.org/source", "First excerpt");
        receipt(run, "first", "web_search", List.of(detail));
        var body = finalizeBody(run, List.of(detail.sourceId()));
        assertThat(finalize(run, body).status()).isEqualTo(200);
        receipt(run, "later", "web_search", List.of(CitationDetail.web(detail.sourceId(), "Later title", detail.url(), "Later excerpt")));
        var replay = finalize(run, body);
        assertThat(replay.status()).withFailMessage(replay.body().toString()).isEqualTo(200);
        assertThat(replay.body().path("replayed").asBoolean()).isTrue();
        assertThat(get(run).body().path("finalResponse").path("citationDetails").get(0).path("title").asText()).isEqualTo("First title");
        var changed = body.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("answer", "Different claim [来源1]");
        assertThat(finalize(run, changed).status()).isEqualTo(409);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("claimToken", UUID.randomUUID().toString());
        assertThat(finalize(run, changed).status()).isEqualTo(409);
    }

    @Test void oldFinalizationsReplayTheirOriginalFingerprintAndGetOnlyAddsUnavailableProjection() throws Exception {
        Run run = create("old-tenant", "owner");
        var body = finalizeBody(run, List.of("historical-source"));
        var old = new LinkedHashMap<String, Object>();
        old.put("answer", body.path("answer").asText()); old.put("citations", List.of("historical-source"));
        old.put("citationContract", "INDEXED_V1"); old.put("insufficientEvidence", false);
        var fingerprintInput = new LinkedHashMap<String, Object>();
        fingerprintInput.put("status", "SUCCEEDED"); fingerprintInput.put("finalResponse", mapper.valueToTree(old));
        fingerprintInput.put("usage", object()); fingerprintInput.put("errorCode", ""); fingerprintInput.put("errorMessage", "");
        String fingerprint = sha(mapper.writeValueAsString(fingerprintInput));
        db.update("UPDATE agent_workflow_run SET status='SUCCEEDED',stage='SUCCEEDED',final_response=?::jsonb,usage='{}',finalize_fingerprint=?,finalized_claim_token=?::uuid WHERE run_id=?",
                mapper.writeValueAsString(old), fingerprint, run.claim(), run.id());
        assertThat(finalize(run, body).status()).isEqualTo(200);
        assertThat(get(run).body().path("finalResponse").path("citationDetails").get(0).path("unavailableReason").asText()).isEqualTo("MISSING_SNAPSHOT");
        assertThat(db.queryForObject("SELECT jsonb_exists(final_response,'citationDetails') FROM agent_workflow_run WHERE run_id=?", Boolean.class, run.id())).isFalse();
    }

    @Test void sidecarOnlyAndIncompleteJavaReceiptsCannotSupplyMetadata() throws Exception {
        Run run = create("missing-tenant", "owner");
        var detail = CitationDetail.knowledge("doc:chunk", "Invented sidecar title", "Invented sidecar text");
        db.update("""
                INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,safe_result,claim_token)
                VALUES (?,'sidecar-only','task-main','kb_search',?,'COMPLETED',?::jsonb,?::uuid)
                """, run.id(), sha("sidecar"), mapper.writeValueAsString(new McpToolResponse(true, "OK", "kb_search", List.of(), List.of(detail))), run.claim());
        db.update("""
                INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,claim_token,
                    mcp_execution_status,mcp_claim_token,mcp_started_at)
                VALUES (?,'incomplete','task-main','kb_search',?,'STARTED',?::uuid,'EXECUTING',?::uuid,now())
                """, run.id(), sha("incomplete"), run.claim(), run.claim());
        assertThat(finalize(run, finalizeBody(run, List.of(detail.sourceId()))).status()).isEqualTo(200);
        var details = get(run).body().path("finalResponse").path("citationDetails");
        assertThat(details.get(0).path("unavailableReason").asText()).isEqualTo("MISSING_SNAPSHOT");
        assertThat(details.toString()).doesNotContain("Invented");
    }
}
