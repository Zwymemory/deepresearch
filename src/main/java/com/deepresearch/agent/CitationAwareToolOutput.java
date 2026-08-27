package com.deepresearch.agent;

import java.util.List;

/**
 * A retrieval tool observation plus the source identifier represented by every
 * locally numbered {@code [来源N]} marker in the observation.
 *
 * <p>The source list is kept outside model-visible text so the native Agent
 * runtime never has to infer a URL or chunk key from untrusted snippets.</p>
 */
public record CitationAwareToolOutput(String content, List<String> sourceIds) {

    public CitationAwareToolOutput {
        content = content == null ? "" : content;
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }

    public static CitationAwareToolOutput withoutSources(String content) {
        return new CitationAwareToolOutput(content, List.of());
    }
}
