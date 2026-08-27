package com.deepresearch.service;

import com.deepresearch.security.AuthPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * W10.6 production：从认证上下文获取用户身份。
 *
 * 当前使用 Bearer JWT，认证后 principal 中包含 tenantId/userId/roles。
 * storageUserId = tenantId:userId，用于复用 W9 表结构做多租户隔离。
 */
@Service
public class UserContextService {

    private final String defaultUserId;
    private final boolean allowLegacyHeaders;

    public UserContextService(@Value("${deepresearch.security.default-user-id:demo-user}") String defaultUserId,
                              @Value("${deepresearch.security.allow-legacy-headers:false}") boolean allowLegacyHeaders) {
        this.defaultUserId = defaultUserId == null || defaultUserId.isBlank() ? "demo-user" : defaultUserId.trim();
        this.allowLegacyHeaders = allowLegacyHeaders;
    }

    public String currentUser(String headerUserId, String fallbackUserId) {
        AuthPrincipal principal = currentPrincipal();
        if (principal != null) {
            return principal.storageUserId();
        }
        if (!allowLegacyHeaders) {
            throw new ResponseStatusException(UNAUTHORIZED, "需要登录");
        }
        if (headerUserId != null && !headerUserId.isBlank()) {
            return sanitize(headerUserId);
        }
        if (fallbackUserId != null && !fallbackUserId.isBlank()) {
            return sanitize(fallbackUserId);
        }
        return defaultUserId;
    }

    public String currentUser() {
        return currentUser(null, null);
    }

    public AuthPrincipal currentPrincipalRequired() {
        AuthPrincipal principal = currentPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "需要登录");
        }
        return principal;
    }

    public void requireAdmin() {
        AuthPrincipal principal = currentPrincipalRequired();
        if (principal.hasRole("ADMIN")) {
            return;
        }
        throw new ResponseStatusException(FORBIDDEN, "需要管理员权限");
    }

    private AuthPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AuthPrincipal authPrincipal ? authPrincipal : null;
    }

    private String sanitize(String userId) {
        String trimmed = userId.trim();
        if (!trimmed.matches("[A-Za-z0-9_.@:-]{1,128}")) {
            throw new ResponseStatusException(FORBIDDEN, "非法 userId");
        }
        return trimmed;
    }
}
