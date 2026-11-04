import { t } from "../../common/i18n";
import { useDraggable } from "@dnd-kit/core";
import { useMemo, useState } from "react";
import type { DiscoveredParameter, FormLayout } from "../../common/types";
import { fieldOf, groupIdOf, UNASSIGNED } from "../layout";

interface Props {
  parameters: DiscoveredParameter[];
  layout: FormLayout;
  selected: string | null;
  added: string[];
  typeChanged: string[];
  onSelect: (name: string) => void;
}

type Filter = "all" | "unassigned" | "assigned";

/** Left column: every discovered parameter, searchable, with its real name and type. */
export function ParametersLibrary({ parameters, layout, selected, added, typeChanged, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<Filter>("all");

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return parameters.filter((p) => {
      const group = groupIdOf(layout, p.name);
      if (filter === "unassigned" && group !== UNASSIGNED) {
        return false;
      }
      if (filter === "assigned" && group === UNASSIGNED) {
        return false;
      }
      if (!q) {
        return true;
      }
      const label = fieldOf(layout, p.name).label ?? "";
      return (
        p.name.toLowerCase().includes(q) ||
        label.toLowerCase().includes(q) ||
        p.typeLabel.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q)
      );
    });
  }, [parameters, layout, query, filter]);

  const unassignedCount = parameters.filter((p) => groupIdOf(layout, p.name) === UNASSIGNED).length;

  return (
    <section className="bpe-panel" aria-label={t("designer.library.title")}>
      <header className="bpe-panel__header">
        <h2 className="bpe-panel__title">{t("designer.library.title")}</h2>
        <span className="bpe-badge">{parameters.length}</span>
      </header>
      <div className="bpe-panel__toolbar">
        <input
          type="search"
          className="bpe-input"
          placeholder={t("designer.library.search")}
          aria-label={t("designer.library.searchLabel")}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <div className="bpe-segmented" role="group" aria-label={t("designer.library.filter")}>
          {(["all", "unassigned", "assigned"] as Filter[]).map((f) => (
            <button
              key={f}
              type="button"
              className={filter === f ? "bpe-segmented__item is-active" : "bpe-segmented__item"}
              onClick={() => setFilter(f)}
            >
              {f === "all" ? t("designer.library.all") : f === "unassigned" ? t("designer.library.unassigned", [unassignedCount]) : t("designer.library.grouped")}
            </button>
          ))}
        </div>
      </div>
      <div className="bpe-panel__body">
        {rows.length === 0 && <p className="bpe-muted">{t("designer.library.empty")}</p>}
        <ul className="bpe-list">
          {rows.map((p) => (
            <LibraryRow
              key={p.name}
              parameter={p}
              layout={layout}
              selected={selected === p.name}
              isNew={added.includes(p.name)}
              isTypeChanged={typeChanged.includes(p.name)}
              onSelect={onSelect}
            />
          ))}
        </ul>
      </div>
    </section>
  );
}

function LibraryRow({
  parameter,
  layout,
  selected,
  isNew,
  isTypeChanged,
  onSelect,
}: {
  parameter: DiscoveredParameter;
  layout: FormLayout;
  selected: boolean;
  isNew: boolean;
  isTypeChanged: boolean;
  onSelect: (name: string) => void;
}) {
  const group = groupIdOf(layout, parameter.name);
  const label = fieldOf(layout, parameter.name).label;
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `lib:${parameter.name}`,
    data: { kind: "field", name: parameter.name, from: group },
  });

  const groupTitle = group === UNASSIGNED ? null : layout.groups.find((g) => g.id === group)?.title;

  return (
    <li
      ref={setNodeRef}
      className={`bpe-list__item${selected ? " is-selected" : ""}${isDragging ? " is-dragging" : ""}`}
      {...attributes}
      {...listeners}
      onClick={() => onSelect(parameter.name)}
    >
      <div className="bpe-list__main">
        <code className="bpe-code">{parameter.name}</code>
        {label && <span className="bpe-list__label">{label}</span>}
      </div>
      <div className="bpe-list__meta">
        <span className="bpe-tag">{parameter.typeLabel}</span>
        {parameter.sensitive && <span className="bpe-tag bpe-tag--warn">{t("designer.tag.sensitive")}</span>}
        {!parameter.managed && <span className="bpe-tag">{t("designer.tag.native")}</span>}
        {isNew && <span className="bpe-tag bpe-tag--new">{t("designer.tag.new")}</span>}
        {isTypeChanged && <span className="bpe-tag bpe-tag--warn">{t("designer.tag.typeChanged")}</span>}
        {groupTitle ? <span className="bpe-muted">{t("designer.library.in", [groupTitle])}</span> : <span className="bpe-muted">{t("designer.tag.unassigned")}</span>}
      </div>
    </li>
  );
}
