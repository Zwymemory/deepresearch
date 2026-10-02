// Human-readable descriptions of recorded events (condensed from the V1 page).
// Only safe, user-visible summaries — never chain-of-thought or provider output.
import type { RunEvent } from "./types";

export const STAGE_LABELS: Record<string, string> = {
  READY: "准备就绪", QUEUED: "排队", PLANNING: "规划", WORKING: "检索执行", REVIEWING: "证据审阅",
  SYNTHESIZING: "答案合成", FINALIZING: "结果收尾", TERMINAL: "运行结束", SUCCEEDED: "已完成",
  INSUFFICIENT_EVIDENCE: "证据不足", FAILED: "运行失败", CANCELLED: "已取消", TIMED_OUT: "已超时",
  BUDGET_EXCEEDED: "预算已用尽",
};

export const STEP_LABELS: Record<string, string> = {
  QUEUED: "排队", PLANNING: "规划", WORKING: "执行", REVIEWING: "审阅", SYNTHESIZING: "合成", FINALIZING: "收尾",
};

const TOOL_LABELS: Record<string, string> = { kb_search: "知识库检索", web_search: "网页搜索", calculator: "计算器" };
export const toolLabel = (tool: unknown) => TOOL_LABELS[String(tool)] ?? String(tool ?? "获准工具");

const ROLE_LABELS: Record<string, string> = {
  SYSTEM: "系统", PLANNER: "规划", WORKER: "执行", REVIEWER: "审阅", SYNTHESIZER: "汇总", AGENT: "研究助手",
};
export const roleLabel = (role: unknown) => ROLE_LABELS[String(role ?? "SYSTEM").toUpperCase()] ?? String(role ?? "系统");

export function describeEvent(event: RunEvent): { title: string; detail: string } {
  const p = (event.payload ?? {}) as Record<string, unknown>;
  switch (event.type) {
    case "QUEUED": return { title: "已进入任务队列", detail: "任务已持久化，正在等待领取。" };
    case "STAGE_CHANGED": return { title: `进入“${STAGE_LABELS[String(p.stage)] ?? String(p.stage)}”阶段`, detail: "工作流阶段已更新。" };
    case "PLAN_COMPLETED": {
      const tools = Array.isArray(p.tools) ? p.tools.map(toolLabel).join("、") : "按需选择";
      return { title: "研究计划已生成", detail: `拆分为 ${p.taskCount ?? "若干"} 个任务；计划使用：${tools}。` };
    }
    case "TASK_STARTED": return { title: `${toolLabel(p.tool)}开始`, detail: "调用范围已通过任务级权限约束。" };
    case "TASK_COMPLETED": return p.errorCode
      ? { title: `${toolLabel(p.tool)}结束`, detail: `结果码：${String(p.errorCode)}。` }
      : { title: `${toolLabel(p.tool)}完成`, detail: `工具回报 ${p.evidenceCount ?? "若干"} 条可用证据。` };
    case "REVIEW_COMPLETED": return { title: "证据审阅完成", detail: p.sufficient === true ? "审阅结论：证据充分。" : `审阅结论：仍需补充；定向补充任务 ${p.revisionTaskCount ?? 0} 项。` };
    case "REVISION_STARTED": return { title: "开始定向补充", detail: `第 ${p.round ?? 1} 轮，共 ${p.taskCount ?? 0} 个任务。` };
    case "SYNTHESIS_COMPLETED": return { title: "答案已合成", detail: `引用 ${p.citationCount ?? 0} 条；发布前仍需服务端校验。` };
    case "SUCCEEDED": return { title: "研究完成", detail: "答案、引用与用量已持久化。" };
    case "INSUFFICIENT_EVIDENCE": return { title: "可信证据不足", detail: "只发布有证据支持的部分，其余保留为待核查事项。" };
    case "FAILED": return { title: "研究失败", detail: p.errorCode ? `结果码：${String(p.errorCode)}。` : "失败信息已按安全规则记录。" };
    case "CANCELLED": return { title: "研究已取消", detail: "服务端已停止后续工具授权。" };
    default: return { title: String(event.type), detail: typeof p.summary === "string" ? p.summary : "记录了一条运行事件。" };
  }
}
