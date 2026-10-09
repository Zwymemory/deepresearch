package com.deepresearch.tool;

import com.deepresearch.model.SearchHit;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
    private static final Pattern DOMAIN = Pattern.compile(
            "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public TavilySearchClient(@Value("${tavily.api-key:}") String apiKey) {
        this(apiKey, boundedClient());
    }

    TavilySearchClient(String apiKey, RestClient restClient) {
        this.apiKey = apiKey;
        this.restClient = restClient;
    }

    private static RestClient boundedClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().baseUrl("https://api.tavily.com").requestFactory(factory).build();
    }

    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }

    public record SearchOutcome(String code, List<SearchHit> hits) {
        public static SearchOutcome failure(String code) { return new SearchOutcome(code, List.of()); }
    }

    /**
     * 执行一次搜索。
     *
     * @param query      搜索关键词
     * @param maxResults 返回结果条数
     * @return 清洗后的检索结果列表（失败时返回空列表，不抛异常打断主流程）
     */
    public List<SearchHit> search(String query, int maxResults) {
        if (!configured()) {
            throw new IllegalStateException("未配置 TAVILY_API_KEY，无法检索");
        }
        return searchChecked(query, maxResults).hits();
    }

    /** Typed failure information for workflow receipts; no response-body error text escapes. */
    public SearchOutcome searchChecked(String query, int maxResults) {
        if (!configured()) return SearchOutcome.failure("WEB_SEARCH_NOT_CONFIGURED");
        if (query == null || query.isBlank() || maxResults < 1 || maxResults > 10)
            return SearchOutcome.failure("INVALID_ARGUMENT");
        List<SiteScope> sites = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (String token : query.split("\\s+")) {
            if (!token.toLowerCase(Locale.ROOT).startsWith("site:")) { terms.add(token); continue; }
            SiteScope site = SiteScope.parse(token.substring(5));
            if (site == null || sites.size() == 3)
                return SearchOutcome.failure("INVALID_ARGUMENT");
            sites.add(site);
        }

        // Tavily 请求体：basic 深度足够日常用，省额度
        Map<String, Object> body = new HashMap<>();
        body.put("api_key", apiKey);
        String providerQuery = String.join(" ", terms).trim();
        if (providerQuery.isBlank()) providerQuery = sites.stream()
                .map(site -> site.domain() + site.path()).collect(java.util.stream.Collectors.joining(" "));
        body.put("query", providerQuery);
        body.put("max_results", maxResults);
        body.put("search_depth", "basic");
        body.put("include_answer", false);
        body.put("include_raw_content", false); // 用 content 摘要即可，省 token
        if (!sites.isEmpty()) {
            body.put("include_domains", sites.stream().map(SiteScope::domain).distinct().toList());
            body.put("include_domains_mode", "restrict");
        }

        try {
            TavilyResponse resp = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(TavilyResponse.class);

            if (resp == null || resp.results() == null) {
                return SearchOutcome.failure("WEB_SEARCH_PROVIDER_UNAVAILABLE");
            }
            List<SearchHit> hits = resp.results().stream()
                    // Provider filtering is not trusted as the final scope check.
                    .filter(r -> sites.isEmpty() || inScope(r.url(), sites))
                    .map(r -> new SearchHit(
                            safe(r.title()),
                            safe(r.url()),
                            safe(r.content()),
                            r.score() != null ? r.score() : 0.0))
                    .toList();
            return new SearchOutcome("OK", hits);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            String code = status == 401 || status == 403 ? "WEB_SEARCH_PROVIDER_AUTH_FAILED"
                    : status == 429 ? "WEB_SEARCH_RATE_LIMITED" : "WEB_SEARCH_PROVIDER_UNAVAILABLE";
            log.warn("Tavily search failed: {}", code);
            return SearchOutcome.failure(code);
        } catch (Exception e) {
            String code = "WEB_SEARCH_PROVIDER_UNAVAILABLE";
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    code = "WEB_SEARCH_TIMEOUT";
                    break;
                }
            }
            log.warn("Tavily search failed: {}", code);
            return SearchOutcome.failure(code);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record SiteScope(String domain, String path) {
        static SiteScope parse(String value) {
            try {
                URI uri = URI.create("https://" + value);
                String host = uri.getHost(), path = uri.getPath();
                if (host == null || !DOMAIN.matcher(host.toLowerCase(Locale.ROOT)).matches()
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                        || uri.getFragment() != null || value.contains("%") || value.contains(":")
                        || !uri.normalize().getPath().equals(path)) return null;
                return new SiteScope(host.toLowerCase(Locale.ROOT), path);
            } catch (IllegalArgumentException invalid) { return null; }
        }
        boolean matches(String host, String actualPath) {
            return (host.equals(domain) || host.endsWith("." + domain))
                    && (path.isEmpty() || path.equals("/") || actualPath.equals(path)
                        || actualPath.startsWith(path.endsWith("/") ? path : path + "/"));
        }
    }

    private static boolean inScope(String url, List<SiteScope> sites) {
        try {
            URI parsed = URI.create(url);
            String host = parsed.getHost();
            if (!("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getUserInfo() != null || host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(normalized).matches()) return false;
            return sites.stream().anyMatch(site -> site.matches(normalized, parsed.normalize().getPath()));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
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
