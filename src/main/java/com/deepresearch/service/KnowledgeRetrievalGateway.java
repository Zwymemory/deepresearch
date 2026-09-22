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
    private static final int MAX_TOTAL_CONTENT = 6000;
    private final RagflowClient client;
    private final RagflowDocumentRegistry registry;
    private final String provider;
    private final int maxResults;
    private final double threshold;

    public KnowledgeRetrievalGateway(RagflowClient client, RagflowDocumentRegistry registry,
            @Value("${deepresearch.retrieval.provider:legacy}") String provider,
            @Value("${deepresearch.ragflow.max-results:20}") int maxResults,
            @Value("${deepresearch.ragflow.similarity-threshold:0.2}") double threshold) {
        if (!List.of("legacy", "ragflow").contains(provider)) throw new IllegalStateException("Unknown retrieval provider");
        this.client = client;
        this.registry = registry;
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
        List<String> activeIds = registry.activeDocumentIds(client.datasets());
        if (activeIds.isEmpty()) return List.of();
        JsonNode data = client.retrieve(question, limit, threshold, activeIds);
        JsonNode chunks = data.path("chunks");
        if (!chunks.isArray()) throw new IllegalStateException("RAGFlow retrieval chunks missing");
        List<RetrievedEvidence> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int totalChars = 0;
        for (JsonNode chunk : chunks) {
            String datasetId = required(chunk, "dataset_id");
            String docId = required(chunk, "document_id");
            String chunkId = required(chunk, "id");
            if (!client.datasets().contains(datasetId)) throw new IllegalStateException("RAGFlow returned disallowed dataset");
            if (!activeIds.contains(docId) || !registry.active(datasetId, docId))
                throw new IllegalStateException("RAGFlow returned inactive document");
            String key = "ragflow:" + datasetId + ":" + docId + ":" + chunkId;
            String content = clean(chunk.path("content").asText(""), 700);
            if (content.isBlank() || !seen.add(key)) continue;
            int remaining = MAX_TOTAL_CONTENT - totalChars;
            if (remaining <= 0) break;
            if (content.length() > remaining) content = content.substring(0, remaining);
            totalChars += content.length();
            int n = output.size() + 1;
            String title = registry.title(datasetId, docId);
            output.add(new RetrievedEvidence("来源" + n, "[来源" + n + "]", key, datasetId, docId,
                    chunkId, clean(title.isBlank() ? chunk.path("document_keyword").asText("") : title, 180),
                    ToolOutputSanitizer.markUntrusted("knowledge-base", content),
                    chunk.hasNonNull("similarity") ? chunk.path("similarity").asDouble() : null,
                    "ragflow", true, null, null));
            if (output.size() == limit) break;
        }
        return List.copyOf(output);
    }

    /** Resolves a persistent citation against the real RAGFlow chunk API. */
    public boolean citationExists(String persistentSourceId) {
        if (!ragflow() || persistentSourceId == null) return false;
        String[] parts = persistentSourceId.split(":", -1);
        if (parts.length != 5 || !"kb".equals(parts[0]) || !"ragflow".equals(parts[1])) return false;
        if (!client.datasets().contains(parts[2])) return false;
        if (!registry.active(parts[2], parts[3])) return false;
        try {
            JsonNode chunk = client.chunk(parts[2], parts[3], parts[4]);
            return parts[4].equals(chunk.path("id").asText()) && parts[3].equals(chunk.path("doc_id").asText());
        } catch (RagflowApiException missing) {
            if (missing.code() == 100) return false;
            throw missing;
        }
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
