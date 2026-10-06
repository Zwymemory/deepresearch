// Saved research progress used to continue a project in a new session (memory M1).
//
// The browser only submits identifiers (sessionId + researchProjectId); the server chooses
// and re-checks the history. Nothing the page observes proves that a model received or used
// that history, so the UI never says so. Errors are explained by stable code only.

export const MEMORY_ERRORS: Record<string, { label: string; text: string; reselect: boolean }> = {
  RESEARCH_MEMORY_REQUEST_INVALID: { label: "续研请求格式不正确", text: "请求格式不被接受，页面没有自动重试。", reselect: false },
  RESEARCH_MEMORY_NOT_FOUND: { label: "项目或会话不可访问", text: "所选项目或会话不存在、无权访问，或该会话已属于其他项目。请在研究档案中重新选择并载入。", reselect: true },
  RESEARCH_MEMORY_UNAVAILABLE: { label: "没有可用的研究进度", text: "该项目当前没有可以纳入规划的已保存进度，研究没有开始。请重新保存或选择其他项目。", reselect: true },
  RESEARCH_MEMORY_REVOKED: { label: "保存的进度已变化", text: "所选进度快照已被删除或更改，后续不会再使用它。请重新载入后再继续。", reselect: true },
  RESEARCH_MEMORY_INVALID: { label: "保存的进度未通过核对", text: "服务端核对历史进度时未通过，不会使用它。请重新载入后再继续。", reselect: true },
  RESEARCH_MEMORY_INPUT_TOO_LARGE: { label: "历史进度超过上限", text: "历史进度超过规划输入的上限，页面不会截断或改用其他内容。", reselect: true },
  RESEARCH_MEMORY_WIRE_BUDGET_EXCEEDED: { label: "运行预算不足以容纳历史进度", text: "加入历史进度后超出本次运行的预算，没有发送。", reselect: false },
  RESEARCH_MEMORY_IDEMPOTENCY_CONFLICT: { label: "请求冲突", text: "同一幂等键已用于不同的请求或项目。请重新发起一次明确的研究。", reselect: false },
  RESEARCH_MEMORY_BINDING_CONFLICT: { label: "会话的项目关联冲突", text: "该会话与多个项目存在关联，服务端拒绝自行选择。请使用新的会话重新载入。", reselect: true },
};

export const isMemoryCode = (code: unknown): code is string => typeof code === "string" && code.startsWith("RESEARCH_MEMORY_");

export function memoryErrorInfo(code: string): { label: string; text: string; reselect: boolean } {
  return MEMORY_ERRORS[code] ?? { label: "历史进度未被使用", text: `服务端拒绝使用历史进度（${code}）。`, reselect: true };
}

/** Loaded project/session pair carried from an explicit load to an explicit Continue. */
export interface Continuation {
  scope: string;
  projectId: string;
  sessionId: string;
  preview: boolean;
  goals: string[];
  unresolved: Array<{ goal: string; gaps: string[]; criteria: string[] }>;
  nextSteps: string[];
}

/** A run created with an explicit project selection (as requested, not as observed by a model). */
export interface MemoryRequest { projectId: string; sessionId: string }
