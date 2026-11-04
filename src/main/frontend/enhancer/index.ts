import type { RenderConfig } from "../common/types";
import { enhance } from "./render";
import { isFailure, scan } from "./scan";
import { buildToolbar } from "./toolbar";
import { setStrings, t } from "../common/i18n";
import "../common/select.css";
import "./enhancer.css";

/**
 * Entry point of the progressive enhancement of Jenkins' Build with Parameters page.
 *
 * Failure policy: any problem leaves (or restores) the page exactly as Jenkins rendered it. The page is never
 * left half-enhanced, and the native form keeps working.
 */

const PENDING = "bpe-pending";
const SAFETY_MS = 3000;

function readConfig(): RenderConfig | null {
  const script =
    (document.currentScript as HTMLScriptElement | null) ??
    document.querySelector<HTMLScriptElement>("script[data-bpe-config]");
  const raw = script?.dataset.bpeConfig;
  if (!raw) {
    return null;
  }
  try {
    const cfg = JSON.parse(raw) as RenderConfig;
    return cfg && Array.isArray(cfg.groups) ? cfg : null;
  } catch (e) {
    console.warn("[build-parameter-enhancer] unreadable configuration", e);
    return null;
  }
}

/** localStorage key for the user's form-mode preference ("classic" | "enhanced"). */
const PREF_KEY = "bpe-form-mode";

/** Safely read localStorage; returns null in environments where it is not available (e.g. HtmlUnit tests). */
function getFormPref(): string | null {
  try {
    return typeof localStorage !== "undefined" ? localStorage.getItem(PREF_KEY) : null;
  } catch (_) {
    return null;
  }
}

const config = readConfig();
setStrings(config?.i18n);

// If the user previously chose classic mode, skip enhancement and show a toggle to switch back.
if (config) {
  const pref = getFormPref();
  if (pref === "classic") {
    // Don't enhance; inject a small "switch to enhanced" banner instead.
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => injectClassicBanner(config), { once: true });
    } else {
      injectClassicBanner(config);
    }
  } else {
    // Normal enhancement path.
    document.documentElement.classList.add(PENDING);
    window.setTimeout(reveal, SAFETY_MS);
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", run, { once: true });
    } else {
      run();
    }
  }
}

function reveal(): void {
  document.documentElement.classList.remove(PENDING);
}

function run(): void {
  try {
    apply(config as RenderConfig);
  } catch (e) {
    console.warn("[build-parameter-enhancer] falling back to the native form", e);
  } finally {
    reveal();
  }
}

function apply(cfg: RenderConfig): void {
  const result = scan(document, cfg.parameterNames);
  if (isFailure(result)) {
    console.warn(`[build-parameter-enhancer] not enhancing this page: ${result.error}`);
    return;
  }
  const enhanced = enhance(result, cfg);
  try {
    enhanced.root.insertBefore(buildToolbar(enhanced, cfg, PREF_KEY), enhanced.root.firstChild);
    autoExpandOnInvalid(result.form, enhanced);
    if (cfg.preview) {
      blockSubmission(result.form);
    }
  } catch (e) {
    enhanced.rollback();
    throw e;
  }
}

/** When the user is in classic mode: insert a small switch-back banner above the parameters form. */
function injectClassicBanner(cfg: RenderConfig): void {
  if (cfg.preview) return;
  const form = document.querySelector<HTMLFormElement>("form[name='parameters']");
  if (!form) return;
  const bar = document.createElement("div");
  bar.className = "bpe-classic-bar";
  const msg = document.createElement("span");
  msg.textContent = t("build.classic.using");
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "jenkins-button";
  btn.textContent = t("build.classic.switchBack");
  btn.addEventListener("click", () => {
    localStorage.removeItem(PREF_KEY);
    window.location.reload();
  });
  bar.append(msg, btn);
  form.insertAdjacentElement("beforebegin", bar);
}


/** A required control inside a collapsed group would otherwise block submission invisibly. */
function autoExpandOnInvalid(form: HTMLFormElement, enhanced: ReturnType<typeof enhance>): void {
  form.addEventListener(
    "invalid",
    (e) => {
      const target = e.target as HTMLElement | null;
      if (!target) {
        return;
      }
      const section = target.closest<HTMLElement>(".bpe-group");
      // Expand the group and, for a sub-group, its parent too.
      for (let view = enhanced.groups.find((g) => g.section === section); view; view = view.parent) {
        if (!view.isExpanded()) {
          view.setExpanded(true);
        }
      }
      const wrapper = target.closest<HTMLElement>(".bpe-field");
      wrapper?.classList.remove("bpe-hidden");
    },
    true,
  );
}

/** In the designer's preview a build must never be triggered, whatever is clicked. */
function blockSubmission(form: HTMLFormElement): void {
  form.addEventListener(
    "submit",
    (e) => {
      e.preventDefault();
      e.stopImmediatePropagation();
      notifyPreview(form);
    },
    true,
  );
  // Jenkins submits some forms programmatically, which does not fire a submit event.
  try {
    (form as unknown as { submit: () => void }).submit = () => notifyPreview(form);
  } catch {
    /* non-configurable in some browsers; the capture listener above still applies */
  }
  form.querySelectorAll("a").forEach((a) => {
    a.addEventListener("click", (e) => e.preventDefault());
  });
}

function notifyPreview(form: HTMLFormElement): void {
  let note = form.querySelector<HTMLElement>(".bpe-preview-blocked");
  if (!note) {
    note = document.createElement("p");
    note.className = "bpe-preview-blocked";
    note.setAttribute("role", "alert");
    note.textContent = t("build.preview.blocked");
    form.appendChild(note);
  }
  note.hidden = false;
}
