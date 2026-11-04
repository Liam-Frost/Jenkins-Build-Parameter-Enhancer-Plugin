/**
 * Locates the native parameter controls on Jenkins' Build with Parameters page.
 *
 * The only structure relied on is the one Jenkins' own form submission relies on: every parameter renders a
 * container with `name="parameter"` holding a hidden `name` input (see ParametersDefinitionProperty#_doBuild).
 * That contract holds for core parameter types and for third-party ones, because a parameter that does not follow
 * it cannot be submitted at all. Nothing is matched by CSS class, label text or position.
 */

export interface Chunk {
  /** Parameter name as submitted to Jenkins. */
  name: string;
  /** Top-level node inside the form body that contains the whole native control. */
  node: HTMLElement;
  /** The `[name=parameter]` container itself. */
  param: HTMLElement;
  originalParent: Node;
  originalNext: Node | null;
}

export interface ScanResult {
  form: HTMLFormElement;
  body: HTMLElement;
  chunks: Chunk[];
}

export type ScanFailure = { error: string };

export function isFailure(r: ScanResult | ScanFailure): r is ScanFailure {
  return (r as ScanFailure).error !== undefined;
}

export function scan(root: ParentNode, expectedNames: string[]): ScanResult | ScanFailure {
  const form = root.querySelector<HTMLFormElement>('form[name="parameters"]');
  if (!form) {
    return { error: "no parameters form on this page" };
  }
  const params = Array.from(form.querySelectorAll<HTMLElement>('[name="parameter"]'));
  if (params.length === 0) {
    return { error: "no parameter containers found" };
  }
  // f:form wraps its body in a single div; parameters are rendered as its children.
  const body = (params[0].closest("form > *") as HTMLElement | null) ?? form;
  const chunks: Chunk[] = [];
  const seen = new Set<string>();
  for (const param of params) {
    const nameInput = param.querySelector<HTMLInputElement>('input[type="hidden"][name="name"]');
    if (!nameInput || !nameInput.value) {
      return { error: "a parameter container has no name input" };
    }
    const name = nameInput.value;
    if (seen.has(name)) {
      return { error: `parameter ${name} appears twice in the form` };
    }
    const node = topLevelNode(param, body);
    if (!node) {
      return { error: `could not find the block of parameter ${name}` };
    }
    if (node.querySelectorAll('[name="parameter"]').length > 1) {
      // Two parameters share one block; moving it would split or duplicate them.
      return { error: `the block of parameter ${name} contains more than one parameter` };
    }
    seen.add(name);
    chunks.push({
      name,
      node,
      param,
      originalParent: node.parentNode as Node,
      originalNext: node.nextSibling,
    });
  }
  const expected = new Set(expectedNames);
  if (expected.size !== seen.size) {
    return { error: `the page has ${seen.size} parameters, the layout describes ${expected.size}` };
  }
  for (const n of expected) {
    if (!seen.has(n)) {
      return { error: `parameter ${n} is not on the page` };
    }
  }
  return { form, body, chunks };
}

/** Walks up from the parameter container to the child of `body` that contains it. */
function topLevelNode(param: HTMLElement, body: HTMLElement): HTMLElement | null {
  let node: HTMLElement | null = param;
  while (node && node.parentElement && node.parentElement !== body) {
    node = node.parentElement;
    if (node === body) {
      return null;
    }
  }
  return node && node.parentElement === body ? node : null;
}
