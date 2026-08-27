package com.deepresearch.agent;

import com.deepresearch.model.SearchHit;
import com.deepresearch.tool.TavilySearchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页搜索工具（Week3）：把 Week2 的 TavilySearchClient 包装成 Agent 可调用的 Tool。
 *
 * 注意分层：TavilySearchClient 是"怎么调 Tavily"的能力层；本类是"把这个能力暴露给 Agent"的工具层，
 * 负责把检索结果格式化成模型易读的 Observation 文本（带编号，便于最终回答引用 [来源N]）。
 */
@Component
public class WebSearchTool implements Tool {

    private final TavilySearchClient searchClient;
    private final int topK;
    /** 单条结果正文截断长度，控制回灌给模型的 token 量 */
    private static final int SNIPPET_LIMIT = 300;

    public WebSearchTool(TavilySearchClient searchClient,
                         @Value("${deepresearch.search-top-k:5}") int topK) {
        this.searchClient = searchClient;
        this.topK = topK;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索引擎。输入一个搜索查询词（query），返回若干相关网页的【编号、标题、链接、正文摘要】。"
                + "适合查找时效性信息、最新数据、事实性资料。一次只查一个聚焦的子问题，效果更好。";
    }

    @Override
    public String execute(String input) {
        return executeWithCitations(input).content();
    }

    /**
     * Native tool-calling variant. Source URLs are captured directly from the
     * typed Tavily result instead of being rediscovered from model-visible text.
     */
    public CitationAwareToolOutput executeWithCitations(String input) {
        if (input == null || input.isBlank()) {
            return CitationAwareToolOutput.withoutSources("（搜索失败：查询词为空）");
        }
        try {
            List<SearchHit> hits = searchClient.search(input.trim(), topK);
            if (hits.isEmpty()) {
                return CitationAwareToolOutput.withoutSources("（未检索到相关结果，可换个查询词再试）");
            }
            StringBuilder sb = new StringBuilder();
            List<String> sourceIds = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                SearchHit h = hits.get(i);
                sourceIds.add(CitationSourceSupport.safeWebUrl(h.url()));
                sb.append("[来源").append(i + 1).append("] ")
                        .append(ToolOutputSanitizer.neutralizeCitationMarkers(h.title())).append("\n")
                        .append("URL: ")
                        .append(ToolOutputSanitizer.neutralizeCitationMarkers(h.url())).append("\n")
                        .append("摘要: ")
                        .append(ToolOutputSanitizer.neutralizeCitationMarkers(truncate(h.content())))
                        .append("\n\n");
            }
            return new CitationAwareToolOutput(
                    ToolOutputSanitizer.markUntrusted("web", sb.toString().trim()), sourceIds);
        } catch (RuntimeException exception) {
            return CitationAwareToolOutput.withoutSources("（搜索失败：服务暂时不可用）");
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= SNIPPET_LIMIT ? s : s.substring(0, SNIPPET_LIMIT) + "…";
    }
}
