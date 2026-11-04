import { describe, expect, it } from "vitest";
import type { FormLayout, GroupLayout } from "../common/types";
import { resolveDrop } from "./dropTarget";
import { bodyItems, moveField, placeInBody, removeStale, setGroupParent } from "./layout";

const group = (id: string, fields: string[], extra: Partial<GroupLayout> = {}): GroupLayout => ({
  id,
  title: id,
  description: null,
  collapsed: false,
  columns: 2,
  fields,
  ...extra,
});

const layoutOf = (...groups: GroupLayout[]): FormLayout => ({
  schemaVersion: 1,
  otherGroupTitle: null,
  groups,
  fields: {},
  knownParameters: {},
});

const order = (l: FormLayout, id: string) => bodyItems(l, id).map((it) => (it.kind === "field" ? it.name : `[${it.id}]`));

describe("sub-groups between parameters", () => {
  const base = layoutOf(group("a", ["A1", "A2", "A3"]), group("s", ["S1"], { parentId: "a" }), group("b", ["B1"]));

  it("shows a sub-group without a position after the parent's parameters", () => {
    expect(order(base, "a")).toEqual(["A1", "A2", "A3", "[s]"]);
  });

  it("moves a sub-group between two parameters", () => {
    const l = placeInBody(base, "a", { kind: "group", id: "s" }, 1);
    expect(order(l, "a")).toEqual(["A1", "[s]", "A2", "A3"]);
    expect(l.groups.find((g) => g.id === "s")?.position).toBe(1);
  });

  it("moves a parameter past a sub-group", () => {
    const l1 = placeInBody(base, "a", { kind: "group", id: "s" }, 1);
    const l2 = placeInBody(l1, "a", { kind: "field", name: "A1" }, 3);
    expect(order(l2, "a")).toEqual(["[s]", "A2", "A3", "A1"]);
  });

  it("nests a top-level group at a chosen index", () => {
    const l = placeInBody(base, "a", { kind: "group", id: "b" }, 0);
    expect(order(l, "a")).toEqual(["[b]", "A1", "A2", "A3", "[s]"]);
    expect(l.groups.find((g) => g.id === "b")?.parentId).toBe("a");
  });

  it("orders two sub-groups at the same spot", () => {
    const l1 = placeInBody(base, "a", { kind: "group", id: "b" }, 4);
    expect(order(l1, "a")).toEqual(["A1", "A2", "A3", "[s]", "[b]"]);
    const l2 = placeInBody(l1, "a", { kind: "group", id: "b" }, 3);
    expect(order(l2, "a")).toEqual(["A1", "A2", "A3", "[b]", "[s]"]);
  });

  it("keeps a sub-group in place when a parameter before it leaves", () => {
    const l1 = placeInBody(base, "a", { kind: "group", id: "s" }, 2);
    const l2 = moveField(l1, "A1", "b");
    expect(order(l2, "a")).toEqual(["A2", "[s]", "A3"]);
  });

  it("keeps a sub-group in place when a parameter is added before it", () => {
    const l1 = placeInBody(base, "a", { kind: "group", id: "s" }, 1);
    const l2 = moveField(l1, "B1", "a", 0);
    expect(order(l2, "a")).toEqual(["B1", "A1", "[s]", "A2", "A3"]);
  });

  it("keeps a sub-group in place when a parameter before it is removed as stale", () => {
    const l1 = placeInBody(base, "a", { kind: "group", id: "s" }, 2);
    const params = ["A2", "A3", "S1", "B1"].map((name) => ({ name }) as never);
    expect(order(removeStale(l1, params), "a")).toEqual(["A2", "[s]", "A3"]);
  });

  it("refuses to nest a group that has sub-groups, or into a sub-group", () => {
    expect(placeInBody(base, "b", { kind: "group", id: "a" }, 0)).toBe(base);
    expect(placeInBody(base, "s", { kind: "group", id: "b" }, 0)).toBe(base);
  });

  it("clears the position when a sub-group goes back to the top level", () => {
    const l1 = placeInBody(base, "a", { kind: "group", id: "s" }, 1);
    const l2 = setGroupParent(l1, "s", null);
    expect(l2.groups.find((g) => g.id === "s")).toMatchObject({ parentId: null, position: null });
  });
});

describe("resolveDrop", () => {
  const l = placeInBody(
    layoutOf(group("a", ["A1", "A2"]), group("s", ["S1"], { parentId: "a" }), group("b", ["B1"])),
    "a",
    { kind: "group", id: "s" },
    1,
  );
  // Rows 20px high from y=0, in display order: A1, [s], A2.
  const boxes: Record<string, { top: number; height: number }> = {
    "field:A1": { top: 0, height: 20 },
    "group:s": { top: 20, height: 60 },
    "grouphead:s": { top: 20, height: 20 },
    "field:A2": { top: 80, height: 20 },
    "group:b": { top: 120, height: 40 },
  };
  const at = (overId: string, y: number, item = { kind: "group", id: "b" } as const) =>
    resolveDrop(l, item, overId, y, (id) => boxes[id]);

  it("puts a group before or after a parameter row by pointer half", () => {
    expect(at("field:A2", 85)).toEqual({ kind: "body", groupId: "a", index: 2 });
    expect(at("field:A2", 95)).toEqual({ kind: "body", groupId: "a", index: 3 });
  });

  it("reorders a sub-group by dropping it on a parameter row of its parent", () => {
    expect(at("field:A1", 5, { kind: "group", id: "s" } as never)).toEqual({ kind: "body", groupId: "a", index: 0 });
    expect(at("field:A2", 95, { kind: "group", id: "s" } as never)).toEqual({ kind: "body", groupId: "a", index: 2 });
  });

  it("places a group dropped inside a sub-group next to that sub-group", () => {
    expect(at("field:S1", 60)).toEqual({ kind: "body", groupId: "a", index: 2 });
    expect(at("grouphead:s", 25)).toEqual({ kind: "body", groupId: "a", index: 1 });
  });

  it("moves a parameter into a sub-group", () => {
    expect(at("groupbody:s", 70, { kind: "field", name: "B1" } as never)).toMatchObject({ kind: "body", groupId: "s" });
  });
});
