package com.deepresearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 相关的自定义配置，绑定 application.yml 中 deepresearch.* 前缀。
 *
 * 通用模型默认值。Agent 运行预算已迁移到 {@link AgentRuntimeProperties}。
 */
@ConfigurationProperties(prefix = "deepresearch")
public class AgentProperties {

    /** 默认温度：规划/工具/反思类任务用低温保证稳定 */
    private double defaultTemperature = 0.3;

    public double getDefaultTemperature() {
        return defaultTemperature;
    }

    public void setDefaultTemperature(double defaultTemperature) {
        this.defaultTemperature = defaultTemperature;
    }

}
