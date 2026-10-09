// Browser walkthrough of the React app's *live* adapters against the preview mock API.
// No Java service, model or retrieval provider is involved; all data is synthetic.
//
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/journey-react-live.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/react-live";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail }); if (!ok) console.error("FAIL", name, detail); };

async function session(browser, { theme = "light", width = 1440, height = 900 } = {}) {
  const context = await browser.newContext({ viewport: { width, height }, colorScheme: theme });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  page.on("request", (r) => { if (r.url().includes("/api/")) requests.push({ method: r.method(), path: new URL(r.url()).pathname, headers: r.headers(), body: r.postData() }); });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/50[23]|40[49]|Failed to load resource/.test(m.text())) errors.push(m.text()); });
  return { context, page, requests, errors };
}

async function connect(page, user = "demo-user", token = null) {
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByLabel("User").fill(user);
  if (token) await page.getByLabel("Bearer Token").fill(token);
  else { await page.getByRole("button", { name: "签发 USER Dev Token" }).click(); await page.getByText("Dev Token 已签发").waitFor(); }
  await page.getByText("仅在当前浏览会话保存 Token").click();
  await page.getByRole("button", { name: "保存并连接" }).click();
}

async function ask(page, question, { mode } = {}) {
  await page.locator("#question").fill(question);
  if (mode) await page.getByLabel("执行方式").selectOption(mode);
  await page.getByRole("button", { name: mode === "legacy" ? "运行基线" : "开始研究", exact: true }).click();
}

const posts = (requests, suffix) => requests.filter((r) => r.method === "POST" && r.path.endsWith(suffix));
const shot = (page, name) => page.screenshot({ path: `${OUT}/${name}.png` });

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  // 1. Connection label, identity, successful run, citations, no duplicate POST (StrictMode dev double effects included).
  for (const theme of ["light", "dark"]) {
    const { context, page, requests, errors } = await session(browser, { theme });
    await page.goto(APP + "?scenario=success");
    await page.getByText("预览服务器 · 模拟 API").waitFor();
    check(`${theme}: header names the preview mock, not Java`, await page.getByText("Java API 在线").count() === 0);
    await ask(page, "合成问题：解释断线恢复。");
    await page.getByRole("dialog", { name: "连接与身份" }).waitFor();
    check(`${theme}: submitting without identity opens the identity dialog and sends nothing`, posts(requests, "/workflows").length === 0);
    await page.keyboard.press("Escape");
    await connect(page);
    await ask(page, "合成问题：解释断线恢复。");
    await page.locator(".activity").waitFor();
    await page.waitForTimeout(3000);
    await shot(page, `${theme}-live-running`);
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    await page.waitForTimeout(500);
    check(`${theme}: exactly one create POST`, posts(requests, "/workflows").length === 1, String(posts(requests, "/workflows").length));
    const create = posts(requests, "/workflows")[0];
    check(`${theme}: create carries Idempotency-Key and bearer token`, !!create?.headers["idempotency-key"] && create?.headers.authorization?.startsWith("Bearer "));
    check(`${theme}: verified citations rendered from live response`, await page.locator(".cite").count() > 0);
    await page.locator(".cite").first().click();
    await page.locator("#inspector").waitFor();
    await shot(page, `${theme}-live-report-inspect`);
    check(`${theme}: no unexpected console errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }

  // 2. Reload mid-run resumes via GET + SSE with cursor; no new POST.
  {
    const { context, page, requests } = await session(browser);
    await page.goto(APP + "?scenario=slow");
    await connect(page);
    await ask(page, "合成问题：慢速运行。");
    await page.waitForTimeout(4000);
    const before = posts(requests, "/workflows").length;
    await page.reload();
    await page.locator(".activity").waitFor();
    await page.waitForTimeout(2500);
    const streams = requests.filter((r) => r.path.endsWith("/events"));
    check("reload resumes without a new create", posts(requests, "/workflows").length === before && before === 1);
    check("resumed stream sends Last-Event-ID", !!streams[streams.length - 1]?.headers["last-event-id"], streams[streams.length - 1]?.headers["last-event-id"]);
    // 3. Server cancellation from the running view.
    await page.getByRole("button", { name: "取消研究" }).click();
    await page.getByText("已取消").first().waitFor({ timeout: 10000 });
    check("cancel is a server POST", posts(requests, "/cancel").length === 1);
    await shot(page, "light-live-cancelled");
    await context.close();
  }

  // 4. Disconnect: the mock drops the first stream; the client resumes from its cursor.
  {
    const { context, page, requests } = await session(browser);
    await page.goto(APP + "?scenario=disconnect");
    await connect(page);
    await ask(page, "合成问题：断线续传。");
    await page.locator(".connection").waitFor({ timeout: 15000 });
    await shot(page, "light-live-reconnecting");
    await page.locator("#report-question").waitFor({ timeout: 40000 });
    const streams = requests.filter((r) => r.path.endsWith("/events"));
    check("disconnect: reconnected with cursor", streams.length >= 2 && !!streams[1].headers["last-event-id"], streams.map((s) => s.headers["last-event-id"] || "-").join(","));
    check("disconnect: still one create", posts(requests, "/workflows").length === 1);
    await context.close();
  }

  // 5. Unknown create outcome survives reload and replays the identical request.
  {
    const { context, page, requests } = await session(browser);
    await page.goto(APP + "?scenario=unknown");
    await connect(page);
    await ask(page, "合成问题：结果未知。");
    await page.locator("#unknown-title").waitFor({ timeout: 25000 });
    await page.reload();
    await page.locator("#unknown-title").waitFor();
    check("unknown outcome: reload sends no POST", posts(requests, "/workflows").length === 1);
    await shot(page, "light-live-unknown-create");
    await page.getByRole("button", { name: "使用原请求安全重试" }).click();
    await page.locator(".activity, #report-question").first().waitFor({ timeout: 15000 });
    const [first, retry] = posts(requests, "/workflows");
    check("unknown outcome: retry reuses key and exact body", retry && first.headers["idempotency-key"] === retry.headers["idempotency-key"] && first.body === retry.body);
    await context.close();
  }

  // 6. Identity change never shows the previous identity's cached run.
  {
    const { context, page } = await session(browser);
    await page.goto(APP + "?scenario=success");
    await connect(page, "alice");
    await ask(page, "合成问题：Alice 的研究。");
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    await connect(page, "mallory", "a-different-local-preview-token");
    await page.getByText("无法读取该运行").waitFor({ timeout: 10000 });
    check("identity change hides the previous identity's report", await page.getByText("Alice 的研究").count() === 0);
    await shot(page, "light-live-identity-isolation");
    await context.close();
  }

  // 7. Failure explanation, LangGraph-shaped citations, Single Agent and autonomous routing.
  {
    const { context, page, requests } = await session(browser, { theme: "dark" });
    await page.goto(APP + "?scenario=failed");
    await connect(page);
    await ask(page, "合成问题：失败。");
    await page.locator(".limits code", { hasText: "MODEL_PROVIDER_FAILED" }).waitFor({ timeout: 30000 });
    check("failure shows the specific code and a recovery action", await page.getByRole("button", { name: "用同一问题重新研究" }).count() === 1);
    await shot(page, "dark-live-failed");
    await page.goto(APP + "?scenario=langgraph");
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await ask(page, "合成问题：LangGraph 形状。");
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    check("LangGraph results state missing source details", await page.getByText(/缺少来源详情/).count() === 1);
    await page.locator(".cite").first().click();
    check("LangGraph inspector does not invent a link", await page.locator("#inspector a[target=_blank]").count() === 0);
    await shot(page, "dark-live-langgraph-sources");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await ask(page, "合成问题：单 Agent。", { mode: "legacy" });
    await page.locator("#report-question").waitFor({ timeout: 15000 });
    check("Single Agent uses the synchronous endpoint and no SSE", posts(requests, "/api/research/agent").length === 1
      && requests.filter((r) => r.path.endsWith("/events")).length === 2);
    await shot(page, "dark-live-single-agent");
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await ask(page, "合成问题：自主研究。", { mode: "agent" });
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    check("autonomous candidate posts to /agents", posts(requests, "/api/research/agents").length === 1);
    await context.close();
  }

  // 8. Phone width: live-only components fit the visible viewport.
  for (const theme of ["light", "dark"]) {
    const { context, page } = await session(browser, { theme, width: 390, height: 844 });
    const overflow = () => page.evaluate(() => Math.max(document.documentElement.scrollWidth, innerWidth) - Math.round(visualViewport.width));
    await page.goto(APP + "?scenario=unknown");
    await connect(page);
    await ask(page, "合成问题：手机上的未知结果。");
    await page.locator("#unknown-title").waitFor({ timeout: 25000 });
    await page.locator(".unknown").scrollIntoViewIfNeeded();
    check(`${theme} 390px: unknown-create card fits`, await overflow() <= 0);
    await shot(page, `${theme}-mobile-live-unknown`);
    await page.goto(APP + "?scenario=failed");
    await page.getByRole("button", { name: "放弃并新建" }).click();
    await ask(page, "合成问题：手机上的失败。");
    await page.locator(".limits code").waitFor({ timeout: 30000 });
    check(`${theme} 390px: failure report fits`, await overflow() <= 0);
    await shot(page, `${theme}-mobile-live-failed`);
    await context.close();
  }

  // 9. Demo mode is network-isolated even with a stored identity.
  {
    const { context, page, requests } = await session(browser);
    await page.goto(APP);
    await connect(page);
    // Let the post-login connection check (ping + capabilities) settle before measuring.
    await page.waitForLoadState("networkidle");
    await page.waitForTimeout(500);
    const before = requests.length;
    await page.getByRole("button", { name: /完整报告/ }).click();
    await page.getByText("示例数据").first().waitFor();
    await page.locator("#report-question").waitFor({ timeout: 20000 });
    check("demo playback makes no API requests", requests.length === before,
      requests.slice(before).map((r) => r.method + " " + r.path).join(", "));
    check("demo URL is explicit", page.url().includes("demo"));
    await context.close();
  }
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/journey.json`, JSON.stringify(results, null, 2));
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} checks passed`);
if (failed.length) { console.log(JSON.stringify(failed, null, 2)); process.exitCode = 1; }
