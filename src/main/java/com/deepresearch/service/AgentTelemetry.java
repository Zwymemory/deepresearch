package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/** Agent 的低基数指标与 Observation；禁止把 prompt、答案、凭证或文档正文放进 tag。 */
@Component
public class AgentTelemetry {

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public AgentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    public AgentResearchResponse observeRun(String mode, Supplier<AgentResearchResponse> action) {
        Observation observation = Observation.createNotStarted("deepresearch.agent.run", observations)
                .lowCardinalityKeyValue("agent.mode", mode)
                .start();
        Timer.Sample sample = Timer.start(meters);
        try (Observation.Scope ignored = observation.openScope()) {
            AgentResearchResponse response = action.get();
            String status = response.status() == null ? "UNKNOWN" : response.status();
            observation.lowCardinalityKeyValue("agent.status", status);
            recordRun(mode, response);
            return response;
        } catch (RuntimeException failure) {
            observation.error(failure);
            throw failure;
        } finally {
            sample.stop(Timer.builder("deepresearch.agent.run.duration")
                    .tag("mode", mode).register(meters));
            observation.stop();
        }
    }

    public <T> T observeModel(Supplier<T> action) {
        return observeChild("deepresearch.agent.model", null, action);
    }

    public <T> T observeTool(String toolName, Supplier<T> action) {
        return observeChild("deepresearch.agent.tool", toolName, action);
    }

    public void recordModel(Duration duration, boolean success, int contextChars) {
        Timer.builder("deepresearch.agent.model.duration")
                .tag("status", success ? "success" : "failure")
                .register(meters).record(duration);
        DistributionSummary.builder("deepresearch.agent.context.chars")
                .register(meters).record(Math.max(0, contextChars));
    }

    public void recordTool(String toolName, Duration duration, boolean success) {
        String safeTool = safeToolName(toolName);
        Counter.builder("deepresearch.agent.tool.calls").tag("tool", safeTool).register(meters).increment();
        if (!success) {
            Counter.builder("deepresearch.agent.tool.failures").tag("tool", safeTool).register(meters).increment();
        }
        Timer.builder("deepresearch.agent.tool.duration")
                .tag("tool", safeTool).tag("status", success ? "success" : "failure")
                .register(meters).record(duration);
    }

    public void recordRetrievalHits(int hits) {
        DistributionSummary.builder("deepresearch.retrieval.hit.count")
                .register(meters).record(Math.max(0, hits));
    }

    private void recordRun(String mode, AgentResearchResponse response) {
        AgentResearchResponse.Usage usage = response.usage();
        DistributionSummary.builder("deepresearch.agent.input.tokens").tag("mode", mode)
                .register(meters).record(usage.inputTokens());
        DistributionSummary.builder("deepresearch.agent.output.tokens").tag("mode", mode)
                .register(meters).record(usage.outputTokens());
        DistributionSummary.builder("deepresearch.agent.total.tokens").tag("mode", mode)
                .register(meters).record(usage.totalTokens());
        DistributionSummary.builder("deepresearch.agent.estimated.cost").tag("currency", usage.costCurrency())
                .register(meters).record(usage.estimatedCost().doubleValue());
        DistributionSummary.builder("deepresearch.agent.rounds").tag("mode", mode)
                .register(meters).record(response.rounds());
        if (!response.finished()) {
            Counter.builder("deepresearch.agent.fallback.count")
                    .tag("status", response.status()).register(meters).increment();
        }
    }

    private <T> T observeChild(String name, String toolName, Supplier<T> action) {
        Observation parent = observations.getCurrentObservation();
        Observation observation = Observation.createNotStarted(name, observations)
                .parentObservation(parent);
        if (toolName != null) {
            observation.lowCardinalityKeyValue("tool.name", safeToolName(toolName));
        }
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            return action.get();
        } catch (RuntimeException failure) {
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private String safeToolName(String toolName) {
        if (toolName == null || !toolName.matches("[a-zA-Z0-9_-]{1,64}")) {
            return "unknown";
        }
        return toolName;
    }
}
