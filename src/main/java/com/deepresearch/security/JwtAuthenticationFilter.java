package com.deepresearch.security;

import com.deepresearch.workflow.WorkflowAccessService;
import com.deepresearch.workflow.WorkflowDelegationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 从 Authorization: Bearer xxx 中解析用户身份，放入 Spring SecurityContext。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern WORKFLOW_CALL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}");

    private final JwtTokenService jwtTokenService;
    private final WorkflowAccessService workflowAccessService;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   WorkflowAccessService workflowAccessService) {
        this.jwtTokenService = jwtTokenService;
        this.workflowAccessService = workflowAccessService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Internal endpoints authenticate their separate, short-lived service JWT in the controller.
        return request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                String token = authorization.substring("Bearer ".length()).trim();
                WorkflowDelegationContext delegation = null;
                AuthPrincipal principal;
                if (request.getRequestURI().startsWith("/mcp/")) {
                    delegation = workflowAccessService.authenticateDelegation(token);
                    validateWorkflowBinding(request, delegation);
                    delegation = delegation.withCallId(workflowCallId(request));
                    principal = delegation.principal();
                } else {
                    principal = jwtTokenService.authenticate(token);
                }
                var authorities = principal.roles().stream()
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                // 认证完成后立即丢弃原始 bearer；下游 MCP 只能使用另行签发的任务级 delegation。
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, authorities);
                if (delegation != null) {
                    authentication.setDetails(delegation);
                }
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (ResponseStatusException e) {
                SecurityContextHolder.clearContext();
                response.setStatus(e.getStatusCode().value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"" + e.getReason() + "\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void validateWorkflowBinding(HttpServletRequest request,
                                         WorkflowDelegationContext delegation) {
        String runId = request.getHeader("X-Workflow-Run-Id");
        String taskId = request.getHeader("X-Workflow-Task-Id");
        if (!delegation.runId().equals(runId) || !delegation.taskId().equals(taskId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "delegation token 与 workflow run/task 不匹配");
        }
    }

    private String workflowCallId(HttpServletRequest request) {
        String callId = request.getHeader("Idempotency-Key");
        if (callId == null || !WORKFLOW_CALL_ID.matcher(callId.trim()).matches()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "workflow MCP 需要合法的 Idempotency-Key");
        }
        return callId.trim();
    }
}
