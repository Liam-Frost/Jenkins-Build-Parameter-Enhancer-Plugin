/**
 * Inline SVG icons for preset scopes. Stroke icons in the style of Jenkins' own symbols (Ionicons outline),
 * drawn with currentColor so they follow the theme.
 */

const svg = (body: string): string =>
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" ' +
  'stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
  body +
  "</svg>";

/** Shared preset, visible to everyone: a group of people. */
export const ICON_GLOBAL = svg(
  '<circle cx="9" cy="8" r="3.2"/><path d="M3 19.5c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5"/>' +
    '<circle cx="17" cy="9" r="2.5"/><path d="M16.5 14.1c2.6.2 4.5 2.1 4.5 4.9"/>',
);

/** Personal preset, visible only to its owner: a single person. */
export const ICON_PERSONAL = svg('<circle cx="12" cy="8" r="3.5"/><path d="M5 20c0-3.9 3.1-6.5 7-6.5s7 2.6 7 6.5"/>');

export function scopeIcon(isGlobal: boolean): string {
  return isGlobal ? ICON_GLOBAL : ICON_PERSONAL;
}

/** Restore the values from before a preset was applied: a counter-clockwise arrow. */
export const ICON_RESTORE = svg('<path d="M4 4v5h5"/><path d="M4.6 9A8 8 0 1 1 4 13"/>');
