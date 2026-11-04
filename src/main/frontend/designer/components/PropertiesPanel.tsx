import { t } from "../../common/i18n";
import type { DiscoveredParameter, FieldLayout, FormLayout, GroupLayout } from "../../common/types";
import { fieldOf, groupIdOf, UNASSIGNED } from "../layout";
import type { Selection } from "./FormStructure";
import { Select } from "./Select";

interface Props {
  layout: FormLayout;
  parameters: DiscoveredParameter[];
  selection: Selection | null;
  onGroupChange: (id: string, patch: Partial<GroupLayout>) => void;
  onFieldChange: (name: string, patch: Partial<FieldLayout>) => void;
  onMoveField: (name: string, groupId: string) => void;
  otherGroupTitle: string | null;
  otherGroupTitleDefault: string;
  onOtherGroupTitleChange: (value: string | null) => void;
}

/** Right column: display properties of the selected group or parameter. */
export function PropertiesPanel({
  layout,
  parameters,
  selection,
  onGroupChange,
  onFieldChange,
  onMoveField,
  otherGroupTitle,
  otherGroupTitleDefault,
  onOtherGroupTitleChange,
}: Props) {
  return (
    <section className="bpe-panel" aria-label={t("designer.props.title")}>
      <header className="bpe-panel__header">
        <h2 className="bpe-panel__title">{t("designer.props.title")}</h2>
      </header>
      <div className="bpe-panel__body">
        {!selection && (
          <>
            <p className="bpe-muted">{t("designer.props.select")}</p>
            <hr className="bpe-sep" />
            <label className="bpe-label" htmlFor="bpe-other-title">
              {t("designer.props.fallbackTitle")}
            </label>
            <input
              id="bpe-other-title"
              className="bpe-input"
              value={otherGroupTitle ?? ""}
              placeholder={otherGroupTitleDefault}
              onChange={(e) => onOtherGroupTitleChange(e.target.value.trim() === "" ? null : e.target.value)}
            />
            <p className="bpe-hint">
              {t("designer.props.fallbackHint")}
            </p>
          </>
        )}
        {selection?.kind === "group" && (
          <GroupProperties
            group={layout.groups.find((g) => g.id === selection.id)}
            layout={layout}
            onChange={(patch) => onGroupChange(selection.id, patch)}
          />
        )}
        {selection?.kind === "field" && (
          <FieldProperties
            name={selection.name}
            layout={layout}
            parameter={parameters.find((p) => p.name === selection.name)}
            onChange={(patch) => onFieldChange(selection.name, patch)}
            onMove={(groupId) => onMoveField(selection.name, groupId)}
          />
        )}
      </div>
    </section>
  );
}

function GroupProperties({ group, layout, onChange }: { group: GroupLayout | undefined; layout: FormLayout; onChange: (p: Partial<GroupLayout>) => void }) {
  if (!group) {
    return <p className="bpe-muted">{t("designer.group.missing")}</p>;
  }
  // Top-level groups only (parentId === null or undefined), excluding this group itself and its children
  const topLevelGroups = layout.groups.filter((g) => !g.parentId && g.id !== group.id);
  // Current parent or self cannot be nested (would create cycle)
  const hasChildren = layout.groups.some((g) => g.parentId === group.id);

  return (
    <div className="bpe-form">
      <Field label={t("designer.group.title")} htmlFor="g-title">
        <input id="g-title" className="bpe-input" value={group.title} onChange={(e) => onChange({ title: e.target.value })} />
      </Field>
      <Field label={t("designer.group.description")} htmlFor="g-desc">
        <input
          id="g-desc"
          className="bpe-input"
          value={group.description ?? ""}
          placeholder={t("designer.group.description.placeholder")}
          onChange={(e) => onChange({ description: e.target.value.trim() === "" ? null : e.target.value })}
        />
      </Field>
      {!hasChildren && topLevelGroups.length > 0 && (
        <Field label={t("designer.group.parent")} htmlFor="g-parent">
          <Select
            id="g-parent"
            value={group.parentId ?? ""}
            options={[{ value: "", label: t("designer.group.parent.top") }].concat(
              topLevelGroups.map((g) => ({ value: g.id, label: g.title })),
            )}
            onChange={(v) => onChange({ parentId: v === "" ? null : v })}
          />
          <p className="bpe-hint">{t("designer.group.parent.hint")}</p>
        </Field>
      )}
      {hasChildren && (
        <p className="bpe-hint">{t("designer.group.hasChildren")}</p>
      )}
      <Field label={t("designer.group.columns")} htmlFor="g-cols">
        <Select
          id="g-cols"
          value={String(group.columns)}
          options={[
            { value: "1", label: t("designer.group.columns.one") },
            { value: "2", label: t("designer.group.columns.two") },
          ]}
          onChange={(v) => onChange({ columns: v === "1" ? 1 : 2 })}
        />
      </Field>
      <label className="bpe-check">
        <input type="checkbox" checked={group.collapsed} onChange={(e) => onChange({ collapsed: e.target.checked })} />
        {t("designer.group.collapsed")}
      </label>
      <p className="bpe-hint">
        {t("designer.group.collapsed.hint")}
      </p>
      <Field label={t("designer.group.id")} htmlFor="g-id">
        <input id="g-id" className="bpe-input" value={group.id} readOnly aria-readonly="true" />
      </Field>
    </div>
  );
}

function FieldProperties({
  name,
  layout,
  parameter,
  onChange,
  onMove,
}: {
  name: string;
  layout: FormLayout;
  parameter: DiscoveredParameter | undefined;
  onChange: (p: Partial<FieldLayout>) => void;
  onMove: (groupId: string) => void;
}) {
  const f = fieldOf(layout, name);
  const group = groupIdOf(layout, name);

  return (
    <div className="bpe-form">
      <div className="bpe-identity">
        <span className="bpe-label">{t("designer.field.parameter")}</span>
        <code className="bpe-code bpe-code--block">{name}</code>
        {parameter ? (
          <p className="bpe-hint">
            {parameter.typeLabel}
            {parameter.hasDefault && !parameter.sensitive ? " · " + t("designer.field.default", [parameter.defaultValue || t("designer.field.empty")]) : ""}
            {parameter.sensitive ? " · " + t("designer.field.defaultHidden") : ""}
          </p>
        ) : (
          <p className="bpe-warning">{t("designer.field.stale")}</p>
        )}
        {parameter && !parameter.managed && (
          <p className="bpe-hint">
            {t("designer.field.native")}
          </p>
        )}
      </div>

      <Field label={t("designer.field.label")} htmlFor="f-label">
        <input
          id="f-label"
          className="bpe-input"
          value={f.label ?? ""}
          placeholder={name}
          onChange={(e) => onChange({ label: e.target.value.trim() === "" ? null : e.target.value })}
        />
      </Field>
      <p className="bpe-hint">{t("designer.field.label.hint")}</p>

      <Field label={t("designer.field.summary")} htmlFor="f-short">
        <input
          id="f-short"
          className="bpe-input"
          value={f.shortDescription ?? ""}
          placeholder={parameter?.description ? firstLine(parameter.description) : t("designer.field.summary.placeholder")}
          onChange={(e) => onChange({ shortDescription: e.target.value.trim() === "" ? null : e.target.value })}
        />
      </Field>

      <Field label={t("designer.field.help")} htmlFor="f-help">
        <textarea
          id="f-help"
          className="bpe-input bpe-textarea"
          rows={6}
          value={f.helpText ?? ""}
          placeholder={t("designer.field.help.placeholder")}
          onChange={(e) => onChange({ helpText: e.target.value.trim() === "" ? null : e.target.value })}
        />
      </Field>
      <p className="bpe-hint">
        {t("designer.field.help.hint")}
      </p>

      <label className="bpe-check">
        <input type="checkbox" checked={f.showHelpIcon} onChange={(e) => onChange({ showHelpIcon: e.target.checked })} />
        {t("designer.field.showHelp")}
      </label>

      <Field label={t("designer.field.group")} htmlFor="f-group">
        <Select
          id="f-group"
          value={group}
          options={[{ value: UNASSIGNED, label: t("designer.field.unassigned") }].concat(
            layout.groups.map((g) => ({
              value: g.id,
              label: g.parentId ? "  ↳ " + g.title : g.title,
            })),
          )}
          onChange={onMove}
        />
      </Field>

      <Field label={t("designer.field.width")} htmlFor="f-width">
        <Select
          id="f-width"
          value={f.width}
          options={[
            { value: "auto", label: t("designer.field.width.auto") },
            { value: "full", label: t("designer.field.width.full") },
          ]}
          onChange={(v) => onChange({ width: v === "full" ? "full" : "auto" })}
        />
      </Field>

      <label className="bpe-check">
        <input type="checkbox" checked={f.linkWithNext} onChange={(e) => onChange({ linkWithNext: e.target.checked })} />
        {t("designer.field.sameRow")}
      </label>
      <p className="bpe-hint">{t("designer.field.sameRow.hint")}</p>
    </div>
  );
}

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: React.ReactNode }) {
  return (
    <div className="bpe-field-group">
      <label className="bpe-label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
    </div>
  );
}

function firstLine(text: string): string {
  const i = text.indexOf("\n");
  return i < 0 ? text : text.slice(0, i);
}
