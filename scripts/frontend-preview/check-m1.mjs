// Browser check of memory M1 continuation in the UI: explicit load → separate Continue → one create
// carrying the loaded session/project pair. MOCK VERIFICATION ONLY (synthetic preview API through the
// Vite proxy): it checks page behaviour and request shape, never that a model received history.
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/check-m1.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/m1";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); if (!ok) console.error("FAIL", name, detail); };

async function session(browser, theme, viewport) {
  const mobile = viewport.width < 768;
  const context = await browser.newContext({ viewport, colorScheme: theme === "smoked" ? "dark" : "light", isMobile: mobile, hasTouch: mobile, deviceScaleFactor: mobile ? 2 : 1 });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  page.on("request", (r) => {
    const url = new URL(r.url());
    if (url.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: url.pathname, body: r.postData() || "", key: r.headers()["idempotency-key"] || "" });
  });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(m.text()); });
  return { context, page, requests, errors };
}
const creates = (requests) => requests.filter((r) => r.method === "POST" && /^\/api\/research\/(agents|workflows)$/.test(r.path));
const resumePosts = (requests) => requests.filter((r) => r.method === "POST" && r.path.endsWith("/resume-context"));
const overflow = (page) => page.evaluate(() => Math.max(document.documentElement.scrollWidth, innerWidth) - Math.round(visualViewport.width));
const shot = (page, name) => page.screenshot({ path: `${OUT}/${name}.png` });

let seq = 0;
async function connect(page) {
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "User", exact: true }).fill("m1-check");
  await page.getByLabel("Bearer Token").fill(`local-preview-m1-${Date.now().toString(36)}-${++seq}`);
  const remember = page.getByRole("checkbox");
  if (!(await remember.isChecked())) await remember.check();
  await page.getByRole("button", { name: "保存并连接" }).click();
}
/** Creates a saved snapshot to continue from: an autonomous run, then an explicit save. */
async function seedSnapshot(page) {
  await page.locator("#question").fill("合成问题：比较方案 A、B 的检索效果与延迟。");
  await page.getByLabel("执行方式").selectOption("agent");
  await page.getByRole("button", { name: "开始研究", exact: true }).click();
  await page.locator("#report-question").waitFor({ timeout: 30000 });
  await page.waitForFunction(() => !document.querySelector('button[aria-describedby="save-status"]')?.hasAttribute("disabled"), null, { timeout: 10000 });
  await page.getByRole("button", { name: "保存研究进度" }).click();
  await page.getByText("服务端已确认保存").waitFor();
}
async function openFirstRecord(page) {
  await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  const item = page.locator(".archive-item").first();
  await item.waitFor();
  await item.click();
  if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
  await page.locator("#archive-record").waitFor();
}

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  for (const [theme, viewport] of [["ivory", { width: 1440, height: 900 }], ["smoked", { width: 390, height: 844 }]]) {
    const tag = `${theme}-${viewport.width}`;
    const { context, page, requests, errors } = await session(browser, theme, viewport);
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await seedSnapshot(page);

    // 1. Continue is not offered before an explicit load; loading starts no research.
    await openFirstRecord(page);
    check(`${tag}: no Continue before load`, await page.getByRole("button", { name: "在此项目继续研究…" }).count() === 0);
    const createsBeforeLoad = creates(requests).length;
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").first().waitFor();
    check(`${tag}: load is one resume POST and creates no run`, resumePosts(requests).length === 1 && creates(requests).length === createsBeforeLoad);
    const resume = await page.evaluate(() => JSON.parse(sessionStorage.getItem("deepresearch.react.loadedProgressContext") || "null"));

    // 2. Refresh recovers by GET and still creates nothing.
    await page.reload();
    await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
    await page.getByText("已用会话 ID 重新读取").waitFor({ timeout: 10000 });
    check(`${tag}: refresh recovers with GET, no POST`, resumePosts(requests).length === 1 && creates(requests).length === createsBeforeLoad);

    // 3. Continue is a separate step that only attaches the pair; nothing is sent yet.
    await page.locator(".loaded-panel").getByRole("button", { name: "在此项目继续研究…" }).click();
    await page.locator(".source-project").waitFor();
    check(`${tag}: history shown apart from an empty question`, await page.locator("#question").inputValue() === ""
      && (await page.locator(".source-project").textContent())?.includes("合成问题：比较方案 A、B"));
    check(`${tag}: mode fixed to autonomous research`, await page.getByLabel("执行方式").isDisabled() && await page.getByLabel("执行方式").inputValue() === "agent");
    check(`${tag}: attaching sends nothing`, creates(requests).length === createsBeforeLoad);
    check(`${tag}: no invented constraints field`, await page.getByText(/约束/).count() === 0);
    await page.locator(".source-project details summary").click();
    if (viewport.width === 1440) await shot(page, `${tag}-continue-composer`);

    // 4. Submit once (double click) with exactly the loaded pair.
    await page.locator("#question").fill("接着做，先推进尚未完成的部分。");
    const submitButton = page.getByRole("button", { name: "在此项目继续研究", exact: true });
    await submitButton.dblclick();
    await page.locator("#run-question").waitFor({ timeout: 10000 });
    const sent = creates(requests).slice(createsBeforeLoad);
    const body = sent[0] ? JSON.parse(sent[0].body) : {};
    check(`${tag}: one create despite a double click`, sent.length === 1, sent.length);
    check(`${tag}: create goes to /api/research/agents`, sent[0]?.path === "/api/research/agents");
    check(`${tag}: create carries the loaded session and project, identifiers only`, body.sessionId === resume?.targetSessionId
      && body.researchProjectId === resume?.projectId && Object.keys(body).sort().join(",") === "question,requestedTools,researchProjectId,sessionId", JSON.stringify(body));
    await page.locator(".memory-note").waitFor();
    check(`${tag}: run states selection without claiming model receipt`, (await page.locator(".memory-note").textContent())?.includes("页面无法确认这些历史是否已进入模型的规划输入"));
    await page.locator(".memory-note details summary").click();
    await shot(page, `${tag}-continued-running`);
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    check(`${tag}: report keeps the selection note`, await page.locator(".memory-note").count() === 1);
    check(`${tag}: no horizontal overflow`, await overflow(page) <= 0, await overflow(page));
    check(`${tag}: no console errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }

  // 5. Server refuses the selection: nothing starts; reselection is offered; no automatic retry.
  {
    const { context, page, requests } = await session(browser, "ivory", { width: 1440, height: 900 });
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await seedSnapshot(page);
    await page.goto(APP + "?scenario=memory-unavailable");
    await openFirstRecord(page);
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    await page.getByRole("button", { name: "在此项目继续研究…" }).first().click();
    await page.locator("#question").fill("接着做。");
    const before = creates(requests).length;
    await page.getByRole("button", { name: "在此项目继续研究", exact: true }).click();
    await page.locator(".memory-rejected").waitFor();
    await page.waitForTimeout(800);
    check("refusal: explained as history not used and research not started", (await page.locator(".memory-rejected").textContent())?.includes("研究没有开始"));
    check("refusal: one request, no automatic retry", creates(requests).length === before + 1);
    check("refusal: reselection offered", await page.getByRole("button", { name: "回到研究档案重新选择" }).count() === 1);
    await page.locator(".memory-rejected details summary").click();
    await shot(page, "ivory-1440-refused");
    await context.close();
  }

  // 6. A run that stops using revoked history shows it plainly and does not restart.
  {
    const { context, page, requests } = await session(browser, "smoked", { width: 1440, height: 900 });
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await seedSnapshot(page);
    await page.goto(APP + "?scenario=memory-revoked");
    await openFirstRecord(page);
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    await page.getByRole("button", { name: "在此项目继续研究…" }).first().click();
    await page.locator("#question").fill("接着做。");
    await page.getByRole("button", { name: "在此项目继续研究", exact: true }).click();
    await page.locator(".memory-note.is-rejected").waitFor({ timeout: 20000 });
    const createsAfter = creates(requests).length;
    await page.waitForTimeout(1200);
    check("revoked: run shows it stopped using the history", (await page.locator(".memory-note.is-rejected").textContent())?.includes("停止使用该项目的历史进度"));
    check("revoked: in-flight limit stated, no recall claim", (await page.locator(".memory-note.is-rejected").textContent())?.includes("可能无法撤回"));
    check("revoked: nothing restarted automatically", creates(requests).length === createsAfter);
    await shot(page, "smoked-1440-revoked");
    await context.close();
  }

  // 7. Deleting the loaded project's snapshot invalidates the pair (snapshot, not project).
  {
    const { context, page } = await session(browser, "ivory", { width: 1440, height: 900 });
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await seedSnapshot(page);
    await openFirstRecord(page);
    await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
    await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").first().waitFor();
    await page.getByRole("button", { name: "删除保存的进度…" }).click();
    await page.getByRole("button", { name: "确认删除" }).click();
    await page.locator("#archive-record").waitFor({ state: "detached", timeout: 10000 });
    check("delete: Continue withdrawn and reload required", await page.getByRole("button", { name: "在此项目继续研究…" }).count() === 0
      && await page.getByText("这个载入记录不能再用于继续研究；请重新载入。").count() === 1);
    check("delete: wording is about a snapshot, not the project", await page.getByText(/项目已删除|删除了项目/).count() === 0);
    await context.close();
  }

  // 8. The workflow mode never sends researchProjectId (ordinary research is unchanged).
  {
    const { context, page, requests } = await session(browser, "ivory", { width: 1440, height: 900 });
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await page.locator("#question").fill("合成问题：普通研究。");
    await page.getByRole("button", { name: "开始研究", exact: true }).click();
    await page.locator("#run-question").waitFor({ timeout: 10000 });
    const body = JSON.parse(creates(requests)[0]?.body || "{}");
    check("ordinary research: no researchProjectId and no memory note", !("researchProjectId" in body) && await page.locator(".memory-note").count() === 0);
    await context.close();
  }
} finally {
  await browser.close();
}
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed (mock UI verification only, not real-chain acceptance)`);
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
if (passed !== results.length) process.exit(1);
