package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.deepresearch.evidence.EvidenceJson.*;

/** Frozen conversation text for resolving follow-ups, never a source of verified facts. */
final class ConversationReferents {
    static final int LIMIT_BYTES = 24576, MAX_REPORTS = 2;
    private ConversationReferents() {}

    static JsonNode freeze(JdbcTemplate db, AuthPrincipal principal, String project,
                           String session, JsonNode selectedProgress) {
        var reports = JSON.createArrayNode();
        var seen = new HashSet<String>();
        // The active session takes precedence over an older project snapshot.
        for (var row : db.queryForList("""
                SELECT w.run_id,w.session_id,w.question,w.final_response->>'answer' AS answer
                FROM agent_workflow_run w JOIN agent_research_run a USING(run_id)
                WHERE w.session_id=? AND w.user_id=? AND a.tenant_id=? AND a.owner_id=?
                  AND a.project_id=? AND w.status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE')
                  AND length(w.final_response->>'answer')>0
                  AND (w.status='SUCCEEDED' OR (jsonb_typeof(w.final_response->'claims')='array'
                       AND w.final_response->'claims' <> '[]'::jsonb))
                ORDER BY w.created_at DESC,w.run_id DESC LIMIT 2
                """, session,principal.storageUserId(),principal.tenantId(),principal.userId(),project)) {
            reports.add(report(row,"same_session")); seen.add((String)row.get("run_id"));
        }
        // Only explicitly selected, access-checked progress is eligible across sessions.
        var queue = new ArrayDeque<JsonNode>();
        if (selectedProgress != null) selectedProgress.path("records").forEach(r -> queue.add(r.path("snapshot")));
        var visited = new HashSet<String>();
        var memory = new ResearchProgressRepository(db);
        while (!queue.isEmpty() && visited.size() < 20) {
            var selected = queue.remove();
            String source = selected.path("source_run_id").asText();
            if (reports.size() >= MAX_REPORTS) break;
            if (!visited.add(source)) continue;
            // Failed follow-ups may be the newest saved item. Follow only their
            // hash-bound, still-accessible predecessors to recover the actual exercise.
            // The existing progress validator rechecks these same references on use.
            for (var ref : selected.path("prior_memory_refs")) {
                var predecessors = memory.saved(project,principal,ref.path("source_run_id").asText());
                if (predecessors.size() == 1 && sha(canonical(predecessors.get(0))).equals(ref.path("snapshot_sha256").asText())
                        && memory.referencesAccessible(project,principal,predecessors.get(0))) queue.add(predecessors.get(0));
            }
            if (!seen.add(source)) continue;
            var rows = db.queryForList("""
                    SELECT w.run_id,w.session_id,w.question,w.final_response->>'answer' AS answer
                    FROM agent_workflow_run w JOIN agent_research_run a USING(run_id)
                    WHERE w.run_id=? AND w.user_id=? AND a.tenant_id=? AND a.owner_id=?
                      AND a.project_id=? AND w.status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE')
                      AND length(w.final_response->>'answer')>0
                      AND (w.status='SUCCEEDED' OR (jsonb_typeof(w.final_response->'claims')='array'
                           AND w.final_response->'claims' <> '[]'::jsonb))
                    """, source,principal.storageUserId(),principal.tenantId(),principal.userId(),project);
            if (rows.size() == 1) reports.add(report(rows.get(0),"selected_project"));
        }
        return reports.isEmpty() ? null : object("schema_version","conversation-referents/1",
                "trusted_as_evidence",false,"reports",reports);
    }

    static ObjectNode report(Map<String,Object> row, String origin) {
        String question = Objects.toString(row.get("question"),"");
        String answer = Objects.toString(row.get("answer"),"");
        var report = object("run_id",row.get("run_id"),"session_id",row.get("session_id"),
                "origin",origin,"question",excerpt(question,1000),"question_truncated",question.codePointCount(0,question.length())>1000,
                "answer",answer,"answer_sha256",sha(answer),"answer_truncated",false);
        int limit = answer.codePointCount(0,answer.length());
        while (canonical(report).getBytes(StandardCharsets.UTF_8).length > 11500) {
            limit /= 2;
            report.put("answer",excerpt(answer,limit)); report.put("answer_truncated",true);
        }
        return report;
    }

    private static String excerpt(String text, int limit) {
        int count = text.codePointCount(0,text.length());
        if (count <= limit) return text;
        int half = limit / 2;
        return text.substring(0,text.offsetByCodePoints(0,half))
                + "\n[中间内容已省略；不可推测省略部分]\n"
                + text.substring(text.offsetByCodePoints(0,count-(limit-half)));
    }
}
