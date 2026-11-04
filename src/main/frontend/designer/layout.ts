import { t } from "../common/i18n";
import { DEFAULT_FIELD, type DiscoveredParameter, type FieldLayout, type FormLayout, type GroupLayout } from "../common/types";

/**
 * Pure operations on a layout. Kept free of React and of the DOM so they can be unit tested, and so that every
 * mutation is a new object (which makes undo/redo a matter of keeping previous values).
 */

export const UNASSIGNED = "__unassigned";

export function emptyLayout(schemaVersion: number): FormLayout {
  return { schemaVersion, otherGroupTitle: null, groups: [], fields: {}, knownParameters: {} };
}

export function fieldOf(layout: FormLayout, name: string): FieldLayout {
  return layout.fields[name] ?? DEFAULT_FIELD;
}

export function groupIdOf(layout: FormLayout, name: string): string {
  for (const g of layout.groups) {
    if (g.fields.includes(name)) {
      return g.id;
    }
  }
  return UNASSIGNED;
}

export function unassignedNames(layout: FormLayout, params: DiscoveredParameter[]): string[] {
  const placed = new Set(layout.groups.flatMap((g) => g.fields));
  return params.map((p) => p.name).filter((n) => !placed.has(n));
}

/** Parameter names referenced by the layout that the job no longer defines. */
export function staleNames(layout: FormLayout, params: DiscoveredParameter[]): string[] {
  const live = new Set(params.map((p) => p.name));
  return layout.groups.flatMap((g) => g.fields).filter((n) => !live.has(n));
}

function withGroups(layout: FormLayout, groups: GroupLayout[]): FormLayout {
  return { ...layout, groups };
}

export function uniqueGroupId(layout: FormLayout, base = "group"): string {
  const taken = new Set(layout.groups.map((g) => g.id));
  let i = 1;
  let id = `${base}-${i}`;
  while (taken.has(id)) {
    id = `${base}-${++i}`;
  }
  return id;
}

export function addGroup(layout: FormLayout, title = t("designer.structure.newGroup")): { layout: FormLayout; id: string } {
  const id = uniqueGroupId(layout);
  const group: GroupLayout = { id, title, description: null, collapsed: false, columns: 2, fields: [] };
  return { layout: withGroups(layout, [...layout.groups, group]), id };
}

export function updateGroup(layout: FormLayout, id: string, patch: Partial<GroupLayout>): FormLayout {
  return withGroups(
    layout,
    layout.groups.map((g) => (g.id === id ? { ...g, ...patch } : g)),
  );
}

/** Groups must be empty before they can be removed, so no parameter can be lost with a group. */
export function removeGroup(layout: FormLayout, id: string): FormLayout {
  const g = layout.groups.find((x) => x.id === id);
  if (!g || g.fields.length > 0 || layout.groups.some((x) => x.parentId === id)) {
    return layout;
  }
  return withGroups(
    layout,
    layout.groups.filter((x) => x.id !== id),
  );
}

/**
 * Nests a group under a top-level parent, or moves it back to the top level when `parentId` is null. Only one level
 * of nesting is allowed, so the parent must be top level and the moved group must have no children of its own.
 * With `beforeId`, the group is also placed right before that group. Invalid moves return the layout unchanged.
 */
export function setGroupParent(
  layout: FormLayout,
  id: string,
  parentId: string | null,
  beforeId?: string,
): FormLayout {
  const g = layout.groups.find((x) => x.id === id);
  if (!g || id === parentId || id === beforeId) {
    return layout;
  }
  if (parentId !== null) {
    const parent = layout.groups.find((x) => x.id === parentId);
    if (!parent || parent.parentId || layout.groups.some((x) => x.parentId === id)) {
      return layout;
    }
  }
  const moved = { ...g, parentId, position: null };
  const rest = layout.groups.filter((x) => x.id !== id);
  const at = beforeId ? rest.findIndex((x) => x.id === beforeId) : -1;
  if (at < 0) {
    return withGroups(layout, [...rest, moved]);
  }
  rest.splice(at, 0, moved);
  return withGroups(layout, rest);
}

export function moveGroup(layout: FormLayout, from: number, to: number): FormLayout {
  if (from === to || from < 0 || to < 0 || from >= layout.groups.length || to >= layout.groups.length) {
    return layout;
  }
  const groups = [...layout.groups];
  const [g] = groups.splice(from, 1);
  groups.splice(to, 0, g);
  return withGroups(layout, groups);
}

/**
 * Moves a parameter to a group, or out of every group when `toGroupId` is {@link UNASSIGNED}. A parameter is only
 * ever in one group, so it is removed from its previous group first.
 */
export function moveField(layout: FormLayout, name: string, toGroupId: string, toIndex?: number): FormLayout {
  const base = withoutField(layout, name);
  if (toGroupId === UNASSIGNED) {
    return base;
  }
  const target = base.groups.find((g) => g.id === toGroupId);
  if (!target) {
    return layout;
  }
  const index = toIndex === undefined || toIndex < 0 || toIndex > target.fields.length ? target.fields.length : toIndex;
  return withGroups(
    base,
    base.groups.map((g) => {
      if (g.id === target.id) {
        return { ...g, fields: [...g.fields.slice(0, index), name, ...g.fields.slice(index)] };
      }
      // Sub-groups shown after the insertion point keep their place relative to the parameters around them.
      if (g.parentId === target.id && typeof g.position === "number" && g.position > index) {
        return { ...g, position: g.position + 1 };
      }
      return g;
    }),
  );
}

/** Takes a parameter out of its group, keeping the sub-groups of that group where they were. */
function withoutField(layout: FormLayout, name: string): FormLayout {
  const source = layout.groups.find((g) => g.fields.includes(name));
  const k = source ? source.fields.indexOf(name) : -1;
  return withGroups(
    layout,
    layout.groups.map((g) => {
      if (source && g.parentId === source.id && typeof g.position === "number" && g.position > k) {
        return { ...g, position: g.position - 1 };
      }
      return g.fields.includes(name) ? { ...g, fields: g.fields.filter((f) => f !== name) } : g;
    }),
  );
}

/** An entry in a group's body: one of its parameters, or one of its sub-groups. */
export type BodyItem = { kind: "field"; name: string } | { kind: "group"; id: string };

/** The id the entry is dragged and sorted by in the designer. */
export function bodyItemId(item: BodyItem): string {
  return item.kind === "field" ? `field:${item.name}` : `group:${item.id}`;
}

/** A group's body in display order: its parameters, with each sub-group at its position (after them if none). */
export function bodyItems(layout: FormLayout, groupId: string): BodyItem[] {
  const g = layout.groups.find((x) => x.id === groupId);
  if (!g) {
    return [];
  }
  const n = g.fields.length;
  const at = (c: GroupLayout) => (typeof c.position === "number" ? Math.min(c.position, n) : n);
  const children = layout.groups.filter((c) => c.parentId === groupId);
  const items: BodyItem[] = [];
  for (let i = 0; i <= n; i++) {
    for (const c of children) {
      if (at(c) === i) {
        items.push({ kind: "group", id: c.id });
      }
    }
    if (i < n) {
      items.push({ kind: "field", name: g.fields[i] });
    }
  }
  return items;
}

/**
 * Writes a body order back: the group's parameters, each sub-group's position, and the sub-groups' order in
 * `layout.groups`. `items` must hold exactly the group's sub-groups. A sub-group after every parameter gets no
 * position, so a parameter added later still goes before it.
 */
function writeBody(layout: FormLayout, groupId: string, items: BodyItem[]): FormLayout {
  const fields = items.flatMap((it) => (it.kind === "field" ? [it.name] : []));
  const position = new Map<string, number | null>();
  const order: string[] = [];
  let count = 0;
  for (const it of items) {
    if (it.kind === "field") {
      count++;
    } else {
      position.set(it.id, count < fields.length ? count : null);
      order.push(it.id);
    }
  }
  const byId = new Map(layout.groups.map((g) => [g.id, g]));
  let k = 0;
  return withGroups(
    layout,
    layout.groups.map((g) => {
      if (g.id === groupId) {
        return { ...g, fields };
      }
      if (g.parentId === groupId) {
        // Sub-groups keep their slots in the list, refilled in the new order.
        const c = byId.get(order[k++]) ?? g;
        return { ...c, position: position.get(c.id) ?? null };
      }
      return g;
    }),
  );
}

/**
 * Puts a parameter or a sub-group at `index` of a group's body (counted among the body's other entries), taking it
 * out of wherever it was. A group can only go into a top-level group and must have no sub-groups of its own.
 * Invalid moves return the layout unchanged.
 */
export function placeInBody(layout: FormLayout, groupId: string, item: BodyItem, index: number): FormLayout {
  const target = layout.groups.find((g) => g.id === groupId);
  if (!target) {
    return layout;
  }
  const same = (x: BodyItem) =>
    item.kind === "field" ? x.kind === "field" && x.name === item.name : x.kind === "group" && x.id === item.id;
  const items = bodyItems(layout, groupId).filter((x) => !same(x));
  let next: FormLayout;
  if (item.kind === "field") {
    next = withoutField(layout, item.name);
  } else {
    const g = layout.groups.find((x) => x.id === item.id);
    if (!g || g.id === groupId || target.parentId || layout.groups.some((x) => x.parentId === g.id)) {
      return layout;
    }
    next = withGroups(
      layout,
      layout.groups.map((x) => (x.id === g.id ? { ...x, parentId: groupId, position: null } : x)),
    );
  }
  items.splice(Math.min(Math.max(index, 0), items.length), 0, item);
  return writeBody(next, groupId, items);
}

export function updateField(layout: FormLayout, name: string, patch: Partial<FieldLayout>): FormLayout {
  const current = layout.fields[name] ?? DEFAULT_FIELD;
  return { ...layout, fields: { ...layout.fields, [name]: { ...current, ...patch } } };
}

/** Drops layout entries for parameters that no longer exist. Only ever called explicitly. */
export function removeStale(layout: FormLayout, params: DiscoveredParameter[]): FormLayout {
  const live = new Set(params.map((p) => p.name));
  const byId = new Map(layout.groups.map((g) => [g.id, g]));
  const groups = layout.groups.map((g) => {
    const kept = { ...g, fields: g.fields.filter((f) => live.has(f)) };
    // A sub-group keeps its place: its position counts only the parent's parameters that remain.
    const parent = g.parentId ? byId.get(g.parentId) : undefined;
    if (parent && typeof g.position === "number") {
      kept.position = parent.fields.slice(0, g.position).filter((f) => live.has(f)).length;
    }
    return kept;
  });
  const fields: Record<string, FieldLayout> = {};
  for (const [name, f] of Object.entries(layout.fields)) {
    if (live.has(name)) {
      fields[name] = f;
    }
  }
  return { ...layout, groups, fields };
}

/** Client-side mirror of the server checks, so problems are shown before saving. */
export function validate(layout: FormLayout): string[] {
  const errors: string[] = [];
  const ids = new Set<string>();
  const placed = new Set<string>();
  for (const g of layout.groups) {
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(g.id)) {
      errors.push(t("designer.validate.badId", [g.id]));
    }
    if (ids.has(g.id)) {
      errors.push(t("designer.validate.duplicateId", [g.id]));
    }
    ids.add(g.id);
    if (!g.title || !g.title.trim()) {
      errors.push(t("designer.validate.noTitle", [g.id]));
    }
    for (const f of g.fields) {
      if (placed.has(f)) {
        errors.push(t("designer.validate.placedTwice", [f]));
      }
      placed.add(f);
    }
  }
  return errors;
}
