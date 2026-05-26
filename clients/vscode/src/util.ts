import { ApiError, RunEventType, type RunEvent } from '@executespec/core';

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.status ? `${e.message} (HTTP ${e.status})` : e.message;
  return e instanceof Error ? e.message : String(e);
}

const TERMINAL = new Set(['COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED']);

export function isTerminal(ev: RunEvent): boolean {
  if (ev.type === RunEventType.COMPLETE) return true;
  if (ev.type === RunEventType.RUN_STATUS && ev.payload) {
    return TERMINAL.has(ev.payload.split('|')[0] ?? ev.payload);
  }
  return false;
}

/** A concise one-line rendering for the output channel, or null for the noisier event types. */
export function renderEvent(ev: RunEvent): string | null {
  switch (ev.type) {
    case RunEventType.RUN_STATUS:
      return `• status ${ev.payload ?? ''}`;
    case RunEventType.TASK_STATUS:
      return `  task ${ev.taskId ?? ''} ${ev.payload ?? ''}`;
    case RunEventType.LOG:
      return `  … ${ev.payload ?? ''}`;
    case RunEventType.FILE_WRITTEN:
      return `  ✎ ${ev.payload ?? ''}`;
    case RunEventType.COST_UPDATE:
      return `  $ ${ev.payload ?? ''}`;
    case RunEventType.COMPLETE:
      return `• complete ${ev.payload ?? ''}`;
    case 'REVIEW_COMPLETE':
      return `  review ${ev.payload ?? ''}`;
    default:
      return null;
  }
}
