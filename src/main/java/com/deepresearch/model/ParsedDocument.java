package com.deepresearch.model;

import java.util.List;

public record ParsedDocument(
        String title,
        SourceType sourceType,
        String filename,
        String rawContent,
        List<ParsedSection> sections
) {
}
