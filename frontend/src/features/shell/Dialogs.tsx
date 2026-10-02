import * as Dialog from "@radix-ui/react-dialog";
import type { ReactNode } from "react";
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
export function IdentityDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="连接与身份" description="示例数据模式下，页面不连接任何服务。">
      <div className="grid gap-3 text-[14px]" style={{ color: "var(--ink-2)" }}>
        <p>真实连接（API Base、Tenant / User、Bearer Token、本地 Dev Token 签发）将在第 2 阶段迁移，并保持现有规则：</p>
        <ul className="grid gap-1 pl-5" style={{ listStyle: "disc" }}>
          <li>只允许同源 API；Token 仅保存在内存或用户选择的当前浏览会话中，不写入 URL 或长期存储。</li>
          <li>身份变化时清除旧缓存并断开旧事件流。</li>
        </ul>
        <p className="note">现有可用版本仍是 V1 页面 <code>/demo.html</code>。</p>
      </div>
    </Sheet>
  );
}

export interface RecentItem { runId: string; question: string; status: string; at: number }

export function RecentDialog({ open, onOpenChange, items, onOpen }: {
  open: boolean; onOpenChange: (open: boolean) => void; items: RecentItem[]; onOpen: (item: RecentItem) => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="最近的研究" description="仅本标签页中的示例运行；不会读取 V1 页面或服务端的记录。">
      {items.length === 0 ? <p className="note" style={{ padding: "18px 0" }}>还没有运行过示例研究。</p> : (
        <div className="grid gap-2">
          {items.map((item) => (
            <button key={item.runId} type="button" className="example" onClick={() => onOpen(item)}>
              <strong style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{item.question}</strong>
              <span>{item.status} · {new Date(item.at).toLocaleTimeString()} · 示例数据</span>
            </button>
          ))}
        </div>
      )}
    </Sheet>
  );
}
