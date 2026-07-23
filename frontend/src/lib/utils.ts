/**
 * Pull a human-readable message out of an unknown thrown value. Used at every
 * `catch` site in the app so we can keep `unknown` (TS 4.4+ default for catch
 * params, strictest type) without sprinkling ad-hoc `e instanceof Error`
 * narrowing. Falls through to `String(e)` for non-Error throwables (string
 * throws, plain objects with a toJSON, etc.).
 */
export function errMsg(e: unknown): string {
  if (e instanceof Error) return e.message;
  if (typeof e === 'string') return e;
  if (
    e &&
    typeof e === 'object' &&
    'message' in e &&
    typeof (e as {message: unknown}).message === 'string'
  ) {
    return (e as {message: string}).message;
  }
  return String(e);
}

/** Rendered when a cost is genuinely unknown, so it cannot be read as a spend of zero. */
export const UNKNOWN_COST = '—';

/**
 * RR-1538 — this used to declare a non-nullable `number` while branching on
 * `!Number.isFinite(usd)` to return `'$0.0000'`, converting "we do not know what this
 * run cost" into the exact claim "this run cost nothing" on a spend-reporting surface.
 * `RunSummary.actualCostUsd` is `number | null`, so the nullable input was always
 * reachable; the signature simply hid it from the compiler. Every existing call site
 * already guarded, so widening the signature is what stops the next one from not.
 *
 * A genuine zero still renders `$0.0000` and stays distinguishable from unknown.
 */
export function fmtCost(usd: number | null | undefined): string {
  if (usd == null || !Number.isFinite(usd)) return UNKNOWN_COST;
  return `$${usd.toFixed(4)}`;
}

// RR-1535 — `cn`, `fmtTokens`, `fmtMs`, `relativeTime`, `truncate` and `describeRunStatus`
// were removed: none had a single importer anywhere in `src/`, and together they were
// ~110 of this file's 135 lines. `cn` was the only consumer of `clsx` + `tailwind-merge`,
// which are now dropped from package.json. Live equivalents already exist elsewhere
// (`relTime` in `signal/lib/runUtils`). `describeRunStatus` in particular read like the
// canonical run-status vocabulary while governing nothing and being wrong in both
// directions (RR-1536) — the real vocabulary now lives in `lib/runStatus.ts`, which the
// surfaces that actually render status consume.
