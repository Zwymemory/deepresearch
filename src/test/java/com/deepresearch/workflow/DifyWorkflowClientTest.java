package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DifyWorkflowClientTest {
    @Test
    void usesStreamingServiceApiAndStableInternalUser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> submitted = new AtomicReference<>();
        AtomicReference<String> trace = new AtomicReference<>();
        AtomicReference<String> stopUser = new AtomicReference<>();
        server.createContext("/v1/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer app-secret");
            String path = exchange.getRequestURI().getPath();
            byte[] response;
            String type = "application/json";
            if (path.equals("/v1/workflows/run") && exchange.getRequestMethod().equals("POST")) {
                submitted.set(json.readTree(exchange.getRequestBody()));
                trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
                type = "text/event-stream";
                response = ("data:{\"event\":\"workflow_started\",\"task_id\":\"task-1\",\"workflow_run_id\":\"remote-1\"}\n\n"
                        + "data: {\"event\":\"workflow_finished\",\"task_id\":\"task-1\",\"workflow_run_id\":\"remote-1\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/workflows/run/remote-1")) {
                response = "{\"id\":\"remote-1\",\"status\":\"succeeded\",\"outputs\":{\"status\":\"INSUFFICIENT_EVIDENCE\"}}"
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/workflows/tasks/task-1/stop")) {
                stopUser.set(json.readTree(exchange.getRequestBody()).path("user").asText());
                response = "{\"result\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(404, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            DifyWorkflowClient client = new DifyWorkflowClient(json,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "app-secret", "dify");
            List<JsonNode> events = new ArrayList<>();
            client.run(Map.of("question", "hello", "java_run_id", "wf-123"), "java:hashed-user", events::add);
            JsonNode detail = client.detail("remote-1");
            client.stop("task-1", "java:hashed-user");

            assertThat(events).hasSize(2);
            assertThat(submitted.get().path("response_mode").asText()).isEqualTo("streaming");
            assertThat(submitted.get().path("user").asText()).isEqualTo("java:hashed-user");
            assertThat(trace.get()).isEqualTo("wf-123");
            assertThat(detail.path("status").asText()).isEqualTo("succeeded");
            assertThat(stopUser.get()).isEqualTo("java:hashed-user");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void difyModeRequiresAppKeyBeforeAcceptingRuns() {
        assertThatThrownBy(() -> new DifyWorkflowClient(new ObjectMapper(),
                "http://127.0.0.1:8081/v1", "", "dify"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("App Key");
    }

    @Test
    void streamEndingAfterStartedButBeforeFinishedIsAnError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workflows/run", exchange -> {
            byte[] response = "data:{\"event\":\"workflow_started\",\"task_id\":\"task-1\",\"workflow_run_id\":\"remote-1\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            DifyWorkflowClient client = new DifyWorkflowClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "app-secret", "dify");
            List<JsonNode> events = new ArrayList<>();

            assertThatThrownBy(() -> client.run(Map.of("java_run_id", "wf-1"),
                    "java:user", events::add))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("before workflow_finished");
            assertThat(events).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedSseLineIsRejectedBeforeItCanGrowWithoutBound() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workflows/run", exchange -> {
            byte[] response = ("data:" + "x".repeat(262_145)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            DifyWorkflowClient client = new DifyWorkflowClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "app-secret", "dify");

            assertThatThrownBy(() -> client.run(Map.of("java_run_id", "wf-1"),
                    "java:user", ignored -> { }))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("too large");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void detailBodyReadHasAHardDeadlineAfterHeadersArrive() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        CountDownLatch release = new CountDownLatch(1);
        server.setExecutor(handlers);
        server.createContext("/v1/workflows/run/remote-1", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{');
                out.flush();
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            DifyWorkflowClient client = new DifyWorkflowClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "app-secret", "dify", Duration.ofSeconds(1), Duration.ofSeconds(1),
                    Duration.ofMillis(150), Duration.ofSeconds(1));
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.detail("remote-1"))
                    .isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
