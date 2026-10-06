// Real-chain M4 page capture against an isolated native demo (real HTTP/SQL/production adapter;
// controlled synthetic history, model and retrieval transports). No mocked API or interception.
//  1. Kafka related question (recall on): USED + planner record, version difference, dispute, unknowns.
//  2. Unrelated question: EMPTY.
//  3. Related question with native original sources: USED with deduplicated source_refs.
//  4. Delete the disposable Kafka source in the archive: the Kafka run turns UNAVAILABLE with no old
//     content — never shown during the re-read nor after a refresh.
// The viewer token is read privately and never logged, saved or shown.
//   PLAYWRIGHT_CORE=... CHROME_PATH=... node scripts/frontend-preview/capture-m4-native.mjs <handoffDir> <outDir> <frontendCommit> [demoDir]
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const [handoff, OUT, frontendCommit = "uncommitted", demoDir = "demo-final"] = process.argv.slice(2);
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5174") + "/app/";
mkdirSync(OUT, { recursive: true });
const ready = JSON.parse(readFileSync(`${handoff}/${demoDir}/demo-ready.json`, "utf8"));
const cfg = JSON.parse(readFileSync(`${handoff}/${demoDir}/demo-private.json`, "utf8"));
const auth = cfg.viewer_token.startsWith("Bearer ") ? cfg.viewer_token : "Bearer " + cfg.viewer_token;
const bare = auth.slice(7);
const claims = JSON.parse(Buffer.from(bare.split(".")[1], "base64url").toString());
const manifestBytes = readFileSync(`${handoff}/candidate-manifest.json`);
const candidateSha = JSON.parse(manifestBytes.toString("utf8")).candidate_sha256 ?? null;
const manifestFileSha = createHash("sha256").update(manifestBytes).digest("hex");
const redact = (t) => String(t).split(bare).join("[redacted]");
const results = [], shots = [], native = {};
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: redact(detail) }); console.log(ok ? "PASS" : "FAIL", name, ok ? "" : redact(detail)); };

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "light" });
const requests = [], errors = [];
context.on("request", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: u.pathname, body: r.method() === "POST" && /\/api\/research\/agents$/.test(u.pathname) ? r.postData() : undefined }); });
const page = await context.newPage();
page.on("pageerror", (e) => errors.push(redact(e)));
page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(redact(m.text())); });
const api = (path) => page.evaluate(async ([a, p]) => { const r = await fetch(p, { headers: { Authorization: a } }); return { http: r.status, body: await r.json().catch(() => null) }; }, [auth, path]);
const recallOf = (runId) => api(`/api/research/agents/${encodeURIComponent(runId)}/memory-recall`);
const currentRunId = () => page.evaluate(() => JSON.parse(sessionStorage.getItem("deepresearch.console.currentRun") || "{}").runId ?? "");
async function shot(name, runId, behaviour) {
  await page.screenshot({ path: `${OUT}/${name}.png` });
  shots.push({ file: `${name}.png`, url: page.url(), capturedAt: new Date().toISOString(), runId, verified: behaviour });
}
const summarize = (b) => b ? { status: b.status, planner_input_recorded: b.planner_input_recorded, trusted_as_evidence: b.trusted_as_evidence, records: (b.records ?? []).length,
  matched_terms: (b.records ?? []).map((r) => r.reason?.matched_terms), cautions: (b.records ?? []).map((r) => r.applicability?.cautions),
  mentioned_versions: (b.records ?? []).map((r) => r.applicability?.mentioned_versions), source_refs: (b.records ?? []).map((r) => (r.source_refs ?? []).length),
  distinct_source_keys: (b.records ?? []).map((r) => new Set((r.source_refs ?? []).map((s) => s.source_key)).size), selection: b.selection } : null;

async function ask(question) {
  await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
  await page.locator("#question").fill(question);
  await page.getByLabel("执行方式").selectOption("agent");
  await page.locator('[data-scope="kb"]').click();          // this demo has no web search configured
  const toggle = page.getByRole("checkbox", { name: /参考相关的历史研究/ });
  if (!(await toggle.isChecked())) await toggle.click();
  await page.getByRole("button", { name: "开始研究", exact: true }).click();
  await page.locator("#run-question").or(page.locator("#report-question")).first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(500);
  return currentRunId();
}
async function waitNative(runId, want, ms = 120000) {
  const end = Date.now() + ms; let last = null;
  while (Date.now() < end) { last = await recallOf(runId); if (want(last.body)) return last; await page.waitForTimeout(2000); }
  return last;
}

try {
  await page.goto(APP);
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "User", exact: true }).fill(String(claims.sub));
  await page.getByLabel("Bearer Token").fill(auth);
  const remember = page.getByRole("checkbox");
  if (!(await remember.isChecked())) await remember.check();
  await page.getByRole("button", { name: "保存并连接" }).click();
  await page.waitForTimeout(400);

  // 1. Kafka related question.
  const kafkaRun = await ask(cfg.question);
  const sent = JSON.parse(requests.filter((r) => r.body).at(-1).body);
  check("create sends memoryRecall:true by default, no memory content", sent.memoryRecall === true && !("researchProjectId" in sent) && Object.keys(sent).sort().join(",") === "memoryRecall,question,requestedTools");
  const k = await waitNative(kafkaRun, (b) => b?.status === "USED" && b?.planner_input_recorded === true);
  native.kafka = { runId: kafkaRun, http: k.http, ...summarize(k.body) };
  check("native Kafka recall USED with planner record", k.http === 200 && k.body?.status === "USED" && k.body?.planner_input_recorded === true && k.body.records.length > 0, JSON.stringify(native.kafka));
  await page.locator("#report-question").waitFor({ timeout: 120000 });
  await page.waitForFunction(() => document.querySelector(".recall-disclosure > summary")?.textContent?.includes("进入本次规划请求"), null, { timeout: 30000 });
  await page.locator(".recall-disclosure > summary").click();
  const kText = await page.locator(".recall-record").first().innerText();
  const oldGoal = k.body.records[0].snapshot?.original_goal ?? "";
  check("UI names the old Kafka study and keyword reason", kText.includes(oldGoal) && kText.includes("相关原因"), oldGoal);
  check("UI shows version difference and a dispute", /版本/.test(kText) && kText.includes("有争议"), kText.slice(0, 400));
  check("UI shows unknown time and conditions", kText.includes("时间：未知，需要重新核查") && kText.includes("条件：未知，需要重新核查"));
  check("UI user view has no raw caution codes or method id", !/[A-Z]{3,}_[A-Z_]+|keyword-baseline/.test(kText), kText);
  await page.locator(".recall-disclosure").scrollIntoViewIfNeeded();
  await shot("m4-01-kafka-used", kafkaRun, "USED with planner record; old study, keyword reason, version difference, dispute, unknown time/conditions");

  // 2. Unrelated question: EMPTY.
  const emptyRun = await ask("香蕉种植土壤与浇水要求");
  const e = await waitNative(emptyRun, (b) => b?.status === "EMPTY", 60000);
  native.empty = { runId: emptyRun, http: e.http, ...summarize(e.body) };
  check("native unrelated recall EMPTY", e.http === 200 && e.body?.status === "EMPTY" && (e.body.records ?? []).length === 0, JSON.stringify(native.empty));
  await page.getByText("没有找到与本问题相关的历史研究").waitFor({ timeout: 30000 });
  check("UI says no related memory", true);
  await page.locator(".recall-line").scrollIntoViewIfNeeded();
  await shot("m4-02-unrelated-empty", emptyRun, "EMPTY: no related past research referenced");

  // 3. Related question with native original sources.
  const srcRun = await ask(cfg.native_source_question);
  const s = await waitNative(srcRun, (b) => b?.status === "USED" && b?.planner_input_recorded === true);
  native.nativeSource = { runId: srcRun, http: s.http, ...summarize(s.body) };
  const distinct = native.nativeSource.distinct_source_keys?.[0] ?? 0;
  check("native source recall USED with nonempty source_refs", s.http === 200 && s.body?.status === "USED" && distinct > 0, JSON.stringify(native.nativeSource));
  await page.locator("#report-question").waitFor({ timeout: 120000 });
  await page.waitForFunction(() => document.querySelector(".recall-disclosure > summary")?.textContent?.includes("进入本次规划请求"), null, { timeout: 30000 });
  await page.locator(".recall-disclosure > summary").click();
  const srcRecord = page.locator(".recall-record", { hasText: "原始来源" }).first();
  const shownSources = await srcRecord.locator("p:has-text('原始来源') + ul > li").count();
  check("UI lists the deduplicated native sources", shownSources === distinct && (await srcRecord.innerText()).includes(`去重后 ${distinct} 个`), `${shownSources} vs ${distinct}`);
  await srcRecord.scrollIntoViewIfNeeded();
  await shot("m4-03-native-source-used", srcRun, "USED with deduplicated native original sources (controlled RAGFlow content, not live internet evidence)");

  // 4. Delete the disposable Kafka source in the archive; the Kafka run becomes UNAVAILABLE.
  await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  await page.locator(".archive-item").first().waitFor({ timeout: 15000 });
  let found = false;
  const n = await page.locator(".archive-item").count();
  for (let i = 0; i < n && !found; i++) {
    await page.locator(".archive-item").nth(i).click();
    if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
    await page.locator("#archive-record").waitFor();
    if ((await page.locator("#archive-record .rec-cover").textContent()).includes(cfg.source_run_id.slice(0, 8))) { found = true; break; }
    await page.keyboard.press("Escape");
    await page.locator("#archive-record").waitFor({ state: "detached" });
  }
  check("disposable Kafka source found in the archive", found);
  await page.getByRole("button", { name: "删除保存的进度…" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await page.locator("#archive-record").waitFor({ state: "detached", timeout: 15000 });
  const deletes = requests.filter((r) => r.method === "DELETE");
  check("one DELETE, of the Kafka source only", deletes.length === 1 && deletes[0].path.endsWith(`/progress/runs/${cfg.source_run_id}`), JSON.stringify(deletes.map((d) => d.path)));
  // Watch the DOM while the Kafka run is reopened: old recalled content must never appear.
  await page.evaluate((goal) => {
    window.__sawOldRecall = false;
    const seen = () => { if (document.querySelector(".recall-record") || (goal && document.querySelector(".summary-row, .recall-disclosure")?.textContent?.includes(goal))) window.__sawOldRecall = true; };
    new MutationObserver(seen).observe(document.body, { subtree: true, childList: true, characterData: true });
  }, oldGoal);
  await page.getByRole("button", { name: "最近的研究（本机）" }).click();
  await page.getByRole("dialog").getByText(cfg.question).first().click();
  await page.locator("#report").waitFor({ timeout: 30000 });
  await page.getByText("引用的历史研究已被修改、删除或不可访问").waitFor({ timeout: 30000 });
  const u = await recallOf(kafkaRun);
  native.kafkaAfterDelete = { runId: kafkaRun, http: u.http, ...summarize(u.body) };
  check("native Kafka recall UNAVAILABLE with records=[]", u.http === 200 && u.body?.status === "UNAVAILABLE" && (u.body.records ?? []).length === 0, JSON.stringify(native.kafkaAfterDelete));
  check("no old recalled content appeared while re-reading", await page.evaluate(() => window.__sawOldRecall === false));
  await page.locator(".summary-row", { hasText: "历史研究参考" }).scrollIntoViewIfNeeded();
  await shot("m4-04-kafka-unavailable", kafkaRun, "UNAVAILABLE after the Kafka source was deleted in the archive; no old content");
  await page.reload();
  await page.locator("#report").waitFor({ timeout: 30000 });
  await page.getByText("引用的历史研究已被修改、删除或不可访问").waitFor({ timeout: 30000 });
  check("after refresh still UNAVAILABLE with no old content", (await page.locator(".recall-record").count()) === 0 && !(await page.locator("#report").textContent()).includes(oldGoal));
  await page.locator(".summary-row", { hasText: "历史研究参考" }).scrollIntoViewIfNeeded();
  await shot("m4-05-kafka-unavailable-after-refresh", kafkaRun, "after a page refresh: still UNAVAILABLE, no cached old content");
  check("no PUT, no PATCH; DELETE only on the disposable Kafka source", !requests.some((r) => r.method === "PUT" || r.method === "PATCH"));
  check("no console errors", errors.length === 0, errors.join(" | "));
} catch (err) {
  check("script completed", false, err?.message ?? err);
  await page.screenshot({ path: `${OUT}/m4-error.png` }).catch(() => {});
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/m4-native-screenshots.json`, redact(JSON.stringify({
  label: "Real HTTP/SQL/production adapter; controlled synthetic history, model and retrieval transports (provider=controlled, fixture_only=true). No paid calls. Not a quality or cost experiment.",
  backend: { base_url: ready.base_url, candidate_sha256: candidateSha, candidate_manifest_file_sha256: manifestFileSha },
  frontend: { branch: "claude/deepresearch-memory-m1", commit: frontendCommit, origin: APP },
  identity: { subject: claims.sub, token: "not recorded" },
  disposableWrites: requests.filter((r) => r.method !== "GET").map((r) => ({ method: r.method, path: r.path })),
  nativeObservations: native, screenshots: shots, checks: results,
}, null, 2)));
console.log(`${results.filter((r) => r.ok).length}/${results.length} checks passed (real native chain, controlled transports)`);
