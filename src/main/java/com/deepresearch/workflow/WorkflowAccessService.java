package com.deepresearch.workflow;

import com.deepresearch.security.AuthPrincipal;
import com.deepresearch.workflow.WorkflowDtos.DelegationToken;
import com.deepresearch.workflow.WorkflowDtos.TokenExchangeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Validates the sidecar identity and exchanges a durable grant for a 90-second task token. */
@Service
public class WorkflowAccessService {

    public static final Set<String> WORKFLOW_TOOLS = Set.of("kb_search", "web_search", "calculator");

    private final WorkflowRepository repository;
    private final WorkflowTokenService tokenService;
    private final String sidecarServiceId;

    public WorkflowAccessService(WorkflowRepository repository,
                                 WorkflowTokenService tokenService,
                                 @Value("${deepresearch.workflow.sidecar-service-id:workflow-sidecar}")
                                 String sidecarServiceId) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.sidecarServiceId = sidecarServiceId;
    }

    public void authenticateInternal(String authorization) {
        String serviceId = tokenService.authenticateService(bearer(authorization));
        if (!sidecarServiceId.equals(serviceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal service identity 无效");
        }
    }

    public DelegationToken exchange(String grantId, TokenExchangeRequest request) {
        UUID claimToken = claimToken(request.claimToken());
        WorkflowRepository.GrantRow grant = repository.activeGrant(grantId, request.runId(), claimToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "workflow grant 不可用"));
        WorkflowStatus status = parseStatus(grant.runStatus());
        if (grant.cancelRequested() || status != WorkflowStatus.WORKING) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "workflow 不在可调用工具的 WORKING 阶段");
        }

        if (request.requestedScopes() == null || request.requestedScopes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "任务级 MCP scope 不能为空");
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>(request.requestedScopes());
        if (requested.size() != 1 || !WORKFLOW_TOOLS.containsAll(requested)
                || !grant.scopes().containsAll(requested)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求的 MCP scope 未获授权");
        }
        List<String> taskScopes = requested.stream().sorted().toList();
        if (!repository.bindTaskGrant(grantId, request.runId(), request.taskId(), claimToken,
                taskScopes, grant.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "taskId 已绑定其他工具 scope");
        }
        String[] subject = grant.subject().split(":", 2);
        if (subject.length != 2) {
            throw new IllegalStateException("workflow grant subject 格式无效");
        }
        long remaining = Math.max(1, grant.expiresAt().toEpochSecond() - Instant.now().getEpochSecond());
        WorkflowTokenService.Issued issued = tokenService.issueDelegation(
                subject[0], subject[1], grant.runId(), grant.grantId(), request.taskId(),
                claimToken.toString(), taskScopes, Math.min(90, remaining));
        return new DelegationToken(issued.token(), "Bearer", issued.expiresAt(), taskScopes);
    }

    public WorkflowDelegationContext authenticateDelegation(String token) {
        WorkflowTokenService.Delegation delegation = tokenService.authenticateDelegation(token);
        UUID claimToken = claimToken(delegation.claimToken());
        WorkflowRepository.GrantRow grant = repository.activeGrant(
                        delegation.grantId(), delegation.runId(), claimToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "workflow grant 已失效"));
        WorkflowStatus status = parseStatus(grant.runStatus());
        if (grant.cancelRequested() || status != WorkflowStatus.WORKING
                || !grant.subject().equals(delegation.storageUserId())
                || !grant.scopes().containsAll(delegation.scopes())
                || !WORKFLOW_TOOLS.containsAll(delegation.scopes())
                || delegation.scopes().size() != 1
                || !repository.activeTaskGrant(delegation.grantId(), delegation.runId(),
                        delegation.taskId(), claimToken, delegation.scopes())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "delegation token 与 grant 不匹配");
        }
        AuthPrincipal principal = new AuthPrincipal(delegation.tenantId(), delegation.userId(), List.of("USER"));
        return new WorkflowDelegationContext(principal, delegation.runId(), delegation.grantId(),
                delegation.taskId(), delegation.claimToken(), Set.copyOf(delegation.scopes()));
    }

    private UUID claimToken(String raw) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "workflow claimToken 无效");
        }
    }

    private WorkflowStatus parseStatus(String raw) {
        try {
            return WorkflowStatus.valueOf(raw);
        } catch (Exception failure) {
            throw new IllegalStateException("未知 workflow 状态：" + raw, failure);
        }
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要 internal bearer token");
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
