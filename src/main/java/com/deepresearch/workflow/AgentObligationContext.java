package com.deepresearch.workflow;

import com.deepresearch.evidence.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Native reconstruction of explicit planning roles and same-task registered Claim refs. */
public final class AgentObligationContext {
    public static final String PLANNER="agent-planning-obligations/3", CLAIMS="agent-obligation-claims/1";
    private final JdbcTemplate db;
    public AgentObligationContext(JdbcTemplate db) { this.db=db; }
    public boolean required(String run) {
        var rows=db.queryForList("SELECT o.safe_result::text AS body FROM agent_research_requirements r JOIN agent_research_operation o ON o.run_id=r.run_id AND o.operation_key=r.declaration_key AND o.attempt=r.declaration_attempt WHERE r.run_id=?",run);
        if(rows.size()!=1) return false;
        var receipt=parse((String)rows.get(0).get("body"));
        return PLANNER.equals(receipt.path("request_binding").path("planner_contract").asText())
            || PLANNER.equals(receipt.path("value").path("planner_contract").asText());
    }
    static JsonNode wire(String question,JsonNode receipt) {
        var value=receipt.path("value");var binding=receipt.path("request_binding");var mapping=AgentQuestionSegments.mapping(question);
        if(!PLANNER.equals(value.path("planner_contract").asText()) || !PLANNER.equals(binding.path("planner_contract").asText())
            || !"agent-frozen-requirements/2".equals(binding.path("continuation_contract").asText())
            || !CLAIMS.equals(binding.path("claims_contract").asText())
            || !"initial".equals(binding.path("planning_phase").asText())
            || !"agent-planner-settlement/1".equals(binding.path("planner_settlement_contract").asText())
            || !AgentQuestionSegments.MAPPING.equals(binding.path("question_mapping_version").asText())
            || !mapping.path("question_sha256").equals(binding.path("question_sha256"))
            || !mapping.path("mapping_sha256").equals(binding.path("question_mapping_sha256"))
            || !sha(canonical(value)).equals(binding.path("response_sha256").asText())) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
        var encoded=binding.path("planner_declaration");
        if(!encoded.isTextual() || encoded.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
        var raw=parse(encoded.asText());
        if(!raw.isObject() || !canonical(raw).equals(encoded.asText()) || !sha(encoded.asText()).equals(binding.path("wire_response_sha256").asText())
                || !PLANNER.equals(raw.path("planner_contract").asText()) || !CLAIMS.equals(raw.path("claims_contract").asText())) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
        return raw;
    }
    public static JsonNode declarations(String question,JsonNode receipt) {
        try {
            var raw=wire(question,receipt);var drafts=adapt(question,raw);
            return drafts.equals(receipt.path("value").path("requirements")) ? drafts : JSON.nullNode();
        } catch(RuntimeException invalid) { return JSON.nullNode(); }
    }
    static JsonNode adapt(String question,JsonNode wire) {
        var obligations=wire.path("obligations");var constraints=wire.path("constraints");
        if(!obligations.isArray() || obligations.isEmpty() || obligations.size()>32 || !constraints.isArray() || constraints.size()>16) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
        Map<String,JsonNode> units=new HashMap<>();AgentQuestionSegments.mapping(question).path("segments").forEach(s->units.put(s.path("segment_id").asText(),s));
        List<ObjectNode> copies=new ArrayList<>();List<LinkedHashSet<String>> refs=new ArrayList<>();
        for(var row:obligations) {
            keys(row,"text","kind","applicability","segment_ids");text(field(row,"text"),400);
            if(!Set.of("factual","inference","recommendation").contains(field(row,"kind"))) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
            var ids=references(row.path("segment_ids"),units);refs.add(ids);ObjectNode copy=row.deepCopy();
            if(!copy.path("applicability").isObject()) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
            ObjectNode scope=copy.path("applicability").deepCopy();if(!scope.has("conditions")) scope.set("conditions",JSON.createArrayNode());
            EvidenceService.applicability(scope);copy.set("applicability",scope);copies.add(copy);
        }
        Set<String> seen=new HashSet<>();
        for(var c:constraints) {
            keys(c,"role","segment_ids","obligation_indices");
            if(!Set.of("source","output").contains(field(c,"role")) || !seen.add(canonical(c))) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
            var ids=references(c.path("segment_ids"),units);var indices=c.path("obligation_indices");
            if(!indices.isArray() || indices.isEmpty() || indices.size()>32) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
            Set<Integer> distinct=new HashSet<>();List<JsonNode> ordered=ids.stream().map(units::get).sorted(Comparator.comparingInt(u->u.path("start").asInt())).toList();
            for(var i:indices) {
                if(!i.isIntegralNumber() || !i.canConvertToInt() || i.asInt()<0 || i.asInt()>=copies.size() || !distinct.add(i.asInt())) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
                refs.get(i.asInt()).addAll(ids);var scope=(ObjectNode)copies.get(i.asInt()).path("applicability");
                var conditions=new LinkedHashSet<String>();scope.path("conditions").forEach(v->conditions.add(v.asText()));ordered.forEach(u->conditions.add(u.path("text").asText()));
                if(conditions.size()>20 || conditions.stream().anyMatch(v->v.codePointCount(0,v.length())>400)) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
                scope.set("conditions",JSON.valueToTree(conditions));
            }
        }
        var result=JSON.createArrayNode();var all=new HashSet<String>();
        for(int i=0;i<copies.size();i++) {
            var copy=copies.get(i);copy.remove("segment_ids");all.addAll(refs.get(i));copy.set("question_spans",spans(refs.get(i),units));result.add(copy);
        }
        if(!all.equals(units.keySet())) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");return result;
    }
    static LinkedHashSet<String> references(JsonNode values,Map<String,JsonNode> units) {
        if(!values.isArray() || values.isEmpty() || values.size()>16) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
        var set=new LinkedHashSet<String>();for(var v:values) if(!v.isTextual() || !units.containsKey(v.asText()) || !set.add(v.asText())) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");return set;
    }
    static JsonNode spans(Set<String> refs,Map<String,JsonNode> units) {
        var spans=JSON.createArrayNode();
        for(var unit:refs.stream().map(units::get).sorted(Comparator.comparingInt(s->s.path("start").asInt())).toList()) {
            int lo=unit.path("start").asInt(),hi=unit.path("end").asInt();
            if(!spans.isEmpty() && spans.get(spans.size()-1).path("end").asInt()==lo) ((ObjectNode)spans.get(spans.size()-1)).put("end",hi);
            else spans.add(object("start",lo,"end",hi));
        }return spans;
    }
    public JsonNode context(String run,String task,String manifestRef,List<EvidenceDtos.ClaimReference> refs) {
        var rows=db.queryForList("SELECT r.manifest::text AS manifest,w.question,w.context_snapshot::text AS context_snapshot,o.safe_result::text AS receipt,o.status,o.kind,o.purpose FROM agent_research_requirements r JOIN agent_workflow_run w USING(run_id) JOIN agent_research_operation o ON o.run_id=r.run_id AND o.operation_key=r.declaration_key AND o.attempt=r.declaration_attempt WHERE r.run_id=?",run);
        if(rows.size()!=1 || refs==null || refs.isEmpty() || refs.size()>4) throw new EvidenceException("REQUIREMENT_CLAIM_REFERENCE_INVALID");
        var row=rows.get(0);var manifest=parse((String)row.get("manifest"));String question=(String)row.get("question");var receipt=parse((String)row.get("receipt"));var declarations=declarations(question,receipt);
        if(!"SETTLED".equals(row.get("status")) || !"MODEL".equals(row.get("kind")) || !"DECISION".equals(row.get("purpose"))
                || !AgentRequirementCompletionService.validManifest(manifest,run,question,declarations)
                || !manifest.path("manifest_sha256").asText().equals(manifestRef)) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");
        var bindings=JSON.createArrayNode();Set<String> rs=new HashSet<>(),cs=new HashSet<>();int index=0;
        for(var ref:refs) {
            if(ref==null || !rs.add(ref.requirement_id()) || !cs.add(ref.criterion_id())) throw new EvidenceException("REQUIREMENT_CLAIM_REFERENCE_INVALID");
            var binding=db.queryForList("SELECT b.requirement_id FROM agent_research_requirement_binding b JOIN agent_research_criterion c USING(run_id,task_id,criterion_id) JOIN agent_research_task t USING(run_id,task_id) WHERE b.run_id=? AND b.task_id=? AND b.requirement_id=? AND b.criterion_id=? AND t.status<>'cancelled'",run,task,ref.requirement_id(),ref.criterion_id());
            if(binding.size()!=1 || find(manifest.path("requirements"),"requirement_id",ref.requirement_id()).isNull()) throw new EvidenceException("REQUIREMENT_CLAIM_REFERENCE_INVALID");
            bindings.add(object("requirement_id",ref.requirement_id(),"criterion_id",ref.criterion_id(),"claim_index",index++));
        }
        var raw=wire(question,receipt);var constraints=JSON.createArrayNode();Map<String,JsonNode> units=new HashMap<>();AgentQuestionSegments.mapping(question).path("segments").forEach(s->units.put(s.path("segment_id").asText(),s));
        List<String> orderedIds=new ArrayList<>();
        for(var draft:declarations) {
            ObjectNode normalized=draft.deepCopy();normalized.set("applicability",AgentCompletionService.normalize(object("text",draft.path("text"),"kind",draft.path("kind"),"applicability",draft.path("applicability"))).path("applicability"));
            String id=null;
            for(var r:manifest.path("requirements")) {ObjectNode copy=r.deepCopy();copy.remove("requirement_id");if(canonical(copy).equals(canonical(normalized))) id=r.path("requirement_id").asText();}
            if(id==null) throw new EvidenceException("ORIGINAL_CONTEXT_INVALID");orderedIds.add(id);
        }
        for(var c:raw.path("constraints")) {var ids=JSON.createArrayNode();c.path("obligation_indices").forEach(i->ids.add(orderedIds.get(i.asInt())));constraints.add(object("role",c.path("role"),"question_spans",spans(references(c.path("segment_ids"),units),units),"requirement_ids",ids));}
        var result = object("contract_version","agent-obligation-context/1","question",question,"manifest_sha256",manifestRef,
                "declaration_sha256",receipt.path("request_binding").path("wire_response_sha256"),"obligations",manifest.path("requirements"),"constraints",constraints,"claim_bindings",bindings);
        var snapshot = row.get("context_snapshot");
        var conversation = snapshot instanceof String text ? parse(text).path("conversation_context") : JSON.nullNode();
        if (conversation.isObject()) {
            result.put("contract_version","agent-obligation-context/2");
            result.set("conversation_context",conversation.deepCopy());
        }
        return result;
    }
    public boolean attested(String run,String task,JsonNode request,JsonNode result,String requestHash,String responseHash) {
        try {
            if(!ObligationVerification.current(request) || !sha(canonical(request)).equals(requestHash)) return false;
            var original=request.path("original_context");List<EvidenceDtos.ClaimReference> refs=new ArrayList<>();
            var bindings=original.path("claim_bindings");
            if(!bindings.isArray() || bindings.size()!=request.path("claims").size()) return false;
            for(int i=0;i<bindings.size();i++) {
                JsonNode selected=null;for(var b:bindings) if(b.path("claim_index").asInt(-1)==i) {if(selected!=null) return false;selected=b;}
                if(selected==null) return false;refs.add(new EvidenceDtos.ClaimReference(selected.path("requirement_id").asText(),selected.path("criterion_id").asText()));
            }
            if(!canonical(context(run,task,original.path("manifest_sha256").asText(),refs)).equals(canonical(original))) return false;
            for(int i=0;i<refs.size();i++) {
                var requirement=find(original.path("obligations"),"requirement_id",refs.get(i).requirement_id());var claim=AgentCompletionService.normalize(request.path("claims").get(i));
                if(!requirement.path("kind").equals(claim.path("kind")) || !requirement.path("applicability").equals(claim.path("applicability"))) return false;
            }
            var verification=result.path("verification");keys(verification,"protocol_version","model_call_id","request_sha256","response_sha256","response");
            if(!"evidence-check/3".equals(verification.path("protocol_version").asText()) || !requestHash.equals(verification.path("request_sha256").asText())
                || !responseHash.equals(verification.path("response_sha256").asText()) || !responseHash.equals(sha(canonical(verification.path("response"))))) return false;
            var models=db.queryForList("SELECT safe_result::text AS receipt FROM agent_research_operation o WHERE run_id=? AND operation_key=? AND kind='MODEL' AND purpose='CHECK' AND status='SETTLED' AND attempt=(SELECT max(attempt) FROM agent_research_operation WHERE run_id=o.run_id AND operation_key=o.operation_key)",run,verification.path("model_call_id").asText());
            if(models.size()!=1) return false;var receipt=parse((String)models.get(0).get("receipt"));var binding=receipt.path("request_binding");
            if(!request.path("check_id").asText().equals(binding.path("check_id").asText()) || !requestHash.equals(binding.path("request_sha256").asText())
                || !responseHash.equals(binding.path("response_sha256").asText()) || !canonical(receipt.path("value")).equals(canonical(verification.path("response")))) return false;
            var response=verification.path("response");keys(response,"claims","follow_up_actions","planning_alignment");keys(response.path("planning_alignment"),"status","reason");
            if(!Set.of("complete","incomplete","uncertain").contains(field(response.path("planning_alignment"),"status"))) return false;
            text(field(response.path("planning_alignment"),"reason"),1000);
            if(response.path("claims").size()!=request.path("claims").size()) return false;
            Set<String> seen=new HashSet<>();
            for(var proposal:response.path("claims")) {
                keys(proposal,"claim_id","relations","limitations","answer_alignment");String id=field(proposal,"claim_id");
                if(!seen.add(id) || find(request.path("claims"),"claim_id",id).isNull() || !Set.of("answers","irrelevant","absence_only","instruction_only","unresolved").contains(field(proposal,"answer_alignment"))) return false;
                if(proposal.path("relations").size()!=request.path("evidence").size()) return false;
                Set<String> sources=new HashSet<>();
                for(var relation:proposal.path("relations")) {
                    keys(relation,"evidence_id","relation","quote","reason","source_alignment");String source=field(relation,"evidence_id");
                    if(!sources.add(source) || find(request.path("evidence"),"evidence_id",source).isNull() || !Set.of("qualifies","wrong_source","unresolved").contains(field(relation,"source_alignment"))) return false;
                    EvidenceAdjudicator.bindQuote(find(request.path("evidence"),"evidence_id",source),relation.path("quote"));
                }
            }
            // Existing records may expose honest insufficiency; positive records must actually
            // carry all three gates for every adopted original, without stitching checks.
            for(var decision:result.path("records")) if("DecisionRecord".equals(decision.path("record_type").asText()) && Set.of("supported","refuted").contains(decision.path("decision_status").asText())) {
                if(decision.path("adopted_evidence_ids").isEmpty()) return false;
                for(var prior:request.path("prior_relations")) if(decision.path("claim_id").asText().equals(prior.path("claim_id").asText())
                    && ObligationVerification.gap(request,response,prior.path("claim_id").asText(),prior.path("evidence_id").asText())!=null) return false;
                for(var evidence:decision.path("adopted_evidence_ids")) if(ObligationVerification.gap(request,response,decision.path("claim_id").asText(),evidence.asText())!=null) return false;
            }
            return true;
        } catch(RuntimeException invalid) { return false; }
    }
    public boolean completedCriterion(String run,String task,String criterion,JsonNode expected,String checkId) {
        var rows=db.queryForList("SELECT request::text AS request,result::text AS result,request_sha256,response_sha256,status FROM agent_evidence_check WHERE run_id=? AND task_id=? AND check_id=?",run,task,checkId);
        if(rows.size()!=1 || !"COMPLETED".equals(rows.get(0).get("status"))) return false;
        var row=rows.get(0);var request=parse((String)row.get("request"));var result=parse((String)row.get("result"));
        if(!attested(run,task,request,result,(String)row.get("request_sha256"),(String)row.get("response_sha256"))) return false;
        JsonNode binding=find(request.path("original_context").path("claim_bindings"),"criterion_id",criterion);
        int index=binding.path("claim_index").asInt(-1);if(index<0 || index>=request.path("claims").size()) return false;
        var claim=request.path("claims").get(index);if(!canonical(AgentCompletionService.normalize(claim)).equals(canonical(expected))) return false;
        var decision=find(result.path("records"),"claim_id",claim.path("claim_id").asText());
        for(var d:result.path("records")) if("DecisionRecord".equals(d.path("record_type").asText()) && claim.path("claim_id").equals(d.path("claim_id"))) decision=d;
        if(!"complete".equals(result.path("verification").path("response").path("planning_alignment").path("status").asText()) || !Set.of("supported","refuted").contains(decision.path("decision_status").asText()) || decision.path("adopted_evidence_ids").isEmpty()) return false;
        for(var e:decision.path("adopted_evidence_ids")) if(ObligationVerification.gap(request,result.path("verification").path("response"),claim.path("claim_id").asText(),e.asText())!=null) return false;
        return true;
    }
    public static JsonNode find(JsonNode list,String key,String value) {for(var row:list) if(value.equals(row.path(key).asText())) return row;return JSON.nullNode();}
    static JsonNode parse(String value) {try{return value==null?JSON.nullNode():JSON.readTree(value);}catch(Exception invalid){return JSON.nullNode();}}
}
