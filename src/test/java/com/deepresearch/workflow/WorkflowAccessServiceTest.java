package com.deepresearch.workflow;

import com.deepresearch.workflow.WorkflowDtos.TokenExchangeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class WorkflowAccessServiceTest {

    private static final String CLAIM = "7e65e2c8-4251-41ed-94aa-123147661234";
    private static final UUID CLAIM_UUID = UUID.fromString(CLAIM);

    private final WorkflowRepository repository = mock(WorkflowRepository.class);
    private final WorkflowTokenService tokenService = mock(WorkflowTokenService.class);
    private final WorkflowAccessService service = new WorkflowAccessService(
            repository, tokenService, "workflow-sidecar");

    @Test
    void exchangesOnlyIntersectionOfDurableGrantAndTaskRequest() {
        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.of(new WorkflowRepository.GrantRow(
                "grant-1", "wf-1", "tenant-a:user-a", List.of("kb_search", "calculator"),
                OffsetDateTime.now().plusMinutes(2), "WORKING", false)));
        when(repository.bindTaskGrant(eq("grant-1"), eq("wf-1"), eq("task-1"), eq(CLAIM_UUID),
                eq(List.of("kb_search")), any(OffsetDateTime.class))).thenReturn(true);
        when(tokenService.issueDelegation("tenant-a", "user-a", "wf-1", "grant-1", "task-1",
                CLAIM, List.of("kb_search"), 90)).thenReturn(new WorkflowTokenService.Issued("token", 123));

        var token = service.exchange("grant-1",
                new TokenExchangeRequest("wf-1", "task-1", CLAIM, List.of("kb_search")));

        assertThat(token.accessToken()).isEqualTo("token");
        assertThat(token.scopes()).containsExactly("kb_search");
    }

    @Test
    void rejectsScopeEscalationAndTerminalGrant() {
        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.of(new WorkflowRepository.GrantRow(
                "grant-1", "wf-1", "tenant-a:user-a", List.of("kb_search"),
                OffsetDateTime.now().plusMinutes(2), "WORKING", false)));

        assertThatThrownBy(() -> service.exchange("grant-1",
                new TokenExchangeRequest("wf-1", "task-1", CLAIM, List.of("file_read"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未获授权");

        when(repository.activeGrant("grant-2", "wf-2", CLAIM_UUID)).thenReturn(Optional.of(new WorkflowRepository.GrantRow(
                "grant-2", "wf-2", "tenant-a:user-a", List.of("kb_search"),
                OffsetDateTime.now().plusMinutes(2), "CANCELLED", true)));
        assertThatThrownBy(() -> service.exchange("grant-2",
                new TokenExchangeRequest("wf-2", "task-1", CLAIM, List.of("kb_search"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("WORKING");
    }

    @Test
    void rejectsRevokedCrossRunCrossTenantAndTokenScopeMismatch() {
        WorkflowTokenService.Delegation delegation = new WorkflowTokenService.Delegation(
                "tenant-a", "user-a", "wf-1", "grant-1", "task-1",
                CLAIM, List.of("kb_search"), 123);
        when(tokenService.authenticateDelegation("token")).thenReturn(delegation);

        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.authenticateDelegation("token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("失效");

        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.of(
                new WorkflowRepository.GrantRow(
                        "grant-1", "wf-1", "tenant-b:user-a", List.of("kb_search"),
                        OffsetDateTime.now().plusMinutes(2), "WORKING", false)));
        assertThatThrownBy(() -> service.authenticateDelegation("token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不匹配");

        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.of(
                new WorkflowRepository.GrantRow(
                        "grant-1", "wf-1", "tenant-a:user-a", List.of("calculator"),
                        OffsetDateTime.now().plusMinutes(2), "WORKING", false)));
        assertThatThrownBy(() -> service.authenticateDelegation("token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void authenticatesOnlyCurrentClaimAndPersistedTaskScope() {
        WorkflowTokenService.Delegation delegation = new WorkflowTokenService.Delegation(
                "tenant-a", "user-a", "wf-1", "grant-1", "task-1",
                CLAIM, List.of("kb_search"), 123);
        when(tokenService.authenticateDelegation("token")).thenReturn(delegation);
        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.of(
                new WorkflowRepository.GrantRow(
                        "grant-1", "wf-1", "tenant-a:user-a", List.of("kb_search"),
                        OffsetDateTime.now().plusMinutes(2), "WORKING", false)));
        when(repository.activeTaskGrant("grant-1", "wf-1", "task-1", CLAIM_UUID,
                List.of("kb_search"))).thenReturn(true);

        WorkflowDelegationContext context = service.authenticateDelegation("token");

        assertThat(context.claimToken()).isEqualTo(CLAIM);
        assertThat(context.scopes()).containsExactly("kb_search");
    }

    @Test
    void rejectsEmptyTaskScopeBeforeMintingToken() {
        when(repository.activeGrant("grant-1", "wf-1", CLAIM_UUID)).thenReturn(Optional.of(
                new WorkflowRepository.GrantRow(
                        "grant-1", "wf-1", "tenant-a:user-a", List.of("kb_search"),
                        OffsetDateTime.now().plusMinutes(2), "WORKING", false)));

        assertThatThrownBy(() -> service.exchange("grant-1",
                new TokenExchangeRequest("wf-1", "task-1", CLAIM, List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能为空");
    }
}
