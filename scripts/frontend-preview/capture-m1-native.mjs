// Real-chain M1 page capture: browser → this frontend (Vite proxy) → isolated native Spring/JWT backend
// → PostgreSQL → Python worker → controlled production-adapter provider (synthetic fixture, no paid model).
// The viewer token is read from the private handoff file and is never logged, saved or shown on screen.
//   DEEPRESEARCH_API_PROXY=<native base_url> npm --prefix frontend run dev -- --port 5174 --strictPort
//   PLAYWRIGHT_CORE=... CHROME_PATH=... node scripts/frontend-preview/capture-m1-native.mjs <handoffDir> <outDir> <frontendCandidate>
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const [handoff, OUT, frontendCandidate = "uncommitted"] = process.argv.slice(2);
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5174") + "/app/";
mkdirSync(OUT, { recursive: true });
const ready = JSON.parse(readFileSync(`${handoff}/demo/demo-ready.json`, "utf8"));
const secret = JSON.parse(readFileSync(`${handoff}/demo/demo-private.json`, "utf8"));
const claims = JSON.parse(Buffer.from(secret.viewer_token.split(".")[1], "base64url").toString());
const backendManifestSha = createHash("sha256").update(readFileSync(`${handoff}/candidate-manifest.json`)).digest("hex");

const results = [];
const shots = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); console.log(ok ? "PASS" : "FAIL", name, ok ? "" : detail); };
const redact = (text) => String(text).split(secret.viewer_token).join("[redacted]");

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "light" });
const requests = [];
const errors = [];
context.on("request", (r) => {
  const url = new URL(r.url());
  if (url.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: url.pathname, search: url.search, body: r.postData() || "", key: r.headers()["idempotency-key"] || "" });
});
const page = await context.newPage();
page.on("pageerror", (e) => errors.push(redact(e)));
page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(redact(m.text())); });
const creates = () => requests.filter((r) => r.method === "POST" && /^\/api\/research\/(agents|workflows)$/.test(r.path));
const resumePosts = () => requests.filter((r) => r.method === "POST" && r.path.endsWith("/resume-context"));
let runId = "", sessionId = "", projectId = ready.project_id;
async function shot(target, name, behaviour) {
  const file = `${name}.png`;
  await target.screenshot({ path: `${OUT}/${file}` });
  shots.push({ file, url: target.url(), capturedAt: new Date().toISOString(), runId: runId || null, sessionId: sessionId || null, projectId, verified: behaviour });
}

// Existing identity controls; the dialog is never captured. Each tab keeps its own session token.
async function connect(target) {
  await target.getByRole("button", { name: /连接与身份/ }).click();
  await target.getByRole("textbox", { name: "Tenant", exact: true }).fill(claims.tenantId).catch(() => {});
  await target.getByRole("textbox", { name: "User", exact: true }).fill(claims.sub);
  await target.getByLabel("Bearer Token").fill(secret.viewer_token);
  const remember = target.getByRole("checkbox");
  if (!(await remember.isChecked())) await remember.check();
  await target.getByRole("button", { name: "保存并连接" }).click();
  await target.waitForTimeout(500);
}

try {
  await page.goto(APP);
  await connect(page);

  // 1. Archive: the saved project, shown by its saved goal; nothing is loaded yet.
  await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  const item = page.locator(".archive-item").first();
  await item.waitFor({ timeout: 15000 });
  await item.click();
  if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
  await page.locator("#archive-record").waitFor();
  await page.waitForTimeout(700);
  const recordText = await page.locator("#archive-record").textContent();
  check("record shows the latency TODO, its conditions and the dispute", ["比较方案 A、B 的检索效果与延迟", "在相同数据集、相同硬件下测量延迟", "同数据集", "同硬件", "无实测数据不得编造数值", "效果结论有争议", "有争议"].every((t) => recordText.includes(t)), recordText.slice(0, 300));
  check("no Continue before an explicit load", await page.getByRole("button", { name: "在此项目继续研究…" }).count() === 0);
  await shot(page, "native-01-record", "saved snapshot shown as recorded: goal, latency TODO with criteria, contested claim; Continue not offered before load");

  // 2. Explicit load: one resume POST, no research run.
  await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
  await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").first().waitFor({ timeout: 15000 });
  await page.waitForTimeout(1500);
  check("load is one resume POST and creates no run", resumePosts().length === 1 && creates().length === 0, JSON.stringify({ resume: resumePosts().length, creates: creates().length }));
  const loaded = await page.evaluate(() => JSON.parse(sessionStorage.getItem("deepresearch.react.loadedProgressContext") || "null"));
  sessionId = loaded?.targetSessionId ?? "";
  await shot(page, "native-02-loaded", "explicit load returned a new session; not sent to a model; no run created");

  // 3. Separate Continue attaches the pair; history stays apart from this round's question.
  await page.getByRole("button", { name: "在此项目继续研究…" }).first().click();
  await page.locator(".source-project").waitFor();
  await page.locator('[data-scope="kb"]').click();
  if ((await page.locator(".toggle-chip").getAttribute("aria-pressed")) === "true") await page.locator(".toggle-chip").click();
  await page.locator("#question").fill("接着做，先推进尚未完成的部分。");
  await page.locator(".source-project details summary").click();
  check("attaching sends nothing", creates().length === 0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await shot(page, "native-03-continue", "source project (saved history) shown separately from this round's question; mode fixed to autonomous research");

  // 4. Submit once with exactly the paired identifiers.
  await page.getByRole("button", { name: "在此项目继续研究", exact: true }).dblclick();
  await page.locator("#run-question").waitFor({ timeout: 20000 });
  const sent = creates();
  const body = sent[0] ? JSON.parse(sent[0].body) : {};
  check("one create despite a double click", sent.length === 1, sent.length);
  check("POST /api/research/agents with exactly the loaded session and project", sent[0]?.path === "/api/research/agents" && body.sessionId === loaded?.targetSessionId
    && body.researchProjectId === loaded?.projectId && Object.keys(body).sort().join(",") === "question,requestedTools,researchProjectId,sessionId", JSON.stringify(body));
  runId = await page.evaluate(() => JSON.parse(sessionStorage.getItem("deepresearch.console.run") || sessionStorage.getItem("deepresearch.react.run") || "null")?.runId ?? "");
  if (!runId) runId = (await page.locator(".memory-note").textContent().catch(() => ""))?.match(/wf-[0-9a-f-]+/)?.[0] ?? "";
  await page.locator(".memory-note").waitFor();
  await page.locator(".memory-note details summary").click();
  runId = runId || ((await page.locator(".memory-note").textContent())?.match(/wf-[0-9a-f-]+/)?.[0] ?? "");
  await page.waitForTimeout(2500);
  await shot(page, "native-04-running", "agent run accepted with the paired identifiers; page states selection only, not model receipt");

  // 5. Terminal state from the native worker.
  await page.locator("#report-question").waitFor({ timeout: 180000 });
  await page.waitForTimeout(1500);
  const report = await page.locator("#report").textContent();
  check("terminal state is honest: insufficient evidence, gaps kept", report.includes("可信证据不足") || report.includes("证据不足"), report.slice(0, 200));
  check("no invented latency numbers in the report", !/\d+(\.\d+)?\s*(ms|毫秒)/.test(report), (report.match(/\d+(\.\d+)?\s*(ms|毫秒)/) || [""])[0]);
  check("selection note retained on the report", await page.locator(".memory-note").count() === 1);
  await page.locator(".memory-note details").evaluate((d) => { d.open = true; });
  await shot(page, "native-05-report", "native terminal state: insufficient evidence with recorded gaps; no measured values invented");
  await page.locator(".unresolved").first().scrollIntoViewIfNeeded().catch(() => {});
  await shot(page, "native-06-report-gaps", "unresolved items listed at the end of the report");

  // 6. Refresh recovers the run without creating another.
  const beforeReload = creates().length;
  await page.reload();
  await page.locator("#report-question").waitFor({ timeout: 30000 });
  await page.waitForTimeout(1500);
  check("refresh recovers the run and creates nothing", creates().length === beforeReload && (await page.locator("#report-question").textContent()).includes("接着做"));
  await shot(page, "native-07-after-refresh", "after reload: same run recovered via GET/SSE; no new create POST");

  // 7. Load, then delete the snapshot in a second page before Continue (product DELETE on this isolated backend).
  const second = await context.newPage();
  await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  await page.locator(".archive-item").first().click();
  if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
  await page.locator("#archive-record").waitFor();
  const reloadButton = page.getByRole("button", { name: /另建一个新会话再次载入|载入此项目的研究进度到新会话/ }).first();
  await reloadButton.click();
  await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").first().waitFor({ timeout: 15000 });
  await page.getByRole("button", { name: "在此项目继续研究…" }).first().click();
  await page.locator("#question").fill("接着做。");
  await second.goto(APP);
  await connect(second);
  await second.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  await second.locator(".archive-item").first().click();
  if (!(await second.locator("#archive-record").count())) await second.keyboard.press("Enter");
  await second.getByRole("button", { name: "删除保存的进度…" }).click();
  await second.getByRole("button", { name: "确认删除" }).click();
  await second.locator("#archive-record").waitFor({ state: "detached", timeout: 15000 });
  await second.close();
  const beforeStale = creates().length;
  await page.locator('[data-scope="kb"]').click().catch(() => {});
  await page.getByRole("button", { name: "在此项目继续研究", exact: true }).click();
  await page.waitForTimeout(4000);
  const refusal = page.locator(".memory-rejected, .memory-note.is-rejected");
  const refused = await refusal.count();
  if (refused) await refusal.first().locator("details").evaluate((d) => { d.open = true; }).catch(() => {});
  check("stale selection after snapshot deletion is refused and not retried", refused > 0 && creates().length === beforeStale + 1, JSON.stringify({ refused, creates: creates().length - beforeStale }));
  await page.evaluate(() => window.scrollTo(0, 0));
  await shot(page, "native-08-deleted-then-continue", "snapshot deleted in another page after load; Continue refused by the server, reselection offered, no automatic retry");
  check("no console errors", errors.length === 0, errors.join(" | "));
} catch (error) {
  check("script completed", false, redact(error?.message ?? error));
  await page.screenshot({ path: `${OUT}/native-error.png` }).catch(() => {});
} finally {
  await browser.close();
}

const manifest = {
  label: "Synthetic fixture project; controlled production-adapter provider (provider=controlled, fixture_only=true). Not a real-model run; no DeepSeek or paid calls from this capture.",
  backend: { base_url: ready.base_url, candidate_manifest_sha256: backendManifestSha, provider: ready.provider, fixture_only: ready.fixture_only },
  frontend: { branch: "claude/deepresearch-memory-m1", base: "066d306", candidate: frontendCandidate, origin: APP },
  identity: { tenantId: claims.tenantId, subject: claims.sub, token: "not recorded" },
  ids: { projectId, sessionId, runId },
  requests: requests.filter((r) => r.method !== "GET").map((r) => ({ method: r.method, path: r.path, body: r.path.endsWith("/agents") ? JSON.parse(r.body || "{}") : undefined, idempotencyKeyPresent: !!r.key })),
  screenshots: shots,
  checks: results,
};
writeFileSync(`${OUT}/m1-native-screenshots.json`, redact(JSON.stringify(manifest, null, 2)));
console.log(`${results.filter((r) => r.ok).length}/${results.length} checks passed (real native chain, controlled provider)`);
