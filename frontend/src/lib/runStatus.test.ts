import {describe, expect, it} from 'vitest';

import {
  isActiveRunStatus,
  isAwaitingApprovalRunStatus,
  isExecutingRunStatus,
  isLiveRunStatus,
  isTerminalRunStatus,
} from './runStatus';
import {isPlanExecutingStatus, isPlanTerminalStatus} from '@/ide/planUtils';
import {isLiveStatus} from '@/ide/moreUtils';
import {isTerminalRunStatus as agentIsTerminal} from '@/ide/agentState';

/**
 * RR-1463 / RR-1521 / RR-1536 — seeded from the backend's own literal sets:
 * `RunLifecycleService.NON_TERMINAL_STATUSES` and `RunPipelineSupport.isTerminal`.
 * Every status the backend can persist must classify, and it must classify the same
 * way through every consumer.
 */
const BACKEND_NON_TERMINAL = [
  'QUEUED',
  'PLANNING',
  'AWAITING_APPROVAL',
  'APPROVAL_RESUMING',
  'WORKING',
  'REVIEWING',
] as const;

const BACKEND_TERMINAL = ['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'] as const;

describe('canonical run-status vocabulary', () => {
  it('treats every backend non-terminal status as active work', () => {
    for (const status of BACKEND_NON_TERMINAL) {
      expect(isActiveRunStatus(status), status).toBe(true);
      expect(isTerminalRunStatus(status), status).toBe(false);
    }
    // Halted-but-unfinished states are still in flight.
    expect(isActiveRunStatus('PAUSED')).toBe(true);
    expect(isActiveRunStatus('STUCK')).toBe(true);
  });

  it('treats every backend terminal status as settled', () => {
    for (const status of BACKEND_TERMINAL) {
      expect(isTerminalRunStatus(status), status).toBe(true);
      expect(isActiveRunStatus(status), status).toBe(false);
    }
  });

  it('classifies APPROVAL_RESUMING as execution, not as an unknown status', () => {
    // The regression that motivated the shared map: an approved run sat in this state
    // while every hardcoded list treated it as neither executing nor active.
    expect(isExecutingRunStatus('APPROVAL_RESUMING')).toBe(true);
    expect(isLiveRunStatus('APPROVAL_RESUMING')).toBe(true);
    expect(isAwaitingApprovalRunStatus('APPROVAL_RESUMING')).toBe(false);
    expect(isTerminalRunStatus('APPROVAL_RESUMING')).toBe(false);
  });

  it('keeps the plan gate distinct from execution', () => {
    expect(isAwaitingApprovalRunStatus('AWAITING_APPROVAL')).toBe(true);
    expect(isExecutingRunStatus('AWAITING_APPROVAL')).toBe(false);
    expect(isLiveRunStatus('AWAITING_APPROVAL')).toBe(true);
  });

  it('normalizes case and treats an absent status as unclassified', () => {
    expect(isExecutingRunStatus('working')).toBe(true);
    expect(isTerminalRunStatus('complete')).toBe(true);
    expect(isActiveRunStatus(null)).toBe(false);
    expect(isActiveRunStatus(undefined)).toBe(false);
    expect(isActiveRunStatus('')).toBe(false);
  });

  it('agrees across every consumer that previously kept its own list', () => {
    for (const status of [...BACKEND_NON_TERMINAL, ...BACKEND_TERMINAL, 'PAUSED', 'STUCK']) {
      expect(isPlanExecutingStatus(status), `plan/${status}`).toBe(isExecutingRunStatus(status));
      expect(isPlanTerminalStatus(status), `plan-terminal/${status}`).toBe(
        isTerminalRunStatus(status),
      );
      expect(isLiveStatus(status), `live/${status}`).toBe(isLiveRunStatus(status));
      expect(agentIsTerminal(status), `agent/${status}`).toBe(isTerminalRunStatus(status));
    }
  });
});
