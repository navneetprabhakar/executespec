import { useState, useEffect } from 'react';

const MOBILE_BREAKPOINT = '(max-width: 1023px)';

/**
 * Returns true when the viewport is below the `lg` Tailwind breakpoint (1024px).
 * Uses matchMedia for efficient listener-based updates (no resize polling).
 */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(() =>
    typeof window !== 'undefined' ? window.matchMedia(MOBILE_BREAKPOINT).matches : false,
  );

  useEffect(() => {
    const mql = window.matchMedia(MOBILE_BREAKPOINT);
    const handler = (e: MediaQueryListEvent) => setIsMobile(e.matches);
    mql.addEventListener('change', handler);
    // Sync in case the initial value changed between render and effect
    setIsMobile(mql.matches);
    return () => mql.removeEventListener('change', handler);
  }, []);

  return isMobile;
}
