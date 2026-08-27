package com.deepresearch.service;

import com.deepresearch.web.dto.AgentResearchRequest;
import com.deepresearch.web.dto.AgentResearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.function.Consumer;

/** 在兼容的 manual-react 与默认 native-tool-calling 运行时之间做显式配置路由。 */
@Service
public class AgentRuntimeRouter {

    private final ReactAgentService manual;
    private final NativeToolCallingAgentService nativeToolCalling;
    private final Mode mode;

    public AgentRuntimeRouter(ReactAgentService manual,
                              NativeToolCallingAgentService nativeToolCalling,
                              @Value("${deepresearch.agent.mode:native-tool-calling}") String mode) {
        this.manual = manual;
        this.nativeToolCalling = nativeToolCalling;
        this.mode = Mode.parse(mode);
    }

    public AgentResearchResponse run(AgentResearchRequest request) {
        return run(request, ignored -> {
        });
    }

    public AgentResearchResponse run(AgentResearchRequest request,
                                     Consumer<AgentResearchResponse.Event> eventSink) {
        return mode == Mode.MANUAL_REACT
                ? manual.run(request, eventSink)
                : nativeToolCalling.run(request, eventSink);
    }

    public String mode() {
        return mode.configValue;
    }

    private enum Mode {
        MANUAL_REACT("manual-react"),
        NATIVE_TOOL_CALLING("native-tool-calling");

        private final String configValue;

        Mode(String configValue) {
            this.configValue = configValue;
        }

        static Mode parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.configValue.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(
                    "deepresearch.agent.mode 只能是 manual-react 或 native-tool-calling");
        }
    }
}
