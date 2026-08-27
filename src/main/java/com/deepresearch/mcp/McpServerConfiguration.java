package com.deepresearch.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把 kb_search ToolCallback 注册给 Spring AI MCP Server starter。 */
@Configuration
public class McpServerConfiguration {

    @Bean
    ToolCallbackProvider mcpKnowledgeToolProvider(McpKnowledgeTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
