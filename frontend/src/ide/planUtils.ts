// RR-1463 — the plan page's own executing/terminal sets omitted APPROVAL_RESUMING and
// WORKING, so a run approved in another tab stayed on the static gate instead of routing
// to Live. Both predicates now delegate to the one shared vocabulary in lib/runStatus.
import {
  isExecutingRunStatus,
  isPlanningRunStatus,
  isQueuedRunStatus,
  isTerminalRunStatus,
} from '@/lib/runStatus';

export function formatPlanUsd(value?: number | null): string {
  return value == null ? '—' : `$${value.toFixed(4)}`;
}

export function managedAiBadgeKind(state?: string | null): 'ok' | 'warn' | 'bad' {
  if (state === 'EXHAUSTED') return 'bad';
  if (state === 'CHEAP_ONLY' || state === 'FRONTIER_LOCKED' || state === 'VALUE_MODE') {
    return 'warn';
  }
  return 'ok';
}

export function isPlanExecutingStatus(status?: string | null): boolean {
  return isExecutingRunStatus(status);
}

export function isPlanTerminalStatus(status?: string | null): boolean {
  return isTerminalRunStatus(status);
}

export function planStatusPhrase(status: string): string {
  const normalized = status.toUpperCase();
  if (isPlanTerminalStatus(normalized)) return 'finished — see the result page';
  if (normalized === 'REVIEWING') return 'in review';
  // APPROVAL_RESUMING is the state a just-approved run occupies while the pipeline
  // picks it back up, so it reads as execution rather than the generic fallback.
  if (isExecutingRunStatus(normalized)) return 'executing';
  if (isPlanningRunStatus(normalized) || isQueuedRunStatus(normalized) || normalized === '') {
    return 'being planned';
  }
  return 'in progress';
}

export function formatManagedAiPlanMessage(input: {
  managed?: boolean | null;
  unlimitedAiCredits?: boolean | null;
  aiCreditsRemaining: number;
  includedAiCredits?: number | null;
  frontierRawCapUsd?: number | null;
  cheapOnlyAfterRawUsd?: number | null;
}): string {
  if (hasUnlimitedAiCredits(input)) {
    return 'Unlimited AI credits for this tier. Managed-AI credit limits do not block this work; run approval and provider-key policy still apply.';
  }
  return `${input.aiCreditsRemaining} AI credits left this cycle. Approval charges run credits now; managed-AI credits debit per successful model call while work executes. Frontier calls step down automatically as you approach the managed-AI cap.`;
}

export function hasUnlimitedAiCredits(input: {
  managed?: boolean | null;
  unlimitedAiCredits?: boolean | null;
  includedAiCredits?: number | null;
  aiCreditsRemaining?: number | null;
}): boolean {
  return (
    input.unlimitedAiCredits === true ||
    input.includedAiCredits === -1 ||
    input.aiCreditsRemaining === -1 ||
    (input.managed === false && (input.includedAiCredits ?? 0) <= 0)
  );
}

export function managedAiStatusLabel(input: {
  managed?: boolean | null;
  unlimitedAiCredits?: boolean | null;
  includedAiCredits?: number | null;
  aiCreditsRemaining?: number | null;
  routingState?: string | null;
}): string {
  return hasUnlimitedAiCredits(input) ? 'Unlimited' : input.routingState || 'FULL';
}

/**
 * RR-1459 — stable identity of the exact plan the approver was shown, matching the server's
 * `RunController.planFingerprintOf`: lowercase hex SHA-256 over the UTF-8 bytes of the persisted
 * plan JSON.
 *
 * Returns `undefined` when the run carries no plan, or when the browser exposes no WebCrypto
 * digest (an insecure context, or a test environment). The server treats an absent fingerprint as
 * "not supplied" and falls back to the credits check, so an unavailable digest degrades to the
 * previous behaviour rather than fabricating an identity that would either wrongly pass or
 * wrongly block.
 */
export async function planFingerprint(planJson?: string | null): Promise<string | undefined> {
  if (!planJson || !planJson.trim()) return undefined;
  const subtle = globalThis.crypto?.subtle;
  if (!subtle || typeof subtle.digest !== 'function') return undefined;
  try {
    const bytes = new TextEncoder().encode(planJson);
    const digest = await subtle.digest('SHA-256', bytes);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  } catch {
    return undefined;
  }
}
