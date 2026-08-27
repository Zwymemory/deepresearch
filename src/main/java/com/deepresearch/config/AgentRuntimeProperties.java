package com.deepresearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** Agent 运行时硬限制；每次 run 都基于该策略创建独立预算账本。 */
@ConfigurationProperties(prefix = "deepresearch.agent")
public class AgentRuntimeProperties {

    private String mode = "native-tool-calling";
    private int maxRounds = 3;
    private Duration maxDuration = Duration.ofSeconds(30);
    private Duration modelTimeout = Duration.ofSeconds(20);
    private Duration toolTimeout = Duration.ofSeconds(10);
    private int maxToolCalls = 6;
    private int maxInputTokens = 120_000;
    private int maxOutputTokens = 120_000;
    private int maxTotalTokens = 120_000;
    private BigDecimal maxEstimatedCost = BigDecimal.ONE;
    private BigDecimal inputCostPerMillion = BigDecimal.ONE;
    private BigDecimal outputCostPerMillion = BigDecimal.valueOf(2);

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public Duration getMaxDuration() { return maxDuration; }
    public void setMaxDuration(Duration maxDuration) { this.maxDuration = maxDuration; }
    public Duration getModelTimeout() { return modelTimeout; }
    public void setModelTimeout(Duration modelTimeout) { this.modelTimeout = modelTimeout; }
    public Duration getToolTimeout() { return toolTimeout; }
    public void setToolTimeout(Duration toolTimeout) { this.toolTimeout = toolTimeout; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(int maxTotalTokens) { this.maxTotalTokens = maxTotalTokens; }
    public BigDecimal getMaxEstimatedCost() { return maxEstimatedCost; }
    public void setMaxEstimatedCost(BigDecimal maxEstimatedCost) { this.maxEstimatedCost = maxEstimatedCost; }
    public BigDecimal getInputCostPerMillion() { return inputCostPerMillion; }
    public void setInputCostPerMillion(BigDecimal inputCostPerMillion) { this.inputCostPerMillion = inputCostPerMillion; }
    public BigDecimal getOutputCostPerMillion() { return outputCostPerMillion; }
    public void setOutputCostPerMillion(BigDecimal outputCostPerMillion) { this.outputCostPerMillion = outputCostPerMillion; }
}
