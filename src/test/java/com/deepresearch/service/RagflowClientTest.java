package com.deepresearch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class RagflowClientTest {
    @Test
    void retrievalSendsExplicitLimitsAndRejectsBusinessError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>("{\"code\":0,\"data\":{\"chunks\":[]}}");
        server.createContext("/api/v1/retrieval", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            RagflowClient client = new RagflowClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", List.of("ds"),
                    Duration.ofSeconds(1), Duration.ofSeconds(2));
            assertThat(client.retrieve("question", 4, 0.2).path("chunks").size()).isZero();
            assertThat(request.get()).contains("\"dataset_ids\":[\"ds\"]", "\"page_size\":4", "\"knn_top_k\":4");
            reply.set("{\"code\":101,\"message\":\"bad\"}");
            assertThatThrownBy(() -> client.retrieve("question", 4, 0.2)).hasMessageContaining("response code 101");
        } finally { server.stop(0); }
    }
}
