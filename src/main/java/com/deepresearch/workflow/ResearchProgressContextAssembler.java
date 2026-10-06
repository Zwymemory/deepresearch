package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Whole saved snapshots only. Uses the existing Java JSON/hash rules, without model calls. */
public final class ResearchProgressContextAssembler {
    public static final int CANDIDATE_LIMIT=20, MAX_RECORDS=3, LIMIT_BYTES=16384;
    private ResearchProgressContextAssembler() {}
    public record Projection(JsonNode priorProgress,String projectionSha256,long canonicalBytes) {
        public Projection { priorProgress=priorProgress.deepCopy(); }
        @Override public JsonNode priorProgress() { return priorProgress.deepCopy(); }
    }
    public static Projection assemble(String project,String targetSession,List<JsonNode> candidates,
                                      Predicate<JsonNode> referencesAccessible) {
        List<JsonNode> rows=candidates.stream().limit(CANDIDATE_LIMIT).map(row->row.<JsonNode>deepCopy()).toList();
        var reasons=new LinkedHashMap<Integer,String>();
        var eligible=new ArrayList<Integer>();
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);
            if(!valid(row,project)) reasons.put(i,"invalid_record");
            else if(targetSession.equals(row.path("source_session_id").asText())) reasons.put(i,"same_target_session");
            else if(!referencesAccessible.test(row)) reasons.put(i,"provenance_unavailable");
            else { eligible.add(i); reasons.put(i,"record_limit"); }
        }
        var selected=new ArrayList<Integer>();
        for(int i:eligible) {
            if(selected.size()==MAX_RECORDS) break;
            selected.add(i); reasons.remove(i);
            if(bytes(envelope(project,rows,selected,reasons,eligible.size()))>LIMIT_BYTES) {
                selected.remove(selected.size()-1); reasons.put(i,"record_too_large");
            }
        }
        // Later omission metadata can grow: the limit always covers the complete final envelope.
        JsonNode envelope=envelope(project,rows,selected,reasons,eligible.size());
        while(!selected.isEmpty() && bytes(envelope)>LIMIT_BYTES) {
            int removed=selected.remove(selected.size()-1); reasons.put(removed,"record_too_large");
            envelope=envelope(project,rows,selected,reasons,eligible.size());
        }
        if(selected.isEmpty()) throw new ResearchMemoryException("RESEARCH_MEMORY_UNAVAILABLE",true);
        String serialized=canonical(envelope);
        return new Projection(envelope,sha(serialized),serialized.getBytes(StandardCharsets.UTF_8).length);
    }
    static boolean valid(JsonNode row,String project) {
        return row.isObject() && "research-progress/1".equals(row.path("schema_version").asText())
            && "prior_progress".equals(row.path("context_kind").asText())
            && row.path("trusted_as_evidence").isBoolean() && !row.path("trusted_as_evidence").asBoolean()
            && project.equals(row.path("project_id").asText())
            && row.path("source_run_id").isTextual() && !row.path("source_run_id").asText().isBlank()
            && row.path("source_run_id").asText().length()<=64
            && row.path("source_session_id").isTextual() && !row.path("source_session_id").asText().isBlank()
            && row.path("original_goal").isTextual() && row.path("completed_work").isArray()
            && row.path("unresolved_questions").isArray() && row.path("next_steps").isArray()
            && row.path("source_evidence").isArray() && row.path("source_claims").isArray();
    }
    private static JsonNode envelope(String project,List<JsonNode> rows,List<Integer> selected,
                                     Map<Integer,String> reasons,int eligible) {
        var records=new ArrayList<JsonNode>();
        for(int i:selected) {
            var row=rows.get(i);
            records.add(object("source_run_id",row.path("source_run_id"),
                "snapshot_sha256",sha(canonical(row)),"snapshot",row));
        }
        var omitted=new ArrayList<JsonNode>();
        for(int i=0;i<rows.size();i++) if(reasons.containsKey(i))
            omitted.add(object("source_run_id",rows.get(i).path("source_run_id").asText(),"reason",reasons.get(i)));
        boolean truncated=reasons.values().stream().anyMatch(r->r.equals("record_limit") || r.equals("record_too_large"));
        return object("schema_version","research-progress-context/1","context_kind","prior_progress",
            "trusted_as_evidence",false,"project_id",project,"records",records,
            "selection",object("candidate_count",rows.size(),"eligible_count",eligible,"omitted",omitted,"truncated",truncated),
            "budget",object("method","utf8-canonical-bytes/1","limit_bytes",LIMIT_BYTES,
                "max_records",MAX_RECORDS,"candidate_limit",CANDIDATE_LIMIT));
    }
    private static long bytes(JsonNode row) { return canonical(row).getBytes(StandardCharsets.UTF_8).length; }
}
