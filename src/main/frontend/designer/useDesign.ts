import { useCallback, useMemo, useRef, useState } from "react";
import type { FormLayout } from "../common/types";

/** Layout state with undo/redo and a dirty flag. */
export interface DesignHistory {
  layout: FormLayout;
  dirty: boolean;
  canUndo: boolean;
  canRedo: boolean;
  /** Applies a change and pushes the previous layout onto the undo stack. */
  apply: (fn: (layout: FormLayout) => FormLayout) => void;
  undo: () => void;
  redo: () => void;
  /** Replaces the layout and clears history, e.g. after loading or saving. */
  reset: (layout: FormLayout, dirty?: boolean) => void;
  markSaved: () => void;
}

const LIMIT = 50;

export function useDesign(initial: FormLayout): DesignHistory {
  const [layout, setLayout] = useState(initial);
  const [dirty, setDirty] = useState(false);
  const past = useRef<FormLayout[]>([]);
  const future = useRef<FormLayout[]>([]);
  const [version, setVersion] = useState(0);

  const apply = useCallback((fn: (l: FormLayout) => FormLayout) => {
    setLayout((current) => {
      const next = fn(current);
      if (next === current) {
        return current;
      }
      past.current = [...past.current.slice(-LIMIT + 1), current];
      future.current = [];
      setDirty(true);
      setVersion((v) => v + 1);
      return next;
    });
  }, []);

  const undo = useCallback(() => {
    setLayout((current) => {
      const prev = past.current.pop();
      if (!prev) {
        return current;
      }
      future.current = [current, ...future.current];
      setDirty(true);
      setVersion((v) => v + 1);
      return prev;
    });
  }, []);

  const redo = useCallback(() => {
    setLayout((current) => {
      const [next, ...rest] = future.current;
      if (!next) {
        return current;
      }
      future.current = rest;
      past.current = [...past.current, current];
      setDirty(true);
      setVersion((v) => v + 1);
      return next;
    });
  }, []);

  const reset = useCallback((l: FormLayout, d = false) => {
    past.current = [];
    future.current = [];
    setLayout(l);
    setDirty(d);
    setVersion((v) => v + 1);
  }, []);

  const markSaved = useCallback(() => setDirty(false), []);

  return useMemo(
    () => ({
      layout,
      dirty,
      canUndo: past.current.length > 0,
      canRedo: future.current.length > 0,
      apply,
      undo,
      redo,
      reset,
      markSaved,
    }),
    // version forces the canUndo/canRedo flags to be recomputed when the refs change.
    [layout, dirty, version, apply, undo, redo, reset, markSaved],
  );
}
