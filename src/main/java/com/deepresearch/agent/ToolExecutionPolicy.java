package com.deepresearch.agent;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.service.AgentEvaluationArtifact;
import com.deepresearch.workflow.WorkflowDelegationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;
import java.util.Set;

/**
 * 生产工具执行点的最小策略层。
 *
 * 模型只能提出工具意图；这里依据经过认证的真实主体再次裁决。当前项目的工具均为只读，
 * 其中项目文件读取仅向 ADMIN 暴露。没有认证主体时默认拒绝；只有显式打开的隔离 Harness
 * 评测 Scope 可以使用其自己的 allowed/forbidden tool 策略。
 */
public final class ToolExecutionPolicy {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPolicy.class);
    private static final Set<String> USER_TOOLS = Set.of(
            "calculate", "calculator",
            "searchknowledge", "kb_search",
            "searchweb", "web_search"
    );
    private static final Set<String> ADMIN_TOOLS = Set.of("readprojectfile", "file_read");

    private ToolExecutionPolicy() {
    }

    public static Decision authorize(String toolName, String argumentFingerprint) {
        String normalized = normalize(toolName);
        WorkflowDelegationContext delegation = currentDelegation();
        if (delegation != null) {
            String scope = workflowScope(normalized);
            boolean permitted = scope != null && delegation.scopes().contains(scope);
            String reason = permitted ? "WORKFLOW_DELEGATION_SCOPE_MATCH" : "WORKFLOW_SCOPE_NOT_GRANTED";
            log.info("tool_authorization decision={} tenant={} actor={} run={} task={} tool={} argumentFingerprint={} reason={}",
                    permitted ? "PERMIT" : "DENY",
                    delegation.principal().tenantId(), delegation.principal().userId(),
                    delegation.runId(), delegation.taskId(), normalized,
                    argumentFingerprint == null ? "" : argumentFingerprint, reason);
            return permitted ? Decision.allow(reason) : Decision.deny(reason);
        }
        AuthPrincipal principal = currentPrincipal();
        if (principal == null) {
            if (AgentEvaluationArtifact.Scope.current() != null) {
                return Decision.allow("EXPLICIT_EVALUATION_SCOPE");
            }
            log.warn("tool_authorization decision=DENY tenant= actor= tool={} argumentFingerprint={} reason=MISSING_AUTHENTICATED_PRINCIPAL",
                    normalized, argumentFingerprint == null ? "" : argumentFingerprint);
            return Decision.deny("MISSING_AUTHENTICATED_PRINCIPAL");
        }

        boolean permitted = USER_TOOLS.contains(normalized)
                || (ADMIN_TOOLS.contains(normalized) && principal.hasRole("ADMIN"));
        String reason = permitted ? "ROLE_AND_TOOL_POLICY_MATCH" : "TOOL_NOT_GRANTED";
        log.info("tool_authorization decision={} tenant={} actor={} tool={} argumentFingerprint={} reason={}",
                permitted ? "PERMIT" : "DENY",
                principal.tenantId(), principal.userId(), normalized,
                argumentFingerprint == null ? "" : argumentFingerprint, reason);
        return permitted ? Decision.allow(reason) : Decision.deny(reason);
    }

    public static boolean visibleToCurrentPrincipal(String toolName) {
        return authorize(toolName, "schema-discovery").permitted();
    }

    private static AuthPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof AuthPrincipal principal ? principal : null;
    }

    private static WorkflowDelegationContext currentDelegation() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getDetails() instanceof WorkflowDelegationContext delegation
                ? delegation : null;
    }

    private static String workflowScope(String normalizedToolName) {
        return switch (normalizedToolName) {
            case "calculate", "calculator" -> "calculator";
            case "searchknowledge", "kb_search" -> "kb_search";
            case "searchweb", "web_search" -> "web_search";
            default -> null;
        };
    }

    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    public record Decision(boolean permitted, String reason) {
        static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }
}
