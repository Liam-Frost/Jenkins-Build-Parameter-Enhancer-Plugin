import { t } from "../../common/i18n";
import { useDraggable, useDroppable } from "@dnd-kit/core";
import { Fragment, type ReactNode } from "react";
import type { DiscoveredParameter, FormLayout, GroupLayout } from "../../common/types";
import type { DropTarget } from "../dropTarget";
import { type BodyItem, bodyItemId, bodyItems, fieldOf, UNASSIGNED, unassignedNames } from "../layout";

interface Props {
  layout: FormLayout;
  parameters: DiscoveredParameter[];
  selection: Selection | null;
  stale: string[];
  onSelect: (s: Selection) => void;
  onAddGroup: () => void;
  onRemoveGroup: (id: string) => void;
  /** Where the entry being dragged would land, shown as an insertion line. */
  drop: DropTarget | null;
  /** Id of the element being dragged (`field:`, `group:` or `lib:` prefix), or null. */
  dragging: string | null;
}

export type Selection = { kind: "group"; id: string } | { kind: "field"; name: string };

/** Shared by every card and row of the structure. */
interface Ctx {
  layout: FormLayout;
  byName: Map<string, DiscoveredParameter>;
  stale: string[];
  selection: Selection | null;
  onSelect: (s: Selection) => void;
  onRemoveGroup: (id: string) => void;
  drop: DropTarget | null;
  /** The dragged entry as a body entry id; a library entry counts as its parameter. */
  dragged: string | null;
}

/**
 * Middle column: the structure users will see, edited by dragging. Nothing moves while dragging; a line shows
 * where the dragged parameter or group will go, worked out by `resolveDrop` (see dropTarget.ts).
 */
export function FormStructure(props: Props) {
  const { layout, parameters, drop } = props;
  const ctx: Ctx = {
    layout,
    byName: new Map(parameters.map((p) => [p.name, p])),
    stale: props.stale,
    selection: props.selection,
    onSelect: props.onSelect,
    onRemoveGroup: props.onRemoveGroup,
    drop,
    dragged: props.dragging?.startsWith("lib:") ? `field:${props.dragging.slice(4)}` : props.dragging,
  };
  const topLevelGroups = layout.groups.filter((g) => !g.parentId);
  // The gaps between top-level groups: dropping a group there places it at the top level.
  const { setNodeRef } = useDroppable({ id: "toplist" });
  const lineBefore = (id: string | null) => drop?.kind === "top" && drop.beforeId === id;

  return (
    <section className="bpe-panel" aria-label={t("designer.structure.title")}>
      <header className="bpe-panel__header">
        <h2 className="bpe-panel__title">{t("designer.structure.title")}</h2>
        <button type="button" className="bpe-button" onClick={props.onAddGroup}>
          {t("designer.structure.addGroup")}
        </button>
      </header>
      <div className="bpe-panel__body">
        <div ref={setNodeRef} className="bpe-top-list">
          {topLevelGroups.map((g) => (
            <Fragment key={g.id}>
              {lineBefore(g.id) && <DropLine />}
              <GroupCard group={g} ctx={ctx} />
            </Fragment>
          ))}
          {lineBefore(null) && <DropLine />}
        </div>
        {layout.groups.length === 0 && (
          <p className="bpe-muted">
            {t("designer.structure.empty")}
          </p>
        )}
        <UnassignedZone names={unassignedNames(layout, parameters)} ctx={ctx} />
      </div>
    </section>
  );
}

function DropLine() {
  return <div className="bpe-drop-line" aria-hidden="true" />;
}

/**
 * A group's body entries with the insertion line at the drop index. The index counts the entries other than the
 * dragged one, which stays in place (dimmed) until it is dropped.
 */
function BodyList({ groupId, ctx, render }: { groupId: string; ctx: Ctx; render: (it: BodyItem) => ReactNode }) {
  const items = bodyItems(ctx.layout, groupId);
  const index = ctx.drop?.kind === "body" && ctx.drop.groupId === groupId ? ctx.drop.index : -1;
  const out: ReactNode[] = [];
  let n = 0;
  for (const it of items) {
    const id = bodyItemId(it);
    if (id !== ctx.dragged) {
      if (n === index) out.push(<DropLine key="drop-line" />);
      n++;
    }
    out.push(<Fragment key={id}>{render(it)}</Fragment>);
  }
  if (index >= n && index >= 0) out.push(<DropLine key="drop-line" />);
  return <>{out}</>;
}

function GroupCard({ group, ctx }: { group: GroupLayout; ctx: Ctx }) {
  const { layout, selection, onSelect, onRemoveGroup } = ctx;
  const id = `group:${group.id}`;
  const data = { kind: "group", id: group.id };
  const drag = useDraggable({ id, data });
  const card = useDroppable({ id, data });
  const head = useDroppable({ id: `grouphead:${group.id}`, data });
  const body = useDroppable({ id: `groupbody:${group.id}`, data });
  const isSelected = selection?.kind === "group" && selection.id === group.id;
  const isNested = !!group.parentId;
  const childGroups = layout.groups.filter((g) => g.parentId === group.id);
  const targeted = ctx.drop?.kind === "body" && ctx.drop.groupId === group.id;
  const empty = group.fields.length === 0 && childGroups.length === 0;

  return (
    <article
      ref={card.setNodeRef}
      className={`bpe-group-card${isSelected ? " is-selected" : ""}${drag.isDragging ? " is-dragging" : ""}${isNested ? " is-nested" : ""}`}
    >
      <header
        ref={head.setNodeRef}
        className="bpe-group-card__header"
        onClick={() => onSelect({ kind: "group", id: group.id })}
      >
        <button
          type="button"
          ref={drag.setNodeRef}
          className="bpe-handle"
          aria-label={t("designer.structure.moveGroup", [group.title])}
          {...drag.attributes}
          {...drag.listeners}
        >
          ⋮⋮
        </button>
        <span className="bpe-group-card__title">{group.title}</span>
        <span className="bpe-badge">{group.fields.length}</span>
        {group.collapsed && <span className="bpe-tag">{t("designer.tag.collapsed")}</span>}
        <span className="bpe-tag">{t("designer.tag.columns", [group.columns])}</span>
        <button
          type="button"
          className="bpe-button bpe-button--danger"
          disabled={!empty}
          title={empty ? t("designer.structure.deleteEmpty") : t("designer.structure.deleteBlocked")}
          onClick={(e) => {
            e.stopPropagation();
            onRemoveGroup(group.id);
          }}
        >
          {t("designer.structure.delete")}
        </button>
      </header>
      <div ref={body.setNodeRef} className={`bpe-group-card__body${targeted ? " is-over" : ""}`}>
        {/* Parameters and sub-groups in one list, in the order the build form shows them. */}
        <BodyList
          groupId={group.id}
          ctx={ctx}
          render={(it) =>
            it.kind === "field" ? (
              <FieldRow name={it.name} ctx={ctx} />
            ) : (
              <GroupCard group={childGroups.find((c) => c.id === it.id) as GroupLayout} ctx={ctx} />
            )
          }
        />
        {empty && !targeted && <p className="bpe-dropzone-hint">{t("designer.structure.dropHere")}</p>}
      </div>
    </article>
  );
}

function FieldRow({ name, ctx }: { name: string; ctx: Ctx }) {
  const id = `field:${name}`;
  const data = { kind: "field", name };
  const drag = useDraggable({ id, data });
  const drop = useDroppable({ id, data });
  const f = fieldOf(ctx.layout, name);
  const parameter = ctx.byName.get(name);
  const selected = ctx.selection?.kind === "field" && ctx.selection.name === name;
  const stale = ctx.stale.includes(name);
  const ref = (node: HTMLElement | null) => {
    drag.setNodeRef(node);
    drop.setNodeRef(node);
  };

  return (
    <div
      ref={ref}
      className={`bpe-field-row${selected ? " is-selected" : ""}${drag.isDragging ? " is-dragging" : ""}${stale ? " is-stale" : ""}`}
      onClick={() => ctx.onSelect({ kind: "field", name })}
      {...drag.attributes}
      {...drag.listeners}
    >
      <span className="bpe-handle" aria-hidden="true">
        ⋮⋮
      </span>
      <span className="bpe-field-row__label">{f.label ?? name}</span>
      {f.label && <code className="bpe-code">{name}</code>}
      <span className="bpe-field-row__meta">
        {parameter ? <span className="bpe-tag">{parameter.typeLabel}</span> : <span className="bpe-tag bpe-tag--warn">{t("designer.tag.stale")}</span>}
        {f.width === "full" && <span className="bpe-tag">{t("designer.tag.fullWidth")}</span>}
        {f.linkWithNext && <span className="bpe-tag">{t("designer.tag.sameRow")}</span>}
      </span>
    </div>
  );
}

function UnassignedZone({ names, ctx }: { names: string[]; ctx: Ctx }) {
  const data = { kind: "groupbody", id: UNASSIGNED };
  const zone = useDroppable({ id: `group:${UNASSIGNED}`, data });
  const body = useDroppable({ id: `groupbody:${UNASSIGNED}`, data });
  const targeted = ctx.drop?.kind === "unassigned";
  const groupDragged = !!ctx.dragged?.startsWith("group:");
  return (
    <article ref={zone.setNodeRef} className="bpe-group-card bpe-group-card--unassigned">
      <header className="bpe-group-card__header">
        <span className="bpe-group-card__title">{t("designer.structure.unassigned")}</span>
        <span className="bpe-badge">{names.length}</span>
        <span className="bpe-muted">{t("designer.structure.unassigned.hint")}</span>
      </header>
      <div ref={body.setNodeRef} className={`bpe-group-card__body${targeted ? " is-over" : ""}`}>
        {names.map((name) => (
          <FieldRow key={name} name={name} ctx={ctx} />
        ))}
        {(names.length === 0 || groupDragged) && (
          <p className="bpe-dropzone-hint">
            {groupDragged ? t("designer.structure.dropTop") : t("designer.structure.allAssigned")}
          </p>
        )}
      </div>
    </article>
  );
}
