package com.deepresearch.workflow;

import com.deepresearch.agent.ToolArgumentFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, untrusted history in the existing optional Dify paragraph input. */
final class DifyContextInputs {
    static final int MAX_SERIALIZED_CHARS = 24_000;
    private static final int SUMMARY_CHARS = 4_000;
    private static final int ITEM_CHARS = 1_200;
    private static final int RECENT_LIMIT = 8;
    private static final int MEMORY_LIMIT = 5;

    private DifyContextInputs() {}

    static Prepared prepare(String snapshotJson, ObjectMapper json) throws Exception {
        JsonNode snapshot = snapshotJson == null || snapshotJson.isBlank()
                ? json.createObjectNode() : json.readTree(snapshotJson);
        if (snapshot.isNull()) snapshot = json.createObjectNode();
        if (!snapshot.isObject()) throw new IllegalArgumentException("context snapshot must be an object");
        String originalSummary = text(snapshot.path("sessionSummary"));
        List<String> allRecent = texts(snapshot.path("recentConversation"));
        List<String> allMemories = texts(snapshot.path("memories"));
        String summary = truncate(originalSummary, SUMMARY_CHARS);
        List<String> recent = new ArrayList<>(allRecent.stream().skip(Math.max(0, allRecent.size() - RECENT_LIMIT))
                .map(value -> truncate(value, ITEM_CHARS)).toList());
        List<String> memories = new ArrayList<>(allMemories.stream().limit(MEMORY_LIMIT)
                .map(value -> truncate(value, ITEM_CHARS)).toList());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", "0.1.0");
        envelope.put("trust", "untrusted_context_not_evidence");
        envelope.put("session_summary", summary);
        envelope.put("recent_conversation", recent);
        envelope.put("memories", memories);
        boolean structured = !allRecent.isEmpty() || !allMemories.isEmpty();
        String serialized = structured ? json.writeValueAsString(envelope) : "";
        while (structured && serialized.length() > MAX_SERIALIZED_CHARS) {
            // Preserve whole JSON and the newest messages; never cut serialized text.
            if (!memories.isEmpty()) memories.remove(memories.size() - 1);
            else if (!recent.isEmpty()) recent.remove(0);
            else {
                summary = truncate(summary, Math.max(0, summary.codePointCount(0, summary.length()) / 2));
                envelope.put("session_summary", summary);
            }
            serialized = json.writeValueAsString(envelope);
        }
        boolean truncated = snapshot.path("truncated").asBoolean(false)
                || !summary.equals(originalSummary) || !recent.equals(allRecent) || !memories.equals(allMemories);
        // Old calls without recent messages or memories retain the old summary value exactly within its bound.
        String input = structured ? serialized : summary;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schema_version", "0.1.0");
        diagnostics.put("selected", Map.of("summary", !originalSummary.isBlank(),
                "recent_messages", allRecent.size(), "memories", allMemories.size()));
        diagnostics.put("submitted", Map.of("summary", !summary.isBlank(),
                "recent_messages", recent.size(), "memories", memories.size()));
        diagnostics.put("used", "unknown");
        diagnostics.put("truncated", truncated);
        diagnostics.put("input_sha256", ToolArgumentFingerprint.sha256(input));
        diagnostics.put("input_chars", input.length());
        return new Prepared(input, Map.copyOf(diagnostics));
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : "";
    }

    private static List<String> texts(JsonNode value) {
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) if (item.isTextual() && !item.asText().isBlank()) result.add(item.asText());
        return result;
    }

    static String truncate(String value, int codePoints) {
        if (value == null) return "";
        return value.codePointCount(0, value.length()) <= codePoints
                ? value : value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    record Prepared(String sessionSummaryInput, Map<String, Object> diagnostics) {}
}
