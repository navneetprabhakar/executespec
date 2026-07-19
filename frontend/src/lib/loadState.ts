/**
 * D4 — honest load failures.
 *
 * The repo-wide anti-pattern this replaces is `.catch(() => setItems([]))`:
 * a FAILED request collapses into an empty array, and the surface then renders
 * an affirmative claim ("No members.", "No repositories yet.") about data it
 * never actually retrieved. The user is told "you have none of these" when the
 * truth is "we could not find out."
 *
 * `useAsyncData` keeps three states genuinely distinct — loading, failed, and
 * loaded-and-empty — so a surface can only make the empty claim when it has
 * proof. `toLoadFailure` classifies the error so a 403 reads as an access
 * problem rather than a generic outage (the model here is the audit log's
 * "Audit log is a TEAM+ feature." copy, which is honest about *why*).
 *
 * Pure logic lives here; the matching presentation lives in `@/ide/LoadError`.
 */
import {useCallback, useEffect, useRef, useState} from 'react';

import {ApiError} from '@/lib/api/transport';

export type LoadFailureKind = 'forbidden' | 'missing' | 'offline' | 'generic';

export type LoadFailure = {
  kind: LoadFailureKind;
  /** HTTP status when known. `0` is the transport's network-error sentinel. */
  status: number | null;
  /** Server-supplied or transport-derived message; already human-readable. */
  message: string;
};

/**
 * Classifies a thrown value into a failure a surface can render honestly.
 *
 * `403` is separated deliberately: "you may not see this" and "this is broken"
 * are different facts, and only the first has a useful next step (upgrade, or
 * ask an admin). `404` usually means the endpoint is absent in this profile or
 * gated off the current plan — retrying cannot help either.
 */
export function toLoadFailure(e: unknown): LoadFailure {
  const status = e instanceof ApiError ? e.status : null;
  const message = e instanceof Error && e.message ? e.message : '';
  if (status === 403) {
    return {kind: 'forbidden', status, message: message || 'You do not have access to this.'};
  }
  if (status === 404 || status === 410) {
    return {kind: 'missing', status, message: message || 'This is not available here.'};
  }
  if (status === 0) {
    return {kind: 'offline', status, message: message || 'Could not reach the server.'};
  }
  return {kind: 'generic', status, message: message || 'Something went wrong.'};
}

/** Retrying a permission or availability failure cannot change the answer. */
export function isRetryable(failure: LoadFailure): boolean {
  return failure.kind === 'offline' || failure.kind === 'generic';
}

export type AsyncData<T> = {
  /** Loaded value, or `null` when never successfully loaded. */
  data: T | null;
  /** Non-null when the most recent attempt failed. */
  failure: LoadFailure | null;
  loading: boolean;
  /** Re-runs the loader. Safe to call from a Retry button. */
  reload: () => void;
  /**
   * Local override, for mutations that already know the new value. Accepts the
   * updater form so existing `setX((prev) => ...)` call sites drop in unchanged.
   */
  setData: (value: T | null | ((prev: T | null) => T | null)) => void;
};

/**
 * Loads a value and tracks loading/failure/loaded as three distinct states.
 *
 * The loader is held in a ref so an inline arrow at the call site does not
 * retrigger the effect; re-runs are driven by `deps` and by `reload()`.
 * In-flight results are dropped after unmount or a dependency change, which
 * also removes the stale-write race the old `.then(setX)` sites had.
 */
export function useAsyncData<T>(
  loader: () => Promise<T>,
  deps: unknown[],
  enabled = true,
): AsyncData<T> {
  const [data, setData] = useState<T | null>(null);
  const [failure, setFailure] = useState<LoadFailure | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [nonce, setNonce] = useState(0);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setFailure(null);
    loaderRef
      .current()
      .then((value) => {
        if (cancelled) return;
        setData(value);
        setFailure(null);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // Deliberately NOT falling back to an empty value: an unproven
        // "you have none" is the defect this module exists to remove.
        setData(null);
        setFailure(toLoadFailure(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, nonce, enabled]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const override = useCallback((value: T | null | ((prev: T | null) => T | null)) => {
    setData(value);
    setFailure(null);
  }, []);

  return {data, failure, loading, reload, setData: override};
}
