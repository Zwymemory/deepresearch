import type { RecallView } from "../../domain/memoryRecall";

/** One Chinese sentence per recall status. SELECTED never claims use; USED requires the planner record. */
export function recallHeadline(v: RecallView): string {
  const n = v.records.length;
  switch (v.status) {
    case "DISABLED": return "本次运行关闭了历史研究参考。";
    case "EMPTY": return "没有找到与本问题相关的历史研究，本次未参考任何旧记录。";
    case "SELECTED": return `已选出 ${n} 条相关历史研究；尚未确认它们已进入规划请求。`;
    case "USED": return v.plannerInputRecorded
      ? `已有 ${n} 条相关历史研究进入本次规划请求，仅作需复查的线索，不是已核验的证据。`
      : `已选出 ${n} 条相关历史研究；尚未确认它们已进入规划请求。`;
    case "UNAVAILABLE": return "引用的历史研究已被修改、删除或不可访问，页面不再显示其内容；如需参考，请重新开始研究。";
    default: return "无法确认本次运行的历史研究参考状态。";
  }
}

/** Familiar Chinese for known applicability caution codes; raw codes stay in technical details. */
const CAUTIONS: Record<string, string> = {
  REVERIFY_BEFORE_USE: "使用前需要重新核查",
  TIME_UNKNOWN: "记录的有效时间未知",
  CONDITIONS_UNKNOWN: "适用条件未知",
  VERSION_DIFFERENCE: "旧研究涉及的版本与本次问题不同",
  VERSION_APPLICABILITY_UNKNOWN: "不确定旧结论是否适用于当前版本",
  DISPUTED_OR_UNVERIFIED: "其中有争议或未核实的内容，不能当作结论",
};
/** Known code → Chinese; anything else → an honest generic note (the raw value is in technical details). */
export const cautionText = (code: string) => CAUTIONS[code] ?? "有一条未识别的适用性提示，需要重新核查";
