package com.deepresearch.tool;

import com.deepresearch.model.SearchHit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily 搜索客户端：调用 Tavily Search API，把网页搜索能力接进来。
 *
 * 选 Tavily 是因为它专为 LLM/Agent 设计——直接返回清洗过的正文片段，
 * 不用自己写爬虫和正文抽取。API 文档：https://docs.tavily.com
 *
 * 这是"能力层"的一块：只负责"调外部检索"，不关心怎么用结果。
 */
@Component
public class TavilySearchClient {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchClient.class);

    private final RestClient restClient;
    private final String apiKey;

    public TavilySearchClient(@Value("${tavily.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.tavily.com")
                .build();
    }

    /**
     * 执行一次搜索。
     *
     * @param query      搜索关键词
     * @param maxResults 返回结果条数
     * @return 清洗后的检索结果列表（失败时返回空列表，不抛异常打断主流程）
     */
    public List<SearchHit> search(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 TAVILY_API_KEY，无法检索");
        }

        // Tavily 请求体：basic 深度足够日常用，省额度
        Map<String, Object> body = new HashMap<>();
        body.put("api_key", apiKey);
        body.put("query", query);
        body.put("max_results", maxResults);
        body.put("search_depth", "basic");
        body.put("include_answer", false);
        body.put("include_raw_content", false); // 用 content 摘要即可，省 token

        try {
            TavilyResponse resp = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

            if (resp == null || resp.results() == null) {
                return List.of();
            }
            List<SearchHit> hits = resp.results().stream()
                    .map(r -> new SearchHit(
                            safe(r.title()),
                            safe(r.url()),
                            safe(r.content()),
                            r.score() != null ? r.score() : 0.0))
                    .toList();
            log.debug("Tavily 检索 \"{}\" 返回 {} 条", query, hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("Tavily 检索失败: {} - {}", query, e.getMessage());
            return List.of();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /* ===== Tavily 响应映射（只取需要的字段，忽略其余） ===== */

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyResponse(List<TavilyResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TavilyResult(
            String title,
            String url,
            String content,
            @JsonProperty("raw_content") String rawContent,
            Double score) {
    }
}
