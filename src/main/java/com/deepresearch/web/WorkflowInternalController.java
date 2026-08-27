package com.deepresearch.web;

import com.deepresearch.workflow.WorkflowAccessService;
import com.deepresearch.workflow.WorkflowDtos.DelegationToken;
import com.deepresearch.workflow.WorkflowDtos.FinalizeRequest;
import com.deepresearch.workflow.WorkflowDtos.FinalizeResponse;
import com.deepresearch.workflow.WorkflowDtos.TokenExchangeRequest;
import com.deepresearch.workflow.WorkflowService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private control endpoints. They authenticate a separate short-lived sidecar service JWT. */
@RestController
@RequestMapping("/internal")
public class WorkflowInternalController {

    private final WorkflowAccessService accessService;
    private final WorkflowService workflowService;

    public WorkflowInternalController(WorkflowAccessService accessService, WorkflowService workflowService) {
        this.accessService = accessService;
        this.workflowService = workflowService;
    }

    @PostMapping("/workflow-grants/{grantId}/token")
    public DelegationToken exchange(@PathVariable String grantId,
                                    @RequestHeader("Authorization") String authorization,
                                    @RequestBody @Valid TokenExchangeRequest request) {
        accessService.authenticateInternal(authorization);
        return accessService.exchange(grantId, request);
    }

    @PostMapping("/research/workflows/{runId}/finalize")
    public FinalizeResponse finalizeRun(@PathVariable String runId,
                                        @RequestHeader("Authorization") String authorization,
                                        @RequestBody @Valid FinalizeRequest request) {
        accessService.authenticateInternal(authorization);
        return workflowService.finalizeRun(runId, request);
    }
}
