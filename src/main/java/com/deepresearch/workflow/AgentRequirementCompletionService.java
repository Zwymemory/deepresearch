package com.deepresearch.workflow;

import com.deepresearch.evidence.EvidenceAuthority;
import com.deepresearch.evidence.EvidenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Native original-question coverage; planner extraction semantics are not certified. */
public final class AgentRequirementCompletionService {
    private static final String VERSION="agent-original-requirements/1";
    private final JdbcTemplate db;
    public AgentRequirementCompletionService(JdbcTemplate db) { this.db=db; }

    public List<EvidenceAuthority.ReportGoal> goals(String run,List<EvidenceAuthority.ReportGoal> nativeGoals) {
        var stored=db.queryForList("""
            SELECT r.manifest::text AS manifest,w.question,o.safe_result::text AS declaration,o.status,o.kind,o.purpose
            FROM agent_research_requirements r JOIN agent_workflow_run w USING(run_id)
            JOIN agent_research_operation o ON o.run_id=r.run_id AND o.operation_key=r.declaration_key AND o.attempt=r.declaration_attempt
            WHERE r.run_id=?
            """,run);
        if(stored.size()!=1) return List.of(gap("original-requirements","Original question coverage","Original requirements have not been explicitly extracted"));
        var row=stored.get(0);JsonNode manifest=parse((String)row.get("manifest"));
        JsonNode declaration=parse((String)row.get("declaration")).path("value").path("requirements");
        String question=(String)row.get("question");
        if(!"SETTLED".equals(row.get("status")) || !"MODEL".equals(row.get("kind")) || !"DECISION".equals(row.get("purpose"))
                || !validManifest(manifest,run,question,declaration))
            return List.of(gap("original-requirements","Original question coverage","Original requirement binding or settled declaration is invalid"));
        var bindings=db.queryForList("SELECT requirement_id,task_id,criterion_id FROM agent_research_requirement_binding WHERE run_id=? ORDER BY requirement_id",run);
        Map<String,EvidenceAuthority.ReportGoal> tasks=new HashMap<>();nativeGoals.forEach(g->tasks.put(g.taskId(),g));
        List<EvidenceAuthority.ReportGoal> result=new ArrayList<>();Set<String> usedClaims=new HashSet<>();
        for(JsonNode requirement:manifest.path("requirements")) {
            String id=requirement.path("requirement_id").asText(),text=requirement.path("text").asText();
            var mapping=bindings.stream().filter(b->id.equals(b.get("requirement_id"))).toList();
            if(mapping.size()!=1) { result.add(gap(id,text,"No distinct native criterion is bound to this original obligation"));continue; }
            var bound=mapping.get(0);var task=tasks.get((String)bound.get("task_id"));
            var criterion=task==null?List.<EvidenceAuthority.ReportCriterion>of():task.criteria().stream().filter(c->c.criterionId().equals(bound.get("criterion_id"))).toList();
            var scoped=db.queryForList("SELECT expected_claim::text AS expected FROM agent_research_criterion WHERE run_id=? AND task_id=? AND criterion_id=?",
                    run,bound.get("task_id"),bound.get("criterion_id"));
            JsonNode expected=scoped.size()==1?parse((String)scoped.get(0).get("expected")):JSON.nullNode();
            if(criterion.size()!=1 || task.status().equals("cancelled") || !criterion.get(0).status().equals("resolved")
                    || !requirement.path("kind").equals(expected.path("kind"))
                    || !canonical(requirement.path("applicability")).equals(canonical(expected.path("applicability")))) {
                result.add(gap(id,text,"Original obligation remains missing, stale or bound to a different Claim scope"));continue;
            }
            var c=criterion.get(0);
            if(c.claimIds().size()!=1 || !usedClaims.add(c.claimIds().get(0))) {
                result.add(gap(id,text,"One Claim cannot cover multiple original obligations"));continue;
            }
            // Native criterion verification already binds exact checked Claim/DecisionRecord,
            // current settlement, quote authority, contradiction/capacity and dependencies.
            result.add(new EvidenceAuthority.ReportGoal(id,text,"done",true,
                    List.of(new EvidenceAuthority.ReportCriterion("coverage-"+id,text,"resolved",c.checkIds(),c.claimIds(),List.of())),List.of()));
        }
        return result;
    }
    private static EvidenceAuthority.ReportGoal gap(String id,String text,String reason) {
        return new EvidenceAuthority.ReportGoal(id,text,"blocked",false,
            List.of(new EvidenceAuthority.ReportCriterion("coverage-"+id,text,"uncovered",List.of(),List.of(),List.of(reason))),List.of(reason));
    }
    static boolean validManifest(JsonNode manifest,String run,String question,JsonNode declaration) {
        try {
            if(manifest==null || !manifest.isObject() || manifest.size()!=6 || question==null
                    || !VERSION.equals(manifest.path("contract_version").asText()) || !run.equals(manifest.path("run_id").asText())
                    || !sha(question).equals(manifest.path("question_sha256").asText())
                    || manifest.path("question_length").asInt(-1)!=question.codePointCount(0,question.length())
                    || !manifest.path("requirements").isArray() || manifest.path("requirements").isEmpty()
                    || manifest.path("requirements").size()>32 || !declaration.isArray()
                    || declaration.size()!=manifest.path("requirements").size()) return false;
            ObjectNode core=(ObjectNode)manifest.deepCopy();core.remove("manifest_sha256");
            if(!sha(canonical(core)).equals(manifest.path("manifest_sha256").asText())) return false;
            int[] chars=question.codePoints().toArray();boolean[] anchors=new boolean[chars.length];
            Set<String> ids=new HashSet<>(),drafts=new HashSet<>();String previous="";
            for(var requirement:manifest.path("requirements")) {
                ObjectNode draft=(ObjectNode)requirement.deepCopy();draft.remove("requirement_id");
                String id="requirement-"+sha(canonical(object("contract_version",VERSION,"run_id",run,
                    "question_sha256",sha(question),"declaration",draft))).substring(0,48);
                if(!id.equals(requirement.path("requirement_id").asText()) || !ids.add(id) || previous.compareTo(id)>0
                        || !drafts.add(canonical(draft))) return false;
                previous=id;
                boolean declared=false;
                for(var raw:declaration) if(canonical(normalizeDraft(raw)).equals(canonical(draft))) declared=true;
                if(!declared || draft.path("question_spans").isEmpty()) return false;
                for(var span:draft.path("question_spans")) {
                    int start=span.path("start").asInt(-1),end=span.path("end").asInt(-1);
                    if(start<0 || end<=start || end>chars.length) return false;
                    boolean substantive=false;
                    for(int i=start;i<end;i++) { anchors[i]=true;if(!Character.isWhitespace(chars[i])) substantive=true; }
                    if(!substantive) return false;
                }
            }
            for(int i=0;i<chars.length;i++) if(!Character.isWhitespace(chars[i]) && !anchors[i]) return false;
            return true;
        } catch(RuntimeException invalid) { return false; }
    }
    private static JsonNode normalizeDraft(JsonNode raw) {
        ObjectNode draft=(ObjectNode)raw.deepCopy();
        draft.set("applicability",AgentCompletionService.normalize(object("text",draft.path("text"),"kind",draft.path("kind"),
                "applicability",draft.path("applicability"))).path("applicability"));
        List<JsonNode> spans=new ArrayList<>();draft.path("question_spans").forEach(spans::add);
        spans.sort(Comparator.comparingInt((JsonNode s)->s.path("start").asInt()).thenComparingInt(s->s.path("end").asInt()));
        draft.set("question_spans",JSON.valueToTree(spans));return draft;
    }
    private static JsonNode parse(String value) {
        if(value==null) return JSON.nullNode();
        try { return EvidenceJson.JSON.readTree(value); } catch(Exception invalid) { return JSON.nullNode(); }
    }
}
