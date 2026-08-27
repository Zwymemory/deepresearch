package com.deepresearch.model;

import java.util.Locale;

public enum SourceType {
    TEXT,
    MARKDOWN,
    PDF;

    public static SourceType fromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return MARKDOWN;
        }
        if (lower.endsWith(".pdf")) {
            return PDF;
        }
        return TEXT;
    }
}
