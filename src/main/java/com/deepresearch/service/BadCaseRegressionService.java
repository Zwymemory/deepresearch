package com.deepresearch.service;

import com.deepresearch.web.dto.AgentBadCaseResponse;
import com.deepresearch.web.dto.AgentHarnessRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** 将人工差评 bad case 转成可审查、可保存、可再次运行的确定性 Harness 用例。 */
@Service
public class BadCaseRegressionService {

    private final AgentStateService stateService;

    public BadCaseRegressionService(AgentStateService stateService) {
        this.stateService = stateService;
    }

    public AgentHarnessRequest convert(int limit, List<String> modes) {
        List<AgentHarnessRequest.AgentHarnessCase> cases = stateService.listBadCases(limit).stream()
                .map(this::toCase)
                .toList();
        return new AgentHarnessRequest("bad-cases-draft", cases.isEmpty() ? null : cases.size(),
                null, cases, modes);
    }

    private AgentHarnessRequest.AgentHarnessCase toCase(AgentBadCaseResponse badCase) {
        boolean groundingFailure = badCase.reason() != null
                && (badCase.reason().toUpperCase(Locale.ROOT).contains("GROUND")
                || badCase.reason().toUpperCase(Locale.ROOT).contains("CITATION"));
        return new AgentHarnessRequest.AgentHarnessCase(
                "badcase-" + badCase.runId(),
                badCase.userId(),
                badCase.question(),
                null,
                List.of(),
                List.of("shell", "exec", "write_file"),
                List.of(),
                List.of(),
                List.of("DONE"),
                true,
                3,
                null,
                List.of(),
                false,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                "bad-case-draft",
                groundingFailure ? List.of("needs-review", "grounding") : List.of("needs-review"),
                false);
    }
}
