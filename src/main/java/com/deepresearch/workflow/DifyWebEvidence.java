package com.deepresearch.workflow;

import com.deepresearch.agent.CitationSourceSupport;
import com.deepresearch.agent.ToolArgumentFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;

/** Identity of the exact sanitized search snapshot, never of model-generated text. */
final class DifyWebEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern ID = Pattern.compile("web:tavily:[a-f0-9]{64}");

    private DifyWebEvidence() {}

    static String id(String url, String title, String content) {
        try {
            return "web:tavily:" + ToolArgumentFingerprint.sha256(
                    "tavily-snapshot-v1\n" + JSON.writeValueAsString(List.of(url, title, content)));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot identify web snapshot", impossible);
        }
    }

    static boolean valid(WorkflowRepository.DifyWebSource source) {
        if (source == null || source.url() == null || source.title() == null || source.content() == null
                || source.content().isBlank() || source.content().length() > 2000
                || source.title().length() > 300 || source.completedAt() == null) return false;
        String safeUrl = CitationSourceSupport.safeWebUrl(source.url());
        return !safeUrl.isEmpty() && safeUrl.equals(source.url())
                && id(source.url(), source.title(), source.content()).equals(source.citationId());
    }
}
