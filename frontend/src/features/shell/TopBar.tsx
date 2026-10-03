import { useEffect, useRef, useState } from "react";
import type { Theme } from "../../app/theme";
import type { Connection } from "../../live/useLiveResearch";
import { Icon } from "../../ui/Icon";

export type AppMode = "live" | "demo";

interface Props {
  theme: Theme;
  mode: AppMode;
  connection: Connection;
  identityLabel: string | null;
  onToggleTheme: (origin: HTMLElement | null) => void;
  onHome: () => void;
  onOpenRecent: () => void;
  onOpenIdentity: () => void;
  onExitDemo: () => void;
}

/** The connection label always states the actual operating mode — never "online" for fixtures or the mock. */
function ConnectionBadge({ mode, connection }: { mode: AppMode; connection: Connection }) {
  if (mode === "demo") {
    return <span className="mode-badge" data-tone="demo" role="status" title="示例数据模式：不连接任何服务，不发送凭据，不发起付费调用">
      <span className="dot" aria-hidden="true" />示例数据<span className="text-long">&nbsp;· 未连接后端</span></span>;
  }
  const [tone, label, title] =
    connection.state === "checking" ? ["neutral", "正在检查服务", "正在检查同源 API"] :
    connection.state === "offline" ? ["error", "API 未连接", "同源 /api/ping 无响应"] :
    connection.kind === "preview" ? ["demo", "预览服务器 · 模拟 API", "当前连接的是本地预览服务器，返回的是合成数据"] :
    ["ok", "Java API 在线", "同源 Java 服务的 /api/ping 已响应"];
  return <span className="mode-badge" data-tone={tone} role="status" title={title}><span className="dot" aria-hidden="true" />{label}</span>;
}

export function TopBar({ theme, mode, connection, identityLabel, onToggleTheme, onHome, onOpenRecent, onOpenIdentity, onExitDemo }: Props) {
  const [scrolled, setScrolled] = useState(false);
  const themeButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 6);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);
  const mist = theme === "mist";
  return (
    <header className="topbar" data-scrolled={scrolled}>
      <button type="button" className="brand" onClick={onHome} aria-label="DeepResearch：返回新研究">
        <span className="brand-mark" aria-hidden="true"><Icon name="search" size={18} /></span>
        <span className="brand-name">DeepResearch</span>
      </button>
      <div className="top-actions">
        <ConnectionBadge mode={mode} connection={connection} />
        {mode === "demo" ? (
          <button type="button" className="btn btn-quiet btn-sm top-label" onClick={onExitDemo}>退出示例</button>
        ) : null}
        <button type="button" className="icon-btn" onClick={onOpenRecent} aria-label="最近的研究"><Icon name="clock" /></button>
        <button ref={themeButton} type="button" className="icon-btn" aria-pressed={mist}
          aria-label={mist ? "当前为雾夜主题，切换到晴空主题" : "当前为晴空主题，切换到雾夜主题"}
          onClick={() => onToggleTheme(themeButton.current)}>
          <span className="theme-orb" aria-hidden="true" />
        </button>
        <button type="button" className={"identity-btn" + (mode === "live" && !identityLabel ? " unauth" : "")} onClick={onOpenIdentity}
          aria-label={"连接与身份：" + (mode === "demo" ? "示例模式" : identityLabel ?? "未认证")}>
          <Icon name="key" />
          <span className="top-label">{mode === "demo" ? "示例" : identityLabel ?? "未认证"}</span>
        </button>
      </div>
    </header>
  );
}
