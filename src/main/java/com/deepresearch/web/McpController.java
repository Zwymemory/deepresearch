package com.deepresearch.web;

import com.deepresearch.mcp.McpKnowledgeClientService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 管理员可调用的 MCP 闭环演示入口；返回协商版本、发现的工具 Schema 与真实调用结果。 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpKnowledgeClientService clientService;
    private final boolean publicDemoEnabled;

    public McpController(McpKnowledgeClientService clientService,
                         @Value("${deepresearch.security.mcp-public:false}") boolean publicDemoEnabled) {
        this.clientService = clientService;
        this.publicDemoEnabled = publicDemoEnabled;
    }

    @GetMapping("/kb-search")
    public McpKnowledgeClientService.McpCallResult search(@RequestParam String query) {
        if (!publicDemoEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "旧 MCP 自调用演示默认关闭；请使用 workflow 的任务级 delegation");
        }
        return clientService.searchKnowledge(query);
    }
}
