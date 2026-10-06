// Browser check of memory M3 in the UI: automatic save status on the report (read-only, bounded
// polling) and the archive correction note (PATCH {note}; a loaded pair becomes stale).
// MOCK VERIFICATION ONLY (synthetic preview API through the Vite proxy) — not native acceptance.
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/check-m3.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/m3";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); if (!ok) console.error("FAIL", name, detail); };

async function session(browser, viewport = { width: 1440, height: 900 }, theme = "light") {
  const mobile = viewport.width < 768;
  const context = await browser.newContext({ viewport, colorScheme: theme, isMobile: mobile, hasTouch: mobile, deviceScaleFactor: mobile ? 2 : 1 });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  page.on("request", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: u.pathname, body: r.postData() || "" }); });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(m.text()); });
  return { context, page, requests, errors };
}
let seq = 0;
async function connect(page) {
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "User", exact: true }).fill("m3-check");
  await page.getByLabel("Bearer Token").fill(`local-preview-m3-${Date.now().toString(36)}-${++seq}`);
  const remember = page.getByRole("checkbox");
  if (!(await remember.isChecked())) await remember.check();
  await page.getByRole("button", { name: "保存并连接" }).click();
}
async function agentRun(page, question) {
  await page.locator("#question").fill(question);
  await page.getByLabel("执行方式").selectOption("agent");
  await page.getByRole("button", { name: "开始研究", exact: true }).click();
  await page.locator("#report-question").waitFor({ timeout: 30000 });
}
const saveGets = (r) => r.filter((x) => x.method === "GET" && x.path.endsWith("/progress-save"));
const puts = (r) => r.filter((x) => x.method === "PUT");
const shot = (page, name) => page.screenshot({ path: `${OUT}/${name}.png` });

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  // 1. Automatic save: PENDING → SAVED by read-only polling; the browser never PUTs.
  {
    const { context, page, requests, errors } = await session(browser);
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await agentRun(page, "合成问题：自动保存。");
    await page.getByText("已自动保存已完成事项与待办，可在研究档案查看。").waitFor({ timeout: 15000 });
    check("auto-save confirmed by polling the read-only status", saveGets(requests).length >= 2 && saveGets(requests).length <= 31, saveGets(requests).length);
    check("browser never initiates the save (no PUT)", puts(requests).length === 0);
    const settled = saveGets(requests).length;
    await page.waitForTimeout(3000);
    check("polling stops once saved", saveGets(requests).length === settled, `${settled} → ${saveGets(requests).length}`);
    await shot(page, "m3-report-autosaved");

    // 2. Archive shows the origin; a correction note is added with PATCH {note} only.
    await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
    const item = page.locator(".archive-item").first();
    await item.waitFor();
    check("archive list marks the automatic save", (await item.textContent()).includes("自动保存"));
    await item.click();
    if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
    await page.locator("#archive-record").waitFor();
    check("record shows its save origin", await page.locator("#archive-record").getByText("自动保存", { exact: true }).count() === 1);
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").first().waitFor();
    await page.getByRole("button", { name: "添加纠正说明…" }).click();
    await page.locator("#correction-note").fill("延迟须在同一台机器上重测；效果结论仍有争议。");
    await page.getByRole("button", { name: "保存纠正说明" }).click();
    await page.getByText("延迟须在同一台机器上重测；效果结论仍有争议。").waitFor();
    const patch = requests.find((x) => x.method === "PATCH");
    check("PATCH sends only the note", patch && JSON.stringify(JSON.parse(patch.body)) === JSON.stringify({ note: "延迟须在同一台机器上重测；效果结论仍有争议。" }), patch?.body);
    check("note shown as a user annotation, not a verified fact", await page.getByText("纠正说明（用户批注，不修改已核验的事实）").count() === 1);
    await page.locator("#archive-record").getByText("该项目的纠正说明已更新").waitFor({ timeout: 5000 }).catch(() => {});
    check("loaded pair becomes stale after correction; Continue withdrawn", await page.locator("#archive-record").getByText("该项目的纠正说明已更新").count() === 1
      && await page.getByRole("button", { name: "在此项目继续研究…" }).count() === 0);
    await shot(page, "m3-correction-saved");
    await page.keyboard.press("Escape");
    await page.locator("#archive-record").waitFor({ state: "detached" });
    check("loaded panel asks for a reload after correction", await page.getByText("该项目的纠正说明已更新").count() === 1);
    check("archive list marks the correction", (await page.locator(".archive-item").first().textContent()).includes("有纠正说明"));

    // 3. Clearing the note sends an empty string.
    await page.locator(".archive-item").first().click();
    if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
    await page.getByRole("button", { name: "修改纠正说明…" }).click();
    await page.getByRole("button", { name: "清除说明" }).click();
    await page.getByText("没有纠正说明。").waitFor();
    check("clearing sends an empty note", JSON.parse(requests.filter((x) => x.method === "PATCH").at(-1).body).note === "");
    check("no console errors", errors.length === 0, errors.join(" | "));
    await context.close();
  }

  // 4. Failure keeps the report and points at the manual retry; disabled runs are not called automatic.
  for (const [scenario, text, name] of [["autosave-fail", "自动保存失败：服务端保存时出错。", "failed"], ["autosave-off", "这次运行没有启用自动保存。", "not-enabled"]]) {
    const { context, page, requests } = await session(browser, scenario === "autosave-off" ? { width: 390, height: 844 } : { width: 1440, height: 900 }, scenario === "autosave-off" ? "dark" : "light");
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await page.goto(APP + "?scenario=" + scenario);
    await agentRun(page, "合成问题：" + name);
    await page.getByText(text).waitFor({ timeout: 15000 });
    check(`${name}: report stays visible with the status`, await page.locator("#report").count() === 1);
    check(`${name}: no automatic PUT`, puts(requests).length === 0);
    if (scenario === "autosave-fail") check("failed: manual save remains the explicit retry", !(await page.getByRole("button", { name: "保存研究进度" }).isDisabled()));
    await shot(page, `m3-report-${name}`);
    await context.close();
  }
} finally {
  await browser.close();
}
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed (mock UI verification only, not native acceptance)`);
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
if (passed !== results.length) process.exit(1);
