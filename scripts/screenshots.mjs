/**
 * Takes the README screenshots (docs/images/*.png, English and Chinese) from a Jenkins started with
 * `mvn hpi:run -Djetty.port=8099`, on a demo job with neutral parameter names.
 *
 * Usage: node scripts/screenshots.mjs [jenkinsUrl]   (default http://localhost:8099/jenkins)
 *
 * The job, its layout and two presets are created through the normal APIs, so running it twice is harmless.
 */
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const base = (process.argv[2] ?? "http://localhost:8099/jenkins").replace(/\/$/, "");
// The throwaway account created by work/init.groovy.d/demo-user.groovy (see the end of CONTRIBUTING.md).
const USER = "demo";
const PASSWORD = "demo";
const JOB = "demo-app";
const OUT = "docs/images";

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].find((p) => existsSync(p));
if (!CHROME) {
  console.error("Chrome not found");
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const param = (cls, name, desc, extra = "") =>
  `<hudson.model.${cls}><name>${name}</name><description>${desc}</description>${extra}</hudson.model.${cls}>`;
const str = (n, d, v) => param("StringParameterDefinition", n, d, `<defaultValue>${v}</defaultValue><trim>true</trim>`);
const bool = (n, d, v) => param("BooleanParameterDefinition", n, d, `<defaultValue>${v}</defaultValue>`);
const choice = (n, d, items) =>
  param("ChoiceParameterDefinition", n, d,
    `<choices class="java.util.Arrays$ArrayList"><a class="string-array">${items.map((i) => `<string>${i}</string>`).join("")}</a></choices>`);
const text = (n, d, v) => param("TextParameterDefinition", n, d, `<defaultValue>${v}</defaultValue><trim>false</trim>`);

const JOB_XML = `<?xml version='1.1' encoding='UTF-8'?>
<project><description>Demo job for the Build Parameter Enhancer screenshots.</description><keepDependencies>false</keepDependencies>
<properties><hudson.model.ParametersDefinitionProperty><parameterDefinitions>
${str("APP_BRANCH", "Branch of the application repository.", "main")}
${str("APP_COMMIT", "Commit to build. Empty means the head of the branch.", "")}
${str("LIB_BRANCH", "Branch of the shared library.", "main")}
${str("LIB_COMMIT", "Commit of the shared library.", "")}
${choice("BUILD_TYPE", "Release builds are optimised; debug builds keep symbols.", ["Release", "Debug"])}
${choice("TARGET_ENV", "Where the build is deployed after it passes.", ["staging", "production", "none"])}
${str("VERSION", "Version written into the package.", "1.4.0")}
${bool("BUILD_API", "Build the API service.", "true")}
${bool("BUILD_WEB", "Build the web front end.", "true")}
${bool("BUILD_WORKER", "Build the background worker.", "false")}
${bool("BUILD_DOCS", "Build the documentation site.", "false")}
${bool("CLEAN_WORKSPACE", "Delete the workspace before building.", "false")}
${bool("SIGN_PACKAGES", "Sign the packages with the release key.", "true")}
${choice("LOG_LEVEL", "Verbosity of the build log.", ["info", "debug", "warn"])}
${text("EXTRA_ARGS", "Extra arguments passed to the build tool, one per line.", "")}
</parameterDefinitions></hudson.model.ParametersDefinitionProperty></properties>
<scm class="hudson.scm.NullSCM"/><canRoam>true</canRoam><disabled>false</disabled>
<blockBuildWhenDownstreamBuilding>false</blockBuildWhenDownstreamBuilding><blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>
<triggers/><concurrentBuild>false</concurrentBuild><builders/><publishers/><buildWrappers/></project>`;

// Labels and help shown on the build page; titles of the generated groups come from the plugin's own rules.
const FIELDS = {
  APP_BRANCH: { en: "Application branch", zh: "应用分支" },
  APP_COMMIT: { en: "Application commit", zh: "应用提交" },
  LIB_BRANCH: { en: "Library branch", zh: "库分支" },
  LIB_COMMIT: { en: "Library commit", zh: "库提交" },
  BUILD_TYPE: { en: "Build type", zh: "构建类型" },
  TARGET_ENV: { en: "Deploy to", zh: "部署到" },
};
// The job's own descriptions are English; the Chinese layout gives each parameter a Chinese one-line summary.
const ZH_SUMMARY = {
  APP_BRANCH: "应用仓库的分支。",
  APP_COMMIT: "要构建的提交，留空表示分支最新提交。",
  LIB_BRANCH: "共享库的分支。",
  LIB_COMMIT: "共享库的提交。",
  BUILD_TYPE: "Release 会优化，Debug 保留调试符号。",
  TARGET_ENV: "构建通过后部署到哪里。",
  VERSION: "写入安装包的版本号。",
  BUILD_API: "构建 API 服务。",
  BUILD_WEB: "构建 Web 前端。",
  BUILD_WORKER: "构建后台任务。",
  BUILD_DOCS: "构建文档站点。",
  CLEAN_WORKSPACE: "构建前清空工作区。",
  SIGN_PACKAGES: "用发布密钥签名安装包。",
  LOG_LEVEL: "构建日志的详细程度。",
  EXTRA_ARGS: "传给构建工具的额外参数，每行一个。",
};

const profile = mkdtempSync(join(tmpdir(), "bpe-shots-"));
const PORT = 9222 + Math.floor(Math.random() * 500);
const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, "--headless=new", "--no-first-run",
  "--no-default-browser-check", "--disable-extensions", "--hide-scrollbars", "about:blank",
], { stdio: "ignore" });

async function wsUrl() {
  for (let i = 0; i < 60; i++) {
    try {
      return (await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()).webSocketDebuggerUrl;
    } catch {
      await sleep(250);
    }
  }
  throw new Error("Chrome did not expose a debugging port");
}

function client(ws) {
  let id = 0;
  const pending = new Map();
  ws.addEventListener("message", (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
    }
  });
  return (method, params = {}, sessionId) =>
    new Promise((resolve, reject) => {
      const msg = { id: ++id, method, params };
      if (sessionId) msg.sessionId = sessionId;
      pending.set(msg.id, { resolve, reject });
      ws.send(JSON.stringify(msg));
    });
}

try {
  const ws = new WebSocket(await wsUrl());
  await new Promise((r) => ws.addEventListener("open", r));
  const send = client(ws);
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  const cmd = (m, p) => send(m, p, sessionId);
  await cmd("Page.enable");
  await cmd("Network.enable");

  const evaluate = async (expr) => {
    const r = await cmd("Runtime.evaluate", {
      expression: `Promise.resolve((async () => { ${expr} })()).then(v => JSON.stringify(v ?? null))`,
      awaitPromise: true,
      returnByValue: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? "eval failed");
    return JSON.parse(r.result.value);
  };
  const open = async (path, lang) => {
    await cmd("Network.setExtraHTTPHeaders", { headers: { "Accept-Language": lang === "zh" ? "zh-CN,zh" : "en-US,en" } });
    await cmd("Emulation.setLocaleOverride", { locale: lang === "zh" ? "zh-CN" : "en-US" }).catch(() => {});
    await cmd("Page.navigate", { url: base + path });
    await sleep(3500);
  };
  // POST from the page itself, so Jenkins' session and crumb apply.
  const post = (path, body, json = true) => evaluate(`
    const h = document.head.dataset;
    const headers = { [h.crumbHeader]: h.crumbValue, "Content-Type": ${json ? '"application/json"' : '"application/xml"'} };
    const r = await fetch(${JSON.stringify(base)} + ${JSON.stringify(path)}, { method: "POST", headers, body: ${JSON.stringify(json ? JSON.stringify(body) : body)} });
    return { status: r.status, body: (await r.text()).slice(0, 300) };
  `);
  const shot = async (name, width, height, clipSelector, pad = 0) => {
    await cmd("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
    await sleep(600);
    if (clipSelector) {
      // Size the window to the content so the sticky Build bar sits right under the form, not at the window bottom.
      const h = await evaluate(`return document.documentElement.scrollHeight;`);
      await cmd("Emulation.setDeviceMetricsOverride", { width, height: Math.min(h, height), deviceScaleFactor: 1, mobile: false });
      await sleep(400);
    }
    let clip;
    if (clipSelector) {
      clip = await evaluate(`const r = document.querySelector(${JSON.stringify(clipSelector)}).getBoundingClientRect();
        const sticker = document.querySelector("#bottom-sticker");
        const bottom = Math.max(r.bottom, sticker ? sticker.getBoundingClientRect().bottom : 0);
        return { x: Math.max(0, r.x - ${pad}), y: Math.max(0, r.y - 16), width: Math.min(${width}, r.width + 16 + ${pad}), height: bottom - Math.max(0, r.y - 16) + 16, scale: 1 };`);
    }
    const { data } = await cmd("Page.captureScreenshot", { format: "png", ...(clip ? { clip } : {}) });
    mkdirSync(OUT, { recursive: true });
    writeFileSync(join(OUT, name), Buffer.from(data, "base64"));
    console.log(`  wrote ${OUT}/${name}`);
  };

  // ---- sign in (personal presets need a user), then set up the demo job
  await open("/login", "en");
  await evaluate(`
    document.querySelector("input[name=j_username]").value = ${JSON.stringify(USER)};
    document.querySelector("input[name=j_password]").value = ${JSON.stringify(PASSWORD)};
    document.querySelector("form[name=login]").submit();`);
  await sleep(3000);
  await open("/", "en");
  const exists = await evaluate(`return (await fetch(${JSON.stringify(`${base}/job/${JOB}/api/json`)})).status === 200;`);
  if (!exists) {
    console.log("creating", JOB, await post(`/createItem?name=${JOB}`, JOB_XML, false));
  }

  for (const lang of ["en", "zh"]) {
    const suffix = lang === "en" ? "" : "-zh";
    console.log(`\n--- ${lang}`);
    // Generate in this language so the default group titles are translated, then label a few fields.
    await open(`/job/${JOB}/build-parameter-enhancer/`, lang);
    const layout = (await evaluate(`
      const h = document.head.dataset;
      const r = await fetch("autoGenerate", { method: "POST", headers: { [h.crumbHeader]: h.crumbValue, "Content-Type": "application/json" }, body: "{}" });
      return (await r.json()).layout;
    `));
    for (const [name, label] of Object.entries(FIELDS)) {
      layout.fields[name] = { ...(layout.fields[name] ?? {}), label: label[lang] };
    }
    for (const [name, summary] of Object.entries(ZH_SUMMARY)) {
      const f = layout.fields[name] ?? {};
      layout.fields[name] = { ...f, shortDescription: lang === "zh" ? summary : null };
    }
    console.log("  publish", (await post(`/job/${JOB}/build-parameter-enhancer/publish`, { layout })).status);

    // Presets: one shared and one personal, so both icons show in the selector.
    const presetsUrl = `/job/${JOB}/bpe-presets`;
    const list = await evaluate(`return (await (await fetch(${JSON.stringify(base + presetsUrl + "/list")})).json()).presets ?? [];`);
    for (const p of list) {
      await post(`${presetsUrl}/delete`, { presetId: p.id, isGlobal: p.isGlobal });
    }
    const values = { BUILD_TYPE: "Release", TARGET_ENV: "production", BUILD_WORKER: "true" };
    await post(`${presetsUrl}/save`, { name: lang === "en" ? "Production release" : "生产发布", description: "", isGlobal: true, values });
    await post(`${presetsUrl}/save`, { name: lang === "en" ? "My debug build" : "我的调试构建", description: "", isGlobal: false, values: { BUILD_TYPE: "Debug", TARGET_ENV: "none" } });

    // Build page
    await open(`/job/${JOB}/build?delay=0sec`, lang);
    await shot(`build-page${suffix}.png`, 1280, 2000, "#main-panel");

    // Save-as-preset dialog
    await evaluate(`[...document.querySelectorAll("#bottom-sticker button")].find(b => b.textContent.trim() === ${JSON.stringify(lang === "en" ? "Save as preset" : "保存为预设")}).click();`);
    await sleep(500);
    await shot(`presets${suffix}.png`, 1280, 900);
    await evaluate(`document.querySelector("#bpe-preset-dialog").close();`);

    // Designer with a parameter selected
    await open(`/job/${JOB}/build-parameter-enhancer/`, lang);
    await evaluate(`[...document.querySelectorAll(".bpe-field-row")].find(r => r.textContent.includes("BUILD_TYPE"))?.click();`);
    await sleep(400);
    await shot(`designer${suffix}.png`, 1600, 1000, "#bpe-designer", 16);
  }
} finally {
  chrome.kill();
}
