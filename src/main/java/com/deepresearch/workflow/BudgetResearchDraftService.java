package com.deepresearch.workflow;

import com.deepresearch.agent.CitationDetail;
import com.deepresearch.agent.CitationSourceSupport;
import com.deepresearch.agent.ToolOutputSanitizer;
import com.deepresearch.evidence.EvidenceJson;
import com.deepresearch.evidence.publicview.EvidenceViewService;
import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Mechanical recovery from Java-owned records: zero provider calls and no publication seal.
 * Search snippets/original text remain unverified material; recorded checks retain their scope.
 */
@Service
public class BudgetResearchDraftService {
    static final int SOURCES = 20, EXCERPT = 1800, MAX_BYTES = 98304;
    private final JdbcTemplate db;
    private final WorkflowRepository repository;
    private final ObjectMapper json;
    private final ObjectProvider<EvidenceViewService> views;
    public BudgetResearchDraftService(JdbcTemplate db, WorkflowRepository repository, ObjectMapper json,
                                     ObjectProvider<EvidenceViewService> views) {
        this.db=db; this.repository=repository; this.json=json; this.views=views;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ObjectNode build(WorkflowRepository.RunRow run, AuthPrincipal principal) {
        if (!"BUDGET_EXCEEDED".equals(run.status()) || !principal.storageUserId().equals(run.userId())) return null;
        var sources = new LinkedHashMap<String, ObjectNode>();
        var checked = new ArrayList<JsonNode>();
        var pending = new ArrayList<JsonNode>();
        boolean agent = "/api/research/agents".equals(run.endpoint());
        if (agent) {
            // Never trust sidecar operation.safe_result as an original source or checked claim.
            for (var row : db.queryForList("""
                SELECT e.payload::text AS payload,e.payload_sha256,r.record_json::text AS record_json,
                    r.metadata::text AS metadata,a.project_id
                FROM agent_evidence_record e JOIN agent_research_run a ON a.run_id=e.run_id
                JOIN agent_workflow_run w ON w.run_id=a.run_id
                JOIN agent_evidence_read_receipt r ON r.receipt_id=e.read_receipt_id
                    AND r.tenant_id=e.tenant_id AND r.owner_id=e.owner_id AND r.project_id=e.project_id AND r.run_id=e.run_id
                WHERE a.run_id=? AND a.tenant_id=? AND a.owner_id=? AND w.user_id=? AND w.status='BUDGET_EXCEEDED'
                    AND e.tenant_id=a.tenant_id AND e.owner_id=a.owner_id AND e.project_id=a.project_id
                    AND e.record_type='Evidence' AND r.status='COMPLETED'
                ORDER BY e.created_at,e.record_id LIMIT 129
                """, run.runId(), principal.tenantId(), principal.userId(), principal.storageUserId())) {
                try {
                    JsonNode record=json.readTree((String)row.get("payload"));
                    JsonNode receipt=json.readTree((String)row.get("record_json"));
                    JsonNode metadata=json.readTree((String)row.get("metadata"));
                    var source=record.path("source"); var snapshot=record.path("snapshot");
                    if (!run.runId().equals(record.path("run_id").asText())
                            || !principal.tenantId().equals(record.path("tenant_id").asText())
                            || !principal.userId().equals(record.path("owner_id").asText())
                            || !Objects.equals(row.get("project_id"),record.path("project_id").asText())
                            || !"Evidence".equals(record.path("record_type").asText())
                            || !EvidenceJson.sha(EvidenceJson.canonical(record)).equals(row.get("payload_sha256"))
                            || !EvidenceJson.canonical(record).equals(EvidenceJson.canonical(receipt))
                            || !EvidenceJson.sha(snapshot.path("text").asText()).equals(snapshot.path("sha256").asText())
                            || !snapshot.path("sha256").asText().equals(metadata.path("snapshot_sha256").asText())
                            || !EvidenceJson.sha(EvidenceJson.canonical(source)).equals(metadata.path("source_metadata_sha256").asText())
                            || !Objects.equals(row.get("payload_sha256"),metadata.path("evidence_sha256").asText())) continue;
                    String kind=source.path("kind").asText();
                    if (!Set.of("web","knowledge").contains(kind)) continue;
                    String id=source.path("source_id").asText();
                    var item=source(id, "web".equals(kind)?"WEB_ORIGINAL":"KNOWLEDGE_CHUNK",
                            source.path("title").asText(),source.path("locator").path("uri").asText(),
                            snapshot.path("text").asText(),source.path("observed_at").asText(),snapshot.path("truncated").asBoolean());
                    if (item != null) sources.putIfAbsent(id,item);
                } catch (Exception invalidRecord) { /* Invalid records never become apparent recovered evidence. */ }
            }
            var viewService=views.getIfAvailable();
            if (viewService != null) {
                try {
                    var view=viewService.read(run.runId(),principal);
                    var sourceIds=new HashMap<String,String>();
                    for(var ref:view.evidence()) sourceIds.put(ref.identity().recordId(),ref.sourceId());
                    for(var claim:view.claims()) if(claim.latestRecordedRound()) {
                        var item=json.createObjectNode(); item.put("text",safe(claim.text(),10000));
                        item.put("decisionStatus",claim.decisionStatus());
                        item.set("applicability",redacted(json.valueToTree(claim.applicability())));
                        item.set("sourceIds",json.valueToTree(claim.evidenceLinks().stream()
                                .map(link->sourceIds.get(link.evidenceId())).filter(Objects::nonNull)
                                .map(id->safe(id,2048)).distinct().limit(20).toList()));
                        checked.add(item);
                    }
                } catch (RuntimeException unavailableProjection) { /* Keep material; do not invent checked conclusions. */ }
            }
            for(var task:db.queryForList("""
                SELECT t.objective,t.status FROM agent_research_task t
                JOIN agent_research_run a ON a.run_id=t.run_id JOIN agent_workflow_run w ON w.run_id=a.run_id
                WHERE a.run_id=? AND a.tenant_id=? AND a.owner_id=? AND w.user_id=? AND t.status<>'done'
                ORDER BY t.task_id LIMIT 65
                """,run.runId(),principal.tenantId(),principal.userId(),principal.storageUserId())) {
                var item=json.createObjectNode(); item.put("text",safe((String)task.get("objective"),1000));
                item.put("status",safe((String)task.get("status"),40)); pending.add(item);
            }
        }
        // Java-authored MCP receipts also recover material from legacy durable workflows.
        for(var receipt:repository.completedSourceReceipts(run.runId(),run.userId())) {
            try {
                var response=json.readValue(receipt.safeResultJson(),com.deepresearch.mcp.McpKnowledgeTools.McpToolResponse.class);
                if (!response.success() || !receipt.toolName().equals(response.tool()) || response.evidence()==null
                        || response.sourceSnapshots().size()>10) continue;
                var ids=response.evidence().stream().filter(e->e!=null && receipt.toolName().equals(e.sourceType()))
                        .map(e->e.evidenceId()).collect(java.util.stream.Collectors.toSet());
                for(CitationDetail detail:response.sourceSnapshots()) {
                    String expected="web_search".equals(receipt.toolName())?"WEB_SEARCH_SNAPSHOT":"KNOWLEDGE_CHUNK";
                    if (!ids.contains(detail.sourceId()) || !"AVAILABLE".equals(detail.metadataStatus())
                            || !expected.equals(detail.kind()) || "web_search".equals(receipt.toolName()) && !detail.sourceId().equals(detail.url())) continue;
                    var item=source(detail.sourceId(),detail.kind(),detail.title(),detail.url(),detail.excerpt(),null,false);
                    if (item != null) sources.putIfAbsent(detail.sourceId(),item);
                }
            } catch (Exception malformed) { /* Historical receipts lacking trusted metadata remain unavailable. */ }
        }
        if (pending.isEmpty()) {
            var item=json.createObjectNode(); item.put("text","原问题尚未形成最终报告，仍需完成最终发布及复核未完成事项。");
            item.put("status","unresolved"); pending.add(item);
        }
        var draft=json.createObjectNode(); draft.put("schemaVersion","research-draft/1");
        draft.put("reasonCode","BUDGET_EXCEEDED"); draft.put("generatedFrom","STORED_RECORDS"); draft.put("additionalModelCalls",0);
        var selected=new ArrayList<JsonNode>(sources.values());
        int omittedSources=Math.max(0,selected.size()-SOURCES), omittedClaims=Math.max(0,checked.size()-20), omittedTasks=Math.max(0,pending.size()-32);
        selected=new ArrayList<>(selected.subList(0,Math.min(SOURCES,selected.size())));
        checked=new ArrayList<>(checked.subList(0,Math.min(20,checked.size())));
        pending=new ArrayList<>(pending.subList(0,Math.min(32,pending.size())));
        var limits=draft.putObject("limits"); limits.put("sourceLimit",SOURCES); limits.put("excerptChars",EXCERPT);
        for(;;) {
            limits.put("omittedSources",omittedSources); limits.put("omittedClaims",omittedClaims); limits.put("omittedTasks",omittedTasks);
            draft.set("sources",json.valueToTree(selected)); draft.set("checkedClaims",json.valueToTree(checked)); draft.set("pendingTasks",json.valueToTree(pending));
            draft.put("markdown",markdown(run,selected,checked,pending,limits));
            if (draft.toString().getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES) return draft;
            if (!selected.isEmpty()) { selected.remove(selected.size()-1); omittedSources++; }
            else if(!checked.isEmpty()) { checked.remove(checked.size()-1); omittedClaims++; }
            else if(pending.size()>1) { pending.remove(pending.size()-1); omittedTasks++; }
            else throw new IllegalStateException("Bounded draft capacity exceeded");
        }
    }

    ObjectNode source(String id,String kind,String title,String url,String text,String observed,boolean truncated) {
        if (id==null || id.isBlank() || text==null || text.isBlank()) return null;
        var item=json.createObjectNode(); item.put("sourceId",safe(id,2048)); item.put("kind",kind);
        item.put("title",safe(title==null || title.isBlank()?id:title,300));
        String safeUrl=CitationSourceSupport.safeWebUrl(url);
        if(safeUrl.isBlank()) item.putNull("url"); else item.put("url",safeUrl);
        String excerpt=safe(text,EXCERPT); item.put("excerpt",excerpt);
        item.put("excerptTruncated",truncated || text.codePointCount(0,text.length())>EXCERPT);
        if(observed==null || observed.isBlank()) item.putNull("observedAt"); else item.put("observedAt",safe(observed,64));
        item.put("verificationStatus","NOT_CLAIM_CHECKED"); return item;
    }
    static String safe(String text,int cap) {
        if(text==null) return "";
        String value=ToolOutputSanitizer.redactSecrets(text).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").trim();
        return value.substring(0,value.offsetByCodePoints(0,Math.min(cap,value.codePointCount(0,value.length()))));
    }
    static String quote(String text) {
        return "> "+safe(text,10000).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\\","\\\\").replaceAll("([`*_\\[\\]#])","\\\\$1").replace("\n","\n> ");
    }
    JsonNode redacted(JsonNode node) {
        if(node.isTextual()) return json.getNodeFactory().textNode(safe(node.textValue(),10000));
        if(node.isArray()) { var array=json.createArrayNode(); node.forEach(n->array.add(redacted(n))); return array; }
        if(node.isObject()) { var result=json.createObjectNode(); node.fields().forEachRemaining(e->result.set(e.getKey(),redacted(e.getValue()))); return result; }
        return node.deepCopy();
    }
    static String markdown(WorkflowRepository.RunRow run,List<JsonNode> sources,List<JsonNode> checked,List<JsonNode> pending,JsonNode limits) {
        var md=new StringBuilder("# 阶段性资料草稿\n\n预算已用尽；以下内容直接整理自已保存记录，未再次调用模型。资料摘录未经完整论断核查，不是最终研究报告。\n\n## 原始问题\n\n")
                .append(quote(run.question())).append("\n\n## 已记录的核查结论（尚未最终发布）\n\n");
        if(checked.isEmpty()) md.append("暂无已记录的可展示核查结论。\n\n");
        for(var c:checked) {
            String status=switch(c.path("decisionStatus").asText()) {
                case "supported" -> "有证据支持";
                case "refuted" -> "被证据反驳";
                case "contested" -> "存在争议";
                default -> "证据不足";
            };
            var scope=c.path("applicability");
            md.append(quote(c.path("text").asText())).append("\n\n核查状态：").append(status).append("；尚未最终发布。\n\n")
                    .append("适用版本：").append(quote(scope.path("version").path("value").asText("未确定"))).append("\n\n")
                    .append("有效时间：").append(quote(scope.path("validAt").path("value").asText("未确定"))).append("\n\n");
            for(var condition:scope.path("conditions")) md.append("附带条件：").append(quote(condition.asText())).append("\n\n");
            for(var sourceId:c.path("sourceIds")) md.append("关联来源：").append(quote(sourceId.asText())).append("\n\n");
        }
        md.append("## 已收集的资料（尚未核查）\n\n");
        if(sources.isEmpty()) md.append("暂无可恢复的来源资料。\n\n");
        for(int i=0;i<sources.size();i++) {
            var s=sources.get(i); md.append("### 资料 ").append(i+1).append("\n\n").append(quote(s.path("title").asText())).append("\n\n")
                    .append("类型：").append(switch(s.path("kind").asText()) {
                        case "WEB_SEARCH_SNAPSHOT" -> "仅搜索摘要";
                        case "KNOWLEDGE_CHUNK" -> "知识库片段";
                        default -> "已读取原文";
                    })
                    .append("；尚未核查。\n\n");
            if(s.hasNonNull("url")) md.append("来源地址：").append(quote(s.path("url").asText())).append("\n\n");
            md.append(quote(s.path("excerpt").asText())).append("\n\n");
            if(s.path("excerptTruncated").asBoolean()) md.append("摘录已截短，请核对完整来源。\n\n");
        }
        md.append("## 仍需完成\n\n");
        for(var task:pending) md.append(quote(task.path("text").asText())).append("\n\n");
        md.append("省略计数：资料 ").append(limits.path("omittedSources").asInt()).append("；核查结论 ")
                .append(limits.path("omittedClaims").asInt()).append("；待办 ").append(limits.path("omittedTasks").asInt()).append("。\n");
        return md.toString();
    }
}
