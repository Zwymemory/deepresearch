package com.deepresearch.service;

/** 可安全返回给调用方的 Agent 运行终止原因，不携带底层异常详情。 */
public final class AgentControlException extends RuntimeException {

    private final String code;

    public AgentControlException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
