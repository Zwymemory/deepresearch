// Run with Playwright CLI run-code --filename from the repository root.
// This browser regression stubs every API request; it never calls a model,
// provider, token issuer, or creates a real workflow. Fixtures are synthetic.
async (page) => {
  const webId = "web:tavily:" + "a".repeat(64);
  const otherId = "web:tavily:" + "b".repeat(64);
  const kbId = "ragflow:fixture-document:fixture-chunk";
  const web = {sourceId: webId, kind: "WEB_SEARCH_SNAPSHOT", title: "Python asyncio documentation",
    url: "https://docs.python.org/3/library/asyncio.html", excerpt: "Synthetic search excerpt for the browser fixture. This text is not a live answer or a quality evaluation."};
  const other = {...web, sourceId: otherId, title: "Python documentation index", url: "https://www.python.org/doc/"};
  const kb = {sourceId: kbId, kind: "KNOWLEDGE_CHUNK", title: "architecture.md",
    excerpt: "Synthetic knowledge excerpt. The document belongs to the knowledge base and has no public web link."};
  const unsafe = ["javascript:alert(1)", "data:text/html,unsafe", "file:///private/fixture",
    "//invalid.test/document", "https://user:pass@invalid.test/document", "https://invalid.test/\ndocument"];
  const cases = [
    {name: "web", ids: [webId], details: [web], links: [web.url]},
    {name: "multiple-web", ids: [webId, otherId], details: [other, web], links: [web.url, other.url]},
    {name: "body-marker-target", ids: [webId, otherId], details: [other, web], links: [web.url, other.url]},
    {name: "noncanonical-marker", ids: [webId], details: [web], links: [web.url]},
    {name: "knowledge", ids: [kbId], details: [kb], links: []},
    {name: "mixed", ids: [webId, kbId], details: [kb, web], links: [web.url]},
    {name: "missing-metadata", ids: [webId], details: [], links: [], missing: true},
    {name: "wrong-source-id", ids: [webId], details: [other], links: [], missing: true},
    {name: "duplicate-metadata", ids: [webId], details: [web, {...web, title: "Conflicting title"}], links: [], missing: true},
    {name: "unsafe-url", ids: unsafe.map((_, i) => "web:tavily:" + String(i).repeat(64)),
      details: unsafe.map((url, i) => ({...web, sourceId: "web:tavily:" + String(i).repeat(64), url})), links: []},
    {name: "unverified-history", ids: [webId], details: [web], contract: "NONE", links: [], missing: true},
    {name: "escaped-text", ids: [webId], details: [{...web, title: "<img src=x onerror=alert(1)>",
      excerpt: "<script>window.citationXss=true</script>"}], links: [web.url]},
    {name: "legacy-details", ids: [webId], details: [web], links: [web.url], legacy: true},
    {name: "sse-reconnect", ids: [webId, kbId], details: [kb, web], links: [web.url], reconnect: true},
    ...[
      ["DIFY_MODEL_OUTPUT_TRUNCATED", "模型输出被截断"],
      ["DIFY_MODEL_OUTPUT_EMPTY", "模型最终输出为空"],
      ["DIFY_MODEL_OUTPUT_INVALID", "模型输出格式无效"],
      ["DIFY_MODEL_PROVIDER_ERROR", "模型服务调用失败"],
      ["CLAIM_EVIDENCE_INVALID", "论断证据无法对应"],
      ["CLAIM_SUPPORT_INSUFFICIENT", "证据不足"]
    ].map(([code, label]) => ({name: code, code, label, ids: [], details: [], links: [],
      status: code === "CLAIM_SUPPORT_INSUFFICIENT" ? "INSUFFICIENT_EVIDENCE" : "FAILED"}))
  ];
  const report = [];
  let active, completed = false, streamCount = 0, traffic = [];
  function assert(condition, message) { if (!condition) throw new Error(message); }
  function finalResponse() {
    const statements = active.ids.map((_, i) => "Synthetic statement [来源" + (i + 1) + "]");
    return {answer: (active.name === "noncanonical-marker" ? "Synthetic [source 1] [来源 1] [来源1]" :
        statements.join(active.name === "body-marker-target" ? " " : "\n\n")) +
        (active.name === "body-marker-target" ? " **fixture tail**" : ""),
      citations: active.ids, citationDetails: active.details, citationContract: active.contract || "INDEXED_V1"};
  }
  function view() {
    return {runId: "fixture-" + active.name, status: completed ? (active.status || "SUCCEEDED") : "DIFY_DISPATCHING",
      stage: completed ? "TERMINAL" : "DIFY_DISPATCHING", progress: completed ? 100 : 40,
      errorCode: active.code || null, usage: {}, trace: [], finalResponse: completed ? finalResponse() : null};
  }
  await page.unrouteAll({behavior: "wait"});
  await page.route("**/*", async route => {
    const request = route.request(), url = request.url(), path = url.split("?")[0];
    if (!url.startsWith("http://127.0.0.1:8080/")) return route.abort();
    if (path.endsWith("/demo.html")) return route.fulfill({path: "src/main/resources/static/demo.html", contentType: "text/html"});
    const method = request.method();
    traffic.push({method, path: path.slice("http://127.0.0.1:8080".length), cursor: await request.headerValue("last-event-id")});
    if (path.endsWith("/api/ping")) return route.fulfill({json: {status: "ok"}});
    if (path.endsWith("/api/research/tools/capabilities")) return route.fulfill({json: {webSearch: {configured: true}}});
    if (method === "POST" && path.endsWith("/api/research/agent") && active.legacy) {
      completed = true;
      return route.fulfill({json: {...view(), ...finalResponse(), finished: true, rounds: 1}});
    }
    if (method === "POST" && path.endsWith("/api/research/workflows") && !active.legacy) {
      completed = !active.reconnect;
      return route.fulfill({json: view()});
    }
    if (method === "GET" && path.endsWith("/events") && active.reconnect) {
      streamCount += 1;
      completed = streamCount >= 2;
      const number = completed ? 12 : 11, runId = "fixture-" + active.name;
      const event = {eventId: number, role: "SYSTEM", type: completed ? "SUCCEEDED" : "STAGE_CHANGED",
        payload: {stage: completed ? "TERMINAL" : "DIFY_DISPATCHING"}};
      return route.fulfill({contentType: "text/event-stream",
        body: "id: " + runId + ":" + number + "\nevent: message\ndata: " + JSON.stringify(event) + "\n\n"});
    }
    if (method === "GET" && path.endsWith("/fixture-" + active.name)) return route.fulfill({json: view()});
    throw new Error("Unexpected request in isolated fixture: " + method + " " + path);
  });
  // A named browser session may already contain a previous fixture run.
  await page.evaluate(() => {try {localStorage.clear(); sessionStorage.clear();} catch (ignored) {}});
  await page.goto("http://127.0.0.1:8080/demo.html?citation-fixture");
  for (const fixture of cases) {
    active = fixture; completed = false; streamCount = 0; traffic = [];
    await page.evaluate(() => {localStorage.clear(); sessionStorage.clear();
      sessionStorage.setItem("deepresearch.console.sessionToken", "fixture-only-token");});
    await page.reload();
    if (fixture.legacy) await page.getByRole("tab", {name: "Single Agent", exact: true}).click();
    await page.locator("#questionInput").fill("Synthetic citation UI fixture");
    await page.getByRole("button", {name: fixture.legacy ? "运行基线" : "开始研究", exact: true}).click();
    if (fixture.code) {
      await page.getByText(fixture.label, {exact: true}).first().waitFor();
      const message = await page.locator("#answerText").textContent();
      assert(message && !message.includes(fixture.code), fixture.name + ": technical error as main explanation");
      assert(await page.locator(".citation-chip,#answerText .answer-citation").count() === 0, fixture.name + ": failed candidate published citations");
      await page.reload();
      await page.getByText(fixture.label, {exact: true}).first().waitFor();
      assert(await page.locator("#answerText").textContent() === message, fixture.name + ": failure explanation changed on restore");
      assert(traffic.filter(r => r.method === "POST").length === 1, fixture.name + ": failure restore recreated task");
      report.push({case: fixture.name, passed: true, citations: 0, links: 0, createPosts: 1, refreshed: true, safeReasonRendered: true});
      continue;
    }
    await page.locator(".citation-chip").first().waitFor({timeout: 15000});
    async function inspect() {
      return page.locator(".citation-chip").evaluateAll(cards => cards.map(card => ({
        id: card.id, heading: card.querySelector(".citation-heading").textContent,
        title: card.querySelector(".citation-title").textContent,
        kind: card.querySelector(".citation-kind").textContent,
        preview: card.querySelector(".citation-excerpt")?.textContent || "",
        missing: card.querySelector(".citation-missing")?.textContent || "",
        href: card.querySelector("a")?.href || "", target: card.querySelector("a")?.target || "",
        rel: card.querySelector("a")?.rel || "", collapsed: !card.querySelector("details").open,
        sourceId: card.querySelector(".citation-source-id").textContent,
        activeContent: !!card.querySelector("img,script,iframe,object")
      })));
    }
    const before = await inspect();
    assert(before.length === fixture.ids.length, fixture.name + ": citation count changed");
    assert(JSON.stringify(before.map(c => c.href).filter(Boolean)) === JSON.stringify(fixture.links), fixture.name + ": unsafe or wrong link");
    before.forEach((card, i) => {
      assert(card.id === "citation-" + (i + 1) && card.heading.startsWith("[来源" + (i + 1) + "]"), fixture.name + ": wrong numbering");
      assert(card.sourceId === "来源 ID：" + fixture.ids[i], fixture.name + ": source ID changed");
      assert(card.collapsed && !card.heading.includes(fixture.ids[i]) && card.preview.length <= 281, fixture.name + ": ID leaked into main view");
      assert(!card.activeContent, fixture.name + ": unescaped source text");
      if (card.href) assert(card.target === "_blank" && card.rel === "noopener noreferrer", fixture.name + ": unsafe new tab");
      if (fixture.missing) assert(card.missing && card.title === "来源信息不足", fixture.name + ": guessed metadata");
      if (fixture.ids[i] === kbId) assert(card.kind === "知识库文档" && card.title === kb.title && !card.href, fixture.name + ": invented KB web link");
    });
    if (fixture.name === "multiple-web") assert(before[0].title === web.title && before[1].title === other.title, "Matched metadata by position");
    if (fixture.name === "noncanonical-marker") {
      assert(await page.locator("#answerText a.answer-citation").count() === 1 &&
        await page.locator("#answerText .answer-citation.unverified").count() === 2,
        "Marker syntax outside backend INDEXED_V1 contract was marked verified");
    }
    if (fixture.contract !== "NONE") {
      await page.getByRole("tab", {name: "原始 JSON", exact: true}).click();
      await page.evaluate(() => {
        window.citationScrollTargets = [];
        const original = Element.prototype.scrollIntoView;
        Element.prototype.scrollIntoView = function (...args) {
          window.citationScrollTargets.push(this.id); return original.apply(this, args);
        };
      });
      const markers = page.locator("#answerText a.answer-citation");
      for (let i = 0; i < await markers.count(); i++) {
        const target = (await markers.nth(i).getAttribute("href")).slice(1);
        await page.evaluate(() => {window.citationScrollTargets = [];});
        await markers.nth(i).click();
        await page.waitForFunction(id => window.citationScrollTargets.includes(id), target, {timeout: 1500});
      }
      assert(await page.locator("#citationsPanel").isVisible(), fixture.name + ": body marker failed to reveal card");
    } else assert(await page.locator("#answerText a.answer-citation").count() === 0, "Unverified marker became a link");
    if (!fixture.legacy) {
      await page.reload();
      await page.locator(".citation-chip").first().waitFor();
      assert(JSON.stringify(await inspect()) === JSON.stringify(before), fixture.name + ": refresh lost citation details");
    }
    const postCount = traffic.filter(r => r.method === "POST").length;
    assert(postCount === 1, fixture.name + ": refresh/reconnect repeated create POST");
    if (fixture.reconnect) {
      const streams = traffic.filter(r => r.path.endsWith("/events"));
      assert(streams.length === 2 && streams[1].cursor === "fixture-sse-reconnect:11", "SSE did not resume from persisted cursor");
    }
    if (["mixed", "missing-metadata"].includes(fixture.name)) {
      await page.locator("#citationsPanel").screenshot({path: "output/playwright/citation-fixture-" + fixture.name + ".png"});
    }
    report.push({case: fixture.name, passed: true, citations: before.length, links: fixture.links.length,
      createPosts: postCount, refreshed: !fixture.legacy, sseStreams: streamCount});
  }
  await page.evaluate(result => {window.citationUiReport = result;}, {fixtureOnly: true, cases: report});
}
