package com.deepresearch;

import com.deepresearch.config.AgentProperties;
import com.deepresearch.config.AgentRuntimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** DeepResearch Java 安全控制面与知识检索服务入口。 */
@SpringBootApplication
@EnableConfigurationProperties({AgentProperties.class, AgentRuntimeProperties.class})
public class DeepResearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepResearchApplication.class, args);
    }
}
