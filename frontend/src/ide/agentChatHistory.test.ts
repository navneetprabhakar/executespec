import {describe, expect, it} from 'vitest';
import type {AgentTurnResponse} from '@/lib/api';
import {
  agentTurnToChatBubbles,
  agentTurnToRunChatMessages,
  sortAgentChatBubbles,
} from './agentChatHistory';

function turn(overrides: Partial<AgentTurnResponse> = {}): AgentTurnResponse {
  return {
    turnId: 'turn-1',
    sessionId: 'session-1',
    sequence: 1,
    userMessage: 'question',
    assistantSummary: 'answer',
    intentType: null,
    intentConfidence: 0.91,
    route: 'READ_ONLY_ANSWER',
    reasonCode: 'read_only',
    actionType: null,
    proposedActionJson: null,
    linkedRunId: null,
    inputTokens: 10,
    outputTokens: 20,
    reasoningTokens: 0,
    costUsd: 0.0001,
    status: 'COMPLETED',
    createdAt: '2026-06-20T00:00:00Z',
    ...overrides,
  };
}

describe('agentChatHistory', () => {
  it('maps an agent turn to timestamped chat bubbles', () => {
    expect(agentTurnToChatBubbles(turn())).toEqual([
      {role: 'user', content: 'question', ts: '2026-06-20T00:00:00Z'},
      {role: 'assistant', content: 'answer', ts: '2026-06-20T00:00:00Z'},
    ]);
  });

  it('maps agent turns to legacy run-chat messages without timestamps', () => {
    expect(agentTurnToRunChatMessages(turn())).toEqual([
      {role: 'user', content: 'question'},
      {role: 'assistant', content: 'answer'},
    ]);
  });

  it('drops empty transcript fields', () => {
    expect(agentTurnToChatBubbles(turn({userMessage: null, assistantSummary: null}))).toEqual([]);
    expect(agentTurnToRunChatMessages(turn({userMessage: null, assistantSummary: null}))).toEqual(
      [],
    );
  });

  it('sorts bubbles by timestamp with missing timestamps first', () => {
    const rows = [
      {role: 'assistant' as const, content: 'new', ts: '2026-06-20T00:00:02Z'},
      {role: 'user' as const, content: 'missing'},
      {role: 'assistant' as const, content: 'old', ts: '2026-06-20T00:00:01Z'},
    ];

    expect(rows.sort(sortAgentChatBubbles).map((row) => row.content)).toEqual([
      'missing',
      'old',
      'new',
    ]);
  });
});
