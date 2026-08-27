package com.deepresearch.service;

/** Agent 状态层统一的用户、session 与文本规范化规则。 */
final class AgentIdentitySupport {

    private static final String DEFAULT_USER = "default";

    private AgentIdentitySupport() {
    }

    static String userId(String value) {
        return value == null || value.isBlank() ? DEFAULT_USER : value.trim();
    }

    static String sessionId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
