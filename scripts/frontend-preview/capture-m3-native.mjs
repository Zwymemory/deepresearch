// Real-chain M3 page capture against an isolated native demo (controlled provider, synthetic history):
//  1. automatic SAVED status on the continuation run's report, compared with the native GET;
//  2. a Chinese correction note submitted by a real PATCH, compared with the native snapshot and
//     still shown after a page refresh;
//  3. a failed automatic save that leaves the original report visible.
// The viewer token is read from the private handoff file and is never logged, saved or shown.
//   PLAYWRIGHT_CORE=... CHROME_PATH=... node scripts/frontend-preview/capture-m3-native.mjs <handoffDir> <outDir> <frontendCommit> [demoDir]
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const [handoff, OUT, frontendCommit = "uncommitted", demoDir = "demo-native"] = process.argv.slice(2);
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5174") + "/app/";
mkdirSync(OUT, { recursive: true });
const ready = JSON.parse(readFileSync(`${handoff}/${demoDir}/demo-ready.json`, "utf8"));
const secret = JSON.parse(readFileSync(`${handoff}/${demoDir}/demo-private.json`, "utf8"));
const auth = secret.viewer_token.startsWith("Bearer ") ? secret.viewer_token : "Bearer " + secret.viewer_token;
const bare = auth.slice(7);
const claims = JSON.parse(Buffer.from(bare.split(".")[1], "base64url").toString());
const manifestBytes = readFileSync(`${handoff}/candidate-manifest.json`);
const candidateSha = JSON.parse(manifestBytes.toString("utf8")).candidate_sha256 ?? null;
const manifestFileSha = createHash("sha256").update(manifestBytes).digest("hex");
const redact = (t) => String(t).split(bare).join("[redacted]");
const results = [];
const shots = [];
const observations = {};
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: redact(detail) }); console.log(ok ? "PASS" : "FAIL", name, ok ? "" : redact(detail)); };

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: "light" });
const requests = [];
const errors = [];
context.on("request", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: u.pathname, body: r.method() === "PATCH" ? r.postData() : undefined }); });
const page = await context.newPage();
page.on("pageerror", (e) => errors.push(redact(e)));
page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(redact(m.text())); });
const writes = () => requests.filter((r) => ["POST", "PUT", "DELETE"].includes(r.method) || r.method === "PATCH");
const api = (path) => page.evaluate(async ([a, p]) => { const r = await fetch(p, { headers: { Authorization: a } }); return { http: r.status, body: await r.json().catch(() => null) }; }, [auth, path]);
async function shot(name, runId, behaviour) {
  await page.screenshot({ path: `${OUT}/${name}.png` });
  shots.push({ file: `${name}.png`, url: page.url(), capturedAt: new Date().toISOString(), runId, projectId: ready.project_id, verified: behaviour });
}
async function openRun(runId, question) {
  await page.evaluate(([id, q]) => sessionStorage.setItem("deepresearch.console.currentRun", JSON.stringify({ mode: "agent", runId: id, question: q })), [runId, question]);
  await page.reload();
  await page.locator("#report").waitFor({ timeout: 30000 });
}
async function questionOf(runId, projectId) {
  const snap = projectId ? await api(`/api/research/projects/${encodeURIComponent(projectId)}/progress/runs/${encodeURIComponent(runId)}`) : null;
  if (snap?.http === 200 && typeof snap.body?.current_question === "string") return snap.body.current_question;
  const summary = await api(`/api/research/agents/${encodeURIComponent(runId)}/context-summary`);
  const goal = (summary.body?.summary?.sections?.goals ?? []).find((g) => g.locator === "question");
  return typeof goal?.value === "string" ? goal.value : "";
}
async function openArchiveRecord(runId) {
  await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  await page.locator(".archive-item").first().waitFor({ timeout: 15000 });
  const count = await page.locator(".archive-item").count();
  for (let i = 0; i < count; i++) {
    await page.locator(".archive-item").nth(i).click();
    if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
    await page.locator("#archive-record").waitFor();
    if ((await page.locator("#archive-record .rec-cover").textContent()).includes(runId.slice(0, 8))) return true;
    await page.keyboard.press("Escape");
    await page.locator("#archive-record").waitFor({ state: "detached" });
  }
  return false;
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

  // 1. Automatic SAVED on the continuation run.
  const run = ready.run_id;
  const question = await questionOf(run, ready.project_id);
  await openRun(run, question);
  const saveView = await api(`/api/research/agents/${encodeURIComponent(run)}/progress-save`);
  observations.saved = { http: saveView.http, status: saveView.body?.status, save_origin: saveView.body?.save_origin ?? null, saved: saveView.body?.saved, error_code: saveView.body?.error_code };
  await page.locator(".auto-save").waitFor({ timeout: 15000 });
  const saveText = await page.locator(".auto-save").textContent();
  check("native progress-save is SAVED with automatic origin", saveView.http === 200 && saveView.body?.status === "SAVED" && saveView.body?.save_origin === "automatic", JSON.stringify(observations.saved));
  check("report shows the automatic save in Chinese", saveText.includes("已自动保存已完成事项与待办，可在研究档案查看"), saveText);
  check("report heading is the run question", question.length > 0 && (await page.locator("#report-question").textContent()) === question, question);
  await page.locator(".auto-save").scrollIntoViewIfNeeded();
  await shot("m3-01-report-autosaved", run, "automatic SAVED status on the original report, matching the native GET");

  // 2. Correction note via real PATCH; reflected in the native snapshot and after refresh.
  check("continuation snapshot found in the archive", await openArchiveRecord(run));
  const before = await api(`/api/research/projects/${encodeURIComponent(ready.project_id)}/progress/runs/${encodeURIComponent(run)}`);
  const oldNote = before.body?.user_correction ?? "";
  const addition = "补充：延迟测量仍须在机器 C 上按同一条件重做，旧结论不作为本轮证据。";
  const newNote = oldNote ? `${oldNote}\n${addition}` : addition;
  await page.getByRole("button", { name: /纠正说明…$/ }).click();
  await page.locator("#correction-note").fill(newNote);
  await page.getByRole("button", { name: "保存纠正说明" }).click();
  await page.getByRole("button", { name: "修改纠正说明…" }).waitFor({ timeout: 15000 });
  const patch = requests.filter((r) => r.method === "PATCH").at(-1);
  const after = await api(`/api/research/projects/${encodeURIComponent(ready.project_id)}/progress/runs/${encodeURIComponent(run)}`);
  observations.correction = { patchPath: patch?.path ?? null, patchBody: patch?.body ? JSON.parse(patch.body) : null, nativeNote: after.body?.user_correction ?? null, previousNoteKept: oldNote ? (after.body?.user_correction ?? "").startsWith(oldNote) : null };
  check("one PATCH with only the note", requests.filter((r) => r.method === "PATCH").length === 1 && JSON.stringify(observations.correction.patchBody) === JSON.stringify({ note: newNote }));
  check("native snapshot holds the corrected note", after.http === 200 && after.body?.user_correction === newNote);
  check("previous note (machine C) preserved", !oldNote || observations.correction.previousNoteKept === true, oldNote);
  check("page shows the corrected note", (await page.locator("#archive-record .correction").textContent()).includes(addition));
  await page.locator("#archive-record .correction").scrollIntoViewIfNeeded();
  await shot("m3-02-correction-saved", run, "Chinese correction note saved by a real PATCH; shown as a user annotation, not a verified fact");
  await page.keyboard.press("Escape");
  await page.reload();
  await page.locator("#report").waitFor({ timeout: 30000 });
  check("after refresh the archive still shows the corrected note", await openArchiveRecord(run) && (await page.locator("#archive-record .correction").textContent()).includes(addition));
  await page.locator("#archive-record .correction").scrollIntoViewIfNeeded();
  await shot("m3-03-correction-after-refresh", run, "after a page refresh the corrected note is read back from the server");
  await page.keyboard.press("Escape");

  // 3. Failed automatic save keeps the original report.
  const failedRun = ready.failed_save_run_id;
  if (failedRun) {
    const failQuestion = await questionOf(failedRun, null);
    await openRun(failedRun, failQuestion);
    const failView = await api(`/api/research/agents/${encodeURIComponent(failedRun)}/progress-save`);
    observations.failed = { http: failView.http, status: failView.body?.status, error_code: failView.body?.error_code, save_origin: failView.body?.save_origin ?? null };
    await page.locator(".auto-save").waitFor({ timeout: 15000 });
    const failText = await page.locator(".auto-save p").first().textContent();
    check("native progress-save is FAILED", failView.http === 200 && failView.body?.status === "FAILED", JSON.stringify(observations.failed));
    check("failure explained in Chinese, raw code only in details", failText.includes("自动保存失败") && !failText.includes("PROGRESS_") && (await page.locator(".auto-save details").count()) === 1, failText);
    check("original report still visible", (await page.locator("#report .outcome").count()) === 1);
    await page.locator(".auto-save details summary").click();
    await page.locator(".auto-save").scrollIntoViewIfNeeded();
    await shot("m3-04-report-save-failed", failedRun, "automatic save FAILED with a Chinese reason; original report kept; manual save remains the explicit retry");
  }
  check("browser initiated no automatic save (no PUT) and no create", !requests.some((r) => r.method === "PUT") && !requests.some((r) => r.method === "POST" && /\/api\/research\/(agents|workflows)$/.test(r.path)));
  check("no console errors", errors.length === 0, errors.join(" | "));
} catch (e) {
  check("script completed", false, e?.message ?? e);
  await page.screenshot({ path: `${OUT}/m3-error.png` }).catch(() => {});
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/m3-native-screenshots.json`, redact(JSON.stringify({
  label: "Synthetic history; controlled provider (provider=controlled, fixture_only=true). No paid model calls.",
  backend: { base_url: ready.base_url, candidate_sha256: candidateSha, candidate_manifest_file_sha256: manifestFileSha },
  frontend: { branch: "claude/deepresearch-memory-m1", commit: frontendCommit, origin: APP },
  identity: { subject: claims.sub, token: "not recorded" },
  ids: { projectId: ready.project_id, runId: ready.run_id, failedSaveRunId: ready.failed_save_run_id ?? null, updatedSourceRunId: ready.updated_run_id ?? null },
  nativeObservations: observations,
  writes: writes().map((r) => ({ method: r.method, path: r.path })),
  screenshots: shots, checks: results,
}, null, 2)));
console.log(`${results.filter((r) => r.ok).length}/${results.length} checks passed (real native chain, controlled provider)`);
