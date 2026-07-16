import {describe, expect, it} from 'vitest';
import type {AgentTurnResponse} from '@/lib/api';
import {
  conversationForStream,
  sessionUsageContext,
  usageFromTurns,
} from './agentConversationContext';

function turn(sequence: number, overrides: Partial<AgentTurnResponse> = {}): AgentTurnResponse {
  return {
    turnId: `turn-${sequence}`,
    sessionId: 'session-1',
    sequence,
    userMessage: ` question ${sequence} `,
    assistantSummary: ` answer ${sequence} `,
    intentType: null,
    intentConfidence: 0,
    route: null,
    reasonCode: null,
    actionType: null,
    proposedActionJson: null,
    linkedRunId: null,
    inputTokens: sequence,
    outputTokens: sequence * 2,
    reasoningTokens: sequence * 3,
    costUsd: sequence / 100,
    status: 'COMPLETED',
    createdAt: `2026-07-13T00:00:0${sequence}Z`,
    ...overrides,
  };
}

describe('agentConversationContext', () => {
  it('aggregates usage and omits a usage system message when empty', () => {
    expect(usageFromTurns([turn(1), turn(2)])).toEqual({
      inputTokens: 3,
      outputTokens: 6,
      reasoningTokens: 9,
      costUsd: 0.03,
    });
    expect(sessionUsageContext(usageFromTurns([]))).toBeNull();
  });

  it('trims transcript content and caps context to the latest eight turns', () => {
    const messages = conversationForStream(
      Array.from({length: 9}, (_, index) => turn(index + 1)),
      ' latest request ',
    );
    expect(messages[0]?.role).toBe('system');
    expect(messages).toHaveLength(18);
    expect(messages[1]).toEqual({role: 'user', content: 'question 2'});
    expect(messages[messages.length - 1]).toEqual({role: 'user', content: ' latest request '});
  });

  it('does not send blank transcript fields', () => {
    expect(
      conversationForStream(
        [
          turn(1, {
            userMessage: ' ',
            assistantSummary: null,
            inputTokens: 0,
            outputTokens: 0,
            reasoningTokens: 0,
            costUsd: 0,
          }),
        ],
        'next',
      ),
    ).toEqual([{role: 'user', content: 'next'}]);
  });
});
