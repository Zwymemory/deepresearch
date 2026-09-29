package com.deepresearch.web;

import com.deepresearch.tool.TavilySearchClient;
import com.deepresearch.workflow.DifyFailureCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Authenticated configuration signal, not a provider health/credential validity promise. */
@RestController
public class ResearchToolCapabilitiesController {
    private final TavilySearchClient search;

    public ResearchToolCapabilitiesController(TavilySearchClient search) { this.search = search; }

    @GetMapping("/api/research/tools/capabilities")
    public Map<String, Object> capabilities() {
        boolean configured = search.configured();
        String code = configured ? "" : "WEB_SEARCH_NOT_CONFIGURED";
        return Map.of("webSearch", Map.of("configured", configured, "reasonCode", code,
                "message", configured ? "已配置网页搜索；实际调用仍可能失败或无结果。" : DifyFailureCodes.message(code)));
    }
}
