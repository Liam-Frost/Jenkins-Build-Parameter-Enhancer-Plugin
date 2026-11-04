/**
 * Interface text sent by the server (Strings.properties, resolved for the page's locale). The build page gets it
 * in its configuration, the designer from its mount element.
 *
 * A missing key shows the key itself, which the key-consistency test keeps from ever reaching users.
 */
let strings: Record<string, string> = {};

export function setStrings(s: Record<string, string> | null | undefined): void {
  strings = s ?? {};
}

/** The text for `key`, with {0}, {1}, ... replaced by `args` (literally, as on the server). */
export function t(key: string, args?: Array<string | number>): string {
  let s = Object.prototype.hasOwnProperty.call(strings, key) ? strings[key] : key;
  if (args) {
    for (let i = 0; i < args.length; i++) {
      s = s.split("{" + i + "}").join(String(args[i]));
    }
  }
  return s;
}
