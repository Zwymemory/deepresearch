package com.deepresearch.agent;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.WorkflowDelegationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionPolicyTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void regularUserGetsReadOnlyResearchToolsButNotProjectFiles() {
        authenticate(new AuthPrincipal("tenant-a", "user-a", List.of("USER")), "ROLE_USER");

        assertThat(ToolExecutionPolicy.authorize("searchKnowledge", "fp").permitted()).isTrue();
        assertThat(ToolExecutionPolicy.authorize("calculate", "fp").permitted()).isTrue();
        assertThat(ToolExecutionPolicy.authorize("readProjectFile", "fp").permitted()).isFalse();
        assertThat(ToolExecutionPolicy.authorize("unknownTool", "fp").permitted()).isFalse();
    }

    @Test
    void adminCanUseAllowlistedProjectFileTool() {
        authenticate(new AuthPrincipal("tenant-a", "admin-a", List.of("ADMIN")), "ROLE_ADMIN");

        assertThat(ToolExecutionPolicy.authorize("file_read", "fp").permitted()).isTrue();
        assertThat(ToolExecutionPolicy.authorize("readProjectFile", "fp").permitted()).isTrue();
    }

    @Test
    void missingPrincipalFailsClosedUnlessEvaluationScopeIsExplicit() {
        assertThat(ToolExecutionPolicy.authorize("searchKnowledge", "fp").permitted()).isFalse();

        try (com.deepresearch.service.AgentEvaluationArtifact.Scope ignored =
                     com.deepresearch.service.AgentEvaluationArtifact.Scope.open(List.of(), List.of())) {
            assertThat(ToolExecutionPolicy.authorize("test-only-tool", "fp").permitted()).isTrue();
        }
    }

    @Test
    void workflowDelegationCanOnlyUseItsTaskScopesEvenForAdminLikeToolNames() {
        AuthPrincipal principal = new AuthPrincipal("tenant-a", "user-a", List.of("USER"));
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, "delegation", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new WorkflowDelegationContext(
                principal, "wf-1", "grant-1", "task-1",
                "7e65e2c8-4251-41ed-94aa-123147661234", Set.of("kb_search")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(ToolExecutionPolicy.authorize("kb_search", "fp").permitted()).isTrue();
        assertThat(ToolExecutionPolicy.authorize("calculator", "fp").permitted()).isFalse();
        assertThat(ToolExecutionPolicy.authorize("file_read", "fp").permitted()).isFalse();
    }

    private void authenticate(AuthPrincipal principal, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, "test-token", List.of(new SimpleGrantedAuthority(authority))));
    }
}
