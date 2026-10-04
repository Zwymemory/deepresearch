package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;
import static com.deepresearch.evidence.EvidenceJson.*;

/** CHECK3 semantic proposals are gated by native context and verified final read identity. */
public final class ObligationVerification {
    private ObligationVerification() {}
    public static boolean current(JsonNode request) {return "evidence-check/3".equals(request.path("protocol_version").asText());}
    public static String gap(JsonNode request,JsonNode response,String claimId,String evidenceId) {
        if(!current(request)) return null;
        if(!"complete".equals(response.path("planning_alignment").path("status").asText())) return "Original question partition is incomplete, misclassified or unverified";
        JsonNode proposal=find(response.path("claims"),"claim_id",claimId);
        if(!"answers".equals(proposal.path("answer_alignment").asText())) return "Claim does not directly answer the original obligation: "+proposal.path("answer_alignment").asText();
        JsonNode relation=find(proposal.path("relations"),"evidence_id",evidenceId);
        if(!"qualifies".equals(relation.path("source_alignment").asText())) return "Required source is missing or mismatched: "+relation.path("source_alignment").asText();
        int index=-1;for(int i=0;i<request.path("claims").size();i++) if(claimId.equals(request.path("claims").get(i).path("claim_id").asText())) index=i;
        var context=request.path("original_context");String requirement=null;
        for(var b:context.path("claim_bindings")) if(b.path("claim_index").asInt(-1)==index) requirement=b.path("requirement_id").asText();
        if(requirement==null) return "Original obligation reference is invalid";
        JsonNode source=find(request.path("evidence"),"evidence_id",evidenceId);
        String uri=source.path("source").path("locator").path("uri").asText();
        // Search title and requested URL are leads; the actual final read locator is authority.
        for(var constraint:context.path("constraints")) {
            if(!"source".equals(constraint.path("role").asText())) continue;
            boolean applies=false;for(var r:constraint.path("requirement_ids")) if(requirement.equals(r.asText())) applies=true;
            if(!applies) continue;
            Set<String> urls=new HashSet<>();String question=context.path("question").asText();int[] chars=question.codePoints().toArray();
            for(var span:constraint.path("question_spans")) {
                int lo=span.path("start").asInt(-1),hi=span.path("end").asInt(-1);
                if(lo<0 || hi<=lo || hi>chars.length) return "Original source anchor is invalid";
                var matches=Pattern.compile("https?://[^\\s<>\\\"'()\\[\\]，。；！？]+",Pattern.CASE_INSENSITIVE).matcher(new String(chars,lo,hi-lo));
                while(matches.find()) urls.add(matches.group().replaceFirst("[.,;!?]+$",""));
            }
            if(!urls.isEmpty() && !urls.contains(uri)) return "Actual final read URL does not satisfy the original explicit URL restriction";
        }
        return null;
    }
    public static JsonNode find(JsonNode values,String key,String value) {for(var row:values) if(value.equals(row.path(key).asText())) return row;return JSON.nullNode();}
}
