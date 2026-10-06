// Browser check of memory M4 cross-question recall in the UI: the default-on toggle and its request
// field, and the read-only recall disclosure for USED / DISABLED / EMPTY / UNAVAILABLE (after a
// correction, never redisplayed from cache). MOCK VERIFICATION ONLY — synthetic preview API; the
// mock's keyword selection is a stand-in, not the backend algorithm.
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/check-m4.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/m4";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); if (!ok) console.error("FAIL", name, detail); };

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const requests = [];
  const errors = [];
  page.on("request", (r) => { const u = new URL(r.url()); if (u.pathname.startsWith("/api/")) requests.push({ method: r.method(), path: u.pathname, body: r.postData() || "" }); });
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|4\d\d|50\d/.test(m.text())) errors.push(m.text()); });
  const creates = () => requests.filter((r) => r.method === "POST" && /^\/api\/research\/(agents|workflows)$/.test(r.path));
  const shot = (name) => page.screenshot({ path: `${OUT}/${name}.png` });

  await page.goto(APP + "?scenario=success");
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "User", exact: true }).fill("m4-check");
  await page.getByLabel("Bearer Token").fill(`local-preview-m4-${Date.now().toString(36)}`);
  if (!(await page.getByRole("checkbox", { name: /记住/ }).isChecked().catch(() => true))) await page.getByRole("checkbox", { name: /记住/ }).check();
  await page.getByRole("button", { name: "保存并连接" }).click();

  async function agentRun(question, recall = true) {
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await page.locator("#question").fill(question);
    await page.getByLabel("执行方式").selectOption("agent");
    const toggle = page.getByRole("checkbox", { name: /参考相关的历史研究/ });
    if ((await toggle.isChecked()) !== recall) await toggle.click();
    await page.getByRole("button", { name: "开始研究", exact: true }).click();
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    return JSON.parse(creates().at(-1).body);
  }

  // Seed: an old study, saved automatically.
  const seed = await agentRun("Kafka v1.0 的检索延迟对比");
  check("toggle defaults on and is sent with the create", seed.memoryRecall === true);
  await page.getByText("已自动保存已完成事项与待办，可在研究档案查看。").waitFor({ timeout: 15000 });

  // 1. Related question: USED with reasons, version warning, dispute, deduplicated sources.
  await agentRun("Kafka v2.0 的检索延迟如何核查？");
  const line = page.locator(".recall-disclosure > summary");
  await line.waitFor({ timeout: 15000 });
  await page.waitForFunction(() => document.querySelector(".recall-disclosure > summary")?.textContent?.includes("进入本次规划请求"), null, { timeout: 15000 });
  check("USED: states it entered the planning request, as a clue not evidence", (await line.textContent()).includes("不是已核验的证据"));
  await line.click();
  const body = await page.locator(".recall-disclosure .summary-body").textContent();
  check("USED: names the old study and keyword reason", body.includes("Kafka v1.0 的检索延迟对比") && body.includes("“kafka”") && body.includes("“延迟”"));
  check("USED: version difference and unknown time/conditions are visible", body.includes("涉及版本：v1.0") && body.includes("旧研究涉及的版本与本问题不同") && body.includes("时间：未知") && body.includes("条件：未知"));
  check("USED: dispute stays a dispute", body.includes("有争议"));
  check("USED: duplicated native sources counted once", body.includes("去重后 1 个") && (await page.locator(".recall-record").first().locator("li", { hasText: "预览来源" }).count()) === 1);
  check("USED: no claim that memory is verified evidence", !body.includes("已核验的事实") && body.includes("只作调查线索"));
  await page.locator(".recall-disclosure").scrollIntoViewIfNeeded();
  await shot("m4-used");
  const usedRunQuestion = "Kafka v2.0 的检索延迟如何核查？";

  // 2. Recall switched off: DISABLED, and the request says so.
  const off = await agentRun("Kafka 检索延迟（关闭参考）", false);
  check("toggle off is sent as memoryRecall:false", off.memoryRecall === false);
  await page.getByText("本次运行关闭了历史研究参考。").waitFor({ timeout: 10000 });
  check("DISABLED: says recall was disabled", true);

  // 3. Unrelated question: EMPTY.
  await agentRun("猫通常一天睡多久？");
  await page.getByText("没有找到与本问题相关的历史研究").waitFor({ timeout: 10000 });
  check("EMPTY: says no relevant memory", true);
  await shot("m4-empty");

  // 4. Correct the recalled snapshot, then reopen the USED run: UNAVAILABLE, no old content redisplayed.
  await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
  const seedItem = page.locator(".archive-item", { hasText: "Kafka v1.0 的检索延迟对比" }).first();
  await seedItem.waitFor({ timeout: 10000 });
  await seedItem.click();
  if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
  if (!(await page.locator("#archive-record").textContent()).includes("Kafka v1.0 的检索延迟对比")) await page.keyboard.press("Enter");
  await page.getByRole("button", { name: /纠正说明…$/ }).click();
  await page.locator("#correction-note").fill("v1.0 的结论不适用于 v2.0。");
  await page.getByRole("button", { name: "保存纠正说明" }).click();
  await page.getByRole("button", { name: "修改纠正说明…" }).waitFor();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "最近的研究（本机）" }).click();
  await page.getByRole("dialog").getByText(usedRunQuestion).first().click();
  await page.locator("#report").waitFor({ timeout: 15000 });
  await page.getByText("引用的历史研究已被修改、删除或不可访问").waitFor({ timeout: 15000 });
  const report = await page.locator("#report").textContent();
  check("UNAVAILABLE after correction: asks to start fresh", report.includes("请重新开始研究"));
  check("UNAVAILABLE: no old recalled content redisplayed", !report.includes("Kafka v1.0 的检索延迟对比") && (await page.locator(".recall-record").count()) === 0);
  await page.locator(".summary-row", { hasText: "历史研究参考" }).scrollIntoViewIfNeeded();
  await shot("m4-unavailable-after-correction");

  // 5. Ordinary workflow research never sends memoryRecall.
  await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
  await page.locator("#question").fill("普通工作流问题");
  await page.getByLabel("执行方式").selectOption("workflow");
  check("workflow mode hides the recall toggle", await page.getByRole("checkbox", { name: /参考相关的历史研究/ }).count() === 0);
  await page.getByRole("button", { name: "开始研究", exact: true }).click();
  await page.locator("#run-question").waitFor({ timeout: 15000 });
  check("workflow create has no memoryRecall", !("memoryRecall" in JSON.parse(creates().at(-1).body)));
  check("no console errors", errors.length === 0, errors.join(" | "));
  await context.close();
} finally {
  await browser.close();
}
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed (mock UI verification only, not native acceptance)`);
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
if (passed !== results.length) process.exit(1);
