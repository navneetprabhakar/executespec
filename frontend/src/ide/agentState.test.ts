import {describe, expect, it} from 'vitest';

import {
  actionFromTurn,
  agentReducer,
  currency,
  initialAgentState,
  pct,
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
});
