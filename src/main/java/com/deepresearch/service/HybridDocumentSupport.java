package com.deepresearch.service;

import org.springframework.ai.document.Document;

/** 统一解释混合检索 Document metadata，避免融合、扩展和 DTO 组装各自定义键语义。 */
final class HybridDocumentSupport {

    private HybridDocumentSupport() {
    }

    static String stableKey(Document document) {
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        return Integer.toHexString(cleanText(document).hashCode());
    }

    static String docKey(Document document) {
        String filename = stringMeta(document, "filename");
        if (!filename.isBlank()) {
            return filename;
        }
        String docId = stringMeta(document, "docId");
        return docId.isBlank() ? stableKey(document) : docId;
    }

    static String title(Document document) {
        return String.valueOf(document.getMetadata().getOrDefault("title", "知识库片段"));
    }

    static String chunkKey(Document document) {
        String filename = stringMeta(document, "filename");
        String sectionPath = stringMeta(document, "sectionPath");
        Integer chunkIndex = intMeta(document, "chunkIndex");
        return filename.isBlank() || chunkIndex == null ? "" : filename + "#" + sectionPath + "#" + chunkIndex;
    }

    static String stringMeta(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    static Integer intMeta(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static String cleanText(Document document) {
        String body = stringMeta(document, "body");
        if (!body.isBlank()) {
            return body;
        }
        String text = document.getText() == null ? "" : document.getText();
        int bodyStart = text.indexOf("正文：");
        return bodyStart >= 0 ? text.substring(bodyStart + "正文：".length()).trim() : text.trim();
    }

    static String preview(Document document) {
        String text = cleanText(document).replaceAll("\\s+", " ").trim();
        return text.length() <= 360 ? text : text.substring(0, 360) + "...";
    }

    static String rerankText(Document document) {
        StringBuilder text = new StringBuilder();
        String sectionPath = stringMeta(document, "sectionPath");
        if (!sectionPath.isBlank()) {
            text.append(sectionPath).append('\n');
        }
        return text.append(cleanText(document)).toString().trim();
    }
}
