import { t } from "../common/i18n";
import type { RenderConfig, RenderField, RenderGroup } from "../common/types";
import type { Chunk, ScanResult } from "./scan";

/**
 * Regroups the native controls. Controls are *moved*, never recreated, so their ids, values, event handlers and
 * any third-party initialisation survive, and each parameter still submits exactly one set of fields.
 */

export interface Enhanced {
  root: HTMLElement;
  fields: FieldView[];
  groups: GroupView[];
  rollback: () => void;
}

export interface FieldView {
  field: RenderField;
  wrapper: HTMLElement;
  group: GroupView;
}

export interface GroupView {
  group: RenderGroup;
  /** The enclosing group of a sub-group; undefined for top-level groups. */
  parent?: GroupView;
  section: HTMLElement;
  body: HTMLElement;
  toggle: HTMLButtonElement;
  count: HTMLElement;
  setExpanded: (expanded: boolean) => void;
  isExpanded: () => boolean;
}

const HIDDEN_NATIVE = "bpe-native-hidden";

export function enhance(scan: ScanResult, config: RenderConfig): Enhanced {
  const byName = new Map<string, Chunk>();
  for (const c of scan.chunks) {
    byName.set(c.name, c);
  }
  const undo: Array<() => void> = [];
  const root = el("div", "bpe-root");
  root.dataset.bpeRoot = "true";
  if (config.preview) {
    root.classList.add("bpe-preview");
  }
  // Insert before the first parameter block so the native heading and the button bar keep their place.
  const anchor = scan.chunks[0].node;
  scan.body.insertBefore(root, anchor);
  undo.push(() => root.remove());

  const groups: GroupView[] = [];
  const fields: FieldView[] = [];
  for (const g of config.groups) {
    const view = buildGroup(g, 0);
    root.appendChild(view.section);
    groups.push(view);
    // One level of nesting: each sub-group sits between the parent's fields at its position (after all of them
    // when it has none), spanning the full width of the parent's grid.
    const children = g.children ?? [];
    const at = (c: RenderGroup) => (typeof c.position === "number" ? Math.min(c.position, g.fields.length) : g.fields.length);
    const mountChildrenAt = (index: number) => {
      for (const child of children) {
        if (at(child) !== index) continue;
        const childView = buildGroup(child, 1, view);
        view.body.appendChild(childView.section);
        groups.push(childView);
        mountFields(childView, child, byName, fields, undo);
      }
    };
    mountFields(view, g, byName, fields, undo, mountChildrenAt);
    mountChildrenAt(g.fields.length);
  }
  // Counted after mounting, so a parent's badge includes the parameters of its sub-groups.
  for (const view of groups) {
    view.count.textContent = String(view.body.querySelectorAll(".bpe-field").length);
  }

  return {
    root,
    fields,
    groups,
    rollback: () => {
      for (let i = undo.length - 1; i >= 0; i--) {
        try {
          undo[i]();
        } catch {
          /* keep unwinding */
        }
      }
    },
  };
}

function mountFields(
  view: GroupView,
  g: RenderGroup,
  byName: Map<string, Chunk>,
  fields: FieldView[],
  undo: Array<() => void>,
  /** Called before the field at each index is mounted, so sub-groups can be placed between fields. */
  before?: (index: number) => void,
): void {
  let i = 0;
  while (i < g.fields.length) {
    if (before) before(i);
    const field = g.fields[i];
    const chunk = byName.get(field.name);
    if (!chunk) {
      i++;
      continue;
    }
    if (field.linkWithNext && i + 1 < g.fields.length) {
      const second = g.fields[i + 1];
      const secondChunk = byName.get(second.name);
      if (secondChunk) {
        const pair = el("div", "bpe-pair");
        view.body.appendChild(pair);
        fields.push(mount(pair, field, chunk, view, undo));
        fields.push(mount(pair, second, secondChunk, view, undo));
        // A pair is never split: a sub-group positioned between its two fields goes right after the pair.
        if (before) before(i + 1);
        i += 2;
        continue;
      }
    }
    fields.push(mount(view.body, field, chunk, view, undo));
    i++;
  }
}

function buildGroup(g: RenderGroup, level: 0 | 1, parent?: GroupView): GroupView {
  const section = el("section", level === 0 ? "bpe-group" : "bpe-group bpe-group--nested");
  section.dataset.groupId = g.id;
  section.dataset.level = String(level);
  const header = el("div", "bpe-group__header");
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "bpe-group__toggle";
  const chevron = el("span", "bpe-group__chevron");
  chevron.setAttribute("aria-hidden", "true");
  const titleWrap = el("span", "bpe-group__titles");
  const title = el("span", "bpe-group__title");
  title.textContent = g.title;
  titleWrap.appendChild(title);
  if (g.description) {
    const desc = el("span", "bpe-group__description");
    desc.textContent = g.description;
    titleWrap.appendChild(desc);
  }
  const count = el("span", "bpe-group__count");
  toggle.append(chevron, titleWrap, count);
  header.appendChild(toggle);
  const body = el("div", "bpe-group__body");
  body.dataset.columns = String(g.columns);
  section.append(header, body);

  const bodyId = `bpe-group-body-${g.id.replace(/[^A-Za-z0-9_-]/g, "")}`;
  body.id = bodyId;
  toggle.setAttribute("aria-controls", bodyId);

  const view: GroupView = {
    group: g,
    parent,
    section,
    body,
    toggle,
    count,
    isExpanded: () => toggle.getAttribute("aria-expanded") === "true",
    setExpanded: (expanded: boolean) => {
      toggle.setAttribute("aria-expanded", String(expanded));
      // display:none, not disabled: hidden inputs are still submitted, so no value can be lost.
      body.hidden = !expanded;
      if (expanded) {
        section.classList.remove("bpe-group--collapsed");
      } else {
        section.classList.add("bpe-group--collapsed");
      }
      if (expanded) {
        // Let controls that measure themselves (CodeMirror, selects) re-layout.
        window.dispatchEvent(new Event("resize"));
      }
    },
  };
  view.setExpanded(!g.collapsed);
  toggle.addEventListener("click", () => view.setExpanded(!view.isExpanded()));
  return view;
}

function mount(
  parent: HTMLElement,
  field: RenderField,
  chunk: Chunk,
  group: GroupView,
  undo: Array<() => void>,
): FieldView {
  const wrapper = el("div", "bpe-field");
  wrapper.dataset.param = field.name;
  wrapper.dataset.type = field.type;
  if (field.width === "full") {
    wrapper.classList.add("bpe-field--full");
  }
  parent.appendChild(wrapper);

  const helpBody = field.helpHtml ? field.helpHtml : "";
  const nativeDescription = findNativeDescription(chunk);
  const showHelp = field.showHelpIcon && (!!helpBody || !!nativeDescription);
  const isCheckbox = field.type === "boolean";

  let panel: HTMLElement | null = null;
  if (showHelp) {
    panel = el("div", "bpe-help");
    panel.hidden = true;
    if (helpBody) {
      const custom = el("div", "bpe-help__body");
      // Produced server-side by Jenkins' configured markup formatter, like native descriptions.
      custom.innerHTML = helpBody;
      panel.appendChild(custom);
    }
  }

  if (field.managed && !isCheckbox) {
    const header = el("div", "bpe-field__header");
    const label = el("span", "bpe-field__label");
    label.textContent = field.label;
    header.appendChild(label);
    if (field.labelOverridden) {
      header.appendChild(chip(field.name));
    }
    if (panel) {
      header.appendChild(helpButton(panel, field.label));
    }
    wrapper.appendChild(header);
    hideNative(chunk, undo);
  }

  if (field.summary) {
    const sum = el("p", "bpe-field__summary");
    sum.textContent = field.summary;
    wrapper.appendChild(sum);
  }

  // Move the native control. appendChild moves the node, keeping its state and listeners.
  const { originalParent, originalNext, node } = chunk;
  wrapper.appendChild(node);
  undo.push(() => originalParent.insertBefore(node, originalNext));

  if (field.managed && isCheckbox) {
    decorateCheckbox(chunk, field, panel, undo);
    hideNative(chunk, undo);
  } else if (!field.managed && panel) {
    // Unknown third-party control: leave its native label and description alone, only offer the help icon.
    const bar = el("div", "bpe-field__aux");
    bar.appendChild(helpButton(panel, field.label));
    wrapper.appendChild(bar);
  }

  if (panel) {
    if (nativeDescription) {
      const moved = nativeDescription;
      const descParent = moved.parentNode as Node;
      const descNext = moved.nextSibling;
      if (helpBody) {
        const caption = el("div", "bpe-help__caption");
        caption.textContent = t("build.help.original");
        panel.appendChild(caption);
      }
      moved.classList.add("bpe-help__native");
      moved.classList.remove(HIDDEN_NATIVE);
      panel.appendChild(moved);
      undo.push(() => {
        moved.classList.remove("bpe-help__native");
        descParent.insertBefore(moved, descNext);
      });
    }
    wrapper.appendChild(panel);
  }

  return { field, wrapper, group };
}

/** Rewrites the checkbox's own label, which is the only label a boolean parameter has. */
function decorateCheckbox(
  chunk: Chunk,
  field: RenderField,
  panel: HTMLElement | null,
  undo: Array<() => void>,
): void {
  const label = chunk.param.querySelector<HTMLLabelElement>("label");
  if (!label) {
    return;
  }
  const previous = label.textContent;
  label.textContent = field.label;
  undo.push(() => {
    label.textContent = previous;
  });
  const holder = label.parentElement ?? chunk.param;
  const aux = el("span", "bpe-field__aux bpe-field__aux--inline");
  if (field.labelOverridden) {
    aux.appendChild(chip(field.name));
  }
  if (panel) {
    aux.appendChild(helpButton(panel, field.label));
  }
  if (aux.childElementCount > 0) {
    holder.appendChild(aux);
    undo.push(() => aux.remove());
  }
}

function hideNative(chunk: Chunk, undo: Array<() => void>): void {
  const targets = chunk.node.querySelectorAll<HTMLElement>(".jenkins-form-label, .jenkins-form-description");
  targets.forEach((t) => {
    // The native description is shown in the help panel instead; the label is replaced by ours.
    t.classList.add(HIDDEN_NATIVE);
    undo.push(() => t.classList.remove(HIDDEN_NATIVE));
  });
}

function findNativeDescription(chunk: Chunk): HTMLElement | null {
  return chunk.node.querySelector<HTMLElement>(".jenkins-form-description");
}

function chip(name: string): HTMLElement {
  const c = el("code", "bpe-chip");
  c.textContent = name;
  c.title = t("build.chip.title", [name]);
  return c;
}

function helpButton(panel: HTMLElement, label: string): HTMLButtonElement {
  const b = document.createElement("button");
  b.type = "button";
  b.className = "bpe-help-button";
  b.setAttribute("aria-expanded", "false");
  b.setAttribute("aria-label", t("build.help.label", [label]));
  b.textContent = "?";
  b.addEventListener("click", () => {
    const open = panel.hidden;
    panel.hidden = !open;
    b.setAttribute("aria-expanded", String(open));
  });
  return b;
}

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (className) {
    e.className = className;
  }
  return e;
}
