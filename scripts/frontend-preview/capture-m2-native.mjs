// Real-chain M2 page capture: the existing native agent run is opened through the app's saved-run
// recovery (test-only setup of the same sessionStorage record the app writes), and the summary
// disclosure is compared with the native GET /api/research/agents/{runId}/context-summary.
// Controlled provider, synthetic history. The viewer token is never logged or saved.
//   PLAYWRIGHT_CORE=... CHROME_PATH=... node scripts/frontend-preview/capture-m2-native.mjs <handoffDir> <outDir> <frontendCommit> [demoDir]
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const [handoff, OUT, frontendCommit = "uncommitted", demoDir = "demo"] = process.argv.slice(2);
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5174") + "/app/";
mkdirSync(OUT, { recursive: true });
const ready = JSON.parse(readFileSync(`${handoff}/${demoDir}/demo-ready.json`, "utf8"));
const secret = JSON.parse(readFileSync(`${handoff}/${demoDir}/demo-private.json`, "utf8"));
const auth = secret.viewer_token;                              // already "Bearer …"
const claims = JSON.parse(Buffer.from(auth.split(" ").pop().split(".")[1], "base64url").toString());
const backendManifestSha = createHash("sha256").update(readFileSync(`${handoff}/candidate-manifest.json`)).digest("hex");
const redact = (t) => String(t).split(auth.split(" ").pop()).join("[redacted]");
const results = [];
const shots = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); console.log(ok ? "PASS" : "FAIL", name, ok ? "" : redact(detail)); };

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "light" });
const requests = [];
const errors = [];
context.on("request", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: u.pathname }); });
const page = await context.newPage();
page.on("pageerror", (e) => errors.push(redact(e)));
page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(redact(m.text())); });
const creates = () => requests.filter((r) => r.method === "POST" && /^\/api\/research\/(agents|workflows)$|resume-context$/.test(r.path));
const summaryGets = () => requests.filter((r) => r.method === "GET" && r.path.endsWith("/context-summary"));
let sessionId = "";
async function shot(name, behaviour) {
  await page.screenshot({ path: `${OUT}/${name}.png` });
  shots.push({ file: `${name}.png`, url: page.url(), capturedAt: new Date().toISOString(), runId: ready.run_id, sessionId, projectId: ready.project_id, verified: behaviour });
}
let native = null;
try {
  await page.goto(APP);
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "User", exact: true }).fill(String(claims.sub));
  await page.getByLabel("Bearer Token").fill(auth);
  const remember = page.getByRole("checkbox");
  if (!(await remember.isChecked())) await remember.check();
  await page.getByRole("button", { name: "保存并连接" }).click();
  await page.waitForTimeout(400);
  // The run's question as stored by the server: the summary goal entry whose locator is "question".
  const question = await page.evaluate(async ([a, id]) => {
    const r = await fetch(`/api/research/agents/${encodeURIComponent(id)}/context-summary`, { headers: { Authorization: a } });
    const v = await r.json();
    const goal = (v.summary?.sections?.goals ?? []).find((g) => g.locator === "question");
    return typeof goal?.value === "string" ? goal.value : "";
  }, [auth, ready.run_id]);
  check("run question available for the report heading", question.length > 0, question);
  // Same record the app saves for a run it is showing; the app then recovers it via GET + SSE.
  await page.evaluate(([runId, q]) => sessionStorage.setItem("deepresearch.console.currentRun", JSON.stringify({ mode: "agent", runId, question: q })), [ready.run_id, question]);
  await page.reload();
  await page.locator("#report-question").waitFor({ timeout: 30000 });
  check("report heading shows the run question", (await page.locator("#report-question").textContent()) === question);
  await page.locator(".summary-disclosure").waitFor({ timeout: 15000 });
  await page.waitForTimeout(800);

  native = await page.evaluate(async ([a, id]) => {
    const r = await fetch(`/api/research/agents/${encodeURIComponent(id)}/context-summary`, { headers: { Authorization: a } });
    return { http: r.status, body: await r.json() };
  }, [auth, ready.run_id]);
  const n = native.body;
  sessionId = await page.evaluate(() => JSON.parse(sessionStorage.getItem("deepresearch.console.currentRun") || "{}").sessionId ?? "");
  const head = await page.locator(".summary-disclosure > summary").textContent();
  check("native summary GET is 200 READY for the owned run", native.http === 200 && n.status === "READY" && n.run_id === ready.run_id, `${native.http} ${n.status}`);
  check("row states status and the exact byte measurement", head.includes("已生成") && head.includes(n.measurement.before_bytes.toLocaleString("en-US"))
    && head.includes(n.measurement.after_bytes.toLocaleString("en-US")) && head.includes(n.measurement.budget_bytes.toLocaleString("en-US")), head);
  check("collapsed by default", !(await page.locator(".summary-disclosure").evaluate((d) => d.open)));
  await page.locator(".summary-disclosure").scrollIntoViewIfNeeded();
  await shot("m2-01-report-collapsed", "summary row under the memory slot: status and server byte measurement (not tokens); collapsed by default");

  await page.locator(".summary-disclosure > summary").click();
  const body = await page.locator(".summary-body").textContent();
  const sectionKeys = Object.keys(n.summary.sections).filter((k) => n.summary.sections[k].length);
  check("every native section is shown", (await page.locator(".summary-body > section").count()) >= sectionKeys.length, sectionKeys.join(","));
  check("constraints, TODO, dispute and failed attempt are preserved", ["同数据集", "同硬件", "无实测数据不得编造数值", "在相同数据集、相同硬件下测量延迟", "contested", "失败尝试缺少测量数据"].every((t) => body.includes(t)));
  check("locators shown for entries", body.includes("context_snapshot/prior_progress/records/0/snapshot/unresolved_questions"));
  check("coverage matches the native record counts", body.includes(`覆盖 ${n.summary.covered_records.length} 条原始记录`) && body.includes(`未覆盖 ${n.uncovered_records.length} 条`));
  check("planner binding described without claiming understanding", body.includes("不证明模型理解或采纳"));
  check("categories are described as highlights; originals remain available", body.includes("这些分类只是重点摘录"));
  check("every original record is listed in full", n.sources.every((s) => body.includes(typeof s.value === "string" ? s.value.slice(-20) : "")));
  await shot("m2-02-summary-expanded", "expanded: sections with saved excerpts and locators, coverage counts, honest planner-binding wording");
  await page.locator(".summary-body details").first().locator("summary").click();
  check("original records listed", (await page.locator(".summary-sources > li").count()) === n.sources.length);
  await page.locator(".summary-body details").last().locator("summary").click();
  await page.locator(".summary-sources").scrollIntoViewIfNeeded();
  await shot("m2-03-sources-and-details", "original records (escaped text, locators) and technical details with hashes and measurement method");

  const createsBefore = creates().length;
  const getsBefore = summaryGets().length;
  await page.reload();
  await page.locator(".summary-disclosure").waitFor({ timeout: 15000 });
  await page.waitForTimeout(1500);
  check("refresh re-reads the summary and creates nothing", creates().length === createsBefore && createsBefore === 0 && summaryGets().length > getsBefore,
    JSON.stringify({ creates: creates().length, summaryGets: summaryGets().length }));
  check("summary GET is bounded (no polling loop)", summaryGets().length <= 6, summaryGets().length);
  await shot("m2-04-after-refresh", "after reload: same run and summary recovered by GET; no create or resume POST");
  check("no console errors", errors.length === 0, errors.join(" | "));
} catch (e) {
  check("script completed", false, e?.message ?? e);
  await page.screenshot({ path: `${OUT}/m2-error.png` }).catch(() => {});
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/m2-native-screenshots.json`, redact(JSON.stringify({
  label: "Synthetic history; controlled provider (provider=controlled, fixture_only=true). No paid model calls in this capture; DeepSeek observations are separate (live/).",
  backend: { base_url: ready.base_url, candidate_manifest_sha256: backendManifestSha },
  frontend: { branch: "claude/deepresearch-memory-m1", commit: frontendCommit, origin: APP },
  identity: { subject: claims.sub, token: "not recorded" },
  ids: { runId: ready.run_id, projectId: ready.project_id, sessionId },
  nativeSummary: native ? { http: native.http, status: native.body.status, measurement: native.body.measurement, within_budget: native.body.within_budget,
    planner_input_recorded: native.body.planner_input_recorded, covered: native.body.summary?.covered_records?.length, uncovered: native.body.uncovered_records?.length,
    sources: native.body.sources?.length, summary_sha256: native.body.summary?.summary_sha256 } : null,
  requests: requests.filter((r) => r.method !== "GET"),
  summaryGetCount: summaryGets().length,
  screenshots: shots, checks: results,
}, null, 2)));
console.log(`${results.filter((r) => r.ok).length}/${results.length} checks passed (real native chain, controlled provider)`);
