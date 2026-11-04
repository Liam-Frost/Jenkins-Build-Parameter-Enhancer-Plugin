import { createRoot } from "react-dom/client";
import { App } from "./App";
import { DesignerApi } from "./api";
import { setStrings, t } from "../common/i18n";
import "../common/select.css";
import "./designer.css";

/** Mounts the designer into the element rendered by BuildFormDesignerAction/index.jelly. */
async function start(): Promise<void> {
  const mount = document.getElementById("bpe-designer");
  if (!mount) {
    return;
  }
  try {
    setStrings(JSON.parse(mount.dataset.i18n ?? "{}"));
  } catch {
    /* keys are shown instead of text */
  }
  const base = mount.dataset.api;
  if (!base) {
    mount.textContent = t("designer.misconfigured");
    return;
  }
  const api = new DesignerApi(base);
  try {
    const state = await api.state();
    createRoot(mount).render(<App api={api} state={state} />);
  } catch (e) {
    const message = (e as { message?: string }).message ?? String(e);
    mount.textContent = t("designer.loadFailed", [message]);
  }
}

void start();
