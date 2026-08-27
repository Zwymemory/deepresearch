package com.deepresearch.agent;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds public, non-secret source identifiers without looking at model text. */
final class CitationSourceSupport {

    private static final int MAX_SOURCE_LENGTH = 2_048;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern SECRET_QUERY = Pattern.compile(
            "(?i)(?:[?&](?:api[_-]?key|access[_-]?token|password|passwd|secret|token)=)");

    private CitationSourceSupport() {
    }

    static String safeWebUrl(String raw) {
        String value = compact(raw);
        if (value.isEmpty() || value.length() > MAX_SOURCE_LENGTH || SECRET_QUERY.matcher(value).find()) {
            return "";
        }
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                return "";
            }
            String normalized = uri.toASCIIString();
            return normalized.length() <= MAX_SOURCE_LENGTH ? normalized : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    static String safeKnowledgeChunk(String raw) {
        String value = compact(raw);
        if (value.isEmpty() || value.length() > 512 || CONTROL.matcher(value).find()) {
            return "";
        }
        return "kb:" + value;
    }

    private static String compact(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        return CONTROL.matcher(value).find() ? "" : value;
    }
}
