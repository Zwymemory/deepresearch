package com.deepresearch.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A bounded retrieval snapshot, never a statement that a cited claim is true. */
public record CitationDetail(String sourceId, String kind, String title, String url,
                             String excerpt, String metadataStatus, String unavailableReason) {
    public CitationDetail {
        if ("AVAILABLE".equals(metadataStatus)) {
            title = safeText(title, 300);
            excerpt = safeText(excerpt, 2400);
            url = "WEB_SEARCH_SNAPSHOT".equals(kind) ? CitationSourceSupport.safeWebUrl(url) : null;
            if (url != null && url.isBlank()) url = null;
            unavailableReason = null;
        } else {
            kind = "UNKNOWN";
            title = null;
            url = null;
            excerpt = null;
            metadataStatus = "UNAVAILABLE";
        }
    }

    public static CitationDetail web(String sourceId, String title, String url, String excerpt) {
        return new CitationDetail(sourceId, "WEB_SEARCH_SNAPSHOT", title, url, excerpt, "AVAILABLE", null);
    }

    public static CitationDetail knowledge(String sourceId, String title, String excerpt) {
        return new CitationDetail(sourceId, "KNOWLEDGE_CHUNK", title, null, excerpt, "AVAILABLE", null);
    }

    public static CitationDetail unavailable(String sourceId, String reason) {
        return new CitationDetail(sourceId, "UNKNOWN", null, null, null, "UNAVAILABLE", reason);
    }

    public CitationDetail withSourceId(String id) {
        return new CitationDetail(id, kind, title, url, excerpt, metadataStatus, unavailableReason);
    }

    /** Preserve citation order; disagreeing snapshots of one identity are not silently resolved. */
    public static List<CitationDetail> project(List<String> citations, List<CitationDetail> snapshots) {
        Map<String, CitationDetail> unique = new LinkedHashMap<>();
        for (CitationDetail snapshot : snapshots) {
            if (snapshot == null || !"AVAILABLE".equals(snapshot.metadataStatus())) continue;
            unique.merge(snapshot.sourceId(), snapshot, (first, next) -> first.equals(next) ? first
                    : unavailable(first.sourceId(), "AMBIGUOUS_SNAPSHOT"));
        }
        return citations.stream().map(id -> unique.getOrDefault(id,
                unavailable(id, "MISSING_SNAPSHOT"))).toList();
    }

    private static String safeText(String value, int limit) {
        if (value == null || value.isBlank()) return null;
        String safe = ToolOutputSanitizer.redactSecrets(value).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return safe.substring(0, safe.offsetByCodePoints(0, Math.min(limit, safe.codePointCount(0, safe.length()))));
    }
}
