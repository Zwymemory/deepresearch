package com.deepresearch.service;

import com.deepresearch.model.RerankCandidate;
import com.deepresearch.model.RerankResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 通过 HTTP 调用 Python FastAPI cross-encoder reranker。
 */
@Service
public class HttpRerankService implements RerankService {

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String url;
    private final int timeoutMs;
    private final String expectedModel;

    public HttpRerankService(ObjectMapper objectMapper,
                             @Value("${deepresearch.rerank.enabled:true}") boolean enabled,
                             @Value("${deepresearch.rerank.url:http://localhost:9000/rerank}") String url,
                             @Value("${deepresearch.rerank.timeout-ms:3000}") int timeoutMs,
                             @Value("${deepresearch.rerank.model:}") String expectedModel) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.url = url;
        this.timeoutMs = timeoutMs;
        this.expectedModel = expectedModel == null ? "" : expectedModel.trim();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<RerankResult> rerank(String question, List<RerankCandidate> candidates) {
        if (!enabled || candidates.isEmpty()) {
            return List.of();
        }
        try {
            List<Map<String, String>> documents = candidates.stream()
                    .map(candidate -> Map.of(
                            "id", safe(candidate.id()),
                            "title", safe(candidate.title()),
                            "content", safe(candidate.content())
                    ))
                    .toList();
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", safe(question),
                    "documents", documents
            ));
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(bytes);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("reranker 返回非 2xx 状态码: "
                        + statusCode + ", body=" + responseBody);
            }
            RerankResponse rerankResponse = objectMapper.readValue(responseBody, RerankResponse.class);
            if (!expectedModel.isBlank() && !expectedModel.equals(rerankResponse.model())) {
                throw new IllegalStateException("reranker 模型与服务端期望配置不一致");
            }
            return rerankResponse.results() == null ? List.of() : rerankResponse.results();
        } catch (IOException e) {
            throw new IllegalStateException("调用 reranker 失败: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private record RerankResponse(
            String model,
            @JsonProperty("results") List<RerankResult> results
    ) {
    }
}
