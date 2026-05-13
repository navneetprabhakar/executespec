import {useCallback, useEffect, useRef, useState} from 'react';

/**
 * PR-L (2026-05-14) — canonical wrapper for "click → async → done" handlers.
 *
 * Pre-PR-L, every submit / approval / chat-send / autofix-dispatch / regen button used a
 * different ad-hoc pattern (or none) to track in-flight state. Half of them had no pending
 * indication at all, so users would tap → see nothing → tap again. The other half rolled their
 * own `pending` boolean + try/finally without disabling the button, so double-tap still got
 * through.
 *
 * This hook is the one-line replacement:
 *
 *   const [pending, run] = useAsyncAction(async (id: string) => {
 *     await api.archiveProject(id);
 *   });
 *   <button disabled={pending} onClick={() => run(projectId)}>
 *     {pending ? 'Archiving…' : 'Archive'}
 *   </button>
 *
 * Contract:
 * - `pending` is `true` from the moment `run()` is invoked until the returned promise settles
 *   (resolved or rejected — either way `pending` flips back to `false`).
 * - `run()` returns the same promise the underlying handler returned (so callers can `await`
 *   it or `.catch()` it).
 * - Concurrent invocations are debounced: if `pending` is already `true`, subsequent calls
 *   no-op and return the in-flight promise. Protects against double-tap on slow networks.
 * - Component unmount during the in-flight call is safe — we drop the state-setter call so
 *   React doesn't warn about setting state on an unmounted component.
 *
 * Why a hook over a custom ESLint rule: rule maintenance + false-positive triage is a
 * cost the team would inherit indefinitely. A hook is opt-in for new code + a mechanical
 * find-and-replace for existing code. Reviewer can flag missing usage during code review.
 */
export function useAsyncAction<Args extends unknown[], R>(
  handler: (...args: Args) => Promise<R>,
): readonly [pending: boolean, run: (...args: Args) => Promise<R | undefined>] {
  const [pending, setPending] = useState(false);
  const inFlightRef = useRef<Promise<R> | null>(null);
  const mountedRef = useRef(true);

  // Track unmount so the .finally() setPending(false) doesn't trip a React warning when a
  // user navigates away mid-request. Flipped on cleanup of an effect that runs once per
  // mount lifetime.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const run = useCallback(
    async (...args: Args): Promise<R | undefined> => {
      // Concurrent-call debounce. If a previous call is still in flight, return its promise
      // instead of starting a new one — protects against double-tap + accidental React 18
      // strict-mode double-invoke.
      if (inFlightRef.current) {
        return inFlightRef.current;
      }
      setPending(true);
      const promise = handler(...args);
      inFlightRef.current = promise;
      try {
        const result = await promise;
        return result;
      } finally {
        inFlightRef.current = null;
        if (mountedRef.current) setPending(false);
      }
    },
    [handler],
  );

  return [pending, run] as const;
}
