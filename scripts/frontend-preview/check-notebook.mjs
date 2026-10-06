// Focused browser check of the research-notebook integration (research progress memory).
// MOCK VERIFICATION ONLY: requests go to the synthetic preview mock through the Vite proxy.
// This is not real-backend acceptance.
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/check-notebook.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/notebook";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); if (!ok) console.error("FAIL", name, detail); };
const SIZES = { desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } };

async function session(browser, theme, size, reducedMotion = "no-preference") {
  const context = await browser.newContext({ viewport: SIZES[size], colorScheme: theme === "smoked" ? "dark" : "light", reducedMotion,
    isMobile: size === "mobile", hasTouch: size === "mobile", deviceScaleFactor: size === "mobile" ? 2 : 1 });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  // Only real API calls: in dev, Vite also serves source modules such as /app/src/api/*.ts.
  page.on("request", (r) => { if (new URL(r.url()).pathname.startsWith("/api/")) requests.push({ method: r.method(), path: new URL(r.url()).pathname, search: new URL(r.url()).search }); });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(m.text()); });
  return { context, page, requests, errors };
}
const memory = (requests) => requests.filter((r) => /progress|resume-context/.test(r.path));
const overflow = (page) => page.evaluate(() => Math.max(document.documentElement.scrollWidth, innerWidth) - Math.round(visualViewport.width));
const shot = (page, name) => page.screenshot({ path: `${OUT}/${name}.png` });

// Each session uses its own local test token: the mock accepts any bearer and scopes records by it,
// so sessions never see each other's saved progress.
let tokenSeq = 0;
const testToken = () => `local-preview-notebook-${Date.now().toString(36)}-${++tokenSeq}`;
async function connect(page, user = "demo-user", token = testToken()) {
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "User", exact: true }).fill(user);
  await page.getByLabel("Bearer Token").fill(token);
  const remember = page.getByRole("checkbox");
  if (!(await remember.isChecked())) await remember.check();
  await page.getByRole("button", { name: "保存并连接" }).click();
}
async function ask(page, question, mode) {
  await page.locator("#question").fill(question);
  if (mode) await page.getByLabel("执行方式").selectOption(mode);
  await page.getByRole("button", { name: "开始研究", exact: true }).click();
  await page.locator("#report-question").waitFor({ timeout: 30000 });
  await page.waitForTimeout(400);
}
const home = (page) => page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
const openNotebook = (page) => page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
// Archive list: a click selects; clicking the selected record (or Enter) opens its reading layer.
async function openRecord(page, item) {
  await item.click();
  if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
  await page.locator("#archive-record").waitFor();
}

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  for (const [theme, size] of [["ivory", "desktop"], ["smoked", "mobile"]]) {
    const tag = `${theme}-${size}`;
    const { context, page, requests, errors } = await session(browser, theme, size);
    await page.goto(APP + "?scenario=success");
    await connect(page);

    // 1. Unsupported mode: workflow results cannot be saved, with a reason; no project is created client-side.
    await ask(page, "合成问题：工作流结果。");
    check(`${tag}: workflow run save is unavailable with a reason`, await page.getByRole("button", { name: "保存研究进度" }).isDisabled()
      && await page.getByText("只有自主研究运行支持保存研究进度").count() === 1);
    check(`${tag}: no progress request for a workflow run`, memory(requests).length === 0, JSON.stringify(memory(requests)));

    // 2. Autonomous run: discovery → explicit save → server-confirmed success.
    await home(page);
    await ask(page, "合成问题：自主研究的进度。", "agent");
    await page.getByRole("button", { name: "保存研究进度" }).waitFor();
    await page.waitForFunction(() => !document.querySelector('button[aria-describedby="save-status"]')?.hasAttribute("disabled"), null, { timeout: 10000 });
    check(`${tag}: discovery before save; nothing saved implicitly`, memory(requests).every((r) => r.method === "GET") && memory(requests).some((r) => r.path.endsWith("/progress-project")));
    await page.getByRole("button", { name: "保存研究进度" }).click();
    await page.getByText("服务端已确认保存").waitFor();
    check(`${tag}: exactly one PUT`, memory(requests).filter((r) => r.method === "PUT").length === 1);
    await shot(page, `${tag}-saved`);

    // 3. Notebook: list fetched only when opened; detail; project-level load with one POST despite a double click.
    check(`${tag}: list not fetched before opening`, !memory(requests).some((r) => r.path === "/api/research/progress"));
    await openNotebook(page);
    await page.locator(".archive-item").first().waitFor();
    check(`${tag}: list fetched on open`, memory(requests).some((r) => r.path === "/api/research/progress"));
    await openRecord(page, page.locator(".archive-item").first());
    const dialog = page.locator("#archive-record");
    check(`${tag}: detail keeps unresolved status and shows no save date`, await dialog.getByText("尚未解决的问题").count() === 1 && await dialog.getByText("保存于").count() === 0);
    const createsBefore = requests.filter((r) => r.method === "POST" && /\/api\/research\/(workflows|agents)$/.test(r.path)).length;
    const loadButton = page.getByRole("button", { name: "载入此项目的研究进度到新会话" });
    await loadButton.click();
    await loadButton.click({ force: true, timeout: 1000 }).catch(() => {});
    await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").waitFor();
    check(`${tag}: one new-session POST despite a double click`, memory(requests).filter((r) => r.method === "POST").length === 1);
    check(`${tag}: loading starts no research`, requests.filter((r) => r.method === "POST" && /\/api\/research\/(workflows|agents)$/.test(r.path)).length === createsBefore);
    check(`${tag}: project-level count shown`, await page.getByText(/项目级载入：返回 \d+ 条研究进度/).count() === 1);
    await shot(page, `${tag}-loaded`);
    await page.keyboard.press("Escape");

    // 4. Reload: loaded context is recovered with GET ?sessionId — never another POST.
    const postsBefore = memory(requests).filter((r) => r.method === "POST").length;
    await page.reload();
    await page.locator("#main").waitFor();
    await openNotebook(page);
    await page.getByText("已用会话 ID 重新读取").waitFor({ timeout: 10000 });
    check(`${tag}: reload recovers via GET with sessionId and no POST`, memory(requests).filter((r) => r.method === "POST").length === postsBefore
      && memory(requests).some((r) => r.method === "GET" && r.path.endsWith("/resume-context") && r.search.includes("sessionId=")));

    // 5. Delete with confirmation; the specific record leaves the list and the loaded copy.
    // (The mock issues one fixed dev token, so records from earlier runs may share this owner.)
    const target = page.locator(".archive-item", { hasText: "合成问题：自主研究的进度。" }).first();
    const cardsBefore = await page.locator(".archive-item").count();
    const loadedBefore = Number((await page.getByText(/项目级载入：返回 \d+ 条研究进度/).textContent())?.match(/(\d+)/)?.[1]);
    await openRecord(page, target);
    await page.getByRole("button", { name: "删除保存的进度…" }).click();
    await page.getByRole("button", { name: "确认删除" }).click();
    // The detail view closes only after the server confirms the deletion.
    await page.getByRole("button", { name: "确认删除" }).waitFor({ state: "detached", timeout: 10000 });
    // The reading layer closes after the server confirms; its return motion finishes before removal.
    await page.locator("#archive-record").waitFor({ state: "detached", timeout: 5000 });
    const focusAfterDelete = await page.evaluate(() => `${document.activeElement?.tagName}.${document.activeElement?.className}`);
    check(`${tag}: after delete, focus returns to the archive list`, /archive-item|text-input|archive-title/.test(focusAfterDelete), focusAfterDelete);
    // The loaded panel re-renders with the list; allow a frame for it to settle.
    await page.waitForFunction((n) => (document.body.innerText.match(/项目级载入：返回 (\d+) 条研究进度/) ?? [])[1] === String(n), loadedBefore - 1, { timeout: 5000 }).catch(() => {});
    const loadedAfter = Number((await page.getByText(/项目级载入：返回 \d+ 条研究进度/).textContent())?.match(/(\d+)/)?.[1]);
    check(`${tag}: delete removes the record and its loaded copy`, memory(requests).filter((r) => r.method === "DELETE").length === 1
      && await page.locator(".archive-item").count() === cardsBefore - 1 && loadedAfter === loadedBefore - 1,
      JSON.stringify({ cardsBefore, cardsAfter: await page.locator(".archive-item").count(), loadedBefore, loadedAfter }));
    await shot(page, `${tag}-deleted`);
    await page.keyboard.press("Escape");

    // 6. Identity change: loaded context and list from the previous identity disappear.
    await connect(page, "someone-else");
    await openNotebook(page);
    await page.getByText(/没有可访问的研究进度/).waitFor({ timeout: 10000 });
    check(`${tag}: identity change hides previous identity's loaded context`, await page.getByText("已载入历史研究进度").count() === 0);
    await page.keyboard.press("Escape");

    check(`${tag}: no horizontal overflow`, await overflow(page) <= 0, await overflow(page));
    check(`${tag}: no unexpected console errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }

  // 7. Errors: 413 save, ambiguous POST failure without automatic retry.
  {
    const { context, page, requests } = await session(browser, "ivory", "desktop");
    await page.goto(APP + "?scenario=memory-oversize");
    await connect(page);
    await ask(page, "合成问题：过大的进度。", "agent");
    await page.waitForFunction(() => !document.querySelector('button[aria-describedby="save-status"]')?.hasAttribute("disabled"), null, { timeout: 10000 });
    await page.getByRole("button", { name: "保存研究进度" }).click();
    await page.getByText(/60,000 字节上限/).waitFor();
    check("413: explained, not truncated, nothing shown as saved", await page.getByText("服务端已确认保存").count() === 0);
    await shot(page, "ivory-desktop-oversize");
    await context.close();
  }
  {
    const { context, page, requests } = await session(browser, "smoked", "desktop");
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await ask(page, "合成问题：载入失败。", "agent");
    await page.waitForFunction(() => !document.querySelector('button[aria-describedby="save-status"]')?.hasAttribute("disabled"), null, { timeout: 10000 });
    await page.getByRole("button", { name: "保存研究进度" }).click();
    await page.getByText("服务端已确认保存").waitFor();
    await page.goto(APP + "?scenario=memory-load-error");
    await openNotebook(page);
    await openRecord(page, page.locator(".archive-item").first());
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    await page.getByText("载入未完成").first().waitFor();
    await page.waitForTimeout(1500);
    check("ambiguous POST failure: no automatic retry", memory(requests).filter((r) => r.method === "POST").length === 1);
    check("ambiguous POST failure: explicit second load offered", await page.getByRole("button", { name: "再次载入（会再新建一个会话）" }).count() === 1);
    await shot(page, "smoked-desktop-load-failed");
    await context.close();
  }

  // 8. Demo preview is labelled and makes no memory requests.
  {
    const { context, page, requests } = await session(browser, "ivory", "mobile", "reduce");
    await page.goto(APP + "?demo&state=report");
    await openNotebook(page);
    await page.getByText("预览 · 示例数据，不联网").waitFor();
    check("demo: notebook preview labelled and network-free", memory(requests).length === 0);
    await openRecord(page, page.locator(".archive-item").first());
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    check("demo: preview load confirmation", await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").count() === 1 && memory(requests).length === 0);
    check("demo mobile reduced-motion: no horizontal overflow", await overflow(page) <= 0);
    await shot(page, "ivory-mobile-demo-notebook");
    await context.close();
  }
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} checks passed (mock verification, not real-backend acceptance)`);
if (failed.length) { console.log(JSON.stringify(failed, null, 2)); process.exitCode = 1; }
