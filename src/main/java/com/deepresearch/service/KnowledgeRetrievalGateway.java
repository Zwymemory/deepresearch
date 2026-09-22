package com.deepresearch.service;

import com.deepresearch.agent.ToolOutputSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One evidence contract for Java answers, MCP tools and the later Dify facade. */
@Service
public class KnowledgeRetrievalGateway {
    private final RagflowClient client;
    private final String provider;
    private final int maxResults;
    private final double threshold;

    public KnowledgeRetrievalGateway(RagflowClient client,
            @Value("${deepresearch.retrieval.provider:legacy}") String provider,
            @Value("${deepresearch.ragflow.max-results:20}") int maxResults,
            @Value("${deepresearch.ragflow.similarity-threshold:0.2}") double threshold) {
        if (!List.of("legacy", "ragflow").contains(provider)) throw new IllegalStateException("Unknown retrieval provider");
        this.client = client;
        this.provider = provider;
        this.maxResults = Math.max(1, Math.min(maxResults, 100));
        this.threshold = threshold;
        if (ragflow()) client.requireConfigured();
    }

    public boolean ragflow() { return "ragflow".equals(provider); }

    public List<RetrievedEvidence> retrieve(String question, Integer topK) {
        if (!ragflow()) throw new IllegalStateException("Gateway retrieval requires ragflow mode");
        if (question == null || question.isBlank()) return List.of();
        int limit = topK == null || topK < 1 ? 5 : Math.min(topK, maxResults);
        JsonNode data = client.retrieve(question, limit, threshold);
        JsonNode chunks = data.path("chunks");
        if (!chunks.isArray()) throw new IllegalStateException("RAGFlow retrieval chunks missing");
        List<RetrievedEvidence> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode chunk : chunks) {
            String datasetId = required(chunk, "dataset_id");
            String docId = required(chunk, "document_id");
            String chunkId = required(chunk, "id");
            if (!client.datasets().contains(datasetId)) throw new IllegalStateException("RAGFlow returned disallowed dataset");
            String key = "ragflow:" + datasetId + ":" + docId + ":" + chunkId;
            if (!seen.add(key)) continue;
            int n = output.size() + 1;
            String content = clean(chunk.path("content").asText(""), 700);
            output.add(new RetrievedEvidence("来源" + n, "[来源" + n + "]", key, datasetId, docId,
                    chunkId, clean(chunk.path("document_keyword").asText(""), 180),
                    ToolOutputSanitizer.markUntrusted("knowledge-base", content),
                    chunk.hasNonNull("similarity") ? chunk.path("similarity").asDouble() : null,
                    "ragflow", true, null, null));
            if (output.size() == limit) break;
        }
        return List.copyOf(output);
    }

    private static String required(JsonNode node, String name) {
        String value = node.path(name).asText("");
        if (value.isBlank() || value.contains(":") || value.length() > 128)
            throw new IllegalStateException("Invalid RAGFlow " + name);
        return value;
    }

    private static String clean(String raw, int max) {
        String value = ToolOutputSanitizer.neutralizeCitationMarkers(raw).replaceAll("(?i)(api[_-]?key|password|token)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
