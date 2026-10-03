// Explanations for terminal and failure codes (ported from the V1 page). Only
// stable, safe codes are explained; provider responses are never shown.

export interface FailureInfo { label: string; description: string; recovery: string }

const RETRY = "可以用同样的问题重新发起一次研究（会创建新的运行）。";
const CONFIG = "需要服务端配置调整后再试。";

export const FAILURES: Record<string, FailureInfo> = {
  FAILED: { label: "研究任务失败", description: "失败信息已按安全规则记录。", recovery: RETRY },
  CANCELLED: { label: "研究已取消", description: "服务端已取消任务并撤销后续工具授权。", recovery: RETRY },
  TIMED_OUT: { label: "研究已超时", description: "任务超过了允许的执行时间。", recovery: RETRY },
  BUDGET_EXCEEDED: { label: "运行预算已用尽", description: "任务已触达模型、工具、Token 或成本上限。", recovery: "可以缩小问题范围后重新提问。" },
  MAX_ROUNDS_REACHED: { label: "推理轮次已用尽", description: "单 Agent 在最大轮数内没有形成最终答案。", recovery: "可以缩小问题范围，或改用 Durable Workflow。" },
  CITATION_VALIDATION_FAILED: { label: "引用映射校验失败", description: "答案编号无法与本次真实工具来源一一对应，候选答案未发布。", recovery: RETRY },
  MODEL_TIMEOUT: { label: "模型调用超时", description: "外部模型调用超过了允许时间。", recovery: RETRY },
  TOOL_TIMEOUT: { label: "工具调用超时", description: "获准工具未在时限内完成。", recovery: RETRY },
  MODEL_RATE_LIMITED: { label: "模型服务限流", description: "外部模型服务暂时拒绝了过多请求。", recovery: "稍后再试。" },
  MODEL_SCHEMA_INVALID: { label: "模型结构化输出无效", description: "模型响应未通过当前节点的类型与字段校验。", recovery: RETRY },
  MODEL_PROVIDER_FAILED: { label: "模型服务调用失败", description: "模型服务返回不可安全重试的错误，或有限重试后仍未成功。", recovery: RETRY },
  MODEL_EXECUTION_FAILED: { label: "模型调用失败", description: "外部模型调用未能安全完成。", recovery: RETRY },
  MODEL_CALL_FAILED: { label: "模型调用失败", description: "规划、审阅或合成节点的模型调用未能产生可用结果。", recovery: RETRY },
  DIFY_MODEL_OUTPUT_TRUNCATED: { label: "模型输出被截断", description: "模型输出达到长度上限，未形成完整结果。", recovery: RETRY },
  DIFY_MODEL_OUTPUT_EMPTY: { label: "模型最终输出为空", description: "模型没有返回可发布的最终结果。", recovery: RETRY },
  DIFY_MODEL_OUTPUT_INVALID: { label: "模型输出格式无效", description: "模型结果未通过 JSON 或字段格式校验。", recovery: RETRY },
  DIFY_MODEL_PROVIDER_ERROR: { label: "模型服务调用失败", description: "模型服务调用发生错误，本次任务未完成。", recovery: RETRY },
  CLAIM_EVIDENCE_INVALID: { label: "论断证据无法对应", description: "答案论断的原文证据无法对应本次检索结果。", recovery: RETRY },
  CLAIM_SUPPORT_INSUFFICIENT: { label: "论断支持不足", description: "现有证据不足以支持完整回答，系统已按证据不足处理。", recovery: "可以补充知识库或调整问题后再试。" },
  WORKFLOW_FAILED: { label: "工作流执行失败", description: "工作流在形成可发布结果前发生了技术故障。", recovery: RETRY },
  WEB_SEARCH_NOT_CONFIGURED: { label: "网页搜索未配置", description: "服务端没有配置网页搜索凭据。", recovery: CONFIG },
  INSUFFICIENT_EVIDENCE: { label: "可信证据不足", description: "系统拒绝在缺少可信证据时自行补全答案。", recovery: "可以补充知识库、启用网页搜索或调整问题。" },
};

export function codeKey(value: unknown): string {
  return String(value ?? "").trim().replace(/[\s-]+/g, "_").toUpperCase();
}

/** The most specific known explanation for a terminal run, or a neutral fallback. */
export function explainFailure(status: string, errorCode?: string | null): FailureInfo & { code: string } {
  for (const candidate of [errorCode, status]) {
    const key = codeKey(candidate);
    if (key && FAILURES[key]) return { code: key, ...FAILURES[key] };
  }
  return { code: codeKey(errorCode || status) || "UNKNOWN", label: "研究任务未完成", description: "服务端返回了未登记的结果码。", recovery: RETRY };
}
