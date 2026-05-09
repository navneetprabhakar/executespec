import {useCallback, useEffect, useRef, useState} from 'react';

/**
 * F-12 fix (2026-05-09) — single fetch state machine for the whole
 * frontend. Replaces the ad-hoc `useState<X | null>(null) + useState(loading) +
 * useEffect(...)` triplet that every page was rolling itself, badly:
 *
 *   - InsightsPage / RunsNewPage / TeamPage / LibraryPage / etc all
 *     swallow errors via `.catch(() => null)` and never set an error
 *     state. The audit found 7/10 v3 pages have no error UI at all.
 *
 *   - Some pages set `loading = true` and never reset on a `.catch`
 *     branch — the spinner spins forever on failure.
 *
 *   - Each page implements its own retry button (or doesn't), with
 *     no consistency on copy or affordance.
 *
 * Pair with `<DataState>` to render the right branch (loading / error /
 * empty / success) without each consumer reinventing it.
 *
 * Usage:
 *   const {data, loading, error, retry} = useFetch(
 *     () => api.getInsights(window),
 *     [window],
 *   );
 *   return <DataState loading={loading} error={error} onRetry={retry} data={data}>
 *     {(d) => <InsightsBody data={d} />}
 *   </DataState>;
 */
export interface FetchState<T> {
  data: T | null;
  loading: boolean;
  error: Error | null;
  retry: () => void;
}

export function useFetch<T>(fn: () => Promise<T>, deps: ReadonlyArray<unknown> = []): FetchState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);
  // Latest-call guard so a stale response doesn't overwrite a fresh one
  // when deps change quickly (e.g. typing a filter).
  const callId = useRef(0);

  useEffect(() => {
    const my = ++callId.current;
    let cancelled = false;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => {
        if (cancelled || my !== callId.current) return;
        setData(d);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (cancelled || my !== callId.current) return;
        setError(e instanceof Error ? e : new Error(String(e)));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // The caller's `deps` plus our `tick` (incremented by `retry()`)
    // drive every refetch. `fn` is intentionally not in the deps —
    // callers usually pass an inline arrow which would refetch every
    // render; if they want fn to participate they should `useCallback`
    // it themselves and put it in `deps`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const retry = useCallback(() => setTick((t) => t + 1), []);
  return {data, loading, error, retry};
}
