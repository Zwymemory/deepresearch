const PATHS: Record<string, string> = {
  search: "M11 4.5a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13ZM16 16l4.5 4.5",
  book: "M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5v-15ZM4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5M9 8h7",
  globe: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM3 12h18M12 3c2.6 2.8 3.9 5.8 3.9 9s-1.3 6.2-3.9 9c-2.6-2.8-3.9-5.8-3.9-9S9.4 5.8 12 3Z",
  mix: "M4 7h9M4 17h5M15 7l5 5-5 5M9 17l5-5",
  sigma: "M18 5H6l6.5 7L6 19h12",
  clock: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM12 7v5l3 2",
  key: "M8 11a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM11 12l8-8M16 7l3 3",
  x: "M7 7l10 10M17 7 7 17",
  arrowLeft: "M19 12H5M11 6l-6 6 6 6",
  external: "M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5",
  compare: "M8 4v16M16 4v16M4 8h4M16 16h4",
  flag: "M5 21V4M5 4h11l-2 4 2 4H5",
  check: "M5 12.5 9.5 17 19 7.5",
  stop: "M7 7h10v10H7z",
  list: "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01",
  spark: "M12 3c.5 4.2 2.8 6.5 7 7-4.2.5-6.5 2.8-7 7-.5-4.2-2.8-6.5-7-7 4.2-.5 6.5-2.8 7-7Z",
  alert: "M12 8v5M12 16.5v.5M10.3 3.9 2.6 17.5A2 2 0 0 0 4.3 20.5h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z",
  pause: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM10 9v6M14 9v6",
  chevronDown: "m6 9 6 6 6-6",
  archive: "M4 8.5 12 4l8 4.5-8 4.5-8-4.5ZM4 12.5l8 4.5 8-4.5M4 16.5l8 4.5 8-4.5",
  arrowUpRight: "M7 17 17 7M9 7h8v8",
  half: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM12 3v18",
};

export type IconName = keyof typeof PATHS;

export function Icon({ name, size = 18, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg className={"i" + (className ? " " + className : "")} viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" focusable="false">
      <path d={PATHS[name]} />
    </svg>
  );
}
