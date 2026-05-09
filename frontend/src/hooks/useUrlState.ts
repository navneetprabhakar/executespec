import {useCallback, useEffect, useState} from 'react';
import {useLocation, useNavigate} from 'react-router-dom';

/**
 * F-24 fix (2026-05-09) — generic URL-backed state hook for arbitrary
 * query params. Replaces ad-hoc `URLSearchParams.get` + manual
 * `history.replaceState` plumbing scattered across the app.
 *
 * Why: the audit found ProjectDashboardPage only round-trips three
 * named query params (`tab` / `sub` / `codeSub`); every other key
 * (`reviewId`, `finding`, `run`, `consumed`, `from`, custom support
 * deep-links etc.) gets silently dropped on first render and isn't
 * preserved across in-app navigation. Support deep links carrying
 * extra context become unreliable.
 *
 * Behaviour:
 *   - Reads the current value of `?key=` on every location.search change.
 *   - `set(value | null)` writes back via history.replaceState (no
 *     back-button hop) preserving every OTHER query key the URL had.
 *   - Stable `set` reference suitable for deps arrays.
 *
 * Usage:
 *   const [reviewId, setReviewId] = useUrlState<string | null>('reviewId', null);
 *   useEffect(() => { if (reviewId) loadReview(reviewId); }, [reviewId]);
 *
 *   const [tab, setTab] = useUrlState<TabKey>('tab', 'overview');
 *
 * Pass a `parser` to coerce the raw string into a typed value (e.g.
 * to clamp to a known enum).
 */
export interface UseUrlStateOptions<T> {
  /** Optional parser; receives the raw query value (or null when absent)
   *  and returns the typed value. Defaults to identity (returns raw or
   *  the supplied default). */
  parse?: (raw: string | null) => T;
  /** Optional serializer; receives the typed value and returns the raw
   *  query value (or null to delete the key). Defaults to identity. */
  serialize?: (value: T) => string | null;
  /** When true, uses `history.pushState` instead of `replaceState` so
   *  the back button steps through value changes. Default false (tab
   *  toggles, etc., shouldn't pollute browser history). */
  pushHistory?: boolean;
}

export function useUrlState<T>(
  key: string,
  defaultValue: T,
  options: UseUrlStateOptions<T> = {},
): [T, (value: T) => void] {
  const location = useLocation();
  const navigate = useNavigate();
  const {parse, serialize, pushHistory = false} = options;

  const read = useCallback(
    (search: string): T => {
      const raw = new URLSearchParams(search).get(key);
      if (parse) return parse(raw);
      // Identity path: raw string, or default when missing.
      return (raw ?? (defaultValue as unknown as string)) as unknown as T;
    },
    // defaultValue intentionally not in deps to avoid retriggering when
    // a caller passes a fresh default object reference each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [key, parse],
  );

  const [value, setValueLocal] = useState<T>(() => read(location.search));

  // Re-derive when the URL changes (browser back/forward, in-app Link
  // clicks that update location.search).
  useEffect(() => {
    setValueLocal(read(location.search));
  }, [location.search, read]);

  const set = useCallback(
    (next: T) => {
      const url = new URL(window.location.href);
      const raw = serialize ? serialize(next) : (next as unknown as string | null);
      if (raw == null || raw === '') {
        url.searchParams.delete(key);
      } else {
        url.searchParams.set(key, raw);
      }
      if (pushHistory) {
        navigate(url.pathname + url.search + url.hash);
      } else {
        window.history.replaceState(null, '', url.toString());
      }
      setValueLocal(next);
    },
    [key, serialize, pushHistory, navigate],
  );

  return [value, set];
}
