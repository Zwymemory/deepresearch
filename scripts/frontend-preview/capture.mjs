// 以本地预览服务器（scripts/frontend-preview/mock_server.py）为后端，截取两种主题、
// 桌面/移动尺寸下的关键状态，并检查控制台错误与横向溢出。全部 API 为合成数据。
//
//   python3 scripts/frontend-preview/mock_server.py &
//   [CHROME_PATH=/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome] PLAYWRIGHT_CORE=/path/to/node_modules/playwright-core node scripts/frontend-preview/capture.mjs [outDir]
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const BASE = process.env.PREVIEW_BASE || "http://127.0.0.1:8090";
const OUT = process.argv[2] || "output/playwright/frontend-preview";
mkdirSync(OUT, { recursive: true });

const VIEWPORTS = { desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } };
const report = [];

async function newPage(browser, theme, viewport, reducedMotion = "no-preference") {
  const context = await browser.newContext({
    viewport: VIEWPORTS[viewport], colorScheme: theme, reducedMotion,
    deviceScaleFactor: viewport === "mobile" ? 2 : 1, isMobile: viewport === "mobile", hasTouch: viewport === "mobile"
  });
  // 预置本地演示身份：预览服务器只校验 Bearer 前缀，这不是任何真实服务的凭据。
  await context.addInitScript(() => {
    if (sessionStorage.getItem("__previewInit")) return;
    sessionStorage.setItem("__previewInit", "1");
    localStorage.setItem("deepresearch.console.auth", JSON.stringify({ baseUrl: "", tenantId: "demo-tenant", userId: "demo-user" }));
    sessionStorage.setItem("deepresearch.console.sessionToken", "local-preview-token-not-a-real-credential");
  });
  const page = await context.newPage();
  const errors = [];
  page.on("console", (msg) => { if (msg.type() === "error") errors.push(msg.text()); });
  page.on("pageerror", (err) => errors.push(String(err)));
  return { context, page, errors };
}

async function check(page, name, errors) {
  const metrics = await page.evaluate(() => ({
    overflowX: document.documentElement.scrollWidth - window.innerWidth,
    theme: document.documentElement.dataset.theme,
    runState: document.body.dataset.runState
  }));
  report.push({ name, ...metrics, errors: errors.filter((e) => !/502|Bad Gateway/.test(e)) });
}

async function shot(page, name, fullPage = false) {
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage });
}

async function ask(page, { scenario = "success", web = true, mode = null }) {
  await page.goto(`${BASE}/demo.html?scenario=${scenario}`);
  await page.waitForSelector("#serviceDot.online");
  if (mode) await page.getByRole("tab", { name: mode, exact: true }).click();
  await page.locator("#questionInput").fill("解释本项目如何通过 checkpoint、claim fencing 与 SSE 重放实现崩溃恢复。");
  if (web && mode !== "Single Agent") await page.locator('input[value="web_search"]').check({ force: true });
  await page.getByRole("button", { name: mode === "Single Agent" ? "运行基线" : "开始研究", exact: true }).click();
}

const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
try {
  for (const theme of ["light", "dark"]) {
    for (const vp of ["desktop", "mobile"]) {
      const tag = `${theme}-${vp}`;
      let { context, page, errors } = await newPage(browser, theme, vp);

      await page.goto(`${BASE}/demo.html`);
      await page.waitForSelector("#serviceDot.online");
      await shot(page, `${tag}-01-empty`);
      await check(page, `${tag} empty`, errors);

      await ask(page, { scenario: "success" });
      await page.waitForTimeout(4200);
      await shot(page, `${tag}-02-running`);
      await check(page, `${tag} running`, errors);

      await page.waitForSelector('body[data-run-state="success"]', { timeout: 20000 });
      await page.waitForTimeout(600);
      await page.locator("#report").scrollIntoViewIfNeeded();
      await shot(page, `${tag}-03-success`);
      if (vp === "desktop") {
        await page.locator("#answerText a.answer-citation").first().hover();
        await shot(page, `${tag}-04-citation-preview`);
        await page.locator("#answerText a.answer-citation").nth(2).click();
        await page.waitForTimeout(250);
        await shot(page, `${tag}-05-citation-target`);
      }
      await shot(page, `${tag}-06-success-full`, true);
      await check(page, `${tag} success`, errors);
      await context.close();

      for (const scenario of ["insufficient", "failed"]) {
        ({ context, page, errors } = await newPage(browser, theme, vp));
        await ask(page, { scenario });
        await page.waitForSelector(`body[data-run-state="${scenario === "failed" ? "error" : "warning"}"]`, { timeout: 25000 });
        await page.locator("#report").scrollIntoViewIfNeeded();
        await shot(page, `${tag}-07-${scenario}`);
        await check(page, `${tag} ${scenario}`, errors);
        await context.close();
      }

      ({ context, page, errors } = await newPage(browser, theme, vp));
      await ask(page, { scenario: "slow" });
      await page.waitForTimeout(3500);
      await page.getByRole("button", { name: "取消当前任务" }).click();
      await page.waitForSelector('body[data-run-state="cancelled"]', { timeout: 10000 });
      await shot(page, `${tag}-08-cancelled`);
      await check(page, `${tag} cancelled`, errors);
      await context.close();

      ({ context, page, errors } = await newPage(browser, theme, vp));
      await ask(page, { scenario: "disconnect" });
      await page.waitForSelector("#connectionBanner:not([hidden])", { timeout: 15000 });
      await shot(page, `${tag}-09-reconnecting`);
      await page.waitForSelector('body[data-run-state="success"]', { timeout: 25000 });
      const reconnects = await page.locator("#reconnectMetric").textContent();
      report.push({ name: `${tag} disconnect recovered`, reconnects });
      await check(page, `${tag} disconnect`, errors);
      await context.close();

      ({ context, page, errors } = await newPage(browser, theme, vp));
      await ask(page, { scenario: "unknown" });
      await page.waitForSelector("#unknownCard:not([hidden])", { timeout: 10000 });
      await shot(page, `${tag}-10-unknown-create`);
      await page.getByRole("button", { name: "使用原请求安全重试" }).click();
      await page.waitForSelector('body[data-run-state="success"]', { timeout: 25000 });
      await check(page, `${tag} unknown→retry`, errors);
      await context.close();

      if (vp === "desktop") {
        ({ context, page, errors } = await newPage(browser, theme, vp));
        await page.goto(`${BASE}/demo.html`);
        await page.waitForSelector("#serviceDot.online");
        await page.locator("#drawerToggle").click();
        await shot(page, `${tag}-11-drawer-collapsed`);
        await page.locator("#authOpenButton").click();
        await shot(page, `${tag}-12-auth-dialog`);
        await check(page, `${tag} dialog`, errors);
        await context.close();
      }
    }
  }

  // 减少动态效果：确认动画被压缩、主题切换不触发 View Transition 圆形扩散。
  const { context, page, errors } = await newPage(browser, "light", "desktop", "reduce");
  await page.goto(`${BASE}/demo.html`);
  const reduced = await page.evaluate(() => {
    const orb = getComputedStyle(document.querySelector(".empty-art .orb"));
    return { orbAnimationDuration: orb.animationDuration, transitionDuration: getComputedStyle(document.querySelector(".btn")).transitionDuration };
  });
  await page.locator("#themeToggle").click();
  await page.waitForTimeout(100);
  reduced.themeAfterToggle = await page.evaluate(() => [document.documentElement.dataset.theme, localStorage.getItem("deepresearch.console.theme")]);
  await page.reload();
  reduced.themeAfterReload = await page.evaluate(() => document.documentElement.dataset.theme);
  report.push({ name: "reduced-motion", ...reduced, errors });
  await context.close();
} finally {
  await browser.close();
}
writeFileSync(`${OUT}/report.json`, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
