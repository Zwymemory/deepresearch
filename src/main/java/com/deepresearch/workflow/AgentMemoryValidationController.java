package com.deepresearch.workflow;

import org.springframework.web.bind.annotation.*;
import static com.deepresearch.workflow.ResearchProgressSelectionDtos.*;

/** Service-authenticated use-time validation, not a dispatch permit or model acknowledgement. */
@RestController
@RequestMapping("/internal/agent/memory")
public class AgentMemoryValidationController {
    private final WorkflowAccessService access;
    private final ResearchProgressSelectionService progress;
    public AgentMemoryValidationController(WorkflowAccessService access, ResearchProgressSelectionService progress) {
        this.access=access;this.progress=progress;
    }
    @PostMapping("/validate")
    public ValidationResult validate(@RequestHeader("Authorization") String authorization,
                                     @RequestBody ValidationRequest request) {
        access.authenticateInternal(authorization);
        return progress.validate(request);
    }
}
