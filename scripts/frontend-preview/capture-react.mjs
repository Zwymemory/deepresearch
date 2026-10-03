// Screenshots and checks for the React preview (frontend/, demo fixtures only).
//
//   npm --prefix frontend run dev &      # http://127.0.0.1:5173
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/node_modules/playwright-core \
//     node scripts/frontend-preview/capture-react.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const BASE = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app";
const OUT = process.argv[2] || "output/playwright/react-preview";
mkdirSync(OUT, { recursive: true });

const SIZES = { desktop: [1440, 900], laptop: [1024, 768], mobile: [390, 844], narrow: [320, 700] };
const STATES = ["entry", "running", "report", "inspect", "compare", "compare-recorded", "partial", "cancelled"];
const report = [];

async function open(browser, theme, size, state, reducedMotion = "no-preference") {
  const [width, height] = SIZES[size];
  const context = await browser.newContext({ viewport: { width, height }, colorScheme: theme === "mist" ? "dark" : "light", reducedMotion,
    deviceScaleFactor: size === "mobile" || size === "narrow" ? 2 : 1, isMobile: width < 760, hasTouch: width < 760 });
  const page = await context.newPage();
  const errors = [];
  page.on("console", (m) => { if (m.type() === "error" || m.type() === "warning") errors.push(m.type() + ": " + m.text()); });
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto(`${BASE}/?state=${state}`);
  await page.waitForSelector("#main");
  await page.waitForTimeout(450);
  return { context, page, errors };
}

async function metrics(page) {
  return page.evaluate(() => {
    const primary = document.querySelector(".composer .btn-primary");
    const box = primary?.getBoundingClientRect();
    return {
      // Mobile Chrome widens the layout viewport to fit oversized content, so measure
      // against the visual viewport (the requested device width), not innerWidth.
      overflowX: Math.max(document.documentElement.scrollWidth, window.innerWidth) - Math.round(window.visualViewport?.width ?? window.innerWidth),
      theme: document.documentElement.dataset.theme,
      primaryBottom: box ? Math.round(box.bottom) : null,
      viewportH: window.innerHeight,
      demoLabel: document.querySelector(".mode-badge")?.textContent?.trim() ?? null,
      // body clips horizontal overflow, so also check that fixed chrome stays inside the viewport.
      controlsInView: [...document.querySelectorAll(".topbar button, .mode-badge, .inspector button, .inspector a")]
        .every((el) => { const r = el.getBoundingClientRect(); return r.left >= 0 && r.right <= (window.visualViewport?.width ?? window.innerWidth) + 0.5; }),
    };
  });
}

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  for (const theme of ["airy", "mist"]) {
    for (const size of Object.keys(SIZES)) {
      for (const state of STATES) {
        const { context, page, errors } = await open(browser, theme, size, state);
        const m = await metrics(page);
        if (size !== "narrow" || state === "entry" || state === "inspect") {
          await page.screenshot({ path: `${OUT}/${theme}-${size}-${state}.png` });
        }
        report.push({ name: `${theme} ${size} ${state}`, ...m, errors });
        await context.close();
      }
    }
  }

  // Live journey: example → running → automatic report; then keyboard citation inspection.
  for (const theme of ["airy", "mist"]) {
    const { context, page, errors } = await open(browser, theme, "desktop", "entry");
    await page.getByRole("button", { name: /完整报告/ }).click();
    await page.waitForSelector(".activity");
    await page.waitForTimeout(3200);
    await page.screenshot({ path: `${OUT}/${theme}-desktop-live-running.png` });
    await page.waitForSelector("#report-question", { timeout: 15000 });
    await page.waitForTimeout(400);
    const cite = page.locator(".cite").nth(2);
    await cite.focus();
    const before = await page.evaluate(() => window.scrollY);
    await page.keyboard.press("Enter");
    await page.waitForSelector("#inspector");
    const opened = await page.evaluate(() => document.activeElement?.id);
    await page.keyboard.press("Escape");
    await page.waitForTimeout(350);
    const after = await page.evaluate(() => ({ focus: document.activeElement?.className, y: window.scrollY, inspector: !!document.querySelector("#inspector") }));
    // Comparison round trip restores the reading position and reopens the source.
    const deep = page.locator(".cite").last();
    await deep.evaluate((n) => n.scrollIntoView({ block: "center" }));
    await page.waitForTimeout(200);
    const readingY = await page.evaluate(() => window.scrollY);
    await deep.click();
    await page.getByRole("button", { name: "与另一来源比较" }).click();
    await page.locator(".picker button").first().click();
    await page.waitForSelector(".compare");
    await page.getByRole("button", { name: "返回报告" }).click();
    await page.waitForSelector("#inspector");
    await page.waitForTimeout(300);
    const back = await page.evaluate((readingY) => ({ view: !!document.querySelector("#report"), readingY, restoredY: Math.round(window.scrollY),
      inspector: document.querySelector("#inspector-title")?.textContent?.slice(0, 20) }), readingY);
    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);
    back.focusAfterClose = await page.evaluate(() => document.activeElement?.textContent);
    report.push({ name: `${theme} live journey`, focusOnOpen: opened, scrollBefore: before, afterClose: after, compareReturn: back, errors });
    await context.close();
  }

  // Cancel from the running state is a real state transition, not a closed stream.
  {
    const { context, page, errors } = await open(browser, "airy", "desktop", "entry");
    await page.getByRole("button", { name: /完整报告/ }).click();
    await page.waitForTimeout(1500);
    await page.getByRole("button", { name: "取消研究" }).click();
    await page.waitForSelector(".status-tag");
    report.push({ name: "cancel", status: await page.locator(".status-tag").textContent(), errors });
    await context.close();
  }

  // Reduced motion: transitions collapse; theme choice persists and is shared with V1's key.
  {
    const { context, page, errors } = await open(browser, "airy", "desktop", "report", "reduce");
    const btn = await page.evaluate(() => getComputedStyle(document.querySelector(".btn")).transitionDuration);
    await page.getByRole("button", { name: /切换到雾夜主题/ }).click();
    await page.waitForTimeout(150);
    const saved = await page.evaluate(() => localStorage.getItem("deepresearch.console.theme"));
    await page.reload();
    await page.waitForSelector("#main");
    report.push({ name: "reduced-motion + theme persistence", btnTransition: btn, saved, afterReload: await page.evaluate(() => document.documentElement.dataset.theme), errors });
    await context.close();
  }
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 2));
const problems = report.filter((r) => (r.errors && r.errors.some((e) => !e.includes("Reduced Motion"))) || r.overflowX > 0 || r.controlsInView === false);
console.log(JSON.stringify({ checks: report.length, problems }, null, 2));
console.log(JSON.stringify(report.filter((r) => !("overflowX" in r)), null, 2));
console.log("entry primary action:", report.filter((r) => r.name.includes("entry") && r.primaryBottom).map((r) => `${r.name}: bottom ${r.primaryBottom}/${r.viewportH}`).join("; "));
