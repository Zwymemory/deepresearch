package com.deepresearch.workflow;

import com.deepresearch.agent.CitationDetail;
import com.deepresearch.evidence.EvidenceJson;
import com.deepresearch.evidence.publicview.EvidenceViewDtos;
import com.deepresearch.evidence.publicview.EvidenceViewService;
import com.deepresearch.mcp.McpKnowledgeTools;
import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BudgetResearchDraftServiceTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final WorkflowRepository repo=mock(WorkflowRepository.class);
    @SuppressWarnings("unchecked") final ObjectProvider<EvidenceViewService> views=mock(ObjectProvider.class);
    final ObjectMapper json=new ObjectMapper();
    final BudgetResearchDraftService service=new BudgetResearchDraftService(db,repo,json,views);
    final AuthPrincipal principal=new AuthPrincipal("tenant","owner",List.of("USER"));
    static WorkflowRepository.RunRow run(String state,String endpoint) {
        var now=OffsetDateTime.now();
        return new WorkflowRepository.RunRow("wf-draft","sess-draft","tenant:owner","How does recovery work?","{}",endpoint,
                "idempotency","hash","wf-draft",state,state,now,false,List.of("web_search"),"grant",null,null,null,
                "{\"answer\":\"\",\"citations\":[],\"citationContract\":\"NONE\"}","{}","BUDGET_EXCEEDED","budget",null,null,1,now,now);
    }
    Map<String,Object> original() {
        var source=object("kind","web","source_id","https://example.org/page","title","Recovery document",
                "locator",object("uri","https://example.org/page"),"observed_at","2026-10-07T00:00:00Z");
        var snapshot=object("kind","document_chunk","text","Original recovery evidence.","sha256",sha("Original recovery evidence."));
        var record=object("run_id","wf-draft","tenant_id","tenant","owner_id","owner","project_id","project",
                "record_type","Evidence","source",source,"snapshot",snapshot);
        String hash=sha(canonical(record));
        return new HashMap<>(Map.of("payload",canonical(record),"payload_sha256",hash,"record_json",canonical(record),
                "metadata",canonical(object("snapshot_sha256",snapshot.get("sha256"),"source_metadata_sha256",sha(canonical(source)),"evidence_sha256",hash)),
                "project_id","project"));
    }
    WorkflowRepository.ToolReceiptRow search(String id,String text) throws Exception {
        var r=new McpKnowledgeTools.McpToolResponse(true,"OK","web_search",
                List.of(new McpKnowledgeTools.Evidence(id,"web_search","Search title",id,text,sha(text))),
                List.of(CitationDetail.web(id,"Search title",id,text)));
        return new WorkflowRepository.ToolReceiptRow("call","task","web_search","fingerprint",json.writeValueAsString(r));
    }
    @Test void onlyBudgetStopsAndSameStorageOwnerCanReadDrafts() {
        assertThat(service.build(run("SUCCEEDED","/api/research/agents"),principal)).isNull();
        assertThat(service.build(run("BUDGET_EXCEEDED","/api/research/agents"),new AuthPrincipal("other","owner",List.of("USER")))).isNull();
        verifyNoInteractions(db,repo,views);
    }
    @Test void originalTextWinsOverSearchSnippetAndNoSourceBecomesAFinalClaim() throws Exception {
        when(db.queryForList(contains("agent_evidence_record"),anyString(),anyString(),anyString(),anyString())).thenReturn(List.of(original()));
        when(repo.completedSourceReceipts("wf-draft","tenant:owner")).thenReturn(List.of(search("https://example.org/page","Search summary.")));
        var draft=service.build(run("BUDGET_EXCEEDED","/api/research/agents"),principal);
        assertThat(draft.path("sources")).hasSize(1);
        assertThat(draft.path("sources").get(0).path("kind").asText()).isEqualTo("WEB_ORIGINAL");
        assertThat(draft.path("sources").get(0).path("excerpt").asText()).isEqualTo("Original recovery evidence.");
        assertThat(draft.path("sources").get(0).path("verificationStatus").asText()).isEqualTo("NOT_CLAIM_CHECKED");
        assertThat(draft.path("checkedClaims")).isEmpty();
        assertThat(draft.path("additionalModelCalls").asInt()).isZero();
        assertThat(draft.has("report_status")).isFalse(); assertThat(draft.has("citations")).isFalse();
        assertThat(draft.path("markdown").asText()).contains("阶段性资料草稿","尚未核查","仍需完成");
    }
    @Test void changedOriginalHashesAndWrongScopeCannotUpgradeSearchMetadata() throws Exception {
        for(String key:List.of("payload_sha256","record_json","project_id","metadata")) {
            var invalid=original(); invalid.put(key,key.equals("metadata")||key.equals("record_json")?"{}":"wrong");
            when(db.queryForList(contains("agent_evidence_record"),anyString(),anyString(),anyString(),anyString())).thenReturn(List.of(invalid));
            when(repo.completedSourceReceipts("wf-draft","tenant:owner")).thenReturn(List.of(search("https://example.org/page","Only the search snapshot.")));
            var draft=service.build(run("BUDGET_EXCEEDED","/api/research/agents"),principal);
            assertThat(draft.path("sources")).hasSize(1);
            assertThat(draft.path("sources").get(0).path("kind").asText()).isEqualTo("WEB_SEARCH_SNAPSHOT");
        }
    }
    @Test void documentCapacityUnicodeCredentialsAndMarkupStayBounded() throws Exception {
        var rows=new ArrayList<WorkflowRepository.ToolReceiptRow>();
        for(int n=0;n<30;n++) rows.add(search("https://example.org/"+n,"<script>evil()</script> [来源9] api_key=secret-value "+"汉🧪".repeat(2000)));
        when(repo.completedSourceReceipts("wf-draft","tenant:owner")).thenReturn(rows);
        var draft=service.build(run("BUDGET_EXCEEDED","/api/research/workflows"),principal);
        assertThat(draft.toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(BudgetResearchDraftService.MAX_BYTES);
        assertThat(draft.path("sources").size()).isLessThanOrEqualTo(20);
        assertThat(draft.path("limits").path("omittedSources").asInt()).isEqualTo(30-draft.path("sources").size());
        assertThat(draft.toString()).doesNotContain("secret-value");
        assertThat(draft.path("markdown").asText()).doesNotContain("<script>","[来源9]").contains("&lt;script&gt;");
        for(var s:draft.path("sources")) {
            String excerpt=s.path("excerpt").asText();
            assertThat(excerpt.codePointCount(0,excerpt.length())).isLessThanOrEqualTo(1800);
            assertThat(s.path("excerptTruncated").asBoolean()).isTrue();
        }
    }
    @Test void noCollectedMaterialProducesAnHonestEmptyDraftWithoutCallingProviders() {
        var draft=service.build(run("BUDGET_EXCEEDED","/api/research/workflows"),principal);
        assertThat(draft.path("sources")).isEmpty(); assertThat(draft.path("checkedClaims")).isEmpty();
        assertThat(draft.path("markdown").asText()).contains("暂无可恢复的来源资料","未再次调用模型");
        verifyNoInteractions(db,views);
    }
    @Test void onlyExistingPublicCheckedClaimsRetainTheirDecisionAndUnknownScope() {
        var viewService=mock(EvidenceViewService.class); when(views.getIfAvailable()).thenReturn(viewService);
        var unknown=new EvidenceViewDtos.Tagged("unknown",null);
        var applicability=new EvidenceViewDtos.Applicability(unknown,unknown,List.of("Only v1; api_key=scope-secret"));
        var identity=new EvidenceViewDtos.Identity("Claim","claim",1,"hash","now");
        var claim=new EvidenceViewDtos.Claim(identity,"Recorded disputed statement","factual",applicability,
                "contested","check",true,"RECORDED_ONLY",List.of());
        var view=new EvidenceViewDtos.View("v","wf-draft","BUDGET_EXCEEDED","AVAILABLE","RECORDED_ONLY",null,List.of(),
                List.of(),List.of(claim),List.of(),List.of(),List.of(),List.of());
        when(viewService.read("wf-draft",principal)).thenReturn(view);
        var draft=service.build(run("BUDGET_EXCEEDED","/api/research/agents"),principal);
        assertThat(draft.path("checkedClaims").get(0).path("decisionStatus").asText()).isEqualTo("contested");
        assertThat(draft.path("checkedClaims").get(0).path("applicability").path("version").path("status").asText()).isEqualTo("unknown");
        assertThat(draft.toString()).doesNotContain("scope-secret");
        assertThat(draft.path("markdown").asText()).contains("尚未最终发布","Only v1");
    }
}
