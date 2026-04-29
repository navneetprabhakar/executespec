/**
 * R-180 — v3 opt-in flag.
 *
 * Lives in localStorage so it survives refresh and is checked
 * synchronously at render time (any async store would race the
 * first paint). Two functions: read + write.
 *
 * The migration plan in the greenfield doc §10 specifies a hard
 * cut eventually; until then, this opt-in path lets users dogfood
 * v3 routes from any v2 entry point without forcing them to
 * remember the /v3/* prefix.
 */

const KEY = 'v3-opt-in';

export function isV3OptedIn(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

export function setV3OptedIn(value: boolean): void {
  if (typeof window === 'undefined') return;
  try {
    if (value) localStorage.setItem(KEY, '1');
    else localStorage.removeItem(KEY);
  } catch {
    /* private mode */
  }
}

/**
 * Map of v2 → v3 route prefixes for the opt-in redirect. Order
 * matters: most-specific match wins. The redirect helper only
 * matches against the requested route's path; query strings + hash
 * are preserved verbatim.
 */
export const V2_TO_V3_REDIRECTS: ReadonlyArray<readonly [v2: string, v3: string]> = [
  // Most-specific first.
  // R-341 (2026-04-29): /v3/landing is now a redirect to root — the
  // static marketing site at executespec.ai is the canonical public
  // landing surface.  v2 →  v3 entry points that previously aimed at
  // /v3/landing now aim at /v3/home (the silent dispatcher).
  ['/runs/new', '/v3/home'],
  ['/welcome', '/v3/home'],
  ['/dashboard', '/v3/insights'],
  ['/exec', '/v3/insights'],
  ['/admin/analytics', '/v3/insights'],
  ['/projects', '/v3/home'],
  ['/integrations', '/v3/integrations'],
  ['/settings', '/v3/settings'],
  ['/billing', '/v3/billing'],
  ['/plans', '/v3/billing'],
  ['/pricing', '/v3/pricing'],
  // Catch-all home — last so the more-specific entries above win.
  ['/', '/v3/home'],
];

/** Resolve the v3 destination for a v2 path, or null if no rule matches. */
export function v3Destination(v2Path: string): string | null {
  // Exact match preferred over prefix match for safety.
  for (const [v2, v3] of V2_TO_V3_REDIRECTS) {
    if (v2Path === v2) return v3;
  }
  return null;
}
