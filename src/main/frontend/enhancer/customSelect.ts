import { t } from "../common/i18n";
import { el } from "./render";

/**
 * A select drawn with Jenkins' own classes: the trigger looks like `jenkins-select`, the menu like the
 * `jenkins-dropdown` menus Jenkins shows elsewhere. Native selects cannot be styled that far, which is why
 * this exists. Only used for controls the plugin adds itself, never for the job's parameter controls.
 */
export interface SelectOption {
  value: string;
  label: string;
  /** Short prefix SVG string (e.g. '<svg>...</svg>') or plain text. */
  icon?: string;
  /** Secondary line under the label. */
  description?: string;
}

export interface SelectGroup {
  group: string;
  options: SelectOption[];
}

export type SelectData = SelectOption | SelectGroup;

export interface CustomSelect {
  container: HTMLElement;
  readonly value: string;
  setValue: (v: string) => void;
  setOptions: (data: SelectData[]) => void;
  setDisabled: (disabled: boolean) => void;
  /** Called after the user picks an option (not after setValue). */
  onChange: (handler: (v: string) => void) => void;
}

let openSelect: (() => void) | null = null;

/** Icons are trusted SVG constants from this bundle (see icons.ts), never user input. */
function iconNode(icon: string, cls = "bpe-select__icon"): HTMLElement {
  const i = el("span", cls);
  if (icon.indexOf("<svg") === 0) {
    i.innerHTML = icon;
  } else {
    i.textContent = icon;
  }
  return i;
}

export function customSelect(placeholder: string, ariaLabel: string, cssClass = ""): CustomSelect {
  let value = "";
  let options: SelectOption[] = [];
  let data: SelectData[] = [];
  const handlers: ((v: string) => void)[] = [];

  const container = el("div", "jenkins-select bpe-select" + (cssClass ? " " + cssClass : ""));
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "jenkins-select__input bpe-select__trigger";
  btn.setAttribute("aria-haspopup", "listbox");
  btn.setAttribute("aria-expanded", "false");
  btn.setAttribute("aria-label", ariaLabel);
  const label = el("span", "bpe-select__label");
  btn.appendChild(label);

  const menu = el("div", "bpe-select__menu");
  menu.setAttribute("role", "listbox");
  menu.hidden = true;
  const list = el("div", "jenkins-dropdown");
  menu.appendChild(list);
  container.append(btn, menu);

  function items(): HTMLButtonElement[] {
    return Array.from(list.querySelectorAll<HTMLButtonElement>(".jenkins-dropdown__item"));
  }

  function render(): void {
    list.innerHTML = "";
    for (const d of data) {
      if ("group" in d) {
        if (d.options.length === 0) continue;
        const h = el("p", "jenkins-dropdown__heading");
        h.textContent = d.group;
        list.appendChild(h);
        d.options.forEach((o) => list.appendChild(item(o)));
      } else {
        list.appendChild(item(d));
      }
    }
    if (options.length === 0) {
      const p = el("p", "jenkins-dropdown__placeholder");
      p.textContent = t("build.select.empty");
      list.appendChild(p);
    }
  }

  function item(o: SelectOption): HTMLButtonElement {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "jenkins-dropdown__item" + (o.value === value ? " bpe-select__item--selected" : "");
    b.setAttribute("role", "option");
    b.setAttribute("aria-selected", String(o.value === value));
    b.dataset.value = o.value;
    if (o.icon) {
      b.appendChild(iconNode(o.icon, "jenkins-dropdown__item__icon"));
    }
    const t = el("span");
    t.textContent = o.label;
    b.appendChild(t);
    if (o.description) {
      const d = el("span", "jenkins-dropdown__item__description");
      d.textContent = o.description;
      b.appendChild(d);
    }
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      choose(o.value);
    });
    return b;
  }

  function choose(v: string): void {
    close();
    btn.focus();
    if (v === value) return;
    setValue(v);
    handlers.forEach((h) => h(v));
  }

  function setValue(v: string): void {
    value = v;
    const o = options.find((x) => x.value === v);
    label.textContent = "";
    if (o && o.icon) {
      label.appendChild(iconNode(o.icon));
    }
    // Labels are user text (preset names): always set as text, never as markup.
    label.appendChild(document.createTextNode(o ? o.label : placeholder));
    label.classList.toggle("bpe-select__label--placeholder", !o || o.value === "");
    render();
  }

  function open(): void {
    if (openSelect) openSelect();
    menu.hidden = false;
    btn.setAttribute("aria-expanded", "true");
    container.classList.add("bpe-select--open");
    // Open upwards when there is no room below (e.g. inside a dialog near the bottom of the screen).
    const r = btn.getBoundingClientRect();
    const below = window.innerHeight - r.bottom;
    container.classList.toggle("bpe-select--up", below < Math.min(menu.scrollHeight, 280) + 12 && r.top > below);
    const sel = items().find((b) => b.dataset.value === value) ?? items()[0];
    if (sel) sel.focus();
    document.addEventListener("mousedown", onOutside, true);
    openSelect = close;
  }

  function close(): void {
    if (menu.hidden) return;
    menu.hidden = true;
    btn.setAttribute("aria-expanded", "false");
    container.classList.remove("bpe-select--open");
    document.removeEventListener("mousedown", onOutside, true);
    if (openSelect === close) openSelect = null;
  }

  function onOutside(e: MouseEvent): void {
    if (!container.contains(e.target as Node)) close();
  }

  btn.addEventListener("click", () => (menu.hidden ? open() : close()));
  btn.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      open();
    }
  });
  menu.addEventListener("keydown", (e) => {
    const all = items();
    const i = all.indexOf(document.activeElement as HTMLButtonElement);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const n = e.key === "ArrowDown" ? Math.min(all.length - 1, i + 1) : Math.max(0, i - 1);
      if (all[n]) all[n].focus();
    } else if (e.key === "Escape") {
      // Keep the Escape from closing a surrounding <dialog>.
      e.preventDefault();
      e.stopPropagation();
      close();
      btn.focus();
    } else if (e.key === "Tab") {
      close();
    }
  });

  return {
    container,
    get value() {
      return value;
    },
    setValue,
    setOptions(d: SelectData[]) {
      data = d;
      options = [];
      for (const x of d) {
        if ("group" in x) {
          // HtmlUnit Rhino doesn't support spread in arguments: avoid push(...x.options)
          for (const opt of x.options) options.push(opt);
        } else {
          options.push(x);
        }
      }
      setValue(value);
    },
    setDisabled(disabled: boolean) {
      btn.disabled = disabled;
      container.classList.toggle("bpe-select--disabled", disabled);
      if (disabled) close();
    },
    onChange(handler: (v: string) => void) {
      handlers.push(handler);
    },
  };
}
