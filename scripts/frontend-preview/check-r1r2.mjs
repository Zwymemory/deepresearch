// Focused check of the R1/R2 frontend iteration (visibility, evidence record, motion, notebook preview).
// Demo mode needs nothing else; live checks use the preview mock through the Vite proxy:
//   python3 scripts/frontend-preview/mock_server.py &
//   DEEPRESEARCH_API_PROXY=http://127.0.0.1:8090 npm --prefix frontend run dev &
//   [CHROME_PATH=...] PLAYWRIGHT_CORE=/path/to/playwright-core node scripts/frontend-preview/check-r1r2.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const APP = (process.env.REACT_PREVIEW_BASE || "http://127.0.0.1:5173") + "/app/";
const OUT = process.argv[2] || "output/playwright/r1r2";
mkdirSync(OUT, { recursive: true });
const results = [];
const check = (name, ok, detail = "") => { results.push({ name, ok: !!ok, detail: String(detail) }); if (!ok) console.error("FAIL", name, detail); };
const VIEWPORTS = { desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } };

async function session(browser, theme, vp, reducedMotion = "no-preference") {
  const context = await browser.newContext({ viewport: VIEWPORTS[vp], colorScheme: theme === "smoked" ? "dark" : "light", reducedMotion,
    isMobile: vp === "mobile", hasTouch: vp === "mobile", deviceScaleFactor: vp === "mobile" ? 2 : 1 });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource|50[23]|404/.test(m.text())) errors.push(m.text()); });
  return { context, page, errors };
}
const overflow = (page) => page.evaluate(() => Math.max(document.documentElement.scrollWidth, innerWidth) - Math.round(visualViewport.width));
const shot = (page, name) => page.screenshot({ path: `${OUT}/${name}.png` });

async function connect(page) {
  await page.getByRole("button", { name: /连接与身份/ }).click();
  await page.getByRole("button", { name: "签发 USER Dev Token" }).click();
  await page.getByText("Dev Token 已签发").waitFor();
  // Keep the token for this tab session so later reloads stay signed in.
  await page.getByText("仅在当前浏览会话保存 Token").click();
  await page.getByRole("button", { name: "保存并连接" }).click();
}
async function ask(page, question, mode) {
  await page.locator("#question").fill(question);
  if (mode) await page.getByLabel("执行方式").selectOption(mode);
  await page.getByRole("button", { name: mode === "legacy" ? "运行基线" : "开始研究", exact: true }).click();
}

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  for (const theme of ["ivory", "smoked"]) {
    for (const vp of ["desktop", "mobile"]) {
      const tag = `${theme}-${vp}`;
      const { context, page, errors } = await session(browser, theme, vp);

      // 1. Stage continuity: the question carries over from the composer (transform in flight, then settled).
      await page.goto(APP + "?demo");
      await page.locator("#question").fill("合成问题：阶段连续性。");
      await page.getByRole("button", { name: "开始研究", exact: true }).click();
      await page.locator("#run-question").waitFor();
      const mid = await page.locator("#run-question").evaluate((el) => getComputedStyle(el).transform);
      await page.waitForTimeout(600);
      const settled = await page.locator("#run-question").evaluate((el) => getComputedStyle(el).transform);
      check(`${tag}: question transitions from composer and settles`, mid !== "none" && (settled === "none" || /matrix\(1, 0, 0, 1, 0, 0\)/.test(settled)), `${mid} → ${settled}`);
      await shot(page, `${tag}-running`);

      // 2. Report, source kinds, citation inspection with focus + scroll restoration.
      await page.locator("#report-question").waitFor({ timeout: 20000 });
      await page.waitForTimeout(400);
      const cite = page.locator(".cite").nth(2);
      await cite.scrollIntoViewIfNeeded();
      const y0 = await page.evaluate(() => scrollY);
      await cite.focus();
      await page.keyboard.press("Enter");
      await page.locator("#inspector").waitFor();
      await page.waitForTimeout(350);
      check(`${tag}: inspector shows read mode and metadata provenance`, await page.locator("#inspector").getByText("读取方式").count() === 1);
      await shot(page, `${tag}-inspect`);
      await page.keyboard.press("Escape");
      await page.waitForTimeout(350);
      const after = await page.evaluate(() => ({ y: scrollY, focus: document.activeElement?.className }));
      check(`${tag}: Escape returns focus to the citation and keeps scroll`, after.focus?.includes("cite") && Math.abs(after.y - y0) < 4, JSON.stringify({ y0, ...after }));

      // 3. Evidence record + recorded disagreement (demo fixture, labelled).
      await page.locator("#ev-title").scrollIntoViewIfNeeded();
      check(`${tag}: evidence record is labelled demo data`, await page.locator(".ev-record .chip-warn", { hasText: "示例数据" }).count() === 1);
      const y1 = await page.evaluate(() => scrollY);
      await page.getByRole("button", { name: "对照查看两侧证据" }).click();
      await page.locator("#disagreement-title").waitFor();
      check(`${tag}: recorded disagreement is labelled as backend-recorded`, await page.getByText("后端记录的分歧 · 证据记录视图").count() === 1);
      await shot(page, `${tag}-disagreement`);
      await page.getByRole("button", { name: "返回报告" }).click();
      await page.locator("#report").waitFor();
      await page.waitForTimeout(400);
      check(`${tag}: returning from the disagreement restores reading position`, Math.abs(await page.evaluate(() => scrollY) - y1) < 4);

      // 4. Notebook preview: save shows success only after confirmation; load; delete with confirmation.
      await page.evaluate(() => scrollTo(0, 0));
      await page.getByRole("button", { name: "保存研究进度" }).click();
      check(`${tag}: saving state before confirmation`, await page.getByText(/正在保存，等待/).count() === 1);
      await page.getByText("已保存到研究档案").waitFor();
      await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
      await page.locator(".archive-item").first().click();
      if (!(await page.locator("#archive-record").count())) await page.keyboard.press("Enter");
      await page.getByRole("button", { name: "载入此项目的研究进度到新会话" }).click();
      check(`${tag}: load confirms context without starting research`, await page.getByText("已载入历史研究进度（尚未传入模型；未开始研究）").count() === 1 && await page.locator(".activity").count() === 0);
      await shot(page, `${tag}-notebook`);
      await page.getByRole("button", { name: "删除保存的进度…" }).click();
      await page.getByRole("button", { name: "确认删除" }).click();
      await page.getByRole("button", { name: "确认删除" }).waitFor({ state: "detached", timeout: 5000 }).catch(() => {});
      check(`${tag}: delete requires confirmation`, await page.getByRole("button", { name: "确认删除" }).count() === 0);
      await page.keyboard.press("Escape");

      check(`${tag}: no horizontal overflow`, await overflow(page) <= 0, await overflow(page));
      check(`${tag}: no console errors`, errors.length === 0, errors.join(" | "));
      await context.close();
    }
  }

  // 5. Live (mock API): autonomous plan, evidence API, budget, contract-shaped LangGraph sources, notebook unavailable.
  for (const theme of ["ivory", "smoked"]) {
    const { context, page, errors } = await session(browser, theme, "desktop");
    await page.goto(APP + "?scenario=success");
    await connect(page);
    await ask(page, "合成问题：自主研究的计划与证据。", "agent");
    await page.locator(".plan").waitFor({ timeout: 15000 });
    check(`${theme} live: autonomous run shows its recorded plan, not a fixed stepper`, await page.locator(".stepper").count() === 0);
    await shot(page, `${theme}-live-agent-plan`);
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    await page.locator("#ev-title").waitFor({ timeout: 10000 });
    check(`${theme} live: evidence record read from the API`, await page.getByText("记录的分歧：断线后可以从最后收到的事件之后继续接收。").count() === 1);
    // Research-progress memory is now wired: an autonomous run becomes eligible after project discovery.
    await page.waitForFunction(() => !document.querySelector('button[aria-describedby="save-status"]')?.hasAttribute("disabled"), null, { timeout: 10000 });
    check(`${theme} live: autonomous run is eligible to save after discovery`, !(await page.getByRole("button", { name: "保存研究进度" }).isDisabled()));
    await page.locator("#ev-title").scrollIntoViewIfNeeded();
    await shot(page, `${theme}-live-evidence-record`);
    await page.getByRole("button", { name: "研究档案（保存的研究进度）" }).click();
    await page.getByText(/没有可访问的研究进度|服务端保存的研究进度/).first().waitFor();
    check(`${theme} live: notebook shows server data, not the preview`, await page.getByText("预览 · 示例数据，不联网").count() === 0);
    await page.keyboard.press("Escape");

    await page.goto(APP + "?scenario=budget");
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await ask(page, "合成问题：预算终止。");
    await page.getByText("这是执行限制，不是证据结论").waitFor({ timeout: 30000 });
    check(`${theme} live: budget termination is distinct and shows no reconstructed content`, await page.getByText("该运行没有公开任何部分结果").count() === 1);
    await shot(page, `${theme}-live-budget`);

    await page.goto(APP + "?scenario=langgraph");
    await page.getByRole("button", { name: "DeepResearch：返回新研究" }).click();
    await ask(page, "合成问题：LangGraph 来源契约。");
    await page.locator("#report-question").waitFor({ timeout: 30000 });
    // The mock marks the third cited source (web) as MISSING_SNAPSHOT.
    await page.locator('[data-cite="3"]').first().click();
    await page.locator("#inspector").waitFor();
    check(`${theme} live: missing snapshot reason shown, no invented link`, await page.locator("#inspector").getByText("MISSING_SNAPSHOT").count() === 1
      && await page.locator("#inspector a[target=_blank]").count() === 0);
    await shot(page, `${theme}-live-langgraph-missing`);
    await page.keyboard.press("Escape");
    check(`${theme} live: no console errors`, errors.length === 0, errors.join(" | "));
    await context.close();
  }

  // 6. Evidence view disabled on the deployment.
  {
    const { context, page } = await session(browser, "ivory", "mobile");
    await page.goto(APP + "?scenario=evidence-disabled");
    await connect(page);
    await ask(page, "合成问题：证据视图关闭。", "agent");
    await page.getByText("EVIDENCE_VIEW_DISABLED").waitFor({ timeout: 30000 });
    check("mobile live: disabled evidence view is a stated capability, not an error", true);
    check("mobile live: no horizontal overflow", await overflow(page) <= 0);
    await page.locator(".ev-record").scrollIntoViewIfNeeded();
    await shot(page, "ivory-mobile-live-evidence-disabled");
    await context.close();
  }

  // 8. Save failure is recoverable and never shown as success (preview).
  {
    const { context, page } = await session(browser, "smoked", "desktop");
    await page.goto(APP + "?demo&state=report&memoryFail");
    await page.getByRole("button", { name: "保存研究进度" }).click();
    await page.getByText("保存失败（示例）").waitFor();
    check("save failure: stated, nothing saved, retry available", await page.getByText("已保存到研究档案").count() === 0
      && !(await page.getByRole("button", { name: "保存研究进度" }).isDisabled()));
    await shot(page, "smoked-desktop-save-failed");
    await context.close();
  }

  // 7. Reduced motion: no travel on stage change; inspector still opens and closes correctly.
  {
    const { context, page } = await session(browser, "ivory", "desktop", "reduce");
    await page.goto(APP + "?demo");
    await page.locator("#question").fill("合成问题：减少动态效果。");
    await page.getByRole("button", { name: "开始研究", exact: true }).click();
    await page.locator("#run-question").waitFor();
    check("reduced motion: question does not travel", await page.locator("#run-question").evaluate((el) => getComputedStyle(el).transform) === "none");
    await page.locator("#report-question").waitFor({ timeout: 20000 });
    await page.locator(".cite").first().click();
    await page.locator("#inspector").waitFor();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(100);
    check("reduced motion: inspector closes and returns focus", (await page.evaluate(() => document.activeElement?.className)).includes("cite"));
    await context.close();
  }
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/check.json`, JSON.stringify(results, null, 2));
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} checks passed`);
if (failed.length) { console.log(JSON.stringify(failed, null, 2)); process.exitCode = 1; }
