import { useCallback, useEffect, useState } from "react";

export type Theme = "airy" | "mist";
// Same key and values as the V1 page, so an explicit choice carries over.
const STORE = "deepresearch.console.theme";

function systemTheme(): Theme {
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "mist" : "airy";
}

function savedTheme(): Theme | null {
  try {
    const value = localStorage.getItem(STORE);
    return value === "light" ? "airy" : value === "dark" ? "mist" : null;
  } catch {
    return null;
  }
}

/** Follows the system until the user chooses explicitly; the choice is then remembered. */
export function useTheme() {
  const [theme, setThemeState] = useState<Theme>(() => (document.documentElement.dataset.theme as Theme) || savedTheme() || systemTheme());
  const [explicit, setExplicit] = useState(() => savedTheme() !== null);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", theme === "mist" ? "#1b2030" : "#f8f3e8");
  }, [theme]);

  useEffect(() => {
    if (explicit) return;
    const query = window.matchMedia?.("(prefers-color-scheme: dark)");
    const follow = (event: MediaQueryListEvent) => setThemeState(event.matches ? "mist" : "airy");
    query?.addEventListener("change", follow);
    return () => query?.removeEventListener("change", follow);
  }, [explicit]);

  const toggle = useCallback((origin?: HTMLElement | null) => {
    const next: Theme = theme === "airy" ? "mist" : "airy";
    try { localStorage.setItem(STORE, next === "mist" ? "dark" : "light"); } catch { /* storage unavailable: keep the in-memory choice */ }
    setExplicit(true);
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    const root = document.documentElement;
    const apply = () => { root.dataset.theme = next; setThemeState(next); };
    const doc = document as Document & { startViewTransition?: (cb: () => void) => { ready: Promise<void> } };
    if (doc.startViewTransition && !reduce) {
      const rect = origin?.getBoundingClientRect();
      const x = rect ? rect.left + rect.width / 2 : innerWidth / 2;
      const y = rect ? rect.top + rect.height / 2 : 0;
      const r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
      doc.startViewTransition(apply).ready.then(() => {
        root.animate({ clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
          { duration: 420, easing: "cubic-bezier(.2,.8,.2,1)", pseudoElement: "::view-transition-new(root)" });
      }).catch(() => {});
    } else {
      root.classList.add("theme-fading");
      apply();
      window.setTimeout(() => root.classList.remove("theme-fading"), 360);
    }
  }, [theme]);

  return { theme, explicit, toggle };
}
