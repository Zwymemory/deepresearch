package com.deepresearch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/** Bounded, server-side RAGFlow HTTP adapter. */
@Component
public class RagflowClient {
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;
    private final List<String> datasets;
    private final Duration timeout;

    public RagflowClient(ObjectMapper json,
            @Value("${deepresearch.ragflow.base-url:http://127.0.0.1:9380}") String baseUrl,
            @Value("${deepresearch.ragflow.api-key:}") String apiKey,
            @Value("${deepresearch.ragflow.dataset-ids:}") List<String> datasets,
            @Value("${deepresearch.ragflow.connect-timeout:3s}") Duration connectTimeout,
            @Value("${deepresearch.ragflow.read-timeout:15s}") Duration timeout) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.datasets = datasets.stream().filter(s -> !s.isBlank()).toList();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    public List<String> datasets() { return datasets; }

    public void requireConfigured() {
        if (apiKey.isBlank() || datasets.isEmpty()) throw new IllegalStateException("RAGFlow API key and dataset allowlist are required");
        URI uri = URI.create(baseUrl);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalStateException("Invalid RAGFlow base URL");
    }

    public JsonNode retrieve(String question, int limit, double threshold) {
        requireConfigured();
        return request("POST", "/api/v1/retrieval", Map.of("question", question, "dataset_ids", datasets,
                "page", 1, "page_size", limit, "knn_top_k", limit, "similarity_threshold", threshold));
    }

    public String upload(String datasetId, String filename, byte[] content) {
        requireAllowed(datasetId);
        String boundary = "ragflow-" + UUID.randomUUID();
        String safeFilename = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + safeFilename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(suffix, 0, body, prefix.length + content.length, suffix.length);
        JsonNode result = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/datasets/" + datasetId + "/documents"))
                .timeout(timeout).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
        String id = result.path(0).path("id").asText("");
        if (id.isBlank()) throw new IllegalStateException("RAGFlow upload returned no document ID");
        return id;
    }

    public void parse(String datasetId, String documentId) {
        requireAllowed(datasetId);
        request("POST", "/api/v1/datasets/" + datasetId + "/chunks", Map.of("document_ids", List.of(documentId)));
    }

    public JsonNode document(String datasetId, String documentId) {
        requireAllowed(datasetId);
        JsonNode docs = request("GET", "/api/v1/datasets/" + datasetId + "/documents?id=" + documentId, null).path("docs");
        if (!docs.isArray() || docs.isEmpty()) throw new IllegalStateException("RAGFlow document missing");
        return docs.get(0);
    }

    public void delete(String datasetId, String documentId) {
        requireAllowed(datasetId);
        request("DELETE", "/api/v1/datasets/" + datasetId + "/documents", Map.of("ids", List.of(documentId)));
    }

    private void requireAllowed(String datasetId) {
        requireConfigured();
        if (!datasets.contains(datasetId) || !datasetId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("RAGFlow dataset is not allowed");
    }

    public JsonNode request(String method, String path, Object body) {
        requireConfigured();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout).header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json");
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return send(builder.build());
        } catch (java.io.IOException e) { throw new IllegalStateException("RAGFlow serialization failed", e); }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) { bytes = stream.readNBytes(MAX_BODY + 1); }
            if (bytes.length > MAX_BODY) throw new IllegalStateException("RAGFlow response too large");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("RAGFlow HTTP " + response.statusCode());
            JsonNode root = json.readTree(bytes);
            if (root.path("code").asInt(-1) != 0) throw new IllegalStateException("RAGFlow response code " + root.path("code").asText());
            return root.path("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RAGFlow request interrupted", e);
        } catch (java.io.IOException e) { throw new IllegalStateException("RAGFlow request failed", e); }
    }
}
