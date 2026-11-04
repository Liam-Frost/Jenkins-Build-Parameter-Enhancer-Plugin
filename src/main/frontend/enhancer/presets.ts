import type { PresetWithMeta, RenderConfig } from "../common/types";
import { el, type Enhanced } from "./render";
import { customSelect, type SelectData } from "./customSelect";
import { t } from "../common/i18n";
import { ICON_GLOBAL, ICON_PERSONAL, ICON_RESTORE, scopeIcon } from "./icons";

/**
 * Preset system: apply saved parameter sets with one click, and save the current values as a new preset.
 * Passwords and file parameters are never stored (excluded server-side).
 */

export interface PresetState {
  presets: PresetWithMeta[];
  active: PresetWithMeta | null;
  /** Refresh the list from the server and redraw the selector. */
  refresh: () => Promise<void>;
  /** Marks a preset as the active one and shows it in the selector, without applying its values. */
  select: (id: string) => void;
}

/** Reads the CSRF crumb from Jenkins' page <head> data attributes. */
function crumb(): Record<string, string> {
  const h = document.head;
  const hdr = h.dataset.crumbHeader;
  const val = h.dataset.crumbValue;
  return hdr && val ? { [hdr]: val } : {};
}

// No async/await in this bundle: HtmlUnit's JS engine (used by the page tests) cannot parse it.
function apiPost(url: string, body: unknown): Promise<unknown> {
  return fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", Accept: "application/json", ...crumb() },
    body: JSON.stringify(body),
  }).then((r) =>
    r.json().then((json: unknown) => {
      if (!r.ok || !(json as { ok?: boolean }).ok) {
        throw new Error((json as { message?: string }).message ?? `HTTP ${r.status}`);
      }
      return json;
    }),
  );
}

/** Captures the current value of every non-password parameter control. */
export function captureValues(enhanced: Enhanced): Record<string, string> {
  const values: Record<string, string> = {};
  for (const f of enhanced.fields) {
    if (f.field.type === "password") continue;
    const ctrl = f.wrapper.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(
      'input[name="value"], select[name="value"], textarea[name="value"]',
    );
    if (!ctrl) continue;
    if (ctrl.type === "checkbox") {
      values[f.field.name] = (ctrl as HTMLInputElement).checked ? "true" : "false";
    } else {
      values[f.field.name] = ctrl.value;
    }
  }
  return values;
}

/**
 * Applies a preset: sets each saved value on its control and dispatches change events.
 * Returns the names of parameters that could not be applied (no control found or option not available).
 */
export function applyPreset(preset: PresetWithMeta, enhanced: Enhanced): string[] {
  return setValues(preset.values, enhanced);
}

/** Sets the given values on their controls; shared by applying a preset and restoring the values from before. */
function setValues(values: Record<string, string>, enhanced: Enhanced): string[] {
  const skipped: string[] = [];
  for (const [name, value] of Object.entries(values)) {
    const fieldView = enhanced.fields.find((f) => f.field.name === name);
    if (!fieldView) {
      skipped.push(name);
      continue;
    }
    const ctrl = fieldView.wrapper.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(
      'input[name="value"], select[name="value"], textarea[name="value"]',
    );
    if (!ctrl) {
      skipped.push(name);
      continue;
    }
    if (ctrl.type === "checkbox") {
      (ctrl as HTMLInputElement).checked = value === "true";
    } else if (ctrl.tagName === "SELECT") {
      const sel = ctrl as HTMLSelectElement;
      // Only set if the option exists; git parameters load async so we try a delayed retry.
      if (Array.from(sel.options).some((o) => o.value === value)) {
        sel.value = value;
      } else {
        // Retry once after a short delay for async-loaded option lists.
        setTimeout(() => {
          if (Array.from(sel.options).some((o) => o.value === value)) {
            sel.value = value;
          }
        }, 1500);
      }
    } else {
      ctrl.value = value;
    }
    ctrl.dispatchEvent(new Event("input", { bubbles: true }));
    ctrl.dispatchEvent(new Event("change", { bubbles: true }));
  }
  return skipped;
}

/** Shows a brief notification bar using Jenkins' native helper if available, else falls back to console. */
function notify(msg: string, kind: "success" | "warning" | "error" = "success"): void {
  type NB = { show(msg: string, opts: object): void; SUCCESS: object; WARNING: object; ERROR: object };
  const nb = (window as unknown as { notificationBar?: NB }).notificationBar;
  if (nb) {
    nb.show(msg, kind === "success" ? nb.SUCCESS : kind === "warning" ? nb.WARNING : nb.ERROR);
  } else {
    console.info(`[bpe] ${msg}`);
  }
}

/** Returns the <dialog> element, creating and appending it if necessary. */
function getOrCreateDialog(id: string): HTMLDialogElement {
  let d = document.getElementById(id) as HTMLDialogElement | null;
  if (!d) {
    d = document.createElement("dialog");
    d.id = id;
    document.body.appendChild(d);
  }
  return d;
}

/** Builds the save-preset dialog and returns a promise that resolves with the submitted data or null on cancel. */
export function showSaveDialog(
  config: RenderConfig,
  _enhanced: Enhanced,
  presets: PresetWithMeta[],
  editTarget: PresetWithMeta | null,
): Promise<{ name: string; description: string; isGlobal: boolean; updateId: string | null } | null> {
  const editablePresets = presets.filter((p) => p.canEdit);
  const d = getOrCreateDialog("bpe-preset-dialog");

  return new Promise((resolve) => {
    d.innerHTML = "";
    d.className = "bpe-dialog";
    const inner = el("div", "bpe-dialog__inner");
    const title = el("h2", "bpe-dialog__title");
    title.textContent = t("build.presetDialog.title");
    inner.appendChild(title);
    d.appendChild(inner);
    const form = document.createElement("form");
    form.method = "dialog";
    form.className = "bpe-dialog__form";
    inner.appendChild(form);

    // Update-existing row
    const updateGroup = el("div", "bpe-dialog__field");
    const updateLabel = el("label", "bpe-dialog__label");
    updateLabel.textContent = t("build.presetDialog.update");
    const updateSel = customSelect(t("build.presetDialog.saveAsNew"), t("build.presetDialog.update"));
    const updateOptions: SelectData[] = [{ value: "", label: t("build.presetDialog.saveAsNew") }];
    for (const p of editablePresets) {
      updateOptions.push({ value: p.id, label: p.name, icon: scopeIcon(p.isGlobal) });
    }
    updateSel.setOptions(updateOptions);
    if (editTarget) updateSel.setValue(editTarget.id);
    updateGroup.append(updateLabel, updateSel.container);
    form.appendChild(updateGroup);

    // Name row
    const nameGroup = el("div", "bpe-dialog__field");
    const nameLabel = el("label", "bpe-dialog__label");
    nameLabel.textContent = t("build.presetDialog.name");
    const nameInput = document.createElement("input");
    nameInput.type = "text";
    nameInput.className = "jenkins-input";
    nameInput.maxLength = 120;
    nameInput.value = editTarget ? editTarget.name : "";
    nameGroup.append(nameLabel, nameInput);
    form.appendChild(nameGroup);

    // Description row
    const descGroup = el("div", "bpe-dialog__field");
    const descLabel = el("label", "bpe-dialog__label");
    descLabel.textContent = t("build.presetDialog.description");
    const descInput = document.createElement("textarea");
    descInput.className = "jenkins-input";
    descInput.rows = 2;
    descInput.maxLength = 300;
    descInput.value = editTarget ? editTarget.description : "";
    descGroup.append(descLabel, descInput);
    form.appendChild(descGroup);

    // Fill name/desc when user picks an existing preset to update
    updateSel.onChange((id) => {
      const chosen = editablePresets.find((p) => p.id === id);
      if (chosen) { nameInput.value = chosen.name; descInput.value = chosen.description; }
    });

    // Scope row (admins only)
    let scopeSel: ReturnType<typeof customSelect> | null = null;
    if (config.canSaveGlobalPresets) {
      const scopeGroup = el("div", "bpe-dialog__field");
      const scopeLabel = el("label", "bpe-dialog__label");
      scopeLabel.textContent = t("build.presetDialog.visibility");
      scopeSel = customSelect(t("build.presetDialog.onlyMe"), t("build.presetDialog.visibility"));
      scopeSel.setOptions([
        { value: "personal", label: t("build.presetDialog.onlyMe"), icon: ICON_PERSONAL },
        { value: "global", label: t("build.presetDialog.everyone"), icon: ICON_GLOBAL },
      ]);
      scopeSel.setValue("personal");
      // Lock scope to the preset's existing scope when updating
      updateSel.onChange((id) => {
        if (!id) { scopeSel!.setDisabled(false); return; }
        const chosen = editablePresets.find((p) => p.id === id);
        scopeSel!.setValue(chosen && chosen.isGlobal ? "global" : "personal");
        scopeSel!.setDisabled(true);
      });
      scopeGroup.append(scopeLabel, scopeSel.container);
      form.appendChild(scopeGroup);
    }

    // Buttons
    const buttons = el("div", "bpe-dialog__buttons");
    const cancelBtn = document.createElement("button");
    cancelBtn.type = "button";
    cancelBtn.className = "jenkins-button";
    cancelBtn.textContent = t("build.presetDialog.cancel");
    cancelBtn.addEventListener("click", () => { d.close(); resolve(null); });
    const saveBtn = document.createElement("button");
    saveBtn.type = "button";
    saveBtn.className = "jenkins-button jenkins-button--primary";
    saveBtn.textContent = t("build.presetDialog.save");
    saveBtn.addEventListener("click", () => {
      if (!nameInput.value.trim()) { nameInput.focus(); return; }
      const isGlobal = !!config.canSaveGlobalPresets && scopeSel !== null && scopeSel.value === "global";
      d.close();
      resolve({ name: nameInput.value.trim(), description: descInput.value.trim(),
        isGlobal, updateId: updateSel.value || null });
    });
    buttons.append(cancelBtn, saveBtn);
    form.appendChild(buttons);

    d.addEventListener("close", () => resolve(null), { once: true });
    d.showModal();
  });
}

/**
 * Main entry point. Adds the preset selector to the toolbar and the Save-as-preset button to the bottom bar.
 * Returns a PresetState handle so the toolbar can react to changes.
 */
export function initPresets(
  config: RenderConfig,
  enhanced: Enhanced,
  toolbarActions: HTMLElement,
): PresetState {
  const state: PresetState = {
    presets: (config.presets ?? []) as PresetWithMeta[],
    active: null,
    refresh: () => {
      if (!config.presetsUrl) return Promise.resolve();
      return fetch(config.presetsUrl + "/list", { credentials: "same-origin" })
        .then((resp) => resp.json())
        .then((json: { ok: boolean; presets?: PresetWithMeta[] }) => {
          if (json.ok && json.presets) {
            state.presets = json.presets;
            redraw();
          }
        })
        .catch(() => {
          /* silent refresh failure */
        });
    },
    select: (id: string) => {
      state.active = state.presets.find((p) => p.id === id) ?? null;
      select.setValue(id);
      delBtn.hidden = !(state.active?.canEdit);
    },
  };

  const wrap = el("div", "bpe-preset-selector");
  const select = customSelect(t("build.preset.load"), t("build.preset.load"));
  const delBtn = document.createElement("button");
  delBtn.type = "button";
  delBtn.className = "jenkins-button jenkins-button--tertiary bpe-preset-delete";
  delBtn.setAttribute("aria-label", t("build.preset.delete"));
  delBtn.title = t("build.preset.delete");
  delBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4h6v2"/></svg>`;
  delBtn.hidden = true;
  const manageBtn = document.createElement("button");
  manageBtn.type = "button";
  manageBtn.className = "jenkins-button jenkins-button--tertiary";
  manageBtn.textContent = t("build.preset.manage");
  manageBtn.title = t("build.preset.manage.title");
  // Undo for applying presets: the values from before the first preset was applied (passwords are never captured).
  const restoreBtn = document.createElement("button");
  restoreBtn.type = "button";
  restoreBtn.className = "jenkins-button jenkins-button--tertiary bpe-preset-restore";
  restoreBtn.setAttribute("aria-label", t("build.preset.restore.title"));
  restoreBtn.title = t("build.preset.restore.title");
  restoreBtn.innerHTML = ICON_RESTORE;
  restoreBtn.appendChild(document.createTextNode(t("build.preset.restore")));
  restoreBtn.hidden = true;
  let before: Record<string, string> | null = null;

  wrap.append(select.container, restoreBtn, delBtn, manageBtn);
  toolbarActions.prepend(wrap);

  restoreBtn.addEventListener("click", () => {
    if (!before) return;
    setValues(before, enhanced);
    before = null;
    restoreBtn.hidden = true;
    state.active = null;
    select.setValue("");
    delBtn.hidden = true;
    notify(t("build.preset.restored"));
  });

  function redraw(): void {
    const options: SelectData[] = [];
    const global = state.presets.filter((p) => p.isGlobal);
    const personal = state.presets.filter((p) => !p.isGlobal);
    if (global.length) {
      options.push({ group: t("build.preset.shared"), options: global.map((p) => ({ value: p.id, label: p.name, icon: ICON_GLOBAL })) });
    }
    if (personal.length) {
      options.push({ group: t("build.preset.mine"), options: personal.map((p) => ({ value: p.id, label: p.name, icon: ICON_PERSONAL })) });
    }
    select.setOptions(options);
    const active = state.active ? state.presets.find((p) => p.id === state.active!.id) : null;
    state.active = active ?? null;
    select.setValue(active?.id ?? "");
    delBtn.hidden = !(active?.canEdit);
  }
  redraw();

  select.onChange((value) => {
    const preset = state.presets.find((p) => p.id === value);
    if (!preset) {
      state.active = null;
      delBtn.hidden = true;
      return;
    }
    state.active = preset;
    delBtn.hidden = !preset.canEdit;
    // Switching between presets keeps the first snapshot, so Restore always goes back to the user's own values.
    if (!before) {
      before = captureValues(enhanced);
    }
    restoreBtn.hidden = false;
    const skipped = applyPreset(preset, enhanced);
    const msg =
      skipped.length === 0
        ? t("build.preset.applied", [preset.name])
        : t("build.preset.appliedPartly", [preset.name, skipped.length, skipped.join(", ")]);
    notify(msg, skipped.length === 0 ? "success" : "warning");
  });

  delBtn.addEventListener("click", () => {
    const target = state.active;
    if (!target || !target.canEdit || !config.presetsUrl) return;
    if (!window.confirm(t("build.preset.deleteConfirm", [target.name]))) return;
    apiPost(config.presetsUrl + "/delete", { presetId: target.id, isGlobal: target.isGlobal })
      .then(() => {
        notify(t("build.preset.deleted", [target.name]));
        state.active = null;
        return state.refresh();
      })
      .catch((e) => notify(String(e), "error"));
  });

  // Save-as-preset button in bottom sticker
  if (config.canSavePresets && config.presetsUrl) {
    injectSaveButton(config, enhanced, state);
  }

  // Manage button opens a management dialog
  manageBtn.addEventListener("click", () => showManageDialog(config, state));

  return state;
}

function injectSaveButton(config: RenderConfig, enhanced: Enhanced, state: PresetState): void {
  // The Build / Cancel row of this form, not any other button row on the page.
  const stickerRow = enhanced.root.closest("form")?.querySelector("#bottom-sticker .jenkins-buttons-row");
  if (!stickerRow) return;
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "jenkins-button";
  btn.textContent = t("build.preset.save");
  btn.addEventListener("click", () => {
    const values = captureValues(enhanced);
    if (Object.keys(values).length === 0) {
      notify(t("build.preset.nothingToSave"), "warning");
      return;
    }
    showSaveDialog(config, enhanced, state.presets, state.active).then((result) => {
      if (!result || !config.presetsUrl) return;
      const endpoint = result.updateId ? "/update" : "/save";
      const body = result.updateId
        ? { presetId: result.updateId, isGlobal: result.isGlobal, name: result.name, description: result.description, values }
        : { name: result.name, description: result.description, isGlobal: result.isGlobal, values };
      apiPost(config.presetsUrl + endpoint, body)
        .then((resp: unknown) => {
          notify(result.updateId ? t("build.preset.updated") : t("build.preset.saved"));
          return state.refresh().then(() => {
            const presetId = (resp as { preset?: PresetWithMeta }).preset?.id;
            if (presetId) state.select(presetId);
          });
        })
        .catch((e) => notify(String(e), "error"));
    });
  });
  stickerRow.appendChild(btn);
}

/** Opens a dialog listing all editable presets with rename and delete actions. */
function showManageDialog(config: RenderConfig, state: PresetState): void {
  if (!config.presetsUrl) return;
  const d = getOrCreateDialog("bpe-manage-dialog");

  function render(): void {
    d.innerHTML = "";
    d.className = "bpe-dialog";
    const inner = el("div", "bpe-dialog__inner");
    const title = el("h2", "bpe-dialog__title");
    title.textContent = t("build.manage.title");
    inner.appendChild(title);
    d.appendChild(inner);
    const editablePresets = state.presets.filter((p) => p.canEdit);
    if (editablePresets.length === 0) {
      const empty = el("p");
      empty.textContent = t("build.manage.empty");
      empty.style.color = "var(--text-color-secondary, #777)";
      inner.appendChild(empty);
    } else {
      const table = el("table", "bpe-manage-table");
      for (const p of editablePresets) {
        const tr = document.createElement("tr");
        const tdIcon = document.createElement("td");
        tdIcon.className = "bpe-manage-table__icon";
        tdIcon.title = p.isGlobal ? t("build.manage.shared") : t("build.manage.onlyMe");
        tdIcon.innerHTML = scopeIcon(p.isGlobal);
        const tdName = document.createElement("td");
        tdName.className = "bpe-manage-table__name";
        const nameInput = document.createElement("input");
        nameInput.type = "text";
        nameInput.className = "jenkins-input";
        nameInput.value = p.name;
        nameInput.maxLength = 120;
        tdName.appendChild(nameInput);
        const tdActions = document.createElement("td");
        tdActions.className = "bpe-manage-table__actions";
        const saveBtn = document.createElement("button");
        saveBtn.type = "button";
        saveBtn.className = "jenkins-button";
        saveBtn.textContent = t("build.manage.rename");
        saveBtn.addEventListener("click", () => {
          const n = nameInput.value.trim();
          if (!n || n === p.name) return;
          apiPost(config.presetsUrl + "/update", { presetId: p.id, isGlobal: p.isGlobal, name: n })
            .then(() => state.refresh().then(() => render()))
            .catch((e) => notify(String(e), "error"));
        });
        const delBtn = document.createElement("button");
        delBtn.type = "button";
        delBtn.className = "jenkins-button jenkins-button--destructive";
        delBtn.textContent = t("build.manage.delete");
        delBtn.addEventListener("click", () => {
          if (!window.confirm(t("build.preset.deleteConfirm", [p.name]))) return;
          apiPost(config.presetsUrl + "/delete", { presetId: p.id, isGlobal: p.isGlobal })
            .then(() => {
              if (state.active && state.active.id === p.id) state.active = null;
              return state.refresh().then(() => render());
            })
            .catch((e) => notify(String(e), "error"));
        });
        tdActions.append(saveBtn, delBtn);
        tr.append(tdIcon, tdName, tdActions);
        table.appendChild(tr);
      }
      inner.appendChild(table);
    }
    const closeBtn = document.createElement("button");
    closeBtn.type = "button";
    closeBtn.className = "jenkins-button jenkins-button--primary";
    closeBtn.textContent = t("build.manage.close");
    closeBtn.style.marginTop = "1rem";
    closeBtn.addEventListener("click", () => d.close());
    inner.appendChild(closeBtn);
    if (!d.open) d.showModal();
  }
  render();
}




