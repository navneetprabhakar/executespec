import {useCallback, useEffect, useState} from 'react';

/**
 * Signal-scoped theme hook. Independent of the existing useUiStore (which
 * controls v3/v4 theme via `<html data-theme>`). This writes to
 * `.signal-app[data-signal-theme]` instead so the two systems coexist.
 *
 * - Reads `signal:theme` from localStorage first
 * - Falls back to `(prefers-color-scheme: dark)`
 * - Defaults to 'light'
 * - Re-applies whenever the system preference changes if no explicit pick
 */
export type SignalTheme = 'light' | 'dark';

const STORAGE_KEY = 'signal:theme';

function resolveInitial(): SignalTheme {
  try {
    const saved = localStorage.getItem(STORAGE_KEY) as SignalTheme | null;
    if (saved === 'light' || saved === 'dark') return saved;
  } catch {
    // localStorage might be unavailable (SSR, private mode)
  }
  if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

export function useSignalTheme(rootRef: React.RefObject<HTMLElement>) {
  const [theme, setThemeState] = useState<SignalTheme>(() => resolveInitial());

  // Apply to root whenever it changes. We write to BOTH the Shell's local
  // root (for backwards-compat with rules that target `.signal-app[data-…]`)
  // AND `<html>` so public routes outside the Shell also receive the theme.
  useEffect(() => {
    const root = rootRef.current;
    if (root) {
      root.setAttribute('data-signal-theme', theme);
    }
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-signal-theme', theme);
    }
  }, [theme, rootRef]);

  // Listen to system preference if user hasn't picked
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return undefined;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (e: MediaQueryListEvent) => {
      try {
        if (localStorage.getItem(STORAGE_KEY)) return; // user has picked, don't override
      } catch {
        return;
      }
      setThemeState(e.matches ? 'dark' : 'light');
    };
    mq.addEventListener?.('change', handler);
    return () => mq.removeEventListener?.('change', handler);
  }, []);

  const setTheme = useCallback((next: SignalTheme) => {
    setThemeState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // ignore
    }
  }, []);

  const toggle = useCallback(() => {
    setThemeState((cur) => {
      const next: SignalTheme = cur === 'dark' ? 'light' : 'dark';
      try {
        localStorage.setItem(STORAGE_KEY, next);
      } catch {
        // ignore
      }
      return next;
    });
  }, []);

  return {theme, setTheme, toggle};
}
