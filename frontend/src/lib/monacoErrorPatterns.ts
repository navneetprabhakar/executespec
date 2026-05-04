/**
 * Patterns matching benign Monaco disposal races.
 *
 * Symptom (first observed run a076c876 apply path 2026-05-03; reproduced
 * on dev 2026-05-04 with the V3 RunDetailPage diff viewer):
 *   "Uncaught Error: TextModel got disposed before DiffEditorWidget model got reset"
 *
 * Used by BOTH:
 *   1. `MonacoSafeMount` — React error boundary, catches in-render throws.
 *   2. `monacoSetup.ts` — window-level handler, catches Monaco's
 *      setTimeout-deferred re-throws that React boundaries can't reach
 *      (Monaco's `onUnexpectedError` wraps the rethrow in `setTimeout`
 *      to surface it past sync handlers, which means it escapes the React
 *      render tree entirely; stack hint: `errors.ts:26 → setTimeout`).
 *
 * Match conservatively — every pattern should have a comment + the run id
 * or context where it was first observed so we don't accidentally swallow
 * real bugs.
 */
export const MONACO_DISPOSE_RACE_PATTERNS: readonly RegExp[] = [
  // Primary signature — fires when the OLD diff TextModel is disposed by
  // the React unmount cleanup before Monaco's deferred model-reset event
  // has a chance to apply to the new TextModel. Both `original` and
  // `modified` sides emit this independently, so a single file switch can
  // produce two "Uncaught Error" lines.
  /TextModel got disposed before DiffEditorWidget model got reset/i,
  // Secondary signature — model accessor invoked on a disposed model
  // during the same race window (e.g. model.getValue()). Less common but
  // observed in the same console burst as the primary signature.
  /Cannot read properties of (?:undefined|null) \(reading 'getValue'\)/i,
];

export function isMonacoDisposeRace(message: string | undefined | null): boolean {
  if (!message) return false;
  return MONACO_DISPOSE_RACE_PATTERNS.some((p) => p.test(message));
}
