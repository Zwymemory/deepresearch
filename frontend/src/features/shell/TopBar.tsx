import { useEffect, useRef, useState } from "react";
import type { Theme } from "../../app/theme";
import { Icon } from "../../ui/Icon";

interface Props {
  theme: Theme;
  onToggleTheme: (origin: HTMLElement | null) => void;
  onHome: () => void;
  onOpenRecent: () => void;
  onOpenIdentity: () => void;
}

export function TopBar({ theme, onToggleTheme, onHome, onOpenRecent, onOpenIdentity }: Props) {
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
        {/* The connection label always reflects the actual operating mode. */}
        <span className="mode-badge" role="status" title="当前为示例数据模式：页面不连接 Java API，也不会发送凭据或发起付费调用">
          <span className="dot" aria-hidden="true" />示例数据<span className="text-long">&nbsp;· 未连接后端</span>
        </span>
        <button type="button" className="icon-btn" onClick={onOpenRecent} aria-label="最近的研究"><Icon name="clock" /></button>
        <button ref={themeButton} type="button" className="icon-btn" aria-pressed={mist}
          aria-label={mist ? "当前为雾夜主题，切换到晴空主题" : "当前为晴空主题，切换到雾夜主题"}
          onClick={() => onToggleTheme(themeButton.current)}>
          <span className="theme-orb" aria-hidden="true" />
        </button>
        <button type="button" className="icon-btn" onClick={onOpenIdentity} aria-label="连接与身份"><Icon name="key" /></button>
      </div>
    </header>
  );
}
