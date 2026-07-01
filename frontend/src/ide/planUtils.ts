const EXECUTING_STATUSES = new Set(['EXECUTING', 'RUNNING', 'REVIEWING', 'BUILDING']);
const TERMINAL_STATUSES = new Set([
  'COMPLETE',
  'COMPLETED',
  'PARTIAL',
  'FAILED',
  'ERROR',
  'CANCELLED',
]);

export function formatPlanUsd(value?: number | null): string {
  return value == null ? '—' : `$${value.toFixed(2)}`;
}

export function managedAiBadgeKind(state?: string | null): 'ok' | 'warn' | 'bad' {
  if (state === 'EXHAUSTED') return 'bad';
  if (state === 'CHEAP_ONLY' || state === 'FRONTIER_LOCKED' || state === 'VALUE_MODE') {
    return 'warn';
  }
  return 'ok';
}

export function isPlanExecutingStatus(status?: string | null): boolean {
  return EXECUTING_STATUSES.has((status || '').toUpperCase());
}

export function isPlanTerminalStatus(status?: string | null): boolean {
  return TERMINAL_STATUSES.has((status || '').toUpperCase());
}

export function planStatusPhrase(status: string): string {
  const normalized = status.toUpperCase();
  if (isPlanTerminalStatus(normalized)) return 'finished — see the result page';
  if (normalized === 'REVIEWING') return 'in review';
  if (
    normalized === 'WORKING' ||
    normalized === 'EXECUTING' ||
    normalized === 'RUNNING' ||
    normalized === 'BUILDING'
  ) {
    return 'executing';
  }
  if (
    normalized === 'PLANNING' ||
    normalized === 'CLASSIFYING' ||
    normalized === 'PENDING' ||
    normalized === 'SUBMITTED' ||
    normalized === ''
  ) {
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
