package com.deepresearch.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowTokenServiceTest {

    private static final String INTERNAL = "internal-test-secret-with-at-least-32-bytes";
    private static final String MCP = "mcp-delegation-test-secret-with-at-least-32-bytes";
    private static final String API = "api-user-test-secret-with-at-least-32-bytes";
    private static final String CLAIM = "7e65e2c8-4251-41ed-94aa-123147661234";
    private final WorkflowTokenService service = new WorkflowTokenService(
            new ObjectMapper(), INTERNAL, MCP, API, true);

    @Test
    void issuesShortServiceIdentityToken() {
        WorkflowTokenService.Issued issued = service.issueServiceToken("workflow-sidecar", 30);

        assertThat(service.authenticateService(issued.token())).isEqualTo("workflow-sidecar");
    }

    @Test
    void issuesTaskScopedDelegationWithoutUserRoles() {
        WorkflowTokenService.Issued issued = service.issueDelegation(
                "tenant-a", "user-a", "wf-1", "grant-1", "task-1",
                CLAIM, List.of("kb_search", "calculator"), 90);

        WorkflowTokenService.Delegation delegation = service.authenticateDelegation(issued.token());

        assertThat(delegation.storageUserId()).isEqualTo("tenant-a:user-a");
        assertThat(delegation.runId()).isEqualTo("wf-1");
        assertThat(delegation.taskId()).isEqualTo("task-1");
        assertThat(delegation.claimToken()).isEqualTo(CLAIM);
        assertThat(delegation.scopes()).containsExactly("kb_search", "calculator");
    }

    @Test
    void rejectsTokenSignedForAnotherPlane() {
        String internal = service.issueServiceToken("workflow-sidecar", 30).token();

        assertThatThrownBy(() -> service.authenticateDelegation(internal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("签名无效");
    }

    @Test
    void enabledWorkflowRequiresThreeDistinctSecrets() {
        assertThatThrownBy(() -> new WorkflowTokenService(
                new ObjectMapper(), API, MCP, API, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("彼此不同");
    }

    @Test
    void rejectsExpiredDelegationToken() throws Exception {
        WorkflowTokenService.Issued issued = service.issueDelegation(
                "tenant-a", "user-a", "wf-1", "grant-1", "task-1",
                CLAIM, List.of("kb_search"), 1);

        Thread.sleep(1_100);

        assertThatThrownBy(() -> service.authenticateDelegation(issued.token()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已过期");
    }
}
