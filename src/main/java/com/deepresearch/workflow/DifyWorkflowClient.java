package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Narrow Dify service API client. The API key never appears in workflow inputs or events. */
@Component
public class DifyWorkflowClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration DETAIL_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(10);
    private static final ScheduledExecutorService BODY_DEADLINES =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "dify-http-body-deadline");
                thread.setDaemon(true);
                return thread;
            });
    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;
    private final Duration runTimeout;
    private final Duration detailTimeout;
    private final Duration controlTimeout;

    @Autowired
    public DifyWorkflowClient(ObjectMapper json,
                              @Value("${deepresearch.workflow.dify.base-url:http://127.0.0.1:8081/v1}") String baseUrl,
                              @Value("${deepresearch.workflow.dify.api-key:}") String apiKey,
                              @Value("${deepresearch.workflow.engine:langgraph}") String engine) {
        this(json, baseUrl, apiKey, engine, CONNECT_TIMEOUT, RUN_TIMEOUT, DETAIL_TIMEOUT, CONTROL_TIMEOUT);
    }

    DifyWorkflowClient(ObjectMapper json, String baseUrl, String apiKey, String engine,
                       Duration connectTimeout, Duration runTimeout,
                       Duration detailTimeout, Duration controlTimeout) {
        if ("dify".equals(engine) && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("Dify App Key 未配置");
        }
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.runTimeout = positive(runTimeout, RUN_TIMEOUT);
        this.detailTimeout = positive(detailTimeout, DETAIL_TIMEOUT);
        this.controlTimeout = positive(controlTimeout, CONTROL_TIMEOUT);
        this.http = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, CONNECT_TIMEOUT))
                .build();
    }

    public void run(Map<String, Object> inputs, String internalUser, Consumer<JsonNode> event) throws IOException, InterruptedException {
        if (apiKey.isBlank()) throw new IllegalStateException("Dify App Key 未配置");
        String body = json.writeValueAsString(Map.of("inputs", inputs, "response_mode", "streaming", "user", internalUser));
        String traceId = String.valueOf(inputs.getOrDefault("java_run_id", ""));
        HttpRequest request = request("POST", "/workflows/run", body, traceId, runTimeout);
        long deadline = System.nanoTime() + runTimeout.toNanos();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Dify run HTTP " + response.statusCode());
        }
        ScheduledFuture<?> watchdog = closeAtDeadline(response.body(), deadline);
        try (var reader = new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = readLine(reader, 262_144)) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode parsed = json.readTree(line.substring(5).trim());
                event.accept(parsed);
                if ("workflow_finished".equals(parsed.path("event").asText())) return;
            }
        } finally {
            watchdog.cancel(false);
        }
        throw new IOException("Dify SSE ended before workflow_finished");
    }

    public JsonNode detail(String workflowRunId) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + detailTimeout.toNanos();
        HttpResponse<java.io.InputStream> response = http.send(request("GET", "/workflows/run/" + safeId(workflowRunId),
                        null, null, detailTimeout),
                HttpResponse.BodyHandlers.ofInputStream());
        ScheduledFuture<?> watchdog = closeAtDeadline(response.body(), deadline);
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Dify detail HTTP " + response.statusCode());
            byte[] body = stream.readNBytes(1_048_577);
            if (body.length > 1_048_576) throw new IOException("Dify detail too large");
            return json.readTree(body);
        } finally {
            watchdog.cancel(false);
        }
    }

    public void stop(String taskId, String internalUser) throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("user", internalUser));
        HttpResponse<InputStream> response = http.send(request("POST", "/workflows/tasks/" + safeId(taskId) + "/stop",
                        body, null, controlTimeout),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var ignored = response.body()) {
            if (response.statusCode() / 100 != 2) throw new IOException("Dify stop HTTP " + response.statusCode());
        }
    }

    private String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Dify ID 无效");
        return id;
    }

    private HttpRequest request(String method, String path, String body, String traceId, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (traceId != null && traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            builder.header("X-Trace-Id", traceId);
        }
        return body == null ? builder.GET().build() : builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static ScheduledFuture<?> closeAtDeadline(InputStream stream, long deadlineNanos) {
        long remaining = Math.max(1, deadlineNanos - System.nanoTime());
        return BODY_DEADLINES.schedule(() -> {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The reader reports the timeout or premature EOF to the caller.
            }
        }, remaining, TimeUnit.NANOSECONDS);
    }

    private static String readLine(Reader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maxChars, 1024));
        while (true) {
            int next = reader.read();
            if (next == -1) return line.isEmpty() ? null : line.toString();
            if (next == '\n') {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            if (line.length() >= maxChars) throw new IOException("Dify SSE event too large");
            line.append((char) next);
        }
    }
}
