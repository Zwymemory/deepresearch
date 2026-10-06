package com.deepresearch.workflow;

import com.deepresearch.service.UserContextService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;

@RestController
public class ResearchRecallController {
    private final ResearchRecallService recall;
    private final UserContextService users;
    private final WorkflowAccessService access;
    public ResearchRecallController(ResearchRecallService recall,UserContextService users,WorkflowAccessService access) {
        this.recall=recall;this.users=users;this.access=access;
    }
    @GetMapping("/api/research/agents/{runId}/memory-recall")
    public JsonNode view(@PathVariable String runId) {return recall.view(runId,users.currentPrincipalRequired());}
    @PostMapping("/internal/agent/memory/recall/validate")
    public ValidationResult validate(@RequestHeader("Authorization") String token,@RequestBody ValidationRequest request) {
        access.authenticateInternal(token);return recall.validate(request);
    }
}
