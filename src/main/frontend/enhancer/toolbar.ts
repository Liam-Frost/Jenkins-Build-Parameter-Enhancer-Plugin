import type { RenderConfig } from "../common/types";
import { el, type Enhanced } from "./render";
import { initPresets } from "./presets";
import { t } from "../common/i18n";

/**
 * Search and expand/collapse controls. Filtering only toggles CSS visibility of the wrappers around the native
 * controls: no input is cloned, removed or disabled, so values and submit semantics never change.
 */
export function buildToolbar(enhanced: Enhanced, config: RenderConfig, prefKey: string | null): HTMLElement {
  const bar = el("div", "bpe-toolbar");

  const searchWrap = el("div", "bpe-toolbar__search");
  const search = document.createElement("input");
  search.type = "search";
  search.className = "bpe-toolbar__input jenkins-input";
  search.placeholder = t("build.search.placeholder");
  search.setAttribute("aria-label", t("build.search.placeholder"));
  searchWrap.appendChild(search);
  bar.appendChild(searchWrap);

  const status = el("span", "bpe-toolbar__status");
  status.setAttribute("role", "status");

  const actions = el("div", "bpe-toolbar__actions");
  actions.append(
    button(t("build.expandAll"), () => setAll(true)),
    button(t("build.collapseAll"), () => setAll(false)),
  );

  // Enhanced / classic toggle
  if (prefKey && !config.preview) {
    const toggleBtn = document.createElement("button");
    toggleBtn.type = "button";
    toggleBtn.className = "jenkins-button jenkins-button--tertiary";
    toggleBtn.textContent = t("build.classic");
    toggleBtn.title = t("build.classic.title");
    toggleBtn.addEventListener("click", () => {
      localStorage.setItem(prefKey, "classic");
      window.location.reload();
    });
    actions.appendChild(toggleBtn);
  }

  bar.append(status, actions);

  // Preset selector goes at the front of the actions row (right side of toolbar)
  if (!config.preview && (config.presets?.length || config.canSavePresets)) {
    initPresets(config, enhanced, actions);
  }

  const empty = el("p", "bpe-empty");
  empty.hidden = true;
  empty.textContent = t("build.search.empty");
  enhanced.root.appendChild(empty);

  const expandedBeforeSearch = new Map<string, boolean>();

  function setAll(expanded: boolean): void {
    for (const g of enhanced.groups) {
      g.setExpanded(expanded);
    }
  }

  function filter(query: string): void {
    const q = query.trim().toLowerCase();
    if (!q) {
      for (const f of enhanced.fields) {
        f.wrapper.classList.remove("bpe-hidden");
      }
      for (const g of enhanced.groups) {
        g.section.classList.remove("bpe-hidden");
        const prev = expandedBeforeSearch.get(g.group.id);
        if (prev !== undefined) {
          g.setExpanded(prev);
        }
      }
      expandedBeforeSearch.clear();
      empty.hidden = true;
      status.textContent = "";
      return;
    }
    if (expandedBeforeSearch.size === 0) {
      for (const g of enhanced.groups) {
        expandedBeforeSearch.set(g.group.id, g.isExpanded());
      }
    }
    let matches = 0;
    const perGroup = new Map<string, number>();
    for (const f of enhanced.fields) {
      const hit = f.field.search.includes(q);
      if (hit) {
        f.wrapper.classList.remove("bpe-hidden");
        matches++;
        // A hit inside a sub-group also keeps its parent visible and expanded.
        for (let g: typeof f.group | undefined = f.group; g; g = g.parent) {
          perGroup.set(g.group.id, (perGroup.get(g.group.id) ?? 0) + 1);
        }
      } else {
        f.wrapper.classList.add("bpe-hidden");
      }
    }
    for (const g of enhanced.groups) {
      const n = perGroup.get(g.group.id) ?? 0;
      if (n === 0) {
        g.section.classList.add("bpe-hidden");
      } else {
        g.section.classList.remove("bpe-hidden");
      }
      if (n > 0) {
        g.setExpanded(true);
      }
    }
    empty.hidden = matches > 0;
    status.textContent = t("build.search.count", [matches, enhanced.fields.length]);
  }

  let timer: number | undefined;
  search.addEventListener("input", () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => filter(search.value), 80);
  });
  search.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && search.value) {
      e.stopPropagation();
      search.value = "";
      filter("");
    }
  });
  document.addEventListener("keydown", (e) => {
    const target = e.target as HTMLElement | null;
    const typing = target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName);
    if (e.key === "/" && !typing && !e.ctrlKey && !e.metaKey) {
      e.preventDefault();
      search.focus();
    }
  });

  if (config.preview) {
    const note = el("span", "bpe-toolbar__preview");
    note.textContent = t("build.preview");
    bar.insertBefore(note, bar.firstChild);
  }
  return bar;
}

function button(text: string, onClick: () => void): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "jenkins-button jenkins-button--tertiary";
  b.textContent = text;
  b.addEventListener("click", onClick);
  return b;
}
