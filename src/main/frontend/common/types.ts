/** Shapes produced by the Java side. Keep in sync with RenderModel and LayoutCodec. */

export type FieldType = "string" | "text" | "boolean" | "choice" | "password" | "git" | "other";

export interface RenderField {
  name: string;
  type: FieldType;
  /** Native markup is the standard f:entry structure, so the label can be restyled safely. */
  managed: boolean;
  label: string;
  labelOverridden: boolean;
  summary: string;
  summaryFromDescription: boolean;
  /** Help text already rendered by Jenkins' markup formatter. Empty when unset. */
  helpHtml: string;
  hasDescription: boolean;
  showHelpIcon: boolean;
  width: "auto" | "full";
  linkWithNext: boolean;
  search: string;
}

export interface RenderGroup {
  id: string;
  title: string;
  description: string;
  collapsed: boolean;
  columns: 1 | 2;
  parentId?: string | null;
  /** For a sub-group: how many of the parent's rendered fields come before it. Absent: after all of them. */
  position?: number;
  children?: RenderGroup[];
  fields: RenderField[];
}

export interface RenderConfig {
  version: number;
  preview: boolean;
  parameterNames: string[];
  groups: RenderGroup[];
  presets?: PresetWithMeta[];
  jobFullName?: string;
  presetsUrl?: string;
  canSavePresets?: boolean;
  canSaveGlobalPresets?: boolean;
  /** Interface text for the page's locale (build.* keys). */
  i18n?: Record<string, string>;
}

export interface PresetWithMeta {
  id: string;
  name: string;
  description: string;
  values: Record<string, string>;
  createdBy: string;
  createdAt: number;
  updatedAt: number;
  isGlobal: boolean;
  canEdit: boolean;
}

/** Layout as exchanged with the designer. */
export interface FieldLayout {
  label: string | null;
  shortDescription: string | null;
  helpText: string | null;
  showHelpIcon: boolean;
  width: "auto" | "full";
  linkWithNext: boolean;
}

export interface GroupLayout {
  id: string;
  title: string;
  description: string | null;
  collapsed: boolean;
  columns: 1 | 2;
  parentId?: string | null;
  /** For a nested group: how many of the parent's fields are shown before it. Null or absent: after all of them. */
  position?: number | null;
  fields: string[];
}

export interface FormLayout {
  schemaVersion: number;
  otherGroupTitle: string | null;
  groups: GroupLayout[];
  fields: Record<string, FieldLayout>;
  knownParameters: Record<string, string>;
}

export interface DiscoveredParameter {
  name: string;
  typeClass: string;
  type: FieldType;
  typeLabel: string;
  description: string;
  defaultValue: string;
  hasDefault: boolean;
  choices: string[];
  sensitive: boolean;
  managed: boolean;
}

export interface Changes {
  added: string[];
  removed: string[];
  typeChanged: string[];
  unassigned: string[];
}

export interface HistoryEntry {
  index: number;
  publishedAt: number;
  publishedBy: string;
  groups: number;
}

export interface DesignerState {
  jobName: string;
  jobDisplayName: string;
  buildUrl: string;
  globallyEnabled: boolean;
  enabled: boolean;
  hasDraft: boolean;
  draftSavedAt: number;
  draftSavedBy: string;
  schemaVersion: number;
  otherGroupTitleDefault: string;
  parameters: DiscoveredParameter[];
  layout: FormLayout | null;
  publishedLayout: FormLayout | null;
  publishedAt?: number;
  publishedBy?: string;
  history: HistoryEntry[];
  changes: Changes;
  rules: unknown[];
}

export const DEFAULT_FIELD: FieldLayout = {
  label: null,
  shortDescription: null,
  helpText: null,
  showHelpIcon: true,
  width: "auto",
  linkWithNext: false,
};
