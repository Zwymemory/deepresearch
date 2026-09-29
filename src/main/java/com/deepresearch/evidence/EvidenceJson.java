package com.deepresearch.evidence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.Map;
import java.util.Set;

public final class EvidenceJson {
    public static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private EvidenceJson() { }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static ObjectNode object(Object... fields) {
        var result = JSON.createObjectNode();
        for (int i = 0; i < fields.length; i += 2) result.set((String) fields[i], JSON.valueToTree(fields[i + 1]));
        return result;
    }
    public static JsonNode known(Object value) { return object("status", "known", "value", value); }
    public static JsonNode unknown(String reason) { return object("status", "unknown", "value", null, "reason", reason); }
    public static String canonical(JsonNode value) {
        try { return JSON.writeValueAsString(sorted(value)); }
        catch (Exception malformed) { throw new EvidenceException("EVIDENCE_JSON_INVALID"); }
    }
    private static Object sorted(JsonNode node) {
        if (node.isObject()) {
            var out = new TreeMap<String, Object>(); node.fields().forEachRemaining(e -> out.put(e.getKey(), sorted(e.getValue()))); return out;
        }
        if (node.isArray()) { var out = new ArrayList<Object>(); node.forEach(v -> out.add(sorted(v))); return out; }
        return JSON.convertValue(node, Object.class);
    }
    public static String id(String value) {
        if (value == null || !value.matches("\\S{1,128}")) throw new EvidenceException("EVIDENCE_ID_INVALID");
        return value;
    }
    public static String text(String value, int max) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max)
            throw new EvidenceException("EVIDENCE_TEXT_INVALID");
        return value;
    }
    public static String field(JsonNode row, String key) {
        if (!row.path(key).isTextual()) throw new EvidenceException("CHECK_RESPONSE_INVALID");
        return text(row.get(key).asText(), 10000);
    }
    public static void keys(JsonNode row, String... required) {
        if (!row.isObject()) throw new EvidenceException("CHECK_RESPONSE_INVALID");
        Set<String> allowed = Set.of(required);
        var actual = new java.util.HashSet<String>(); row.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) throw new EvidenceException("CHECK_RESPONSE_INVALID");
    }
    public static ObjectNode scoped(String kind, EvidenceAuthority.Grant grant, Object... fields) {
        var row = object("record_type", kind, "schema_version", "0.1.0", "tenant_id", grant.principal().tenantId(),
                "owner_id", grant.principal().userId(), "project_id", grant.projectId());
        row.setAll(object(fields)); return row;
    }
}
