package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;

import java.util.Set;

/** Authenticated, database-backed MCP capability context for one workflow task. */
public record WorkflowDelegationContext(
        AuthPrincipal principal,
        String runId,
        String grantId,
        String taskId,
        String claimToken,
        Set<String> scopes,
        String callId
) {

    public WorkflowDelegationContext(AuthPrincipal principal, String runId, String grantId,
                                     String taskId, String claimToken, Set<String> scopes) {
        this(principal, runId, grantId, taskId, claimToken, scopes, null);
    }

    public WorkflowDelegationContext withCallId(String value) {
        return new WorkflowDelegationContext(
                principal, runId, grantId, taskId, claimToken, scopes, value);
    }
}
