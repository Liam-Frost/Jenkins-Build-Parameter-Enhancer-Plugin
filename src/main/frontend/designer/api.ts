import { t } from "../common/i18n";
import type { DesignerState, FormLayout } from "../common/types";

/** Thin client for BuildFormDesignerAction. Every POST carries Jenkins' CSRF crumb. */

export interface ApiError {
  message: string;
  errors?: string[];
}

export class DesignerApi {
  constructor(private readonly base: string) {}

  private crumb(): { header: string; value: string } | null {
    const head = document.head;
    const header = head.dataset.crumbHeader;
    const value = head.dataset.crumbValue;
    return header && value ? { header, value } : null;
  }

  async state(): Promise<DesignerState> {
    const r = await fetch(`${this.base}/state`, {
      headers: { Accept: "application/json" },
      credentials: "same-origin",
    });
    if (!r.ok) {
      throw await this.toError(r);
    }
    return (await r.json()) as DesignerState;
  }

  async post<T = { ok: boolean }>(path: string, body: unknown): Promise<T> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json",
    };
    const crumb = this.crumb();
    if (crumb) {
      headers[crumb.header] = crumb.value;
    }
    const r = await fetch(`${this.base}/${path}`, {
      method: "POST",
      headers,
      credentials: "same-origin",
      body: JSON.stringify(body ?? {}),
    });
    if (!r.ok) {
      throw await this.toError(r);
    }
    return (await r.json()) as T;
  }

  saveDraft(layout: FormLayout) {
    return this.post("saveDraft", { layout });
  }

  publish(layout: FormLayout) {
    return this.post("publish", { layout });
  }

  discardDraft() {
    return this.post("discardDraft", {});
  }

  rollback() {
    return this.post("rollback", {});
  }

  setEnabled(enabled: boolean) {
    return this.post<{ ok: boolean; enabled: boolean }>("setEnabled", { enabled });
  }

  autoGenerate(mode: "replace" | "merge", layout: FormLayout | null) {
    return this.post<{ ok: boolean; layout: FormLayout }>("autoGenerate", { mode, layout });
  }

  /**
   * Renders the preview into an iframe by POSTing the layout through a real form, so the server can render the
   * genuine parameter controls. A GET with the layout in the query string would hit URL length limits.
   */
  renderPreview(iframe: HTMLIFrameElement, layout: FormLayout): void {
    const name = iframe.name || "bpe-preview-frame";
    iframe.name = name;
    const form = document.createElement("form");
    form.method = "POST";
    form.action = `${this.base}/preview`;
    form.target = name;
    form.style.display = "none";
    const crumb = this.crumb();
    if (crumb) {
      form.appendChild(hidden(crumb.header, crumb.value));
    }
    form.appendChild(hidden("layout", JSON.stringify(layout)));
    document.body.appendChild(form);
    form.submit();
    form.remove();
  }

  private async toError(r: Response): Promise<ApiError> {
    let message = `${r.status} ${r.statusText}`;
    let errors: string[] | undefined;
    try {
      const body = (await r.json()) as ApiError;
      if (body?.message) {
        message = body.message;
      }
      if (Array.isArray(body?.errors)) {
        errors = body.errors;
      }
    } catch {
      if (r.status === 403) {
        message = t("designer.forbidden");
      }
    }
    return { message, errors };
  }
}

function hidden(name: string, value: string): HTMLInputElement {
  const i = document.createElement("input");
  i.type = "hidden";
  i.name = name;
  i.value = value;
  return i;
}
