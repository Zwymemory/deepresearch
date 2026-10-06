package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Small deterministic keyword baseline. Whole histories remain untrusted investigation leads. */
final class ResearchRecallAssembler {
    static final int LIMIT_BYTES=8192, MAX_RECORDS=3;
    private static final Set<String> STOP=Set.of("verify","document","requirement","requirements","investigation","explain","outline","could","would","should","does","have","more","same","data","question","evidence","research","compare","current","please","version","conditions","unknown","api","the","and","with","from","this","that","如何","什么","当前","研究","问题","比较","方案","版本","条件","是否","继续","请问","进行","相关","一下","我们","可以","需要","已经","结果","结论","核查","哪些","一个","怎么","以及","对于","支持","情况","完成","验证","分析","说明","存在","新的","最新","使用","争议");
    private static final Pattern WORD=Pattern.compile("[a-z][a-z0-9_-]{2,}|[\\p{IsHan}]+");
    private static final Pattern VERSION=Pattern.compile("(?i)(?:v|version\\s*|版本\\s*)?(\\d+\\.\\d+(?:\\.\\d+)?)");
    static Set<String> tokens(String text) {
        var result=new TreeSet<String>();var m=WORD.matcher(text.toLowerCase(Locale.ROOT));
        while(m.find()) {String word=m.group();
            if(word.codePoints().allMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                int[] cp=word.codePoints().toArray();for(int i=0;i+1<cp.length;i++) {
                    String pair=new String(cp,i,2);if(!STOP.contains(pair)) result.add(pair);
                }
            } else if(!STOP.contains(word)) result.add(word);
        }
        return result;
    }
    static String topic(JsonNode snapshot) {
        // Do not rank by IDs, hashes, status flags or generic implementation metadata.
        return snapshot.path("original_goal").asText()+" "+snapshot.path("current_question").asText();
    }
    static Set<String> versions(String value) {
        var out=new TreeSet<String>();var m=VERSION.matcher(value);while(m.find()) out.add(m.group(1));return out;
    }
    static ObjectNode assemble(String question,String session,List<JsonNode> rows,
                               Function<JsonNode,List<JsonNode>> accessibleSources) {
        var query=tokens(question);var candidates=new ArrayList<ObjectNode>();int inaccessible=0,unrelated=0;
        for(var row:rows) {
            String project=row.path("project_id").asText();
            if(!ResearchProgressContextAssembler.valid(row,project) || session.equals(row.path("source_session_id").asText())) {inaccessible++;continue;}
            var matched=new TreeSet<>(tokens(topic(row)));matched.retainAll(query);
            boolean distinctive=matched.stream().anyMatch(t->t.matches("[a-z][a-z0-9_-]{3,}") && !Set.of("latency","performance","authentication","retrieval").contains(t));
            if(matched.size()<2 && !distinctive) {unrelated++;continue;}
            var sources=accessibleSources.apply(row);if(sources==null) {inaccessible++;continue;}
            var cautions=new ArrayList<String>(List.of("REVERIFY_BEFORE_USE","TIME_UNKNOWN","CONDITIONS_UNKNOWN"));
            var oldVersions=versions(topic(row));var newVersions=versions(question);
            if(!oldVersions.isEmpty() && !newVersions.isEmpty() && Collections.disjoint(oldVersions,newVersions)) cautions.add("VERSION_DIFFERENCE");
            else cautions.add("VERSION_APPLICABILITY_UNKNOWN");
            for(var claim:row.path("source_claims")) if(Set.of("contested","refuted","insufficient").contains(claim.path("decision_status").asText())) {cautions.add("DISPUTED_OR_UNVERIFIED");break;}
            candidates.add(object("source_project_id",project,"source_run_id",row.path("source_run_id").asText(),
                "snapshot_sha256",sha(canonical(row)),"snapshot",row,
                "reason",object("method","keyword-baseline/1","matched_terms",matched,"score",matched.size()),
                "applicability",object("status","RECHECK_REQUIRED","cautions",cautions,"mentioned_versions",oldVersions,
                    "time",unknown("Saved history has no verified current validity period"),"conditions",unknown("Must verify for the new question")),
                "source_refs",sources));
        }
        // Stable sort preserves repository recency for ties.
        candidates.sort(Comparator.comparingInt((ObjectNode r)->r.path("reason").path("score").asInt()).reversed());
        var records=JSON.createArrayNode();var seenSources=new HashSet<String>();int duplicates=0,omitted=0;
        var out=object("schema_version","research-recall-context/1","context_kind","recalled_progress","trusted_as_evidence",false,
            "records",records,"selection",object("candidate_limit",20,"max_records",MAX_RECORDS,"limit_bytes",LIMIT_BYTES,
                "method","keyword-baseline/1","unrelated",unrelated,"inaccessible",inaccessible,"duplicates",0,"omitted",0));
        records=(com.fasterxml.jackson.databind.node.ArrayNode)out.path("records");
        for(var row:candidates) {
            if(records.size()>=MAX_RECORDS) {omitted++;continue;}
            var keys=new HashSet<String>();for(var source:row.path("source_refs")) keys.add(source.path("source_key").asText());
            // Without a native source use snapshot identity, never manufacture independent evidence.
            if(keys.isEmpty()) keys.add("snapshot:"+row.path("snapshot_sha256").asText());
            if(keys.stream().anyMatch(seenSources::contains)) {duplicates++;continue;}
            records.add(row);if(bytes(out)>LIMIT_BYTES-128) {records.remove(records.size()-1);omitted++;continue;}
            seenSources.addAll(keys);
        }
        ((ObjectNode)out.path("selection")).put("duplicates",duplicates).put("omitted",omitted);
        return out;
    }
    static int bytes(JsonNode value) {return canonical(value).getBytes(StandardCharsets.UTF_8).length;}
}
