/**
 * Loads the real Build with Parameters page in headless Chrome and reports what the enhancer did.
 *
 * HtmlUnit cannot see CSS-cascade bugs (it honours `hidden` regardless of specificity), so this checks the
 * things only a real engine can answer: computed styles, collapse, search, and whether every native control
 * still sits inside the form exactly once.
 *
 * Usage: node scripts/verify-in-browser.mjs <jenkinsUrl> <jobPath> <user> <token>
 */
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const [base, jobPath, user, token] = process.argv.slice(2);
if (!base || !jobPath || !user || !token) {
  console.error("usage: node scripts/verify-in-browser.mjs <url> <jobPath> <user> <token>");
  process.exit(2);
}

import { existsSync } from "node:fs";

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));
if (!CHROME) { console.error("Chrome not found"); process.exit(2); }

const profile = mkdtempSync(join(tmpdir(), "bpe-chrome-"));
const PORT = 9222 + Math.floor(Math.random() * 500);
const chrome = spawn(CHROME, [
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  "--headless=new",
  "--no-first-run",
  "--no-default-browser-check",
  "--disable-extensions",
  "--window-size=1600,1200",
  "about:blank",
], { stdio: "ignore" });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function wsUrl() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      return (await r.json()).webSocketDebuggerUrl;
    } catch { await sleep(250); }
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

const results = [];
const check = (name, pass, detail) => {
  results.push({ name, pass, detail });
  console.log(`${pass ? "  PASS" : "  FAIL"}  ${name}${detail ? `  — ${detail}` : ""}`);
};

try {
  const ws = new WebSocket(await wsUrl());
  await new Promise((r) => ws.addEventListener("open", r));
  const send = client(ws);

  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  const cmd = (m, p) => send(m, p, sessionId);

  await cmd("Page.enable");
  await cmd("Runtime.enable");
  await cmd("Network.enable");
  await cmd("Network.setExtraHTTPHeaders", {
    headers: { Authorization: "Basic " + Buffer.from(`${user}:${token}`).toString("base64") },
  });

  const consoleErrors = [];
  ws.addEventListener("message", (e) => {
    const m = JSON.parse(e.data);
    if (m.method === "Runtime.exceptionThrown") {
      consoleErrors.push(m.params.exceptionDetails.exception?.description ?? "exception");
    }
  });

  const url = `${base}/${jobPath}/build?delay=0sec`;
  console.log(`\nloading ${url}\n`);
  await cmd("Page.navigate", { url });
  await sleep(4000);

  const evaluate = async (expr) => {
    // Promise.resolve lets a step await its own debounce before serialising.
    const r = await cmd("Runtime.evaluate", {
      expression: `Promise.resolve((async () => { ${expr} })()).then(v => JSON.stringify(v))`,
      awaitPromise: true,
      returnByValue: true,
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? "eval failed");
    if (r.result.value === undefined) throw new Error("step returned nothing");
    return JSON.parse(r.result.value);
  };

  console.log("--- enhancement ---");
  const base_ = await evaluate(`
    const root = document.querySelector('.bpe-root');
    return {
      enhanced: !!root,
      pending: document.documentElement.classList.contains('bpe-pending'),
      groups: [...document.querySelectorAll('.bpe-group')].map(s => ({
        id: s.dataset.groupId,
        expanded: s.querySelector('.bpe-group__toggle').getAttribute('aria-expanded'),
        display: getComputedStyle(s.querySelector('.bpe-group__body')).display,
        fields: s.querySelectorAll('.bpe-field').length,
      })),
      fields: document.querySelectorAll('.bpe-field').length,
      nativeParams: document.querySelectorAll('form[name=parameters] [name=parameter]').length,
      nativeInForm: [...document.querySelectorAll('[name=parameter]')].every(p => p.closest('form[name=parameters]')),
      toolbar: !!document.querySelector('.bpe-toolbar__input'),
    };
  `);
  check("the page is enhanced", base_.enhanced);
  check("the loading guard was removed", !base_.pending);
  check("every parameter rendered once", base_.fields === 27 && base_.nativeParams === 27,
    `fields=${base_.fields} nativeContainers=${base_.nativeParams}`);
  check("every native control is still inside the form", base_.nativeInForm);
  check("the toolbar is present", base_.toolbar);
  for (const g of base_.groups) {
    const want = g.id === "advanced" ? "none" : "grid";
    check(`group ${g.id}: ${g.expanded === "true" ? "expanded" : "collapsed"} renders ${want}`,
      g.display === want, `display=${g.display} fields=${g.fields}`);
  }

  console.log("\n--- collapse ---");
  const collapse = await evaluate(`
    const s = document.querySelector('[data-group-id=config]');
    const body = s.querySelector('.bpe-group__body');
    const before = getComputedStyle(body).display;
    s.querySelector('.bpe-group__toggle').click();
    const after = getComputedStyle(body).display;
    s.querySelector('.bpe-group__toggle').click();
    return { before, after, restored: getComputedStyle(body).display };
  `);
  check("clicking the header really hides the body", collapse.before === "grid" && collapse.after === "none",
    `${collapse.before} -> ${collapse.after}`);
  check("clicking again restores it", collapse.restored === "grid");

  console.log("\n--- help panel ---");
  const help = await evaluate(`
    const w = document.querySelector('[data-param=ENGINE_BUILD_MODE]');
    const btn = w.querySelector('.bpe-help-button');
    const panel = w.querySelector('.bpe-help');
    if (!btn || !panel) return { missing: true };
    const before = getComputedStyle(panel).display;
    btn.click();
    return { missing: false, before, after: getComputedStyle(panel).display };
  `);
  check("the help panel opens on click", !help.missing && help.before === "none" && help.after !== "none",
    help.missing ? "no help button" : `${help.before} -> ${help.after}`);

  console.log("\n--- search ---");
  const search = await evaluate(`
    const i = document.querySelector('.bpe-toolbar__input');
    i.value = 'commit';
    i.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(r => setTimeout(r, 400));
    const fields = [...document.querySelectorAll('.bpe-field')];
    return {
      visible: fields.filter(f => getComputedStyle(f).display !== 'none').map(f => f.dataset.param),
      stillInForm: document.querySelectorAll('form[name=parameters] [name=parameter]').length,
      status: document.querySelector('.bpe-toolbar__status')?.textContent ?? '',
    };
  `);
  check("search hides non-matching fields", search.visible.length > 0 && search.visible.length < 27,
    `${search.visible.length} visible: ${search.visible.join(", ")} | ${search.status}`);
  check("search creates no extra inputs", search.stillInForm === 27);

  console.log("\n--- git parameter ---");
  const git = await evaluate(`
    const w = document.querySelector('[data-param=APP_BRANCH]');
    const sel = w.querySelector('select, input[type=text]');
    return {
      inside: !!w, container: !!w.querySelector('.git-parameter-container'),
      control: sel ? sel.tagName.toLowerCase() : null,
      options: sel && sel.tagName === 'SELECT' ? sel.options.length : null,
      nameInput: w.querySelector('input[name=name]')?.value,
    };
  `);
  check("the git parameter moved with its container", git.inside && git.container,
    `control=${git.control} options=${git.options} name=${git.nameInput}`);

  console.log("\n--- submission ---");
  const submit = await evaluate(`
    const set = (p, v) => {
      const w = document.querySelector('[data-param=' + p + ']');
      if (!w) return 'no wrapper';
      const el = w.querySelector('input[name=value], select[name=value], textarea[name=value]');
      if (!el) return 'no control';
      if (el.type === 'checkbox') { el.checked = v; } else { el.value = v; }
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return el.value;
    };
    set('VERSION', '9.9.9-bpe');
    set('SIGN_BATCH_SIZE', '7');
    set('APP_COMMIT_HASH', 'deadbee');
    // Collapse every group first: a value inside a collapsed group must still submit.
    // Clear the search first, so "collapse all" and the form tree see every field.
    const si = document.querySelector('.bpe-toolbar__input');
    si.value = '';
    si.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(r => setTimeout(r, 300));

    document.querySelectorAll('.bpe-toolbar__actions button')[1].click();

    // buildFormTree is what Jenkins itself runs on submit; it writes the payload into the form's
    // hidden json input and returns true. That input is the real contract.
    const form = document.forms.parameters;
    const ok = buildFormTree(form);
    const raw = form.querySelector('input[name=json]')?.value ?? null;
    let list = [];
    try {
      const sent = raw ? JSON.parse(raw) : null;
      list = sent && sent.parameter ? [].concat(sent.parameter) : [];
    } catch (e) { /* reported via rawHead below */ }
    return {
      collapsedAll: [...document.querySelectorAll('.bpe-group__body')]
        .every(b => getComputedStyle(b).display === 'none'),
      treeResult: typeof ok,
      rawHead: raw ? raw.slice(0, 120) : null,
      params: list.map(p => [p.name, p.value]),
    };
  `);
  check("collapse all really hides every group", submit.collapsedAll);
  const sentMap = new Map(submit.params);
  check("the form tree carries every parameter", sentMap.size === 27,
    `${sentMap.size} parameters | buildFormTree -> ${submit.treeResult} | json=${submit.rawHead}`);
  for (const [k, v] of [["VERSION", "9.9.9-bpe"], ["SIGN_BATCH_SIZE", "7"], ["APP_COMMIT_HASH", "deadbee"]]) {
    check(`${k} submits the edited value from a collapsed group`, sentMap.get(k) === v,
      `got ${JSON.stringify(sentMap.get(k))}`);
  }

  check("no uncaught script errors", consoleErrors.length === 0, consoleErrors.join(" | ") || undefined);

  const failed = results.filter((r) => !r.pass);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  if (failed.length) {
    console.log("failed:");
    for (const f of failed) console.log(`  - ${f.name}${f.detail ? `: ${f.detail}` : ""}`);
  }
  ws.close();
  process.exitCode = failed.length ? 1 : 0;
} catch (e) {
  console.error("\nverification error:", e.message);
  process.exitCode = 2;
} finally {
  chrome.kill();
  try { rmSync(profile, { recursive: true, force: true }); } catch {}
}
