package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/** Narrow Dify service API client. The API key never appears in workflow inputs or events. */
@Component
public class DifyWorkflowClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;

    public DifyWorkflowClient(ObjectMapper json,
                              @Value("${deepresearch.workflow.dify.base-url:http://127.0.0.1:8081/v1}") String baseUrl,
                              @Value("${deepresearch.workflow.dify.api-key:}") String apiKey,
                              @Value("${deepresearch.workflow.engine:langgraph}") String engine) {
        if ("dify".equals(engine) && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("Dify App Key 未配置");
        }
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public void run(Map<String, Object> inputs, String internalUser, Consumer<JsonNode> event) throws IOException, InterruptedException {
        if (apiKey.isBlank()) throw new IllegalStateException("Dify App Key 未配置");
        String body = json.writeValueAsString(Map.of("inputs", inputs, "response_mode", "streaming", "user", internalUser));
        String traceId = String.valueOf(inputs.getOrDefault("java_run_id", ""));
        HttpRequest request = request("POST", "/workflows/run", body, traceId);
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (var stream = response.body()) { stream.readNBytes(2048); }
            throw new IOException("Dify run HTTP " + response.statusCode());
        }
        try (var reader = new BufferedReader(new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                if (line.length() > 262_144) throw new IOException("Dify SSE event too large");
                JsonNode parsed = json.readTree(line.substring(5).trim());
                event.accept(parsed);
                if ("workflow_finished".equals(parsed.path("event").asText())) return;
            }
        }
    }

    public JsonNode detail(String workflowRunId) throws IOException, InterruptedException {
        HttpResponse<java.io.InputStream> response = http.send(request("GET", "/workflows/run/" + safeId(workflowRunId), null, null),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Dify detail HTTP " + response.statusCode());
            byte[] body = stream.readNBytes(1_048_577);
            if (body.length > 1_048_576) throw new IOException("Dify detail too large");
            return json.readTree(body);
        }
    }

    public void stop(String taskId, String internalUser) throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("user", internalUser));
        HttpResponse<String> response = http.send(request("POST", "/workflows/tasks/" + safeId(taskId) + "/stop", body, null),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("Dify stop HTTP " + response.statusCode());
    }

    private String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Dify ID 无效");
        return id;
    }

    private HttpRequest request(String method, String path, String body, String traceId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(150))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (traceId != null && traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            builder.header("X-Trace-Id", traceId);
        }
        return body == null ? builder.GET().build() : builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
