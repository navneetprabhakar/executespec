import {useState, useEffect, useRef, useCallback} from 'react';

type Breakpoint = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

const BREAKPOINTS: {name: Breakpoint; minWidth: number}[] = [
  {name: 'xl', minWidth: 1280},
  {name: 'lg', minWidth: 1024},
  {name: 'md', minWidth: 768},
  {name: 'sm', minWidth: 640},
];

function getBreakpoint(): Breakpoint {
  if (typeof window === 'undefined') return 'lg';
  const w = window.innerWidth;
  for (const bp of BREAKPOINTS) {
    if (w >= bp.minWidth) return bp.name;
  }
  return 'xs';
}

/**
 * Returns the current Tailwind breakpoint based on viewport width.
 * Matches Tailwind defaults: sm:640, md:768, lg:1024, xl:1280.
 * Uses a debounced resize listener (150ms) and cleans up on unmount.
 */
export function useBreakpoint(): Breakpoint {
  const [breakpoint, setBreakpoint] = useState<Breakpoint>(getBreakpoint);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleResize = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      setBreakpoint(getBreakpoint());
    }, 150);
  }, []);

  useEffect(() => {
    window.addEventListener('resize', handleResize);
    // Sync in case value changed between render and effect
    setBreakpoint(getBreakpoint());
    return () => {
      window.removeEventListener('resize', handleResize);
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [handleResize]);

  return breakpoint;
}

/** Returns true when the viewport is below the `md` breakpoint (768px). */
export function useIsSmall(): boolean {
  const bp = useBreakpoint();
  return bp === 'xs' || bp === 'sm';
}

/** Returns true when the viewport is between `md` (768px) and `lg` (1024px). */
export function useIsTablet(): boolean {
  const bp = useBreakpoint();
  return bp === 'md';
}

/** Returns true when the viewport is at or above the `lg` breakpoint (1024px). */
export function useIsDesktop(): boolean {
  const bp = useBreakpoint();
  return bp === 'lg' || bp === 'xl';
}
