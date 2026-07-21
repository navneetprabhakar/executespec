/**
 * Per-run cost + per-attempt LLM usage DTOs.
 *
 * Extracted from `core.ts` to keep that shared registry under its committed god-class ceiling
 * (`GodClassLineCeilingTest`), which is explicit that the fix for a breach is to extract the new
 * responsibility rather than raise the limit. `core.ts` re-exports these so the stable import
 * surface documented in `frontend/CLAUDE.md` is unchanged for every existing caller.
 */
export interface RunStepCostCall {
  id: string;
  taskId: string | null;
  agentRole: string | null;
  stepId: string | null;
  retryOf: string | null;
  provider: string | null;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  reasoningTokens: number;
  costUsd: number;
  callIndex: number;
  createdAt: string;
}

export interface RunCostEvidence {
  runId: string;
  schemaVersion: number;
  source: 'stored_total' | 'live_tracker' | 'legacy_estimator';
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number | null;
  cacheWriteTokens: number | null;
  reasoningTokens: number | null;
  calls: number | null;
  phases: {classifier: number; outline: number; expansion: number; coherence: number};
  /**
   * The per-attempt physical LLM ledger. RR-1552: the backend now restricts this to own-org
   * ORG_ADMIN and SUPER_ADMIN, because each row carries key-source (BYOK vs platform),
   * exact provider/model routing, per-attempt spend and redacted provider errors. Every
   * other principal with run read access receives `[]` — which is NOT the same as "this run
   * made no provider calls". Read `stepCallsIncluded` / `stepCallsTotal` to tell those apart.
   */
  stepCalls: RunStepCostCall[];
  /** False when the ledger was withheld for authorization rather than being empty. */
  stepCallsIncluded?: boolean;
  /** Machine-readable reason the ledger was withheld, present only when withheld. */
  stepCallsRestrictedReason?: string | null;
  /** Total attempts on the run, independent of what this page returned. */
  stepCallsTotal?: number;
  /** RR-1558 paging — page 0 is the MOST RECENT attempts. */
  stepCallsPage?: number;
  stepCallsSize?: number;
  stepCallsHasMore?: boolean;
  /**
   * Sum of attempts whose cost is PROVEN. Failed attempts and unpriced routes are persisted
   * as a hard zero, so this is a lower bound whenever `unknownCostAttempts > 0`.
   * `knownCostUsdLowerBound` carries the same number under the honest name.
   */
  attributedCostUsd: number;
  knownCostUsdLowerBound?: number;
  unknownCostAttempts?: number;
  costStatus?: 'KNOWN' | 'UNKNOWN_PROVIDER_NOT_REPORTED_PRESENT' | string;
  unattributedCostUsd: number;
  reconciled: boolean;
  reconciliationToleranceUsd?: number;
}
