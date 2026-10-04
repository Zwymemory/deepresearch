package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Deterministic v2 declaration reconstruction; reference units do not prove semantics. */
final class AgentQuestionSegments {
    static final String PLANNER="agent-planning-segments/2", MAPPING="agent-question-segments/1";
    private static final String BREAKS="。！？；，、：;\n\r";
    private AgentQuestionSegments() {}

    static boolean whitespace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c==0x85;
    }
    static ObjectNode mapping(String question) {
        if(question==null || question.length()>4000) throw new IllegalArgumentException("question limit");
        int[] chars=question.codePoints().toArray();
        if(chars.length==0 || Arrays.stream(chars).allMatch(AgentQuestionSegments::whitespace)
                || Arrays.stream(chars).anyMatch(c->c>=0xd800 && c<=0xdfff))
            throw new IllegalArgumentException("invalid question");
        List<int[]> ranges=new ArrayList<>();int start=0;
        for(int i=0;i<chars.length;i++) {
            int c=chars[i];
            boolean boundary=BREAKS.indexOf(c)>=0 || ".?!,".indexOf(c)>=0 && (i+1==chars.length || whitespace(chars[i+1]));
            if(!boundary) continue;
            boolean delimiterOnly=true;
            for(int j=start;j<=i;j++) if(!whitespace(chars[j]) && BREAKS.indexOf(chars[j])<0 && ".?!,".indexOf(chars[j])<0) delimiterOnly=false;
            if(delimiterOnly) { if(!ranges.isEmpty()) { ranges.get(ranges.size()-1)[1]=i+1;start=i+1; }continue; }
            ranges.add(new int[]{start,i+1});start=i+1;
        }
        if(start<chars.length) {
            boolean blank=true;for(int i=start;i<chars.length;i++) if(!whitespace(chars[i])) blank=false;
            if(!ranges.isEmpty() && blank) ranges.get(ranges.size()-1)[1]=chars.length;
            else ranges.add(new int[]{start,chars.length});
        }
        if(ranges.isEmpty()) ranges.add(new int[]{0,chars.length});
        String hash=sha(question);int width=(ranges.size()+15)/16;
        var segments=JSON.createArrayNode();
        for(int i=0;i<ranges.size();i+=width) {
            int lo=ranges.get(i)[0],hi=ranges.get(Math.min(i+width,ranges.size())-1)[1];
            segments.add(object("segment_id","qs1-"+hash+"-"+String.format(Locale.ROOT,"%02d",segments.size()),
                    "start",lo,"end",hi,"text",new String(chars,lo,hi-lo)));
        }
        ObjectNode core=object("mapping_version",MAPPING,"question_sha256",hash,"question_length",chars.length,"segments",segments);
        core.put("mapping_sha256",sha(canonical(core)));return core;
    }

    static JsonNode declarations(String question,JsonNode receipt) {
        try {
            var stored=receipt.path("value");var binding=receipt.path("request_binding");
            if(!stored.has("planner_contract") && !binding.has("planner_contract")) {
                for(String key:new String[]{"planner_settlement_contract","planner_declaration","wire_response_sha256","question_mapping_version","question_mapping_sha256"})
                    if(binding.has(key)) return JSON.nullNode();
                return stored.path("requirements");
            }
            var map=mapping(question);
            if(!PLANNER.equals(stored.path("planner_contract").asText()) || !PLANNER.equals(binding.path("planner_contract").asText())
                    || !"agent-planner-settlement/1".equals(binding.path("planner_settlement_contract").asText())
                    || !MAPPING.equals(binding.path("question_mapping_version").asText())
                    || !map.path("question_sha256").equals(binding.path("question_sha256"))
                    || !map.path("mapping_sha256").equals(binding.path("question_mapping_sha256"))
                    || !sha(canonical(stored)).equals(binding.path("response_sha256").asText())) return JSON.nullNode();
            var encoded=binding.path("planner_declaration");
            if(!encoded.isTextual() || encoded.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) return JSON.nullNode();
            JsonNode value;
            try {value=JSON.readTree(encoded.asText());} catch(Exception invalid) {return JSON.nullNode();}
            if(!value.isObject() || !canonical(value).equals(encoded.asText())
                    || !sha(encoded.asText()).equals(binding.path("wire_response_sha256").asText())
                    || !PLANNER.equals(value.path("planner_contract").asText())) return JSON.nullNode();
            var raw=value.path("requirements");if(!raw.isArray() || raw.isEmpty() || raw.size()>32) return JSON.nullNode();
            Map<String,JsonNode> units=new HashMap<>();map.path("segments").forEach(s->units.put(s.path("segment_id").asText(),s));
            Set<String> all=new HashSet<>();var drafts=JSON.createArrayNode();
            for(var row:raw) {
                if(!row.isObject() || row.size()!=4 || !row.has("text") || !row.has("kind") || !row.has("applicability")) return JSON.nullNode();
                var refs=row.path("segment_ids");if(!refs.isArray() || refs.isEmpty() || refs.size()>16) return JSON.nullNode();
                Set<String> seen=new HashSet<>();List<JsonNode> selected=new ArrayList<>();
                for(var ref:refs) {
                    if(!ref.isTextual() || !units.containsKey(ref.asText()) || !seen.add(ref.asText())) return JSON.nullNode();
                    all.add(ref.asText());selected.add(units.get(ref.asText()));
                }
                selected.sort(Comparator.comparingInt(s->s.path("start").asInt()));
                var spans=JSON.createArrayNode();
                for(var unit:selected) {
                    int lo=unit.path("start").asInt(),hi=unit.path("end").asInt();
                    if(!spans.isEmpty() && spans.get(spans.size()-1).path("end").asInt()==lo) ((ObjectNode)spans.get(spans.size()-1)).put("end",hi);
                    else spans.add(object("start",lo,"end",hi));
                }
                ObjectNode draft=row.deepCopy();draft.remove("segment_ids");draft.set("question_spans",spans);drafts.add(draft);
            }
            if(!all.equals(units.keySet()) || !drafts.equals(stored.path("requirements"))) return JSON.nullNode();
            return drafts;
        } catch(RuntimeException invalid) { return JSON.nullNode(); }
    }
}
