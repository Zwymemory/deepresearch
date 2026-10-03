import * as Dialog from "@radix-ui/react-dialog";
import { useState, type ReactNode } from "react";
import type { Identity } from "../../api/identity";
import { Icon } from "../../ui/Icon";

export function Sheet({ open, onOpenChange, title, description, children }: {
  open: boolean; onOpenChange: (open: boolean) => void; title: string; description?: string; children: ReactNode;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog" {...(description ? {} : { "aria-describedby": undefined })}>
          <div className="flex items-start justify-between gap-4">
            <div>
              <Dialog.Title>{title}</Dialog.Title>
              {description ? <Dialog.Description className="note" style={{ marginTop: 4 }}>{description}</Dialog.Description> : null}
            </div>
            <Dialog.Close className="icon-btn" aria-label="关闭"><Icon name="x" /></Dialog.Close>
          </div>
          <div style={{ marginTop: 16 }}>{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Demo mode never asks for, stores or sends credentials. */
export function IdentityDialog({ open, onOpenChange, mode, identity, onApply, onDevToken }: {
  open: boolean; onOpenChange: (open: boolean) => void; mode: "live" | "demo"; identity: Identity;
  onApply: (next: Identity) => string | null;
  onDevToken: (tenantId: string, userId: string) => Promise<{ token: string; error: string | null }>;
}) {
  const [draft, setDraft] = useState<Identity>(identity);
  const [showToken, setShowToken] = useState(false);
  const [message, setMessage] = useState<{ tone: "error" | "ok"; text: string } | null>(null);
  const [issuing, setIssuing] = useState(false);
  // Re-seed the form each time the dialog opens.
  const [seededFor, setSeededFor] = useState(false);
  if (open && !seededFor) { setSeededFor(true); setDraft(identity); setMessage(null); setShowToken(false); }
  if (!open && seededFor) setSeededFor(false);

  if (mode === "demo") {
    return (
      <Sheet open={open} onOpenChange={onOpenChange} title="连接与身份" description="示例数据模式下，页面不连接任何服务，也不会使用已保存的凭据。">
        <p className="note">退出示例模式后，可以在这里连接同源 Java API。</p>
      </Sheet>
    );
  }
  const set = (patch: Partial<Identity>) => setDraft((d) => ({ ...d, ...patch }));
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="连接与身份" description="Token 只保存在内存，或按你的选择保存在当前浏览会话中；不会写入 URL 或长期存储。">
      <form className="grid gap-3" onSubmit={(e) => { e.preventDefault(); const error = onApply(draft); if (error) setMessage({ tone: "error", text: error }); else onOpenChange(false); }}>
        <label className="field-row"><span>API Base</span>
          <input className="text-input" value={draft.baseUrl} onChange={(e) => set({ baseUrl: e.target.value })} placeholder="同源时留空" autoComplete="url" />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="field-row"><span>Tenant</span><input className="text-input" value={draft.tenantId} onChange={(e) => set({ tenantId: e.target.value })} autoComplete="off" /></label>
          <label className="field-row"><span>User</span><input className="text-input" value={draft.userId} onChange={(e) => set({ userId: e.target.value })} autoComplete="off" /></label>
        </div>
        <label className="field-row"><span>Bearer Token</span>
          <span className="flex gap-2">
            <input className="text-input" type={showToken ? "text" : "password"} value={draft.token} autoComplete="off" spellCheck={false}
              onChange={(e) => set({ token: e.target.value })} placeholder="粘贴可信 JWT，或在本地签发" />
            <button type="button" className="btn btn-quiet btn-sm" onClick={() => setShowToken((v) => !v)} aria-pressed={showToken}>{showToken ? "隐藏" : "显示"}</button>
          </span>
        </label>
        <label className="flex items-start gap-2 note">
          <input type="checkbox" checked={draft.remember} onChange={(e) => set({ remember: e.target.checked })} style={{ marginTop: 4 }} />
          <span>仅在当前浏览会话保存 Token（关闭标签页后清除）。</span>
        </label>
        <div className="dev-box">
          <strong>本地开发 Token</strong>
          <p className="note">服务端默认关闭匿名签发，需要显式开启 <code>DEEPRESEARCH_DEV_TOKEN_ENABLED=true</code>；这里只请求 USER 角色。</p>
          <button type="button" className="btn btn-quiet btn-sm" disabled={issuing} onClick={async () => {
            setIssuing(true);
            const result = await onDevToken(draft.tenantId || "demo-tenant", draft.userId || "demo-user");
            setIssuing(false);
            if (result.error) setMessage({ tone: "error", text: result.error });
            else { set({ token: result.token }); setMessage({ tone: "ok", text: "Dev Token 已签发，保存后生效。" }); }
          }}>{issuing ? "正在签发…" : "签发 USER Dev Token"}</button>
        </div>
        {message ? <p role="alert" className={message.tone === "error" ? "missing" : "note"}>{message.text}</p> : null}
        <p className="note">切换身份会清除页面中已缓存的运行内容并断开事件流。旧版页面仍可在 <a href="/demo.html">/demo.html</a> 使用（由同一服务提供时）。</p>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className="btn btn-quiet" onClick={() => set({ token: "" })}>清除 Token</button>
          <button type="submit" className="btn btn-primary">保存并连接</button>
        </div>
      </form>
    </Sheet>
  );
}

export interface RecentItem { runId: string; question: string; status: string; at: number }

export function RecentDialog({ open, onOpenChange, items, onOpen, demo }: {
  open: boolean; onOpenChange: (open: boolean) => void; items: RecentItem[]; onOpen: (item: RecentItem) => void; demo: boolean;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="最近的研究"
      description={demo ? "仅本标签页中的示例运行。" : "仅记录在本浏览器（与旧版页面共用），最多 5 条；打开时仍需拥有该运行权限的身份。"}>
      {items.length === 0 ? <p className="note" style={{ padding: "18px 0" }}>{demo ? "还没有运行过示例研究。" : "本浏览器还没有已知的运行。"}</p> : (
        <div className="grid gap-2">
          {items.map((item) => (
            <button key={item.runId} type="button" className="example" onClick={() => onOpen(item)}>
              <strong style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{item.question}</strong>
              <span>{item.status} · {new Date(item.at).toLocaleString()}{demo ? " · 示例数据" : ""}</span>
            </button>
          ))}
        </div>
      )}
    </Sheet>
  );
}
