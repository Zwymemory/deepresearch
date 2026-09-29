package com.deepresearch.workflow;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/research/agents")
public class AgentWorkflowController {
    private final AgentRunService agents;
    public AgentWorkflowController(AgentRunService agents) { this.agents=agents; }
    @PostMapping
    public ResponseEntity<WorkflowDtos.Accepted> create(@Valid @RequestBody WorkflowDtos.CreateRequest request,
                                                     @RequestHeader("Idempotency-Key") String key) {
        var accepted=agents.create(request,key);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Idempotency-Replayed",Boolean.toString(accepted.replayed())).body(accepted);
    }
}
