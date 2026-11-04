import {
  DndContext,
  DragOverlay,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragMoveEvent,
  type DragStartEvent,
  type UniqueIdentifier,
} from "@dnd-kit/core";
import { sortableKeyboardCoordinates } from "@dnd-kit/sortable";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { DesignerState, FieldLayout, FormLayout, GroupLayout } from "../common/types";
import type { ApiError, DesignerApi } from "./api";
import { ChangesBanner } from "./components/ChangesBanner";
import { FormStructure, type Selection } from "./components/FormStructure";
import { ParametersLibrary } from "./components/ParametersLibrary";
import { Preview } from "./components/Preview";
import { PropertiesPanel } from "./components/PropertiesPanel";
import {
  addGroup,
  emptyLayout,
  type BodyItem,
  moveField,
  placeInBody,
  removeGroup,
  removeStale,
  setGroupParent,
  staleNames,
  UNASSIGNED,
  updateField,
  updateGroup,
  validate,
} from "./layout";
import { type DropTarget, excludedIds, resolveDrop } from "./dropTarget";
import { useDesign } from "./useDesign";
import { t } from "../common/i18n";

type Mode = "edit" | "preview";
type Message = { kind: "info" | "error"; text: string; details?: string[] } | null;

export function App({ api, state: initial }: { api: DesignerApi; state: DesignerState }) {
  const [serverState, setServerState] = useState(initial);
  const design = useDesign(initial.layout ?? emptyLayout(initial.schemaVersion));
  const [selection, setSelection] = useState<Selection | null>(null);
  const [mode, setMode] = useState<Mode>("edit");
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const [previewNonce, setPreviewNonce] = useState(0);
  const [dragging, setDragging] = useState<string | null>(null);
  const [drop, setDrop] = useState<DropTarget | null>(null);

  const { layout } = design;
  const stale = useMemo(() => staleNames(layout, serverState.parameters), [layout, serverState.parameters]);
  const errors = useMemo(() => validate(layout), [layout]);

  // Browsers only show their own message, but it does stop an accidental navigation away from unsaved work.
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (design.dirty) {
        e.preventDefault();
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [design.dirty]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) {
        return;
      }
      const k = e.key.toLowerCase();
      if (k === "z" && !e.shiftKey) {
        e.preventDefault();
        design.undo();
      } else if ((k === "z" && e.shiftKey) || k === "y") {
        e.preventDefault();
        design.redo();
      } else if (k === "s") {
        e.preventDefault();
        void saveDraft();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  });

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const run = useCallback(
    async (what: string, fn: () => Promise<void>) => {
      setBusy(true);
      setMessage(null);
      try {
        await fn();
      } catch (e) {
        const err = e as ApiError;
        setMessage({ kind: "error", text: t("designer.failed", [what, err.message ?? String(e)]), details: err.errors });
      } finally {
        setBusy(false);
      }
    },
    [],
  );

  const refresh = useCallback(async () => {
    const s = await api.state();
    setServerState(s);
    return s;
  }, [api]);

  const saveDraft = useCallback(async () => {
    if (errors.length > 0) {
      setMessage({ kind: "error", text: t("designer.fixBeforeSave"), details: errors });
      return;
    }
    await run(t("designer.saveDraft.task"), async () => {
      await api.saveDraft(layout);
      design.markSaved();
      await refresh();
      setMessage({ kind: "info", text: t("designer.draftSaved") });
    });
  }, [api, design, errors, layout, refresh, run]);

  const publish = useCallback(async () => {
    if (errors.length > 0) {
      setMessage({ kind: "error", text: t("designer.fixBeforePublish"), details: errors });
      return;
    }
    await run(t("designer.publish.task"), async () => {
      await api.publish(layout);
      design.markSaved();
      await refresh();
      setMessage({ kind: "info", text: t("designer.published") });
    });
  }, [api, design, errors, layout, refresh, run]);

  const autoGenerate = useCallback(
    (mergeOnly: boolean) =>
      run(t("designer.autoGenerate"), async () => {
        const r = await api.autoGenerate(mergeOnly ? "merge" : "replace", mergeOnly ? layout : null);
        design.apply(() => r.layout);
        setSelection(null);
        setMessage({
          kind: "info",
          text: mergeOnly
            ? t("designer.placed")
            : t("designer.generated"),
        });
      }),
    [api, design, layout, run],
  );

  /** The parameter or group being dragged, from the dragged element's data (library entries count as parameters). */
  const dragItem = (data: unknown): BodyItem | null => {
    const d = data as { kind?: string; name?: string; id?: string } | undefined;
    if (d?.kind === "field" && d.name) return { kind: "field", name: d.name };
    if (d?.kind === "group" && d.id) return { kind: "group", id: d.id };
    return null;
  };

  // Pointer and droppable boxes as of the last collision pass, read when working out the drop target.
  const pointerY = useRef(0);
  const boxes = useRef<Map<UniqueIdentifier, { top: number; height: number }>>(new Map());
  // The pointer in viewport coordinates, from the browser. dnd-kit's own pointer coordinates are kept relative to
  // where scrolling stood when the drag started, so after the panel auto-scrolls they no longer match the boxes.
  const pointer = useRef<{ x: number; y: number } | null>(null);
  useEffect(() => {
    const track = (e: PointerEvent) => {
      pointer.current = { x: e.clientX, y: e.clientY };
    };
    window.addEventListener("pointerdown", track, true);
    window.addEventListener("pointermove", track, true);
    return () => {
      window.removeEventListener("pointerdown", track, true);
      window.removeEventListener("pointermove", track, true);
    };
  }, []);

  /**
   * Hit test by pointer position for every drag: the smallest droppable under the pointer wins, so a parameter row
   * beats the body around it and a sub-group beats its parent. The dragged entry and its contents never count.
   */
  const collisionDetection = useCallback<CollisionDetection>(
    (args) => {
      const item = dragItem(args.active.data.current);
      if (!item) return [];
      const r = args.collisionRect;
      // Keyboard drags have no pointer: use the centre of the dragged element instead.
      const p = (args.pointerCoordinates && pointer.current) || { x: r.left + r.width / 2, y: r.top + r.height / 2 };
      pointerY.current = p.y;
      // Boxes are read from the DOM on every pass, in viewport coordinates like the pointer: dnd-kit's
      // droppableRects are measured when the drag starts and do not follow the panel when it scrolls.
      const rects = new Map<UniqueIdentifier, DOMRect>();
      for (const c of args.droppableContainers) {
        const node = c.node.current;
        if (node) rects.set(c.id, node.getBoundingClientRect());
      }
      boxes.current = rects;
      const skip = excludedIds(layout, item);
      let best: { id: UniqueIdentifier; area: number } | null = null;
      for (const c of args.droppableContainers) {
        const id = String(c.id);
        if (skip.has(id) || id.startsWith("lib:")) continue;
        const b = rects.get(c.id);
        if (!b || p.x < b.left || p.x > b.left + b.width || p.y < b.top || p.y > b.top + b.height) continue;
        const area = b.width * b.height;
        if (!best || area < best.area) best = { id: c.id, area };
      }
      return best ? [{ id: best.id, data: { droppableContainer: args.droppableContainers.find((c) => c.id === best!.id), value: best.area } }] : [];
    },
    [layout],
  );

  const targetFor = (activeData: unknown, overId: UniqueIdentifier | undefined): { item: BodyItem; target: DropTarget } | null => {
    const item = dragItem(activeData);
    if (!item || overId === undefined) return null;
    const target = resolveDrop(layout, item, String(overId), pointerY.current, (id) => boxes.current.get(id));
    return target ? { item, target } : null;
  };

  const onDragStart = (e: DragStartEvent) => {
    setDragging(String(e.active.id));
    setDrop(null);
  };

  const onDragMove = (e: DragMoveEvent) => {
    const t = targetFor(e.active.data.current, e.over?.id)?.target ?? null;
    setDrop((prev) => (JSON.stringify(prev) === JSON.stringify(t) ? prev : t));
  };

  // dnd-kit re-checks collisions on pointer moves; while the panel auto-scrolls under a still pointer it does not,
  // so a scroll replays the last pointer position to keep the insertion line under the pointer.
  useEffect(() => {
    if (!dragging) return;
    const replay = () => {
      const p = pointer.current;
      if (p) document.dispatchEvent(new PointerEvent("pointermove", { clientX: p.x, clientY: p.y, isPrimary: true, bubbles: true }));
    };
    document.addEventListener("scroll", replay, true);
    return () => document.removeEventListener("scroll", replay, true);
  }, [dragging]);

  const onDragCancel = () => {
    setDragging(null);
    setDrop(null);
  };

  const onDragEnd = (e: DragEndEvent) => {
    setDragging(null);
    setDrop(null);
    const r = targetFor(e.active.data.current, e.over?.id);
    if (!r) return;
    const { item, target } = r;
    design.apply((l) => {
      if (target.kind === "body") return placeInBody(l, target.groupId, item, target.index);
      if (item.kind === "field") return moveField(l, item.name, UNASSIGNED);
      return setGroupParent(l, item.id, null, target.kind === "top" ? (target.beforeId ?? undefined) : undefined);
    });
    setSelection(item.kind === "field" ? { kind: "field", name: item.name } : { kind: "group", id: item.id });
  };

  const dirtyLabel = design.dirty
    ? t("designer.state.unsaved")
    : serverState.hasDraft
      ? t("designer.state.draft")
      : serverState.publishedLayout
        ? t("designer.state.published")
        : t("designer.state.none");

  return (
    <DndContext sensors={sensors} collisionDetection={collisionDetection}
      onDragStart={onDragStart}
      onDragMove={onDragMove}
      onDragEnd={onDragEnd}
      onDragCancel={onDragCancel}
    >
      <div className={`bpe-designer${dragging?.startsWith("group:") ? " is-dragging-group" : ""}`}>
        <header className="bpe-header">
          <div className="bpe-header__titles">
            <h1 className="bpe-header__title">{t("designer.title")}</h1>
            <p className="bpe-header__subtitle">{serverState.jobDisplayName}</p>
          </div>
          <div className="bpe-header__state">
            <span className={design.dirty ? "bpe-state bpe-state--dirty" : "bpe-state"}>{dirtyLabel}</span>
            <label className="bpe-check">
              <input
                type="checkbox"
                checked={serverState.enabled}
                disabled={busy}
                onChange={(e) =>
                  void run(t("designer.enabled.task"), async () => {
                    await api.setEnabled(e.target.checked);
                    await refresh();
                  })
                }
              />
              {t("designer.enabled")}
            </label>
          </div>
        </header>

        {!serverState.globallyEnabled && (
          <p className="bpe-warning">
            {t("designer.globallyDisabled")}
          </p>
        )}

        <div className="bpe-actions">
          <div className="bpe-actions__group">
            <button type="button" className="bpe-button" disabled={busy} onClick={() => void autoGenerate(false)}>
              {t("designer.autoGenerate")}
            </button>
            <button type="button" className="bpe-button" disabled={busy} onClick={() => void autoGenerate(true)}>
              {t("designer.placeUnassigned")}
            </button>
            <button type="button" className="bpe-button" disabled={!design.canUndo} onClick={design.undo}>
              {t("designer.undo")}
            </button>
            <button type="button" className="bpe-button" disabled={!design.canRedo} onClick={design.redo}>
              {t("designer.redo")}
            </button>
          </div>
          <div className="bpe-actions__group">
            <div className="bpe-segmented" role="group" aria-label={t("designer.mode")}>
              <button
                type="button"
                className={mode === "edit" ? "bpe-segmented__item is-active" : "bpe-segmented__item"}
                onClick={() => setMode("edit")}
              >
                {t("designer.mode.edit")}
              </button>
              <button
                type="button"
                className={mode === "preview" ? "bpe-segmented__item is-active" : "bpe-segmented__item"}
                onClick={() => {
                  setMode("preview");
                  setPreviewNonce((n) => n + 1);
                }}
              >
                {t("designer.mode.preview")}
              </button>
            </div>
            {mode === "preview" && (
              <button type="button" className="bpe-button" onClick={() => setPreviewNonce((n) => n + 1)}>
                {t("designer.refreshPreview")}
              </button>
            )}
            <button type="button" className="bpe-button" disabled={busy} onClick={() => void saveDraft()}>
              {t("designer.saveDraft")}
            </button>
            <button type="button" className="bpe-button bpe-button--primary" disabled={busy} onClick={() => void publish()}>
              {t("designer.publish")}
            </button>
            {serverState.hasDraft && (
              <button
                type="button"
                className="bpe-button"
                disabled={busy}
                onClick={() =>
                  void run(t("designer.discard.task"), async () => {
                    await api.discardDraft();
                    const s = await refresh();
                    design.reset(s.layout ?? emptyLayout(s.schemaVersion));
                    setMessage({ kind: "info", text: t("designer.discarded") });
                  })
                }
              >
                {t("designer.discard")}
              </button>
            )}
            {serverState.history.length > 0 && (
              <button
                type="button"
                className="bpe-button"
                disabled={busy}
                onClick={() =>
                  void run(t("designer.restore.task"), async () => {
                    await api.rollback();
                    const s = await refresh();
                    design.reset(s.layout ?? emptyLayout(s.schemaVersion));
                    setMessage({ kind: "info", text: t("designer.restored") });
                  })
                }
              >
                {t("designer.restore")}
              </button>
            )}
            <a className="bpe-button" href={serverState.buildUrl} target="_blank" rel="noreferrer">
              {t("designer.openBuildPage")}
            </a>
          </div>
        </div>

        {message && (
          <div className={message.kind === "error" ? "bpe-message bpe-message--error" : "bpe-message"} role="status">
            <span>{message.text}</span>
            {message.details && (
              <ul>
                {message.details.map((d) => (
                  <li key={d}>{d}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        {errors.length > 0 && (
          <div className="bpe-message bpe-message--error" role="alert">
            <span>{t("designer.invalid")}</span>
            <ul>
              {errors.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          </div>
        )}

        <ChangesBanner
          changes={serverState.changes}
          stale={stale}
          onPlaceNew={() => void autoGenerate(true)}
          onRemoveStale={() => design.apply((l) => removeStale(l, serverState.parameters))}
        />

        {serverState.parameters.length === 0 ? (
          <p className="bpe-warning">
            {t("designer.noParameters")}
          </p>
        ) : mode === "preview" ? (
          <Preview api={api} layout={layout} nonce={previewNonce} />
        ) : (
          <div className="bpe-columns">
            <ParametersLibrary
              parameters={serverState.parameters}
              layout={layout}
              selected={selection?.kind === "field" ? selection.name : null}
              added={serverState.changes.added}
              typeChanged={serverState.changes.typeChanged}
              onSelect={(name) => setSelection({ kind: "field", name })}
            />
            <FormStructure
              layout={layout}
              drop={drop}
              dragging={dragging}
              parameters={serverState.parameters}
              selection={selection}
              stale={stale}
              onSelect={setSelection}
              onAddGroup={() =>
                design.apply((l) => {
                  const { layout: next, id } = addGroup(l);
                  setSelection({ kind: "group", id });
                  return next;
                })
              }
              onRemoveGroup={(id) => {
                design.apply((l) => removeGroup(l, id));
                setSelection(null);
              }}
            />
            <PropertiesPanel
              layout={layout}
              parameters={serverState.parameters}
              selection={selection}
              otherGroupTitle={layout.otherGroupTitle}
              otherGroupTitleDefault={serverState.otherGroupTitleDefault}
              onOtherGroupTitleChange={(v) => design.apply((l) => ({ ...l, otherGroupTitle: v }))}
              onGroupChange={(id: string, patch: Partial<GroupLayout>) => design.apply((l) => updateGroup(l, id, patch))}
              onFieldChange={(name: string, patch: Partial<FieldLayout>) => design.apply((l) => updateField(l, name, patch))}
              onMoveField={(name: string, groupId: string) => design.apply((l) => moveField(l, name, groupId))}
            />
          </div>
        )}
      </div>
      <DragOverlay>
        {dragging && <div className="bpe-drag-overlay">{dragging.replace(/^(lib:|field:|group:)/, "")}</div>}
      </DragOverlay>
    </DndContext>
  );
}

export type { FormLayout };
