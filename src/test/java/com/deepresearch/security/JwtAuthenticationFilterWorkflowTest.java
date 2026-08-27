package com.deepresearch.security;

import com.deepresearch.workflow.WorkflowAccessService;
import com.deepresearch.workflow.WorkflowDelegationContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterWorkflowTest {

    private final JwtTokenService apiTokens = mock(JwtTokenService.class);
    private final WorkflowAccessService workflowAccess = mock(WorkflowAccessService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(apiTokens, workflowAccess);
    private final WorkflowDelegationContext delegation = new WorkflowDelegationContext(
            new AuthPrincipal("tenant-a", "user-a", List.of("USER")),
            "run-1", "grant-1", "task-1", "7e65e2c8-4251-41ed-94aa-123147661234",
            Set.of("kb_search"));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void workflowDelegationRequiresIdempotencyKey() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(workflowAccess.authenticateDelegation("delegation-token")).thenReturn(delegation);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Idempotency-Key");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void validatedCallIdIsPropagatedInDelegationDetails() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("Idempotency-Key", "tool-call-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(workflowAccess.authenticateDelegation("delegation-token")).thenReturn(delegation);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        WorkflowDelegationContext details = (WorkflowDelegationContext) SecurityContextHolder
                .getContext().getAuthentication().getDetails();
        assertThat(details.callId()).isEqualTo("tool-call-0001");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/sse");
        request.addHeader("Authorization", "Bearer delegation-token");
        request.addHeader("X-Workflow-Run-Id", "run-1");
        request.addHeader("X-Workflow-Task-Id", "task-1");
        return request;
    }
}
