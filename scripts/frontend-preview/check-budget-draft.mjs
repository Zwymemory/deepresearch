// Budget-stop draft UI check. Part A uses the separate mock (synthetic budget-draft scenario, UI-only).
// Part B reads real runs on the live stack (read-only, demo USER via the dialog's dev-token button;
// the token is never read or printed): the native budget run without a draft keeps the previous
// behaviour, and the real source list is not labelled as sample data.
//   MOCK_BASE=http://127.0.0.1:5175 LIVE_BASE=http://127.0.0.1:5173 node scripts/frontend-preview/check-budget-draft.mjs <outDir>
import { createRequire } from "node:module";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const OUT = process.argv[2] || "output/playwright/budget-draft";
const MOCK = (process.env.MOCK_BASE || "http://127.0.0.1:5175") + "/app/";
const LIVE = (process.env.LIVE_BASE || "http://127.0.0.1:5173") + "/app/";
const BUDGET_RUN = "wf-50124710-0812-450d-8c1d-68375f2cdde0", CITED_RUN = "wf-5c0b351c-e9c1-43b7-b62f-e7929f87e676";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); console.log(ok ? "PASS" : "FAIL", name, ok ? "" : detail); };
const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});

async function session(viewport, theme = "light") {
  const mobile = viewport.width < 500;
  const context = await browser.newContext({ viewport, colorScheme: theme, deviceScaleFactor: mobile ? 2 : 1, isMobile: mobile, hasTouch: mobile, acceptDownloads: true });
  const page = await context.newPage();
  const errors = [], writes = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("request", (r) => { const p = new URL(r.url()).pathname; if (r.method() !== "GET" && p.startsWith("/api/research")) writes.push(r.method() + " " + p); });
  return { context, page, errors, writes };
}
async function identity(page, tenant, user, devToken) {
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("textbox", { name: "Tenant", exact: true }).fill(tenant);
  await page.getByRole("textbox", { name: "User", exact: true }).fill(user);
  if (devToken) { await page.getByRole("button", { name: /签发 USER Dev Token/ }).click(); await page.getByText("Dev Token 已签发").waitFor({ timeout: 10000 }); }
  else await page.getByLabel("Bearer Token").fill(`local-preview-draft-${Date.now().toString(36)}`);
  const remember = page.getByRole("checkbox", { name: /记住|保存/ }).first();
  if (await remember.count() && !(await remember.isChecked())) await remember.check();
  await page.getByRole("button", { name: "保存并连接" }).click();
  await page.waitForTimeout(400);
}
const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - innerWidth);

try {
  // A. Mock: draft present (synthetic).
  for (const [w, h] of [[1440, 900], [319, 760]]) {
    const tag = `mock-${w}`;
    const { context, page, errors } = await session({ width: w, height: h });
    await page.goto(MOCK + "?scenario=budget-draft");
    await identity(page, "demo-tenant", "draft-check", false);
    await page.locator("#question").fill("（合成）Kafka 延迟资料整理");
    await page.getByLabel("执行方式").selectOption("agent");
    await page.locator('[data-scope="kb"]').click();
    await page.getByRole("button", { name: "开始研究", exact: true }).click();
    await page.locator(".research-draft").waitFor({ timeout: 30000 });
    const report = await page.locator("#report").innerText();
    const draft = await page.locator(".research-draft").innerText();
    check(`${tag}: budget stop stays visible, not a completed report`, report.includes("因预算上限终止") && !report.includes("研究完成"));
    check(`${tag}: draft labelled unpublished and unchecked`, draft.includes("阶段性资料草稿") && draft.includes("未发布") && draft.includes("尚未核查"));
    check(`${tag}: original vs search-snippet vs knowledge labels`, draft.includes("已读取原文（网页）") && draft.includes("仅搜索摘要") && draft.includes("已读取原文（知识库片段）"));
    check(`${tag}: recorded conclusion with status and unknown scope, not final`, draft.includes("已记录的核查结论（尚未最终发布）") && draft.includes("有争议") && draft.includes("版本未确定") && draft.includes("有效时间未确定"));
    check(`${tag}: omission note and pending task`, draft.includes("另有 2 条资料未列出") && draft.includes("在同数据集、同硬件下测量端到端延迟"));
    check(`${tag}: excerpts rendered as text, never HTML`, (await page.locator(".research-draft b").count()) === 0);
    check(`${tag}: draft sources are not report citations`, (await page.getByRole("button", { name: /全部来源/ }).count()) === 0 && (await page.locator(".prose .cite").count()) === 0);
    check(`${tag}: source links open safely`, (await page.locator(".research-draft a[target=_blank][rel*=noopener]").count()) === 2);
    if (w === 1440) {
      const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "下载草稿（.md）" }).click()]);
      const path = `${OUT}/${download.suggestedFilename()}`;
      await download.saveAs(path);
      const served = await page.evaluate(async () => {
        const id = JSON.parse(sessionStorage.getItem("deepresearch.console.currentRun") || "{}").runId;
        const auth = "Bearer " + sessionStorage.getItem("deepresearch.console.sessionToken");
        return (await (await fetch("/api/research/workflows/" + id, { headers: { Authorization: auth } })).json()).finalResponse.researchDraft.markdown;
      });
      check(`${tag}: download is exactly the server markdown (.md)`, readFileSync(path, "utf8") === served && download.suggestedFilename().endsWith(".md"), download.suggestedFilename());
    }
    check(`${tag}: no horizontal overflow`, await overflow(page) <= 0, await overflow(page));
    check(`${tag}: no page errors`, errors.length === 0, errors.join(" | "));
    await page.locator(".research-draft").scrollIntoViewIfNeeded();
    await page.screenshot({ path: `${OUT}/${tag}-draft.png` });
    await context.close();
  }

  // B. Live (read-only): native budget run without a draft; real source list label.
  for (const [w, h] of [[1440, 900], [319, 760]]) {
    const tag = `live-${w}`;
    const { context, page, errors, writes } = await session({ width: w, height: h });
    await page.goto(LIVE);
    await identity(page, "demo-tenant", "demo-user", true);
    await page.evaluate((id) => sessionStorage.setItem("deepresearch.console.currentRun", JSON.stringify({ mode: "agent", runId: id, question: "" })), BUDGET_RUN);
    await page.reload();
    await page.getByText("因预算上限终止").first().waitFor({ timeout: 30000 });
    const hasDraft = await page.evaluate(async (id) => {
      const auth = "Bearer " + sessionStorage.getItem("deepresearch.console.sessionToken");
      return !!(await (await fetch("/api/research/workflows/" + id, { headers: { Authorization: auth } })).json()).finalResponse?.researchDraft;
    }, BUDGET_RUN);
    check(`${tag}: native budget run renders ${hasDraft ? "its draft" : "the previous budget-stop view (no draft returned yet)"}`, hasDraft ? (await page.locator(".research-draft").count()) === 1 : (await page.locator(".research-draft").count()) === 0);
    await page.screenshot({ path: `${OUT}/${tag}-budget-native.png` });
    if (w === 1440) {
      await page.evaluate((id) => sessionStorage.setItem("deepresearch.console.currentRun", JSON.stringify({ mode: "agent", runId: id, question: "" })), CITED_RUN);
      await page.reload();
      await page.getByRole("button", { name: /全部来源/ }).click();
      const desc = await page.getByRole("dialog").innerText();
      check(`${tag}: real source list is not labelled as sample data`, !desc.includes("示例数据"), desc.slice(0, 120));
      await page.keyboard.press("Escape");
    }
    check(`${tag}: no writes`, writes.length === 0, writes.join(","));
    check(`${tag}: no horizontal overflow`, await overflow(page) <= 0, await overflow(page));
    check(`${tag}: no page errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }
} catch (e) {
  check("script completed", false, e?.message ?? e);
} finally {
  await browser.close();
}
const passed = results.filter((r) => r.ok).length;
console.log(`${passed}/${results.length} checks passed (A: mock UI only; B: live read-only)`);
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
if (passed !== results.length) process.exit(1);
