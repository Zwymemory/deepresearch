import { isMemoryCode, memoryErrorInfo, type MemoryRequest } from "../../domain/researchMemory";
import { Icon } from "../../ui/Icon";

/**
 * Shown on runs created with an explicit project selection. It states what the page knows:
 * the request named a project and the server accepted the run. It cannot observe whether the
 * history reached a model or influenced planning, so it never says so.
 */
export function MemoryNote({ request, runId, errorCode, onReselect }: {
  request: MemoryRequest; runId: string; errorCode: string | null; onReselect: () => void;
}) {
  const rejected = isMemoryCode(errorCode) ? memoryErrorInfo(errorCode) : null;
  return (
    <section className={"memory-note" + (rejected ? " is-rejected" : "")} aria-label="项目历史进度">
      {rejected ? (
        <>
          <p><strong><Icon name="alert" size={15} />{rejected.label}</strong>：这次运行停止使用该项目的历史进度。{rejected.text}</p>
          <p className="note">已经发出的请求可能无法撤回；页面不会自动重新开始，也不会改为不带历史继续。</p>
          {rejected.reselect ? <button type="button" className="btn btn-quiet btn-sm" style={{ justifySelf: "start" }} onClick={onReselect}>回到研究档案重新选择</button> : null}
        </>
      ) : (
        <p><strong>已选择项目历史进度作为规划参考。</strong>服务端已接受这次运行；页面无法确认这些历史是否已进入模型的规划输入，也不代表模型理解或采纳了它们。历史是不可信上下文，不是本次的证据。</p>
      )}
      <details className="note">
        <summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary>
        <dl className="facts" style={{ marginTop: 8 }}>
          <dt>researchProjectId</dt><dd className="source-id">{request.projectId}</dd>
          <dt>sessionId</dt><dd className="source-id">{request.sessionId}</dd>
          <dt>runId</dt><dd className="source-id">{runId}</dd>
          {errorCode ? <><dt>errorCode</dt><dd className="source-id">{errorCode}</dd></> : null}
        </dl>
      </details>
    </section>
  );
}
