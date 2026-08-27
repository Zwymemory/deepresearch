package com.deepresearch.model;

public record ParsedSection(
        String sectionPath,
        Integer pageNumber,
        String text
) {
}
