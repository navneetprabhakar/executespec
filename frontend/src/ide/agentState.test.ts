import {describe, expect, it} from 'vitest';

import {
  actionFromTurn,
  agentReducer,
  currency,
  deriveBarStatus,
  initialAgentState,
  isTerminalRunStatus,
  pct,
  runOpenPath,
  runPhaseLabel,
  runProgress,
  sessionTitle,
  toMessages,
} from './agentState';
import type * as api from '@/lib/api';

const session: api.AgentSessionResponse = {
  id: 'session-1',
  projectId: 'project-1',
  runId: null,
  repoRemote: null,
  surface: 'WEB',
  title: 'A very long agent session title that should be capped for narrow rails',
  status: 'ACTIVE',
  defaultProvider: 'deepseek',
  defaultModel: 'deepseek-v4-pro-native',
  defaultExecutionMode: 'PRODUCTION',
  approvalPolicy: 'CONFIRM_MUTATIONS',
  latestLinkedRunId: null,
  latestLinkedRunStatus: null,
  latestLinkedRunTitle: null,
  latestLinkedRunStartedAt: null,
  latestLinkedRunCompletedAt: null,
  latestLinkedRunPrUrl: null,
  createdAt: '2026-06-20T00:00:00Z',
  updatedAt: '2026-06-20T00:00:00Z',
};

const turn: api.AgentTurnResponse = {
  turnId: 'turn-1',
  sessionId: 'session-1',
  sequence: 1,
  userMessage: 'Create a run',
  assistantSummary: 'Create a cloud run?',
  intentType: 'CODE_RUN_REQUIRED',
  intentConfidence: 0.9,
  route: 'CODE_RUN_REQUIRED',
  reasonCode: 'explicit_code_mutation_request',
  actionType: 'create_cloud_run',
  proposedActionJson: JSON.stringify({
    type: 'create_cloud_run',
    arguments: {spec: 'Add audit logs'},
    requiresConfirmation: true,
    billingImpact: 'run_credit_on_plan_approval',
    confirmationPrompt: 'Create a cloud run?',
  }),
  linkedRunId: null,
  inputTokens: 20,
  outputTokens: 10,
  reasoningTokens: 0,
  costUsd: 0.001,
  status: 'NEEDS_CONFIRMATION',
  createdAt: '2026-06-20T00:00:00Z',
};

describe('agentState', () => {
  it('tracks route and proposal transitions in the reducer', () => {
    const streaming = agentReducer(initialAgentState, {
      type: 'stream:start',
      message: 'Add audit logs',
    });
    expect(streaming.status).toBe('streaming');
    expect(streaming.pendingUser).toBe('Add audit logs');

    const routed = agentReducer(streaming, {
      type: 'stream:route',
      route: {
        session_id: 'session-1',
        turn_id: 'turn-1',
        route: 'CODE_RUN_REQUIRED',
        confidence: 0.92,
        reason_code: 'explicit_code_mutation_request',
        run_api_allowed: true,
        requires_confirmation: true,
      },
    });
    expect(routed.routingState?.route).toBe('CODE_RUN_REQUIRED');
    expect(routed.routingState?.runApiAllowed).toBe(true);

    const proposed = agentReducer(routed, {
      type: 'stream:proposal',
      proposal: {
        session_id: 'session-1',
        turn_id: 'turn-1',
        route: 'CODE_RUN_REQUIRED',
        action: {
          type: 'create_cloud_run',
          arguments: {spec: 'Add audit logs'},
          requiresConfirmation: true,
          billingImpact: 'run_credit_on_plan_approval',
          confirmationPrompt: 'Create a cloud run?',
        },
      },
    });
    expect(proposed.status).toBe('confirmationRequired');
    expect(proposed.pendingAction?.action.type).toBe('create_cloud_run');
  });

  it('derives messages and pending actions from stored turns', () => {
    expect(toMessages([turn])).toEqual([
      {
        id: 'turn-1:user',
        role: 'user',
        content: 'Create a run',
        status: 'NEEDS_CONFIRMATION',
      },
      {
        id: 'turn-1:assistant',
        role: 'assistant',
        content: 'Create a cloud run?',
        status: 'NEEDS_CONFIRMATION',
      },
    ]);

    const pending = actionFromTurn(turn);
    expect(pending?.sessionId).toBe('session-1');
    expect(pending?.turnId).toBe('turn-1');
    expect(pending?.action.type).toBe('create_cloud_run');
    expect(pending?.source).toBe('history');
  });

  it('formats compact labels and usage values', () => {
    expect(sessionTitle(session)).toHaveLength(54);
    expect(pct(5, 20)).toBe(25);
    expect(pct(5, 0)).toBe(0);
    expect(currency(4.125)).toBe('$4.13');
    expect(currency(-1)).toBe('n/a');
  });

  it('derives honest bar status, run phase, progress, and open path', () => {
    expect(
      deriveBarStatus({
        status: 'idle',
        hasPendingAction: false,
        pendingGateCount: 0,
        runActive: false,
      }),
    ).toEqual({label: 'Ready', tone: 'ok'});
    expect(
      deriveBarStatus({
        status: 'idle',
        hasPendingAction: true,
        pendingGateCount: 0,
        runActive: false,
      }),
    ).toEqual({label: 'Waiting for you', tone: 'waiting'});
    expect(
      deriveBarStatus({
        status: 'idle',
        hasPendingAction: false,
        pendingGateCount: 2,
        runActive: false,
      }).tone,
    ).toBe('waiting');
    expect(
      deriveBarStatus({
        status: 'idle',
        hasPendingAction: false,
        pendingGateCount: 0,
        runActive: true,
      }),
    ).toEqual({label: 'Running', tone: 'working'});
    expect(
      deriveBarStatus({
        status: 'streaming',
        hasPendingAction: false,
        pendingGateCount: 0,
        runActive: false,
      }).tone,
    ).toBe('working');
    expect(
      deriveBarStatus({
        status: 'error',
        hasPendingAction: true,
        pendingGateCount: 3,
        runActive: true,
      }),
    ).toEqual({label: 'Error', tone: 'error'});

    expect(runPhaseLabel('AWAITING_APPROVAL')).toBe('Waiting for approval');
    expect(runPhaseLabel('WORKING')).toBe('Coding');
    expect(runPhaseLabel('COMPLETE')).toBe('Done');
    expect(runPhaseLabel('FAILED')).toBe('Failed');

    expect(isTerminalRunStatus('COMPLETE')).toBe(true);
    expect(isTerminalRunStatus('WORKING')).toBe(false);

    expect(runOpenPath('r1', 'AWAITING_APPROVAL')).toBe('/plan/r1');
    expect(runOpenPath('r1', 'WORKING')).toBe('/live/r1');
    expect(runOpenPath('r1', 'COMPLETE')).toBe('/result/r1');

    expect(
      runProgress([
        {status: 'COMPLETE', title: 'a'},
        {status: 'WORKING', title: 'b'},
        {status: 'PENDING', title: 'c'},
      ]),
    ).toEqual({done: 1, total: 3, pct: 33, current: 'b'});
    expect(runProgress([])).toEqual({done: 0, total: 0, pct: 0, current: null});
  });

  it('clears errors without wiping non-error state', () => {
    const cleared = agentReducer(
      {...initialAgentState, status: 'error', error: 'boom'},
      {type: 'error:clear'},
    );
    expect(cleared.error).toBeNull();
    expect(cleared.status).toBe('idle');
  });
});
