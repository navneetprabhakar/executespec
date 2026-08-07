/**
 * Per-browser "skip" marks for rows in the All decisions queue.
 *
 * <h2>What a skip is, and is not</h2>
 *
 * Skipping hides a row from THIS queue, in THIS browser. It does not touch the run: nothing is
 * cancelled, rejected, archived, or reported to the server, and the run keeps executing and keeps
 * appearing everywhere else. There is no server-side "decision skipped" fact to write — the four
 * server-side decisions (SKIP / RETRY / FIX / ATTEMPT_ANYWAY) belong to the Execution View
 * per-solution gate, which is a different surface with a different state machine. Conflating the
 * two would let a triage gesture here silently claim an authority it does not have.
 *
 * So the UI must say "hidden from your queue", never "skipped" in a way that implies the work was
 * skipped. A skip is always reversible and always visible as a count.
 *
 * <h2>Storage</h2>
 *
 * localStorage, versioned key, bounded and time-pruned so an unbounded triage history cannot grow
 * without limit. A quota error or a disabled store degrades to "nothing is skipped" rather than
 * throwing into a render.
 */

const KEY = 'es:decisions:skipped:v1';
const MAX_ENTRIES = 200;
const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

export interface DecisionSkip {
  runId: string;
  at: number;
}

function read(): DecisionSkip[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (e): e is DecisionSkip =>
        !!e &&
        typeof e === 'object' &&
        typeof (e as DecisionSkip).runId === 'string' &&
        (e as DecisionSkip).runId.length > 0 &&
        typeof (e as DecisionSkip).at === 'number' &&
        Number.isFinite((e as DecisionSkip).at),
    );
  } catch {
    return [];
  }
}

function write(entries: DecisionSkip[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(entries));
  } catch {
    // A full or unavailable store must not break triage; the row simply stays visible.
  }
}

/** Current skips, newest first, with expired entries dropped. */
export function loadSkips(now: number = Date.now()): DecisionSkip[] {
  const fresh = read()
    .filter((e) => now - e.at < MAX_AGE_MS)
    .sort((a, b) => b.at - a.at)
    .slice(0, MAX_ENTRIES);
  return fresh;
}

export function skipDecision(runId: string, now: number = Date.now()): DecisionSkip[] {
  if (!runId) return loadSkips(now);
  const next = [{runId, at: now}, ...loadSkips(now).filter((e) => e.runId !== runId)].slice(
    0,
    MAX_ENTRIES,
  );
  write(next);
  return next;
}

export function unskipDecision(runId: string, now: number = Date.now()): DecisionSkip[] {
  const next = loadSkips(now).filter((e) => e.runId !== runId);
  write(next);
  return next;
}

export function clearSkips(): DecisionSkip[] {
  write([]);
  return [];
}

export function skippedIds(skips: DecisionSkip[]): Set<string> {
  return new Set(skips.map((e) => e.runId));
}
