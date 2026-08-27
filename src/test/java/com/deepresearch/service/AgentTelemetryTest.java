package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTelemetryTest {

    @Test
    void recordsLowCardinalityRunModelToolAndUsageMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentTelemetry telemetry = new AgentTelemetry(meters, ObservationRegistry.create());
        AgentResearchResponse response = response();

        AgentResearchResponse observed = telemetry.observeRun("native-tool-calling", () -> {
            telemetry.recordModel(Duration.ofMillis(12), true, 321);
            telemetry.recordTool("kb_search", Duration.ofMillis(8), false);
            telemetry.recordRetrievalHits(7);
            return response;
        });

        assertThat(observed).isSameAs(response);
        assertThat(meters.get("deepresearch.agent.run.duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("deepresearch.agent.model.duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("deepresearch.agent.tool.calls").counter().count()).isEqualTo(1);
        assertThat(meters.get("deepresearch.agent.tool.failures").counter().count()).isEqualTo(1);
        assertThat(meters.get("deepresearch.agent.total.tokens").summary().totalAmount()).isEqualTo(30);
        assertThat(meters.get("deepresearch.retrieval.hit.count").summary().totalAmount()).isEqualTo(7);
    }

    private AgentResearchResponse response() {
        AgentResearchResponse.Usage usage = new AgentResearchResponse.Usage(
                20, 10, 30, false, new BigDecimal("0.001"), "CNY", 50, 2, 1);
        return new AgentResearchResponse(
                "run-1", null, "answer", 2, true,
                new AgentResearchResponse.MemoryContext("", List.of(), List.of(),
                        new AgentResearchResponse.Diagnostics(false, 0, 0, 0, "test")),
                List.of(), List.of(), "SUCCESS", usage);
    }
}
