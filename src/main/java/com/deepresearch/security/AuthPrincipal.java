package com.deepresearch.security;

import java.util.List;

/**
 * W10.6 production：认证后的用户主体。
 *
 * storageUserId 用 tenantId:userId 作为数据库隔离键，避免不同租户下相同 userId 互相看见记忆。
 */
public record AuthPrincipal(
        String tenantId,
        String userId,
        List<String> roles
) {

    public String storageUserId() {
        return tenantId + ":" + userId;
    }

    public boolean hasRole(String role) {
        return roles != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
