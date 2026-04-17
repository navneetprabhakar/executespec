import { useState, useEffect } from 'react';

const COMPACT_BREAKPOINT = '(max-width: 1023px)';
const MOBILE_BREAKPOINT  = '(max-width: 767px)';

function useMatch(query: string): boolean {
  const [matches, setMatches] = useState(() =>
    typeof window !== 'undefined' ? window.matchMedia(query).matches : false,
  );
  useEffect(() => {
    const mql = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener('change', handler);
    setMatches(mql.matches);
    return () => mql.removeEventListener('change', handler);
  }, [query]);
  return matches;
}

/**
 * v2.5.12 — semantic split. Previously {@code useIsMobile} returned true
 * for any viewport below 1024 px (the `lg` Tailwind breakpoint), which
 * incorrectly classified tablets as "mobile" and drove the wrong layout
 * branch in places like {@code ResponsiveModal} and {@code RunDetail}.
 *
 * - {@link useIsCompactLayout} — true under `lg` (1024 px). Use for layouts
 *   that should collapse a 3-pane desktop view into a stacked one.
 * - {@link useIsMobile} — true under `md` (768 px). Use for layouts that
 *   are genuinely phone-sized (no side panels, single column, larger tap
 *   targets).
 */
export function useIsCompactLayout(): boolean {
  return useMatch(COMPACT_BREAKPOINT);
}

export function useIsMobile(): boolean {
  // BACKCOMPAT: existing call sites historically assumed "below 1024 px"
  // semantics, so this name keeps that meaning until call sites migrate.
  // New code should pick the explicit hook above.
  return useMatch(COMPACT_BREAKPOINT);
}

/** True under 768 px (md). Use for genuinely phone-sized affordances. */
export function useIsPhone(): boolean {
  return useMatch(MOBILE_BREAKPOINT);
}
