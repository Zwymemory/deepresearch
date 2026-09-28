package com.deepresearch.workflow;

import java.util.Map;

/** Public reason codes/messages. Raw provider errors and credentials never leave the server. */
public final class DifyFailureCodes {
    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("WEB_SEARCH_NOT_CONFIGURED", "网页搜索尚未配置 Tavily 凭据。请配置后重试，或使用知识库检索。"),
            Map.entry("WEB_SEARCH_PROVIDER_UNAVAILABLE", "网页搜索服务暂时不可用，本次未发布答案。"),
            Map.entry("WEB_SEARCH_PROVIDER_AUTH_FAILED", "网页搜索服务未接受当前凭据，请检查服务端配置。"),
            Map.entry("WEB_SEARCH_RATE_LIMITED", "网页搜索服务限流，请稍后重试。"),
            Map.entry("WEB_SEARCH_TIMEOUT", "网页搜索请求超时，本次未发布答案。"),
            Map.entry("WEB_SEARCH_NO_RESULTS", "网页搜索没有返回可用摘要证据，无法据此作答。"),
            Map.entry("NO_RELEVANT_EVIDENCE", "没有足够的相关证据，本次未发布答案。"),
            Map.entry("DIFY_MODEL_OUTPUT_INVALID", "模型输出未通过格式或字段校验，本次未发布答案。"),
            Map.entry("DIFY_TOOL_RESPONSE_INVALID", "工具响应未通过证据格式校验，本次未发布答案。"),
            Map.entry("DIFY_TOOL_TRANSPORT_ERROR", "编排服务未收到有效的工具响应，本次未发布答案。"),
            Map.entry("TOOL_UNAVAILABLE", "所选工具暂时不可用，本次未发布答案。"),
            Map.entry("INVALID_ARGUMENT", "工具参数无效，本次未发布答案。"),
            Map.entry("TOOL_BUDGET_EXCEEDED", "工具调用次数已达到本次任务上限。"),
            Map.entry("CALL_ID_CONFLICT", "同一工具调用标识出现参数冲突，已拒绝执行。"),
            Map.entry("RESULT_UNKNOWN", "工具调用结果未知，未自动重复执行。"),
            Map.entry("RESULT_TOO_LARGE", "工具结果超过安全大小限制，本次未发布答案。"),
            Map.entry("DIFY_OUTPUT_INVALID", "编排结果未通过输出契约校验，本次未发布答案。"),
            Map.entry("DIFY_WORKFLOW_FAILED", "编排运行失败，本次未发布答案。"),
            Map.entry("CITATION_VALIDATION_FAILED", "引用不属于当前任务或编号映射无效，本次未发布答案。"),
            Map.entry("CITATION_SOURCE_UNAVAILABLE", "引用来源不可用或搜索回执无法验证，本次未发布答案。"));

    private DifyFailureCodes() {}

    public static boolean known(String code) { return MESSAGES.containsKey(code); }

    public static String message(String code) { return MESSAGES.getOrDefault(code, "任务未完成，请查看运行状态。"); }
}
