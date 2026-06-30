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
  aiCreditsRemaining: number;
  frontierRawCapUsd?: number | null;
  cheapOnlyAfterRawUsd?: number | null;
}): string {
  if (input.managed === false) {
    return 'Using your own provider keys. Managed AI credits are not applicable to this work; run credits and your provider account still apply.';
  }
  return `${input.aiCreditsRemaining} AI credits left this cycle. Approval charges run credits now; managed-AI credits debit per successful model call while work executes. Frontier calls step down automatically as you approach the managed-AI cap.`;
}
