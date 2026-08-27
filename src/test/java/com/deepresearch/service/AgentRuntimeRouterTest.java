package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRuntimeRouterTest {

    private final ReactAgentService manual = mock(ReactAgentService.class);
    private final NativeToolCallingAgentService nativeAgent = mock(NativeToolCallingAgentService.class);
    private final AgentResearchRequest request = new AgentResearchRequest("q", "s", "u");

    @Test
    void routesToNativeToolCallingByConfiguredMode() {
        AgentRuntimeRouter router = new AgentRuntimeRouter(manual, nativeAgent, "native-tool-calling");

        router.run(request);

        assertThat(router.mode()).isEqualTo("native-tool-calling");
        verify(nativeAgent).run(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void routesToManualReactAndRejectsUnknownMode() {
        AgentRuntimeRouter router = new AgentRuntimeRouter(manual, nativeAgent, "manual-react");
        router.run(request);

        assertThat(router.mode()).isEqualTo("manual-react");
        verify(manual).run(org.mockito.ArgumentMatchers.eq(request), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> new AgentRuntimeRouter(manual, nativeAgent, "typo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manual-react");
    }
}
