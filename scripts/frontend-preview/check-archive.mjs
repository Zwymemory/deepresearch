// Browser check of the RhineLab-inspired Stage 1 slice: entrance → select a record → open → return.
// Demo sessions use labelled synthetic fixtures and make no requests; the live session talks to the
// synthetic preview mock through the Vite proxy. MOCK VERIFICATION ONLY — not real-backend acceptance.
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/check-archive.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/archive";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); if (!ok) console.error("FAIL", name, detail); };

async function session(browser, theme, { width, height }, reducedMotion = "no-preference") {
  const mobile = width < 768;
  const context = await browser.newContext({ viewport: { width, height }, colorScheme: theme === "smoked" ? "dark" : "light", reducedMotion,
    isMobile: mobile, hasTouch: mobile, deviceScaleFactor: mobile ? 2 : 1 });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  page.on("request", (r) => { if (new URL(r.url()).pathname.startsWith("/api/")) requests.push({ method: r.method(), path: new URL(r.url()).pathname }); });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(m.text()); });
  return { context, page, requests, errors };
}
const overflow = (page) => page.evaluate(() => Math.max(document.documentElement.scrollWidth, innerWidth) - Math.round(visualViewport.width));
const shot = (page, name) => page.screenshot({ path: `${OUT}/${name}.png` });
const active = (page) => page.evaluate(() => {
  const el = document.activeElement;
  return { cls: el?.className ?? "", id: el?.id ?? "", selected: el?.getAttribute("aria-selected"), text: (el?.textContent ?? "").slice(0, 40) };
});
const openArchive = (page) => page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();

const VIEWPORTS = [
  ["ivory", { width: 1440, height: 900 }], ["smoked", { width: 1440, height: 900 }],
  ["ivory", { width: 1024, height: 768 }], ["smoked", { width: 1024, height: 768 }],
  ["ivory", { width: 390, height: 844 }], ["smoked", { width: 390, height: 844 }],
  ["ivory", { width: 320, height: 640 }], ["smoked", { width: 320, height: 640 }],
];

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  for (const [theme, vp] of VIEWPORTS) {
    const tag = `${theme}-${vp.width}`;
    const { context, page, requests, errors } = await session(browser, theme, vp);

    // 1. Entrance: the question is usable at once (no boot sequence), with separate labelled paths.
    await page.goto(APP + "?demo", { waitUntil: "domcontentloaded" });
    await page.locator("#question").fill("合成问题：入口立即可用。");
    check(`${tag}: question accepts input immediately`, await page.locator("#question").inputValue() === "合成问题：入口立即可用。");
    check(`${tag}: theme applied`, await page.evaluate(() => document.documentElement.dataset.theme) === theme);
    check(`${tag}: separate archive and local-run paths`, await page.locator(".path-card", { hasText: "研究档案" }).count() === 1
      && await page.locator(".path-card", { hasText: "最近运行（本机）" }).count() === 1);
    check(`${tag}: new user sees an intentional empty local-run state`, await page.getByText("本机还没有运行记录").count() === 1);
    if (vp.width === 1440 || vp.width === 390) await shot(page, `${tag}-entrance`);

    // 2. Archive: labelled preview, list and stage in sync, empty slots drawn as empty.
    await page.locator(".path-card", { hasText: "研究档案" }).click();
    await page.locator(".archive-item").first().waitFor();
    const items = await page.locator(".archive-item").count();
    check(`${tag}: preview is labelled`, await page.getByText("预览 · 示例数据，不联网").count() === 1);
    check(`${tag}: stage mirrors the list (records + empty slots = 20)`, await page.locator(".folio:not(.folio-empty)").count() === items
      && await page.locator(".folio-empty").count() === 20 - items, `${items} items`);
    check(`${tag}: stage is labelled as a CSS prototype`, await page.getByText(/CSS 透视原型（Three.js 场景尚在计划中）/).count() === 1);
    check(`${tag}: no network in demo`, requests.length === 0, JSON.stringify(requests));

    // 3. Keyboard: select without dragging; selection mirrored on the stage and in the panel.
    await page.locator(".archive-item").first().focus();
    await page.keyboard.press("ArrowDown");
    const second = page.locator(".archive-item").nth(1);
    check(`${tag}: arrow key moves selection and focus`, await second.getAttribute("aria-selected") === "true" && (await active(page)).selected === "true");
    check(`${tag}: stage lifts the selected folio`, await page.locator('.folio[data-selected="true"] .folio-n').textContent() === "02");
    check(`${tag}: selection panel follows`, (await page.locator(".archive-hud .hud-index").textContent())?.includes("02"));
    await page.waitForTimeout(350);
    if (vp.width !== 1024) await shot(page, `${tag}-archive-selected`);

    // 4. Open: state changes at once (animation never gates it); title receives focus.
    const scrollBefore = await page.evaluate(() => scrollY);
    await page.keyboard.press("Enter");
    check(`${tag}: open is immediate`, await page.locator("#archive-record").count() === 1 && (await active(page)).id === "rec-title");
    check(`${tag}: background is inert while reading`, await page.locator(".archive-browse").evaluate((el) => el.inert === true));
    check(`${tag}: record shows the selected snapshot`, (await page.locator("#rec-title").textContent())?.includes("BM25"));
    await page.waitForTimeout(700);
    check(`${tag}: reading text is report-sized (16–18px)`, await page.locator(".rec-body").evaluate((el) => { const s = parseFloat(getComputedStyle(el).fontSize); return s >= 16 && s <= 18; }));
    check(`${tag}: no horizontal overflow while reading`, await overflow(page) <= 0, await overflow(page));
    if (vp.width !== 1024) await shot(page, `${tag}-record`);

    // 5. Return: Escape restores selection, focus and scroll; the layer leaves after its motion.
    await page.keyboard.press("Escape");
    await page.waitForTimeout(60);
    const back = await active(page);
    check(`${tag}: Escape returns focus to the selected record`, back.cls.includes("archive-item") && back.selected === "true" && back.text.includes("BM25"), JSON.stringify(back));
    check(`${tag}: background usable at once`, await page.locator(".archive-browse").evaluate((el) => el.inert === false));
    await page.locator("#archive-record").waitFor({ state: "detached", timeout: 2000 });
    check(`${tag}: scroll restored`, Math.abs(await page.evaluate(() => scrollY) - scrollBefore) < 4);

    // 6. Rapid input retargets: open/close/open in quick succession settles on one open layer.
    await page.keyboard.press("Enter");
    await page.keyboard.press("Escape");
    await page.waitForTimeout(30);
    await page.keyboard.press("Enter");
    await page.waitForTimeout(900);
    check(`${tag}: rapid open/close/open settles on one layer`, await page.locator("#archive-record").count() === 1);
    await page.getByRole("button", { name: "返回档案" }).click();
    await page.locator("#archive-record").waitFor({ state: "detached", timeout: 2000 });

    // 7. Search and status filter work on contract fields; non-matching folios dim in place.
    await page.locator("#archive-search").fill("BM25");
    check(`${tag}: search narrows the list`, await page.locator(".archive-item").count() === 1);
    check(`${tag}: stage keeps positions and dims non-matches`, await page.locator('.folio[data-match="false"]').count() === items - 1);
    await page.locator("#archive-search").fill("");
    await page.locator(".filter-tab", { hasText: "已取消" }).click();
    check(`${tag}: run-status filter`, await page.locator(".archive-item").count() === 1);
    await page.locator(".filter-tab", { hasText: "全部" }).click();

    // 8. Pointer path on the stage (desktop): click selects, clicking the selected folio opens.
    if (vp.width >= 1024) {
      const folio = page.locator(".folio:not(.folio-empty)").nth(2);
      await folio.click();
      check(`${tag}: clicking a folio selects it`, await folio.getAttribute("data-selected") === "true");
      await folio.click();
      await page.locator("#archive-record").waitFor();
      check(`${tag}: clicking the selected folio opens it`, (await page.locator("#rec-title").textContent())?.includes("引用"));
      await page.keyboard.press("Escape");
      await page.locator("#archive-record").waitFor({ state: "detached", timeout: 2000 });
    }

    check(`${tag}: no horizontal overflow`, await overflow(page) <= 0, await overflow(page));
    check(`${tag}: no console errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }

  // 9. Reduced motion: no travel — the cover is not transformed, only a short fade.
  {
    const { context, page } = await session(browser, "ivory", { width: 1440, height: 900 }, "reduce");
    await page.goto(APP + "?demo&state=archive");
    await page.locator(".archive-item").first().focus();
    await page.keyboard.press("Enter");
    const travel = await page.locator(".rec-cover").evaluate((el) => el.getAnimations().length);
    check("reduced motion: the cover does not travel", travel === 0, travel);
    check("reduced motion: selected folio has no lift transition", await page.locator('.folio[data-selected="true"]').evaluate((el) => !getComputedStyle(el).transitionProperty.includes("--lift")));
    await page.keyboard.press("Escape");
    await page.waitForTimeout(60);
    check("reduced motion: Escape still returns focus", (await active(page)).cls.includes("archive-item"));
    await context.close();
  }

  // 10. Review deep links open directly.
  {
    const { context, page } = await session(browser, "smoked", { width: 1440, height: 900 });
    await page.goto(APP + "?state=record");
    await page.locator("#archive-record").waitFor();
    check("deep link ?state=record opens the first record", (await page.locator("#rec-title").textContent())?.includes("崩溃恢复"));
    await context.close();
  }

  // 11. Live adapter against the MOCK: identity required; list fetched only when the archive opens;
  // an empty list never claims there are no older records.
  {
    const { context, page, requests, errors } = await session(browser, "ivory", { width: 1440, height: 900 });
    await page.goto(APP + "?scenario=success");
    await openArchive(page);
    await page.locator(".archive-title").waitFor();
    check("live (mock): no identity → explains and offers to connect", await page.getByText("需要先连接身份才能读取研究档案。").count() === 1);
    check("live (mock): nothing requested without identity", !requests.some((r) => r.path === "/api/research/progress"));
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await page.getByRole("button", { name: /连接与身份/ }).click();
    await page.getByRole("textbox", { name: "User", exact: true }).fill("archive-check");
    await page.getByLabel("Bearer Token").fill(`local-preview-archive-${Date.now().toString(36)}`);
    await page.getByRole("button", { name: "保存并连接" }).click();
    await page.waitForTimeout(300);
    check("live (mock): list not fetched on the entrance", !requests.some((r) => r.path === "/api/research/progress"));
    await openArchive(page);
    await page.getByText(/这不代表没有更早的记录/).waitFor({ timeout: 10000 });
    check("live (mock): list fetched when the archive opens", requests.filter((r) => r.path === "/api/research/progress").length === 1);
    check("live (mock): server data, not the preview", await page.getByText("预览 · 示例数据，不联网").count() === 0 && await page.getByText("服务端保存的研究进度").count() >= 1);
    check("live (mock): empty archive draws only empty slots", await page.locator(".folio:not(.folio-empty)").count() === 0 && await page.locator(".folio-empty").count() > 0);
    await shot(page, "ivory-1440-live-empty");
    check("live (mock): no console errors", errors.length === 0, errors.join(" | "));
    await context.close();
  }
} finally {
  await browser.close();
}
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed`);
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
if (passed !== results.length) process.exit(1);
