package com.deepresearch.evidence;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.RagflowClient;
import com.deepresearch.service.RagflowDocumentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real readers, service, parsing, immutable records and V18 in disposable PostgreSQL. */
@Testcontainers
class EvidenceServiceIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("deepresearch").withUsername("deepresearch").withPassword("deepresearch").withInitScript("init-workflow-role.sql");
    static JdbcTemplate db; static JdbcEvidenceStore store; static final List<JsonNode> EXPORTS = new ArrayList<>(), REPAIR_EXPORTS = new ArrayList<>();
    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); store = new JdbcEvidenceStore(db,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        db.update("INSERT INTO agent_session(session_id,user_id,title) VALUES ('evidence-session','fixture-tenant:fixture-owner','evidence')");
    }
    @AfterAll static void export() throws Exception {
        Path out = Path.of("target/evidence-round1-service-records.json");
        Files.writeString(out,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object("fixture_origin","synthetic_transport_and_model_receipt",
                "actual_service_and_postgres",true,"real_network",false,"real_model",false,"bundles",EXPORTS))+"\n");
        Files.writeString(Path.of("target/evidence-round1-repair-service-records.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object(
                "fixture_origin","ordinary synthetic conflict; experimental port not used","actual_service_and_postgres",true,"real_network",false,"real_model",false,"bundles",REPAIR_EXPORTS))+"\n");
    }
    static final class Harness implements EvidenceAuthority {
        final String run = "wf-" + UUID.randomUUID(), project = "project-" + UUID.randomUUID(), claim = UUID.randomUUID().toString();
        final Map<String,Candidate> candidates = new LinkedHashMap<>(); final Map<String,String> bodies = new HashMap<>();
        final Map<String,Candidate> originalCandidates = new HashMap<>();
        final Map<String,JsonNode> models = new HashMap<>(); final List<String> assessments = new ArrayList<>();
        final RagflowClient ragflow = mock(RagflowClient.class); final EvidenceService service;
        boolean failCommit; boolean allowPublicationRead = true; int publicationSettlements;
        int publicationReadBudget = Integer.MAX_VALUE;
        String task = "task-main";
        List<ReportGoal> goals = List.of(verifiedGoal("task-main", "Synthetic limit question", "done"));
        Runnable onFetch = () -> { };
        AuthPrincipal principal = new AuthPrincipal("fixture-tenant","fixture-owner",List.of("USER"));
        Harness() {
            new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())).executeWithoutResult(tx -> {
            db.update("""
                INSERT INTO agent_workflow_run(run_id,session_id,user_id,question,endpoint,idempotency_key,request_fingerprint,
                    graph_thread_id,status,stage,deadline_at,requested_scopes,grant_id,claim_token,lease_until)
                VALUES (?,'evidence-session','fixture-tenant:fixture-owner','Synthetic limit question','evidence',?,'f',?,
                    'WORKING','WORKING',now()+interval '10 minutes',ARRAY['read_source','check_claims'],?,?,now()+interval '10 minutes')
                """,run,run,run,"grant-"+run,UUID.fromString(claim));
            db.update("INSERT INTO agent_workflow_grant(grant_id,run_id,subject,scopes,expires_at) VALUES (?,?,'fixture-tenant:fixture-owner',ARRAY['read_source','check_claims'],now()+interval '10 minutes')","grant-"+run,run);
            });
            var web = new SafeWebReader(host -> List.of(SafeWebReaderTest.ip("8.8.8.8")),(uri,addresses,remaining,max) -> {
                String sourceId = uri.getPath().substring(1); onFetch.run();
                return SafeWebReaderTest.response(200,Map.of("content-type","text/plain; charset=utf-8"),bodies.get(sourceId),"8.8.8.8");
            });
            service = new EvidenceService(this,store,new ManagedSourceReader(web,ragflow,new RagflowDocumentRegistry(db),this));
        }
        EvidenceDtos.Identifiers ids(String call) { return new EvidenceDtos.Identifiers(project,run,task,call,claim); }
        Grant current(String call) { return new Grant(principal,project,run,task,call,claim); }
        public Grant authorize(String authorization,String operation,EvidenceDtos.Identifiers ids) {
            if (!"test-service-token".equals(authorization) || !run.equals(ids.run_id()) || !project.equals(ids.project_id()) || !claim.equals(ids.claim_token())) throw EvidenceException.denied();
            return current(ids.call_id());
        }
        public boolean active(Grant g) {
            return db.queryForObject("SELECT count(*) FROM agent_workflow_run WHERE run_id=? AND claim_token=? AND cancel_requested=false AND lease_until>now() AND deadline_at>now()",
                    Integer.class,run,UUID.fromString(g.claimToken())) == 1;
        }
        public Candidate candidate(Grant g,String sourceId) {
            var candidate = candidates.get(sourceId); if (candidate == null) throw EvidenceException.denied();
            if (db.queryForObject("SELECT count(*) FROM agent_workflow_tool_receipt WHERE run_id=? AND call_id=? AND status='COMPLETED'",Integer.class,run,candidate.parentReceiptId()) != 1) throw EvidenceException.denied();
            return candidate;
        }
        public Candidate originalCandidate(Grant g,String sourceId,String parent) {
            var original = originalCandidates.get(sourceId+":"+parent);
            if (original == null || !g.runId().equals(run) || !g.principal().userId().equals("fixture-owner")
                    || db.queryForObject("SELECT count(*) FROM agent_workflow_tool_receipt WHERE run_id=? AND call_id=? AND status='COMPLETED'",Integer.class,run,parent) != 1) throw EvidenceException.denied();
            return original;
        }
        public List<ReportGoal> reportGoals(Grant g) { return goals; }
        public JsonNode reportState(Grant g) {
            return new com.deepresearch.workflow.AgentResearchStateService(db).proof(g,goals);
        }
        public void commitRead(Grant g,String source,JsonNode evidence,String receipt) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            db.update("UPDATE agent_workflow_tool_receipt SET status='COMPLETED',safe_result=?::jsonb,completed_at=now() WHERE run_id=? AND call_id=?",
                    canonical(evidence),run,g.callId());
            if (failCommit) throw new EvidenceException("TEST_COMMIT_FAILED");
        }
        public String modelReceipt(Grant g,String check,String input,String model,String output) {
            var row = models.get(model);
            if (row == null || !input.equals(row.path("request").asText()) || !output.equals(row.path("response").asText()) || !check.equals(row.path("check").asText())) throw EvidenceException.denied();
            String assessment = "assessment-" + model; assessments.add(assessment); return assessment;
        }
        public Observation controlledObservation(Grant g,Candidate source) { return new Observation("artifact-"+source.sourceId(),bodies.get(source.sourceId()),Instant.now()); }
        public PublicationReadPermit publicationRead(Grant g,JsonNode evidence) {
            if (!allowPublicationRead) throw EvidenceException.denied();
            if (publicationSettlements >= publicationReadBudget) throw new EvidenceException("AGENT_BUDGET_EXCEEDED");
            return new PublicationReadPermit("validation-"+UUID.randomUUID(),null);
        }
        public void completePublicationRead(Grant g,PublicationReadPermit permit,String hash,String error) {
            assertThat(active(g)).isTrue(); publicationSettlements++;
        }
        void source(String identity,String kind,String original) {
            String parent = "search-"+identity;
            candidates.put(identity,new Candidate(identity,kind,"https://sources.example.org/"+identity,"dataset-evidence","doc-"+identity,"chunk-"+identity,"Synthetic "+identity,parent));
            originalCandidates.put(identity+":"+parent,candidates.get(identity));
            bodies.put(identity,original);
            db.update("INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,safe_result,completed_at) VALUES (?,?,'task-main','web_search','f','COMPLETED','{}',now())",run,parent);
        }
        JsonNode read(String identity,String call) {
            db.update("INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status) VALUES (?,?,'task-main','read_source','f','EXECUTING') ON CONFLICT DO NOTHING",run,call);
            return service.read("test-service-token",new EvidenceDtos.ReadRequest(ids(call),identity));
        }
        EvidenceDtos.PreparedCheck prepare(List<EvidenceDtos.ClaimSpec> specs,List<JsonNode> evidence,int round,String parent,String call) {
            return service.prepare("test-service-token",new EvidenceDtos.PrepareRequest(ids(call),specs,evidence.stream().map(e->e.path("evidence_id").asText()).toList(),round,parent));
        }
        EvidenceDtos.RecordResult complete(EvidenceDtos.PreparedCheck prepared,JsonNode response,String call) {
            String model = "model-"+UUID.randomUUID(); models.put(model,object("check",prepared.check_id(),"request",prepared.request_sha256(),"response",sha(canonical(response))));
            return service.complete("test-service-token",new EvidenceDtos.CompleteRequest(ids(call),prepared.check_id(),model,response));
        }
        void export(String caseId,EvidenceDtos.RecordResult result,List<JsonNode> originals,JsonNode packet) {
            EXPORTS.add(bundle(caseId,result,originals,packet));
        }
        JsonNode bundle(String caseId,EvidenceDtos.RecordResult result,List<JsonNode> originals,JsonNode packet) {
            var rows = new ArrayList<JsonNode>(originals); rows.addAll(result.records()); if (packet != null) rows.add(packet);
            var scope = object("tenant_id","fixture-tenant","owner_id","fixture-owner","project_id",project);
            var refs = new ArrayList<JsonNode>();
            refs.add(object("record_type","ResearchProject","tenant_id","fixture-tenant","owner_id","fixture-owner","project_id",project));
            refs.add(object("record_type","Run","run_id",run,"tenant_id","fixture-tenant","owner_id","fixture-owner","project_id",project));
            refs.add(object("record_type","Task","task_id","task-main","run_id",run,"status","done","tenant_id","fixture-tenant","owner_id","fixture-owner","project_id",project));
            for (String assessment : assessments) refs.add(object("record_type","Assessment","assessment_id",assessment,"tenant_id","fixture-tenant","owner_id","fixture-owner","project_id",project));
            for (JsonNode source : originals) {
                JsonNode meta = store.readMetadata(current("export"),source.path("receipt_id").asText());
                refs.add(object("record_type","Receipt","receipt_id",source.path("receipt_id").asText(),"run_id",run,"task_id","task-main","status","completed","authorized",true,
                        "source_bindings",List.of(object("source_id",meta.path("source_id").asText(),"snapshot_sha256",meta.path("snapshot_sha256").asText(),"source_metadata_sha256",meta.path("source_metadata_sha256").asText())),
                        "tenant_id","fixture-tenant","owner_id","fixture-owner","project_id",project));
            }
            return object("fixture_id",caseId,"fixture_origin","synthetic_transport_and_model_receipt","authorized_scope",scope,"records",rows,"external_refs",refs,
                    "semantic_truth_guaranteed",false,"actual_persistence",true);
        }
    }
    static EvidenceDtos.ClaimSpec spec(String sentence,String version) {
        return new EvidenceDtos.ClaimSpec(sentence,"factual",object("subject","Synthetic ExampleService limit","version",known(version),"valid_at",unknown("Synthetic scope, no real-world valid time"),"conditions",List.of("Controlled fixture only")));
    }
    /** This authority fixture declares verified native proof; production must compute it independently. */
    static EvidenceAuthority.ReportGoal verifiedGoal(String task, String text, String status) {
        return new EvidenceAuthority.ReportGoal(task,text,status,true,List.of(new EvidenceAuthority.ReportCriterion(
                "fixture-criterion-"+task,text,"resolved",List.of("fixture-check"),List.of("fixture-claim"),List.of())),List.of());
    }
    /** Deterministic stand-in reads actual original paragraphs; never branches on fixture_id. */
    static JsonNode propose(JsonNode request) {
        return propose(request, false);
    }
    static JsonNode propose(JsonNode request, boolean explicitOffsets) {
        var claims = new ArrayList<JsonNode>();
        for (JsonNode claim : request.path("claims")) {
            var target = Pattern.compile("limit is ([0-9]+)").matcher(claim.path("text").asText()); assertThat(target.find()).isTrue();
            String expected = target.group(1); var relations = new ArrayList<JsonNode>();
            for (JsonNode evidence : request.path("evidence")) {
                String original = evidence.path("snapshot").path("text").asText(); var observed = Pattern.compile("(?m)^Limit: ([0-9]+).*$").matcher(original);
                assertThat(observed.find()).isTrue(); String paragraph = observed.group(); int left=observed.start(),right=observed.end();
                relations.add(object("evidence_id",evidence.path("evidence_id").asText(),"relation",expected.equals(observed.group(1)) ? "supports":"refutes",
                        "quote",explicitOffsets ? object("start",original.codePointCount(0,left),"end",original.codePointCount(0,right),"text",paragraph,"sha256",sha(paragraph)) : paragraph,"reason","Deterministic numeric relation stand-in; not a language model"));
            }
            claims.add(object("claim_id",claim.path("claim_id").asText(),"relations",relations,"limitations",List.of("Synthetic model receipt only")));
        }
        return object("claims",claims,"follow_up_actions",List.of(object("action","seek_counterevidence","query","Find original version-specific request limit","reason","Investigate applicable conflicting originals")));
    }
    static List<String> statuses(EvidenceDtos.RecordResult result) { return result.records().stream().filter(r->r.path("record_type").asText().equals("DecisionRecord")).map(r->r.path("decision_status").asText()).toList(); }

    @Test void omittedCounterevidenceDuplicateSupportAndModelRelabellingCannotResolveConflict() {
        var h = new Harness(); h.source("support", "web", "Version: 1.0\n\nLimit: 10"); h.source("counter", "web", "Version: 1.0\n\nLimit: 20");
        h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Synthetic limit question","blocked"));
        var support = h.read("support", "read-support"); var counter = h.read("counter", "read-counter");
        var specs = List.of(spec("The request limit is 10.", "1.0"));
        var root = h.prepare(specs, List.of(support, counter), 0, null, "root");
        var original = h.complete(root, propose(root.request()), "complete-root");
        assertThat(statuses(original)).containsExactly("contested");
        var next = h.prepare(specs, List.of(support), 1, root.check_id(), "next");
        assertThat(next.required_evidence_ids()).containsExactlyInAnyOrder(support.path("evidence_id").asText(), counter.path("evidence_id").asText());
        var dishonest = propose(next.request());
        for (var relation : dishonest.path("claims").get(0).path("relations"))
            if (relation.path("evidence_id").equals(counter.path("evidence_id"))) ((ObjectNode)relation).put("relation", "insufficient");
        assertThat(statuses(h.complete(next, dishonest, "complete-next"))).containsExactly("contested");
        h.source("duplicate", "web", "Version: 1.0\n\nLimit: 10"); var duplicate = h.read("duplicate", "read-duplicate");
        var last = h.prepare(specs, List.of(duplicate), 2, next.check_id(), "last");
        assertThat(last.required_evidence_ids()).hasSize(3).contains(counter.path("evidence_id").asText());
        assertThat(statuses(h.complete(last, propose(last.request()), "complete-last"))).containsExactly("contested");
        var report = h.service.report("test-service-token", new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("answer").asText()).contains("仍有争议", "Synthetic limit question");
        assertThat(store.check(h.current("history"), root.check_id()).result()).isEqualTo(JSON.valueToTree(original));
        var packet = h.service.packet("test-service-token",new EvidenceDtos.PacketRequest(h.ids("export-packet"),List.of(root.check_id(),next.check_id(),last.check_id())));
        REPAIR_EXPORTS.add(h.bundle("retained-counterevidence", h.complete(last,propose(last.request()),"replay-complete"),List.of(support,counter,duplicate),packet));
    }
    @Test void ordinaryWrongMaterialConflictUsesNoControlledObservationAndExportsActualRecords() throws Exception {
        var fixture = JSON.readTree(Files.readString(Path.of("testdata/agent-round1-repair/evidence/ordinary-conflict.json")));
        var h = new Harness(); var originals = new ArrayList<JsonNode>();
        for (var source : fixture.path("sources")) { h.source(source.path("id").asText(),source.path("kind").asText(),source.path("text").asText()); originals.add(h.read(source.path("id").asText(),"read-"+source.path("id").asText())); }
        var check = h.prepare(List.of(spec(fixture.path("claim").path("text").asText(),fixture.path("claim").path("version").asText())),originals,0,null,"root");
        var result = h.complete(check,propose(check.request()),"complete"); assertThat(statuses(result)).containsExactly(fixture.path("expected_status").asText());
        for (var original : originals) assertThat(store.readMetadata(h.current("metadata"),original.path("receipt_id").asText()).path("verified_observation").asBoolean()).isFalse();
        var packet = h.service.packet("test-service-token",new EvidenceDtos.PacketRequest(h.ids("packet"),List.of(check.check_id())));
        var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("terminal_status").asText()).isEqualTo(fixture.path("expected_terminal_status").asText());
        REPAIR_EXPORTS.add(h.bundle(fixture.path("fixture_id").asText(),result,originals,packet));
    }

    @Test void evidenceCapacityFailurePreservesCurrentConflictAndOriginalHistory() {
        var h = new Harness(); var originals = new ArrayList<JsonNode>();
        for (int n=0; n<5; n++) { h.source("source-"+n, "web", "Version: 1.0\n\nLimit: " + (n==1 ? "20" : "10")); originals.add(h.read("source-"+n, "read-"+n)); }
        var specs = List.of(spec("The request limit is 10.", "1.0"));
        var root = h.prepare(specs, originals.subList(0,4), 0, null, "root"); h.complete(root, propose(root.request()), "complete");
        assertThatThrownBy(() -> h.prepare(specs, List.of(originals.get(4)), 1, root.check_id(), "overflow")).hasMessageContaining("EVIDENCE_CAPACITY_EXCEEDED");
        var state = h.service.investigation("test-service-token", new EvidenceDtos.InvestigationRequest(h.ids("state"), root.investigation_id()));
        assertThat(state.path("check_id").asText()).isEqualTo(root.check_id()); assertThat(state.path("required_evidence_ids")).hasSize(5);
        assertThat(state.path("gaps")).isNotEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_check WHERE run_id=?", Integer.class, h.run)).isEqualTo(1);
        assertThat(h.service.report("test-service-token", new EvidenceDtos.ReportRequest(h.ids("report"))).path("report_status").asText()).isEqualTo("insufficient");
    }

    @Test void canonicalInvestigationIdentityRejectsReorderTaskCallParentAndRoundBypasses() {
        var h = new Harness(); var specs = List.of(spec("The request limit is 10.", "1.0"), spec("The request limit is 20.", "2.0"));
        var root = h.prepare(specs, List.of(), 0, null, "root"); h.complete(root, propose(root.request()), "complete");
        h.task = "task-new";
        assertThatThrownBy(() -> h.prepare(List.of(specs.get(1), specs.get(0)), List.of(), 0, null, "new-root")).hasMessageContaining("CHECK_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> h.prepare(specs, List.of(), 2, root.check_id(), "skip")).hasMessageContaining("DISPUTE_PARENT_INVALID");
        assertThatThrownBy(() -> h.prepare(specs, List.of(), 1, "invented-parent", "fake")).hasMessageContaining("DISPUTE_PARENT_INVALID");
        var changed = List.of(spec("The request limit is 999.", "1.0"));
        assertThatThrownBy(() -> h.service.prepare("test-service-token", new EvidenceDtos.PrepareRequest(h.ids("changed"), changed, List.of(), 1, root.check_id(), root.investigation_id()))).hasMessageContaining("CLAIM_SCOPE_CHANGED");
        var other = new Harness();
        assertThatThrownBy(() -> other.service.investigation("test-service-token", new EvidenceDtos.InvestigationRequest(other.ids("foreign"), root.investigation_id()))).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
    }

    @Test void sameDocumentOrUnrelatedSnapshotAloneCannotProveMissingOriginalScope() {
        for (boolean sameDocument : List.of(false, true)) {
            var h = new Harness(); h.source("support", "web", "Version: 1.0\n\nLimit: 10"); h.source("counter", "web", "Version: 1.0\n\nLimit: 20");
            var support = h.read("support", "support"); var counter = h.read("counter", "counter");
            var scope = (ObjectNode)spec("The request limit is 10.", "1.0").applicability().deepCopy(); scope.set("conditions", JSON.valueToTree(List.of("mode=general")));
            var specs = List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.", "factual", scope));
            var root = h.prepare(specs, List.of(support,counter), 0, null, "root"); h.complete(root, propose(root.request()), "complete-root");
            String expanded = "Version: 1.0\n\nDocument conditions: mode=legacy\n\nLimit: 20";
            JsonNode clarification;
            if (sameDocument) { h.bodies.put("counter", expanded); clarification = h.read("counter", "expanded-counter"); }
            else { h.source("unrelated", "web", expanded); clarification = h.read("unrelated", "expanded-unrelated"); }
            var next = h.prepare(specs, List.of(support,clarification), 1, root.check_id(), "next");
            var result = h.complete(next, propose(next.request()), "complete-next");
            assertThat(statuses(result)).containsExactly("contested");
            var packet = h.service.packet("test-service-token", new EvidenceDtos.PacketRequest(h.ids("packet"), List.of(root.check_id(),next.check_id())));
            assertThat(packet.path("status").asText()).isEqualTo("partial");
            var report = h.service.report("test-service-token", new EvidenceDtos.ReportRequest(h.ids("report")));
            assertThat(report.path("report_status").asText()).isEqualTo("insufficient");
            if (sameDocument) {
                assertThat(report.path("answer").asText()).contains("仍有争议").doesNotContain("争议解决依据");
                assertThat(report.path("citations")).hasSize(2); // Old/new snapshots of counter share one URI.
                var decision = result.records().stream().filter(r -> r.path("record_type").asText().equals("DecisionRecord")).findFirst().orElseThrow();
                assertThat(decision.path("unresolved_evidence_ids").toString()).contains(counter.path("evidence_id").asText());
                REPAIR_EXPORTS.add(h.bundle("unproved-document-scope-clarification",result,List.of(support,counter,clarification),packet));
            }
            assertThat(store.check(h.current("history"), root.check_id()).result().toString()).contains("contested");
        }
    }

    @Test void successfulSupplementReplacesActiveGapsButKeepsImmutablePriorChallenges() {
        var h = new Harness(); var specs = List.of(spec("The request limit is 10.", "1.0"));
        var root = h.prepare(specs, List.of(), 0, null, "root"); var original = h.complete(root, propose(root.request()), "complete-root");
        h.source("new-original", "web", "Version: 1.0\n\nLimit: 10"); var source = h.read("new-original", "read");
        var next = h.prepare(specs, List.of(source), 1, root.check_id(), "next"); h.complete(next, propose(next.request()), "complete-next");
        var packet = h.service.packet("test-service-token", new EvidenceDtos.PacketRequest(h.ids("packet"), List.of(root.check_id(),next.check_id())));
        assertThat(packet.path("status").asText()).isEqualTo("complete"); assertThat(packet.path("gaps")).isEmpty();
        assertThat(packet.path("claim_ids")).hasSize(1); assertThat(packet.path("challenge_ids")).isEmpty();
        assertThat(h.service.report("test-service-token", new EvidenceDtos.ReportRequest(h.ids("report"))).path("terminal_status").asText()).isEqualTo("SUCCEEDED");
        assertThat(store.check(h.current("history"), root.check_id()).result()).isEqualTo(JSON.valueToTree(original));
    }
    @Test void olderRepeatedOrLaterCapturedSnapshotCannotEraseAmbiguousCounterevidence() {
        for (String variant : List.of("old-cache", "same-materials", "later-capture")) {
            var h = new Harness();
            String legacy = "Version: 1.0\n\nDocument conditions: mode=legacy\n\nLimit: 20";
            h.source("counter", "web", legacy); var cached = h.read("counter", "cache");
            h.bodies.put("counter", "Version: 1.0\n\nDocument conditions: mode=legacy\n\nDocument conditions: mode=general\n\nLimit: 20");
            var ambiguous = h.read("counter", "ambiguous");
            h.source("support", "web", "Version: 1.0\n\nDocument conditions: mode=general\n\nLimit: 10");
            var support = h.read("support", "support");
            var scope = (ObjectNode) spec("The request limit is 10.", "1.0").applicability().deepCopy();
            scope.set("conditions", JSON.valueToTree(List.of("mode=general")));
            var specs = List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.", "factual", scope));
            var originals = variant.equals("same-materials") ? List.of(support, ambiguous, cached) : List.of(support, ambiguous);
            var root = h.prepare(specs, originals, 0, null, "root");
            assertThat(statuses(h.complete(root, propose(root.request()), "complete-root"))).containsExactly("contested");
            assertThat(store.readMetadata(h.current("metadata"), ambiguous.path("receipt_id").asText()).path("condition_declaration_status").asText()).isEqualTo("AMBIGUOUS");
            if (variant.equals("later-capture")) {
                h.bodies.put("counter", legacy); cached = h.read("counter", "later-cache");
                assertThat(Instant.parse(cached.path("source").path("observed_at").asText()))
                        .isAfter(Instant.parse(ambiguous.path("source").path("observed_at").asText()));
            }
            var next = h.prepare(specs, List.of(cached), 1, root.check_id(), "next");
            var outcome = h.complete(next, propose(next.request()), "complete-next");
            assertThat(statuses(outcome)).containsExactly("contested");
            var decision = outcome.records().stream().filter(r -> r.path("record_type").asText().equals("DecisionRecord")).findFirst().orElseThrow();
            assertThat(decision.path("unresolved_evidence_ids").toString()).contains(ambiguous.path("evidence_id").asText());
            assertThat(decision.path("dismissed_evidence").toString()).doesNotContain("Scope clarification");
            if (variant.equals("same-materials")) assertThat(next.required_evidence_ids()).containsExactlyInAnyOrderElementsOf(root.required_evidence_ids());
            var report = h.service.report("test-service-token", new EvidenceDtos.ReportRequest(h.ids("report")));
            assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(report.path("answer").asText()).contains("仍有争议").doesNotContain("争议解决依据");
            assertThat(store.check(h.current("history"), root.check_id()).result().toString()).contains("contested");
        }
    }
    @Test void explicitOriginalScopeCanResolveAndRepeatedMaterialsPreserveThatResult() {
        var h = new Harness();
        h.source("support", "web", "Version: 1.0\n\nDocument conditions: mode=general\n\nLimit: 10");
        h.source("counter", "web", "Version: 1.0\n\nDocument conditions: mode=legacy\n\nLimit: 20");
        var support = h.read("support", "support"); var counter = h.read("counter", "counter");
        var scope = (ObjectNode) spec("The request limit is 10.", "1.0").applicability().deepCopy();
        scope.set("conditions", JSON.valueToTree(List.of("mode=general")));
        var specs = List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.", "factual", scope));
        var root = h.prepare(specs, List.of(support, counter), 0, null, "root");
        assertThat(statuses(h.complete(root, propose(root.request()), "complete-root"))).containsExactly("supported");
        var next = h.prepare(specs, List.of(support, counter), 1, root.check_id(), "next");
        var outcome = h.complete(next, propose(next.request()), "complete-next");
        assertThat(statuses(outcome)).containsExactly("supported");
        var packet = h.service.packet("test-service-token", new EvidenceDtos.PacketRequest(h.ids("packet"), List.of(root.check_id(), next.check_id())));
        assertThat(packet.path("gaps")).isEmpty();
        var report = h.service.report("test-service-token", new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("terminal_status").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.path("answer").asText()).contains("mode=general");
        REPAIR_EXPORTS.add(h.bundle("explicit-original-scope", outcome, List.of(support, counter), packet));
    }
    @Test void wrongVersionMissingOriginalQuoteAndFreeFormConditionsDoNotProveScopeResolution() {
        for (String variation : List.of("version", "quote", "free-form")) {
            var h = new Harness(); h.source("support","web","Version: 1.0\n\nLimit: 10"); h.source("counter","web","Version: 1.0\n\nLimit: 20");
            var support = h.read("support","read-support"); var counter = h.read("counter","read-counter");
            var scope = (ObjectNode)spec("The request limit is 10.","1.0").applicability().deepCopy();
            scope.set("conditions",JSON.valueToTree(List.of(variation.equals("free-form") ? "general usage" : "mode=general")));
            var specs = List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.","factual",scope));
            var root = h.prepare(specs,List.of(support,counter),0,null,"root"); h.complete(root,propose(root.request()),"complete-root");
            h.bodies.put("counter","Version: " + (variation.equals("version") ? "2.0" : "1.0") + "\n\nDocument conditions: "
                    + (variation.equals("free-form") ? "legacy usage" : "mode=legacy") + "\n\nLimit: " + (variation.equals("quote") ? "30" : "20"));
            var candidate = h.read("counter","expanded");
            var next = h.prepare(specs,List.of(candidate),1,root.check_id(),"next");
            assertThat(statuses(h.complete(next,propose(next.request()),"complete-next"))).containsExactly("contested");
        }
    }

    @Test void mixedFullReportCannotHideRefutedContestedInsufficientOrUnfinishedGoalsUsingSelectors() {
        var h = new Harness(); h.source("v1", "web", "Version: 1.0\n\nLimit: 10"); h.source("v2-a", "web", "Version: 2.0\n\nLimit: 10"); h.source("v2-b", "web", "Version: 2.0\n\nLimit: 20");
        var evidence = List.of(h.read("v1","read-v1"),h.read("v2-a","read-v2-a"),h.read("v2-b","read-v2-b"));
        var specs = List.of(spec("The request limit is 10.", "1.0"),spec("The request limit is 99.", "1.0"),spec("The request limit is 10.", "2.0"),spec("The request limit is 1.", "3.0"));
        var check = h.prepare(specs,evidence,0,null,"root"); var result = h.complete(check,propose(check.request()),"complete");
        assertThat(statuses(result)).containsExactly("supported","refuted","contested","insufficient");
        h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Verify all versioned limits","running"), new EvidenceAuthority.ReportGoal("pending-goal","Unexamined latency question","pending"));
        var packet = h.service.packet("test-service-token",new EvidenceDtos.PacketRequest(h.ids("packet"),List.of(check.check_id())));
        var report = h.service.publish("test-service-token",new EvidenceDtos.PublishRequest(h.ids("publish"),packet.path("packet_id").asText(),List.of(result.records().get(0).path("claim_id").asText())));
        assertThat(report.path("claims")).hasSize(4); assertThat(report.path("report_status").asText()).isEqualTo("partial");
        assertThat(report.path("answer").asText()).contains("已支持","被反驳的主张","仍有争议","证据不足","未完成目标","Unexamined latency question","适用版本：3.0");
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("citations")).hasSize(3); assertThat(report.path("answer_sha256").asText()).isEqualTo(sha(report.path("answer").asText()));
    }

    @Test void renamedNewInvestigationAndOlderPacketCannotHidePreviousUnresolvedQuestion() {
        var h = new Harness(); var first = h.prepare(List.of(spec("The request limit is 10.","1.0")),List.of(),0,null,"first"); h.complete(first,propose(first.request()),"complete-first");
        h.source("answer", "web", "Version: 1.0\n\nLimit: 20"); var source = h.read("answer","read");
        var second = h.prepare(List.of(spec("The request limit is 20.","1.0")),List.of(source),0,null,"second"); var answer = h.complete(second,propose(second.request()),"complete-second");
        var packet = h.service.packet("test-service-token",new EvidenceDtos.PacketRequest(h.ids("packet"),List.of(second.check_id())));
        var report = h.service.publish("test-service-token",new EvidenceDtos.PublishRequest(h.ids("publish"),packet.path("packet_id").asText(),List.of(answer.records().get(0).path("claim_id").asText())));
        assertThat(report.path("claims")).hasSize(2); assertThat(report.path("report_status").asText()).isEqualTo("partial");
        assertThat(report.path("answer").asText()).contains("The request limit is 10.","The request limit is 20.");
    }

    @Test void originalCompletedReceiptSurvivesLaterHitAndDuplicateReadUsesOneCitationNumber() {
        var h = new Harness(); h.source("same", "web", "Version: 1.0\n\nLimit: 10"); var first = h.read("same","first-read"); var c = h.candidates.get("same");
        var nextCandidate = new EvidenceAuthority.Candidate(c.sourceId(),c.kind(),c.url(),c.datasetId(),c.documentId(),c.chunkId(),c.title(),"later-search");
        db.update("INSERT INTO agent_workflow_tool_receipt(run_id,call_id,task_id,tool_name,request_fingerprint,status,safe_result,completed_at) VALUES (?,'later-search','task-main','web_search','f','COMPLETED','{}',now())",h.run);
        h.candidates.put("same",nextCandidate); h.originalCandidates.put("same:later-search",nextCandidate); var second = h.read("same","second-read");
        var check = h.prepare(List.of(spec("The request limit is 10.","1.0")),List.of(first,second),0,null,"root"); h.complete(check,propose(check.request()),"complete");
        var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("report_status").asText()).isEqualTo("complete"); assertThat(report.path("citations")).hasSize(1);
        assertThat(report.path("citations").get(0).path("occurrences")).hasSize(2);
        assertThat(report.path("answer").asText()).contains("[来源1]").doesNotContain("[来源2]");
        assertThatThrownBy(() -> h.originalCandidate(new EvidenceAuthority.Grant(h.principal,h.project,"foreign-run",h.task,"call",h.claim),"same",c.parentReceiptId())).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        var outsider = new Harness(); assertThatThrownBy(() -> outsider.service.prepare("test-service-token",new EvidenceDtos.PrepareRequest(outsider.ids("cross-run"),List.of(spec("The request limit is 10.","1.0")),List.of(first.path("evidence_id").asText()),0,null))).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
    }

    @Test void gapOnlyReportIsDeterministicWithoutFabricatingEvidenceOrAssessments() {
        var h = new Harness(); h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Synthetic limit question","pending"));
        var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("first")));
        var repeat = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("second")));
        assertThat(report).isEqualTo(repeat); assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.path("citations")).isEmpty(); assertThat(report.path("claims")).isEmpty(); assertThat(report.path("unfinished_goals")).hasSize(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_record WHERE run_id=?",Integer.class,h.run)).isZero();
    }
    @Test void supportedClaimDoesNotCompleteNativeBlockedOrPendingGoal() {
        var h = new Harness(); h.source("original","web","Version: 1.0\n\nLimit: 10"); var original = h.read("original","read");
        var check = h.prepare(List.of(spec("The request limit is 10.","1.0")),List.of(original),0,null,"root"); h.complete(check,propose(check.request()),"complete");
        for (String nativeStatus : List.of("pending","blocked","running")) {
            h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Additional acceptance criterion unfinished",nativeStatus));
            var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report-"+nativeStatus)));
            assertThat(report.path("report_status").asText()).isEqualTo("partial");
            assertThat(report.path("answer").asText()).contains("已支持","Additional acceptance criterion unfinished");
        }
    }
    @Test void missingBlockedOrStaleCriterionIsVisibleEvenWhenGoalClaimsVerifiedDone() {
        var h = new Harness(); h.source("original","web","Version: 1.0\n\nLimit: 10"); var source = h.read("original","read");
        var check = h.prepare(List.of(spec("The request limit is 10.","1.0")),List.of(source),0,null,"root");
        var result = h.complete(check,propose(check.request()),"complete");
        String claim = result.records().stream().filter(r -> r.path("record_type").asText().equals("Claim")).findFirst().orElseThrow().path("claim_id").asText();
        var covered = new EvidenceAuthority.ReportCriterion("criterion-version","Verify the document version","resolved",List.of(check.check_id()),List.of(claim),List.of());
        for (String status : List.of("uncovered","blocked","stale")) {
            var missing = new EvidenceAuthority.ReportCriterion("criterion-rate","Verify the per-minute request rate",status,List.of(),List.of(),List.of("Rate has no current verified coverage"));
            h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Verify version and rate","done",true,List.of(covered,missing),List.of()));
            var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report-"+status)));
            assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(report.path("report_status").asText()).isEqualTo("partial");
            assertThat(report.path("unfinished_goals").toString()).contains("criterion-rate",status,"Rate has no current verified coverage");
            assertThat(report.path("answer").asText()).contains("已支持","Verify the per-minute request rate","Rate has no current verified coverage");
            assertThat(report.path("citations")).hasSize(1);
        }
    }
    @Test void legacyDoneAndUnverifiedCompletionDoNotEstablishNativeCoverage() {
        var h = new Harness(); h.source("original","web","Version: 1.0\n\nLimit: 10"); var source = h.read("original","read");
        var check = h.prepare(List.of(spec("The request limit is 10.","1.0")),List.of(source),0,null,"root"); h.complete(check,propose(check.request()),"complete");
        var legacy = new EvidenceAuthority.ReportGoal("task-main","Legacy task still needs coverage","done");
        var verified = verifiedGoal("task-main","Unverified task proof","done");
        for (var goal : List.of(legacy,new EvidenceAuthority.ReportGoal(verified.taskId(),verified.text(),verified.status(),false,verified.criteria(),List.of()),
                new EvidenceAuthority.ReportGoal("task-main","Empty proof cannot complete","done",true,List.of(),List.of()))) {
            h.goals = List.of(goal);
            var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report-"+h.goals.indexOf(goal))));
            assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(report.path("answer").asText()).contains("未完成目标",goal.text());
            assertThat(report.path("claims")).hasSize(1); assertThat(report.path("citations")).hasSize(1);
        }
    }
    @Test void allVerifiedCriteriaMayCompleteIncludingRefutedClaimButGoalGapPreventsSuccess() {
        var h = new Harness(); h.source("original","web","Version: 1.0\n\nLimit: 10"); var source = h.read("original","read");
        var check = h.prepare(List.of(spec("The request limit is 10.","1.0"),spec("The request limit is 20.","1.0")),List.of(source),0,null,"root");
        var result = h.complete(check,propose(check.request()),"complete");
        var claims = result.records().stream().filter(r -> r.path("record_type").asText().equals("Claim")).toList();
        assertThat(statuses(result)).containsExactly("supported","refuted");
        var criteria = new ArrayList<EvidenceAuthority.ReportCriterion>();
        for (int n=0; n<claims.size(); n++) criteria.add(new EvidenceAuthority.ReportCriterion("criterion-"+n,"Verify limit hypothesis "+n,"resolved",
                List.of(check.check_id()),List.of(claims.get(n).path("claim_id").asText()),List.of()));
        h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Verify both hypotheses","done",true,criteria,List.of()));
        var complete = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(complete.path("terminal_status").asText()).isEqualTo("SUCCEEDED");
        assertThat(complete.path("goals").get(0).path("criteria")).hasSize(2);
        h.goals = List.of(new EvidenceAuthority.ReportGoal("task-main","Verify both hypotheses","done",true,criteria,List.of("Dependency proof changed; explicit recheck needed")));
        var partial = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("partial")));
        assertThat(partial.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(partial.path("answer").asText()).contains("Dependency proof changed","已支持","被反驳的主张");
    }
    @Test void capacityFailureAfterSupportedCheckIsDurableAndCannotBeHiddenBySubsetOrNewCall() {
        var h = new Harness(); var originals = new ArrayList<JsonNode>();
        for (int n=0; n<5; n++) { h.source("source-"+n,"web","Version: 1.0\n\nLimit: " + (n==4 ? "20" : "10")); originals.add(h.read("source-"+n,"read-"+n)); }
        var specs = List.of(spec("The request limit is 10.","1.0")); var root = h.prepare(specs,originals.subList(0,4),0,null,"root"); h.complete(root,propose(root.request()),"complete");
        assertThatThrownBy(() -> h.prepare(specs,List.of(originals.get(4)),1,root.check_id(),"new-material")).hasMessageContaining("EVIDENCE_CAPACITY_EXCEEDED");
        assertThatThrownBy(() -> h.prepare(specs,List.of(originals.get(0)),1,root.check_id(),"drop-new-material")).hasMessageContaining("EVIDENCE_CAPACITY_EXCEEDED");
        var restored = new EvidenceService(h,store,(g,c) -> { throw new AssertionError("No provider read needed for web report"); });
        var report = restored.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("report_status").asText()).isEqualTo("partial");
        assertThat(report.path("answer").asText()).contains("已支持","EVIDENCE_CAPACITY_EXCEEDED");
        assertThat(report.path("terminal_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_blocked_attempt WHERE run_id=?",Integer.class,h.run)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT has_table_privilege('deepresearch_workflow','agent_evidence_blocked_attempt','INSERT')",Boolean.class)).isFalse();
        assertThatThrownBy(() -> db.update("UPDATE agent_evidence_blocked_attempt SET payload='{}'::jsonb WHERE run_id=?",h.run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void oversizedOriginalRequestPersistsGapWithoutCreatingFakeCheckOrDecision() {
        var h = new Harness(); var originals = new ArrayList<JsonNode>();
        for (int n=0; n<2; n++) { h.source("large-"+n,"web","Version: 1.0\n\n" + "🧪".repeat(8500) + "\n\nLimit: 10"); originals.add(h.read("large-"+n,"read-"+n)); }
        var specs = List.of(spec("The request limit is 10.","1.0"));
        assertThatThrownBy(() -> h.prepare(specs,originals,0,null,"too-large")).hasMessageContaining("CHECK_REQUEST_TOO_LARGE");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_check WHERE run_id=?",Integer.class,h.run)).isZero();
        var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("report_status").asText()).isEqualTo("insufficient"); assertThat(report.path("claims")).isEmpty();
        assertThat(report.path("answer").asText()).contains("The request limit is 10.","CHECK_REQUEST_TOO_LARGE");
    }

    @Test void unfinishedSupplementCannotPublishOldCompleteStateAsComplete() {
        var h = new Harness(); h.source("original","web","Version: 1.0\n\nLimit: 10"); var source = h.read("original","read");
        var specs = List.of(spec("The request limit is 10.","1.0")); var root = h.prepare(specs,List.of(source),0,null,"root"); h.complete(root,propose(root.request()),"complete-root");
        h.prepare(specs,List.of(source),1,root.check_id(),"pending-supplement");
        var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
        assertThat(report.path("report_status").asText()).isEqualTo("partial"); assertThat(report.path("unfinished_goals")).isNotEmpty();
        assertThat(report.path("answer").asText()).contains("已支持","Check has no completed");
    }

    @Test void allFourCasesGoThroughRealReadParseCheckRecordAndPacketPaths() throws Exception {
        var scenarios = JSON.readTree(Files.readString(Path.of("testdata/agent-round1/evidence/scenarios.json")));
        for (JsonNode scenario : scenarios.path("cases")) {
            var harness = new Harness(); var sources = new ArrayList<JsonNode>();
            for (JsonNode source : scenario.path("sources")) { harness.source(source.path("id").asText(),source.path("kind").asText(),Files.readString(Path.of(source.path("path").asText()))); sources.add(harness.read(source.path("id").asText(),"read-"+source.path("id").asText())); }
            var specs = new ArrayList<EvidenceDtos.ClaimSpec>(); scenario.path("claims").forEach(c->specs.add(spec(c.path("text").asText(),c.path("version").asText())));
            var prepared = harness.prepare(specs,sources,0,null,"check-root"); var result = harness.complete(prepared,propose(prepared.request()),"check-complete");
            assertThat(statuses(result)).containsExactlyElementsOf(JSON.convertValue(scenario.path("expected_statuses"),List.class));
            assertThat(result.semantic_truth_guaranteed()).isFalse();
            JsonNode packet = harness.service.packet("test-service-token",new EvidenceDtos.PacketRequest(harness.ids("packet"),List.of(prepared.check_id())));
            assertThat(harness.service.packet("test-service-token",new EvidenceDtos.PacketRequest(harness.ids("packet"),List.of(prepared.check_id())))).isEqualTo(packet);
            harness.export(scenario.path("id").asText(),result,sources,packet);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_record WHERE run_id=?",Integer.class,harness.run)).isEqualTo(sources.size()+result.records().size()+1);
            if (statuses(result).stream().allMatch(s->s.equals("supported"))) {
                List<String> claimIds = result.records().stream().filter(r->r.path("record_type").asText().equals("Claim")).map(r->r.path("claim_id").asText()).toList();
                var published = harness.service.publish("test-service-token",new EvidenceDtos.PublishRequest(harness.ids("publish"),packet.path("packet_id").asText(),claimIds));
                assertThat(published.path("answer_sha256").asText()).isEqualTo(sha(published.path("answer").asText())); assertThat(published.path("citations")).hasSize(2);
                assertThat(published.path("answer").asText()).contains("适用版本：1.0", "适用版本：2.0");
            }
        }
    }
    @Test void changedOriginalChangesAcceptableResultAndOldQuoteIsRejected() {
        List<String> outcomes = new ArrayList<>();
        for (String measured : List.of("10","99")) {
            var h = new Harness(); h.source("guide","web","Version: 1.0\n\nLimit: 99"); h.source("test","controlled_test","Version: 1.0\n\nLimit: "+measured);
            var sources = List.of(h.read("guide","read-guide"),h.read("test","read-test")); var prepared=h.prepare(List.of(spec("The request limit is 99.","1.0")),sources,0,null,"root");
            var response=propose(prepared.request()); outcomes.add(statuses(h.complete(prepared,response,"complete")).get(0));
            if (measured.equals("99")) {
                var tampered=(ObjectNode)propose(prepared.request(),true); var quote=(ObjectNode)tampered.path("claims").get(0).path("relations").get(1).path("quote");
                quote.put("text","Limit: 10").put("sha256",sha("Limit: 10"));
                assertThatThrownBy(()->new EvidenceAdjudicator().adjudicate(h.current("verify"),prepared.request(),tampered,"assessment",Map.of(sources.get(0).path("evidence_id").asText(),object("verified_observation",false),sources.get(1).path("evidence_id").asText(),object("verified_observation",true)))).hasMessageContaining("CHECK_QUOTE_BINDING_INVALID");
            }
        }
        assertThat(outcomes).containsExactly("refuted","supported");
    }
    @Test void twoSupplementRoundsPersistHistoryAndCannotResetByChangingCallId() {
        var h=new Harness(); h.source("one","web","Version: 1.0\n\nLimit: 10"); h.source("two","web","Version: 1.0\n\nLimit: 20");
        var evidence=List.of(h.read("one","read-one"),h.read("two","read-two")); String parent=null;
        for(int round=0;round<=2;round++) {
            var prepared=h.prepare(List.of(spec("The request limit is 10.","1.0")),evidence,round,parent,"prepare-"+round);
            var result=h.complete(prepared,propose(prepared.request()),"complete-"+round); assertThat(statuses(result)).containsExactly("contested"); parent=prepared.check_id();
            if(round==2) assertThat(result.follow_up_actions()).allSatisfy(a->assertThat(a.path("action").asText()).isEqualTo("stop_with_gaps"));
        }
        String last=parent;
        assertThatThrownBy(()->h.prepare(List.of(spec("The request limit is 10.","1.0")),evidence,3,last,"more")).hasMessageContaining("CHECK_REQUEST_INVALID");
        assertThatThrownBy(()->h.prepare(List.of(spec("The request limit is 10.","1.0")),evidence,0,null,"new-root")).hasMessageContaining("CHECK_IDEMPOTENCY_CONFLICT");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_check WHERE run_id=?",Integer.class,h.run)).isEqualTo(3);
    }
    @Test void wrongHashPartialQuoteUnknownVersionAndUnbudgetedResultFailThroughService() {
        var h=new Harness(); h.source("qualified","web","Version: 1.0\n\nLimit: 99 only in legacy mode; the general limit is 10.");
        var original=h.read("qualified","read-qualified"); var specs=List.of(spec("The request limit is 99.","1.0"));
        var prepared=h.prepare(specs,List.of(original),0,null,"root");
        var partial=(ObjectNode)propose(prepared.request(),true); var quote=(ObjectNode)partial.path("claims").get(0).path("relations").get(0).path("quote");
        quote.put("end",quote.path("start").asInt()+9).put("text","Limit: 99").put("sha256",sha("Limit: 99"));
        assertThatThrownBy(()->h.complete(prepared,partial,"partial")).hasMessageContaining("CHECK_QUOTE_CONTEXT_INCOMPLETE");
        var badHash=(ObjectNode)propose(prepared.request(),true); ((ObjectNode)badHash.path("claims").get(0).path("relations").get(0).path("quote")).put("sha256","0".repeat(64));
        assertThatThrownBy(()->h.complete(prepared,badHash,"hash")).hasMessageContaining("CHECK_QUOTE_BINDING_INVALID");
        assertThatThrownBy(()->h.service.complete("test-service-token",new EvidenceDtos.CompleteRequest(h.ids("no-budget"),prepared.check_id(),"invented-model",propose(prepared.request())))).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        var corrupt=(ObjectNode)original.deepCopy(); corrupt.put("evidence_id","corrupt-snapshot"); ((ObjectNode)corrupt.path("snapshot")).put("sha256","0".repeat(64));
        store.transaction(h.current("insert-corrupt"),()->{store.put(h.current("insert-corrupt"),corrupt);return null;});
        assertThatThrownBy(()->h.prepare(specs,List.of(corrupt),0,null,"wrong-snapshot")).hasMessageContaining("EVIDENCE_RECEIPT_BINDING_INVALID");
        var unknown=new Harness(); unknown.source("unknown","web","Limit: 99"); var unknownEvidence=unknown.read("unknown","read");
        var check=unknown.prepare(specs,List.of(unknownEvidence),0,null,"check"); assertThat(statuses(unknown.complete(check,propose(check.request()),"complete"))).containsExactly("insufficient");
    }
    static List<JsonNode> managedOriginals(Harness h, int count) {
        when(h.ragflow.datasets()).thenReturn(List.of("dataset-evidence"));
        var originals = new ArrayList<JsonNode>();
        for (int n=0; n<count; n++) {
            String identity = "managed-" + h.run + "-" + n; h.source(identity, "knowledge", "Version: 1.0\n\nLimit: 10");
            var c = h.candidates.get(identity); String legacy = h.run + "-" + n;
            db.update("INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,status) VALUES (?,'managed','text','managed.md','text','h',1,'DONE')", legacy);
            db.update("INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,version,content_hash,sync_status) VALUES (?,?,?,1,'h','DONE')", legacy, c.datasetId(), c.documentId());
            when(h.ragflow.chunk(c.datasetId(),c.documentId(),c.chunkId())).thenReturn(object("id",c.chunkId(),"doc_id",c.documentId(),"content",h.bodies.get(identity)));
            originals.add(h.read(identity, "read-" + identity));
        }
        return originals;
    }
    static void completeTwoManagedInvestigations(Harness h, List<JsonNode> originals) {
        var first = h.prepare(List.of(spec("The request limit is 10 for partition A.", "1.0")), originals.subList(0,2), 0, null, "check-A");
        var second = h.prepare(List.of(spec("The request limit is 10 for partition B.", "1.0")), originals.subList(2,originals.size()), 0, null, "check-B");
        assertThat(statuses(h.complete(first,propose(first.request()),"complete-A"))).containsExactly("supported");
        assertThat(statuses(h.complete(second,propose(second.request()),"complete-B"))).containsExactly("supported");
    }
    @Test void fullReportRevalidatesTwoPlusTwoAndTwoPlusThreeKnowledgeOriginals() {
        for (int count : List.of(4,5)) {
            var h = new Harness(); var originals = managedOriginals(h,count); completeTwoManagedInvestigations(h,originals);
            var report = h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report")));
            assertThat(report.path("terminal_status").asText()).isEqualTo("SUCCEEDED");
            assertThat(report.path("claims")).hasSize(2); assertThat(report.path("citations")).hasSize(count);
            assertThat(report.path("validation_receipts")).hasSize(count); assertThat(h.publicationSettlements).isEqualTo(count);
            for (var candidate : h.candidates.values()) verify(h.ragflow,times(2)).chunk(candidate.datasetId(),candidate.documentId(),candidate.chunkId());
        }
    }
    @Test void reportBudgetDenialCannotApproveSupportedSubsetOrEraseOtherInvestigation() {
        var h = new Harness(); var originals = managedOriginals(h,5); completeTwoManagedInvestigations(h,originals);
        h.publicationReadBudget = 4;
        assertThatThrownBy(() -> h.service.report("test-service-token",new EvidenceDtos.ReportRequest(h.ids("report"))))
                .hasMessageContaining("AGENT_BUDGET_EXCEEDED");
        assertThat(h.publicationSettlements).isEqualTo(4);
        assertThat(store.checks(h.current("history"))).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.check().status()).isEqualTo("COMPLETED");
            assertThat(statuses(JSON.convertValue(entry.check().result(),EvidenceDtos.RecordResult.class))).containsExactly("supported");
        });
        int calls = 0; for (var candidate : h.candidates.values()) calls += mockingDetails(h.ragflow).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("chunk") && invocation.getArgument(2).equals(candidate.chunkId())).count();
        assertThat(calls).isEqualTo(9); // Five original reads plus four permitted validations.
    }
    @Test void managedChunkIdentityActiveRegistryAndPublicationContentChangeAreEnforced() {
        var h=new Harness(); h.source("managed","knowledge","Version: 1.0\n\nLimit: 10"); var c=h.candidates.get("managed");
        db.update("INSERT INTO kb_document(doc_id,title,source_type,filename,raw_content,content_hash,version,status) VALUES (?,'managed','text','managed.md','text','h',1,'DONE')",h.run);
        db.update("INSERT INTO kb_ragflow_document(legacy_doc_id,dataset_id,document_id,version,content_hash,sync_status) VALUES (?,?,?,1,'h','DONE')",h.run,c.datasetId(),c.documentId());
        when(h.ragflow.datasets()).thenReturn(List.of(c.datasetId()));
        when(h.ragflow.chunk(c.datasetId(),c.documentId(),c.chunkId())).thenReturn(object("id",c.chunkId(),"doc_id",c.documentId(),"content",h.bodies.get("managed")));
        var evidence=h.read("managed","read-managed"); assertThat(evidence.path("snapshot").path("kind").asText()).isEqualTo("document_chunk");
        var check=h.prepare(List.of(spec("The request limit is 10.","1.0")),List.of(evidence),0,null,"check"); var result=h.complete(check,propose(check.request()),"complete");
        var packet=h.service.packet("test-service-token",new EvidenceDtos.PacketRequest(h.ids("packet"),List.of(check.check_id())));
        String claimId=result.records().stream().filter(r->r.path("record_type").asText().equals("Claim")).findFirst().orElseThrow().path("claim_id").asText();
        var publication=new EvidenceDtos.PublishRequest(h.ids("publish"),packet.path("packet_id").asText(),List.of(claimId));
        h.allowPublicationRead=false;
        assertThatThrownBy(()->h.service.publish("test-service-token",publication)).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        verify(h.ragflow,times(1)).chunk(c.datasetId(),c.documentId(),c.chunkId());
        assertThat(h.publicationSettlements).isZero(); h.allowPublicationRead=true;
        h.originalCandidates.put("managed:"+c.parentReceiptId(),new EvidenceAuthority.Candidate(c.sourceId(),c.kind(),c.url(),c.datasetId(),"substituted-doc",c.chunkId(),c.title(),c.parentReceiptId()));
        assertThatThrownBy(()->h.service.publish("test-service-token",publication)).hasMessageContaining("PUBLICATION_SOURCE_IDENTITY_CHANGED");
        verify(h.ragflow,times(1)).chunk(c.datasetId(),c.documentId(),c.chunkId()); h.originalCandidates.put("managed:"+c.parentReceiptId(),c);
        var published=h.service.publish("test-service-token",publication);
        assertThat(published.path("citations")).hasSize(1); assertThat(published.path("validation_receipts")).hasSize(1);
        assertThat(h.publicationSettlements).isEqualTo(1);
        when(h.ragflow.chunk(c.datasetId(),c.documentId(),c.chunkId())).thenReturn(object("id",c.chunkId(),"doc_id",c.documentId(),"content","Version: 1.0\n\nLimit: 99"));
        assertThatThrownBy(()->h.service.publish("test-service-token",publication)).hasMessageContaining("PUBLICATION_SOURCE_CHANGED");
        assertThat(h.publicationSettlements).isEqualTo(2);
        when(h.ragflow.chunk(c.datasetId(),c.documentId(),c.chunkId())).thenReturn(object("id","different","doc_id",c.documentId(),"content","wrong"));
        assertThatThrownBy(()->h.read("managed","read-wrong-id")).hasMessageContaining("SOURCE_KB_IDENTITY_CHANGED");
        db.update("UPDATE kb_document SET status='FAILED' WHERE doc_id=?",h.run);
        assertThatThrownBy(()->h.read("managed","read-inactive")).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
    }
    @Test void authIsolationCancellationAndReadCompletionRollbackAreRealDatabaseBoundaries() {
        var h=new Harness(); h.source("page","web","Version: 1.0\n\nLimit: 10");
        assertThatThrownBy(()->h.service.read("bad-token",new EvidenceDtos.ReadRequest(h.ids("read"),"page"))).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        assertThatThrownBy(()->h.service.read("test-service-token",new EvidenceDtos.ReadRequest(new EvidenceDtos.Identifiers("other-project",h.run,"task-main","read",h.claim),"page"))).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        h.failCommit=true; assertThatThrownBy(()->h.read("page","rollback-read")).hasMessageContaining("TEST_COMMIT_FAILED");
        assertThat(db.queryForObject("SELECT status FROM agent_workflow_tool_receipt WHERE run_id=? AND call_id='rollback-read'",String.class,h.run)).isEqualTo("EXECUTING");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_record WHERE run_id=?",Integer.class,h.run)).isZero();
        h.failCommit=false; h.onFetch=()->db.update("UPDATE agent_workflow_run SET cancel_requested=true WHERE run_id=?",h.run);
        assertThatThrownBy(()->h.read("page","cancelled-read")).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_record WHERE run_id=?",Integer.class,h.run)).isZero();
    }
    @Test void immutableRecordsIdempotentReadAndSidecarWriteDenialPersist() {
        var h=new Harness(); h.source("page","web","Version: 1.0\n\nLimit: 10"); var original=h.read("page","read");
        assertThat(h.read("page","read")).isEqualTo(original);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_read_receipt WHERE run_id=?",Integer.class,h.run)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE agent_evidence_record SET payload=payload||'{\"text\":\"replacement\"}'::jsonb WHERE run_id=?",h.run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE agent_evidence_read_receipt SET metadata='{}'::jsonb WHERE run_id=?",h.run)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT has_table_privilege('deepresearch_workflow','agent_evidence_record','INSERT')",Boolean.class)).isFalse();
        var other=new Harness(); assertThatThrownBy(()->store.get(other.current("lookup"),"Evidence",original.path("evidence_id").asText())).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
    }
    @Test void explicitTimeScopeCannotBeInventedAndUnversionedSnapshotRemainsConditional() {
        var h=new Harness(); h.source("dated","web","Version: 1.0\n\nValid at: 2020-01-01T00:00:00Z\n\nLimit: 10");
        var evidence=h.read("dated","read"); var application=(ObjectNode)spec("The request limit is 10.","1.0").applicability().deepCopy();
        application.set("valid_at",known("2026-09-29T08:00:00Z"));
        var prepared=h.prepare(List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.","factual",application)),List.of(evidence),0,null,"prepare");
        var result=h.complete(prepared,propose(prepared.request()),"complete"); assertThat(statuses(result)).containsExactly("insufficient");
        var unknown=new Harness(); unknown.source("unversioned","web","Limit: 10"); var raw=unknown.read("unversioned","read");
        var unversioned=(ObjectNode)spec("The request limit is 10.","1.0").applicability().deepCopy(); unversioned.set("version",unknown("Question has no declared software version"));
        var check=unknown.prepare(List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.","factual",unversioned)),List.of(raw),0,null,"prepare");
        var output=unknown.complete(check,propose(check.request()),"complete"); assertThat(statuses(output)).containsExactly("supported");
        var packet=unknown.service.packet("test-service-token",new EvidenceDtos.PacketRequest(unknown.ids("packet"),List.of(check.check_id())));
        String accepted=output.records().get(0).path("claim_id").asText();
        assertThat(unknown.service.publish("test-service-token",new EvidenceDtos.PublishRequest(unknown.ids("publish"),packet.path("packet_id").asText(),List.of(accepted))).path("answer").asText()).contains("仅描述引用快照");
    }
    @Test void factEffectiveTimeRequiresRealOffsetTimestampWhileVersionRemainsText() {
        var h=new Harness(); h.source("declared-time","web","Version: 2026-09-27 修订版\n\nValid at: 2026-09-27T00:00:00Z\n\nLimit: 10");
        var evidence=h.read("declared-time","read-declared-time");
        assertThat(evidence.path("applicability").path("valid_at").path("value").asText()).isEqualTo("2026-09-27T00:00:00Z");
        for (String invalid : List.of("2026-09-27 修订版","2026-09-27","2026-09-27T00:00:00","2026-02-30T00:00:00Z","2026-09-27T00:00:00+18:01")) {
            var application=(ObjectNode)spec("The request limit is 10.","2026-09-27 修订版").applicability().deepCopy();
            application.set("valid_at",known(invalid));
            assertThatThrownBy(()->h.prepare(List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.","factual",application)),List.of(evidence),0,null,"bad-time-"+invalid.hashCode()))
                    .hasMessageContaining("CHECK_REQUEST_INVALID");
        }
        var knownScope=(ObjectNode)spec("The request limit is 10.","2026-09-27 修订版").applicability().deepCopy();
        knownScope.set("valid_at",known("2026-09-27T08:00:00+08:00"));
        assertThat(h.prepare(List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.","factual",knownScope)),List.of(evidence),0,null,"known-time").check_id()).isNotBlank();
        var unknownScope=(ObjectNode)spec("The request limit is 10.","2026-09-27 修订版").applicability().deepCopy();
        assertThat(h.prepare(List.of(new EvidenceDtos.ClaimSpec("The request limit is 10.","factual",unknownScope)),List.of(evidence),0,null,"unknown-time").check_id()).isNotBlank();
    }
    @Test void databaseOwnerFenceRejectsDifferentAuthenticatedOwnerBeforeRead() {
        var h = new Harness(); h.source("page","web","Version: 1.0\n\nLimit: 10");
        h.principal = new AuthPrincipal("fixture-tenant","different-owner",List.of("USER"));
        h.onFetch = () -> fail("Owner fence must reject before provider access");
        assertThatThrownBy(() -> h.read("page","cross-owner-read")).hasMessageContaining("EVIDENCE_ACCESS_DENIED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_evidence_read_receipt WHERE run_id=?",Integer.class,h.run)).isZero();
    }
}
