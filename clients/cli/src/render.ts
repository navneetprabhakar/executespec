import type { RunSummary } from '@executespec/core';

import { dim, green, red, yellow } from './ui.js';

/** Colour a run/task status string by its leading state (handles the `STATUS|extra` shape). */
export function statusColor(status: string): string {
  switch ((status ?? '').split('|')[0]) {
    case 'COMPLETE':
      return green(status);
    case 'FAILED':
    case 'CANCELLED':
      return red(status);
    case 'PARTIAL':
    case 'AWAITING_APPROVAL':
      return yellow(status);
    default:
      return status;
  }
}

/** One-line run summary: `id  status  spec` (spec collapsed + truncated). */
export function runLine(r: RunSummary): string {
  const spec = (r.userRequest ?? '').replace(/\s+/g, ' ').slice(0, 60);
  return `${dim(r.runId)}  ${statusColor(r.status)}  ${spec}`;
}

export function truncate(s: string | undefined | null, n: number): string {
  if (!s) return '';
  return s.length > n ? s.slice(0, n) + '…' : s;
}
