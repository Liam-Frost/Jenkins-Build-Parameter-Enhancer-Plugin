import type { FormLayout } from "../common/types";
import { type BodyItem, bodyItemId, bodyItems, groupIdOf, UNASSIGNED } from "./layout";

/**
 * Where a dragged parameter or group would land, worked out from the element under the pointer and the pointer's
 * vertical position. Shared by the insertion line shown while dragging and the drop itself, so both always agree.
 *
 * Droppable ids: `field:<name>` (a parameter row), `group:<id>` (a group card), `grouphead:<id>` (its header),
 * `groupbody:<id>` (its body, or the Unassigned zone), `toplist` (the gaps between top-level groups).
 */
export type DropTarget =
  /** At `index` of a group's body, counted among the body's entries other than the dragged one. */
  | { kind: "body"; groupId: string; index: number }
  /** A top-level group, placed before `beforeId` or after every other top-level group. */
  | { kind: "top"; beforeId: string | null }
  /** Out of every group. */
  | { kind: "unassigned" };

export interface Box {
  top: number;
  height: number;
}

export function resolveDrop(
  layout: FormLayout,
  item: BodyItem,
  overId: string,
  y: number,
  boxOf: (droppableId: string) => Box | undefined,
): DropTarget | null {
  const sep = overId.indexOf(":");
  const kind = sep < 0 ? overId : overId.slice(0, sep);
  const ref = sep < 0 ? "" : overId.slice(sep + 1);
  const byId = new Map(layout.groups.map((g) => [g.id, g]));
  const parentOf = (id: string) => byId.get(id)?.parentId ?? null;
  const draggedId = bodyItemId(item);

  const below = (id: string) => {
    const b = boxOf(id);
    return !!b && y > b.top + b.height / 2;
  };
  const others = (groupId: string) => bodyItems(layout, groupId).filter((it) => bodyItemId(it) !== draggedId);
  const indexOf = (groupId: string, id: string) => others(groupId).findIndex((it) => bodyItemId(it) === id);
  /** The first entry whose middle is below the pointer; the end when there is none. */
  const indexByY = (ids: string[]) => {
    for (let i = 0; i < ids.length; i++) {
      const b = boxOf(ids[i]);
      if (b && y < b.top + b.height / 2) return i;
    }
    return ids.length;
  };
  const topLevel = () =>
    layout.groups.filter((g) => !g.parentId && !(item.kind === "group" && g.id === item.id)).map((g) => g.id);
  const besideTop = (groupId: string, after: boolean): DropTarget => {
    const tops = topLevel();
    const i = tops.indexOf(groupId);
    return { kind: "top", beforeId: after ? (tops[i + 1] ?? null) : groupId };
  };
  /** The dragged entry right before or after a sub-group, in its parent's body. */
  const besideChild = (childId: string, after: boolean): DropTarget | null => {
    const parent = parentOf(childId);
    if (!parent) return null;
    const i = indexOf(parent, `group:${childId}`);
    return i < 0 ? null : { kind: "body", groupId: parent, index: i + (after ? 1 : 0) };
  };

  // The group under the pointer, when the pointer is on a group rather than a parameter row.
  const groupRef = kind === "group" || kind === "grouphead" || kind === "groupbody" ? ref : null;
  const fieldGroup = kind === "field" ? groupIdOf(layout, ref) : null;
  const onUnassigned = groupRef === UNASSIGNED || fieldGroup === UNASSIGNED;

  if (kind === "toplist") {
    return item.kind === "group" ? topByY() : null;
  }

  if (item.kind === "group") {
    const hasChildren = layout.groups.some((g) => g.parentId === item.id);
    if (onUnassigned) {
      return { kind: "top", beforeId: null };
    }
    const target = groupRef ?? fieldGroup;
    if (!target || !byId.has(target)) return null;
    if (hasChildren) {
      // A group with sub-groups stays top level: it can only be reordered among the top-level groups.
      const top = parentOf(target) ?? target;
      return besideTop(top, below(`group:${top}`));
    }
    const nested = parentOf(target) !== null;
    if (kind === "grouphead" || kind === "group") {
      const after = below(overId);
      return nested ? besideChild(target, after) : besideTop(target, after);
    }
    if (nested) {
      // Inside a sub-group's body: one level of nesting only, so it goes next to that sub-group.
      return besideChild(target, below(`group:${target}`));
    }
    if (kind === "field") {
      const i = indexOf(target, overId);
      return i < 0 ? null : { kind: "body", groupId: target, index: i + (below(overId) ? 1 : 0) };
    }
    return { kind: "body", groupId: target, index: indexByY(others(target).map(bodyItemId)) };
  }

  // A parameter.
  if (onUnassigned) {
    return { kind: "unassigned" };
  }
  if (kind === "field" && fieldGroup) {
    const i = indexOf(fieldGroup, overId);
    return i < 0 ? null : { kind: "body", groupId: fieldGroup, index: i + (below(overId) ? 1 : 0) };
  }
  if (!groupRef || !byId.has(groupRef)) return null;
  if (kind === "groupbody") {
    return { kind: "body", groupId: groupRef, index: indexByY(others(groupRef).map(bodyItemId)) };
  }
  // On a group's header (or its border): first in that group.
  return { kind: "body", groupId: groupRef, index: 0 };

  function topByY(): DropTarget {
    const tops = topLevel();
    const i = indexByY(tops.map((id) => `group:${id}`));
    return { kind: "top", beforeId: tops[i] ?? null };
  }
}

/** Every droppable a dragged entry must not land on: itself and, for a group, everything inside it. */
export function excludedIds(layout: FormLayout, item: BodyItem): Set<string> {
  if (item.kind === "field") {
    return new Set([`field:${item.name}`]);
  }
  const ids = new Set<string>();
  for (const g of layout.groups) {
    if (g.id === item.id || g.parentId === item.id) {
      ids.add(`group:${g.id}`);
      ids.add(`grouphead:${g.id}`);
      ids.add(`groupbody:${g.id}`);
      for (const f of g.fields) ids.add(`field:${f}`);
    }
  }
  return ids;
}
