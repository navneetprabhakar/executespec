import {describe, expect, it, vi} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';

import {ProjectChatConfirmationCard, formatConfirmationTarget} from './ProjectChatConfirmationCard';
import {
  PROJECT_CHAT_UNAVAILABLE_ACTIONS,
  ProjectChatConfirmationSession,
} from './projectChatConfirmation';

const scope = {
  action: 'RUN_CODE_REVIEW' as const,
  provider: 'deepseek',
  model: 'deepseek-v4-pro-native',
  target: {repoId: '11111111-1111-1111-1111-111111111111'},
};

function issued(expiresAt = '2030-01-01T00:05:00Z') {
  return {
    confirmation: {
      confirmationId: 'confirmation-1',
      projectId: 'project-1',
      ...scope,
      capabilities: ['PROJECT_ANALYSE', 'SPEND_AI'],
      targetHash: 'hash',
      expiresAt,
      consumed: false,
      budget: {maxTotalTokens: 300_000, maxCostUsd: 5, maxProviderCalls: 2},
    },
    confirmationToken: 'es_chat_confirm_memory_only',
  };
}

it('displays the exact action, provider, model, target, and expiry without rendering the bearer', () => {
  const markup = renderToStaticMarkup(
    <ProjectChatConfirmationCard
      pending={{scope, confirmation: issued().confirmation}}
      busy={false}
      onConfirm={() => {}}
      onCancel={() => {}}
    />,
  );
  expect(markup).toContain('RUN_CODE_REVIEW');
  expect(markup).toContain('deepseek-v4-pro-native');
  expect(markup).toContain('11111111-1111-1111-1111-111111111111');
  expect(markup).toContain('Expires');
  expect(markup).toContain('300,000 tokens');
  expect(markup).toContain('$5');
  expect(markup).not.toContain('es_chat_confirm_memory_only');
  expect(formatConfirmationTarget({branch: '', z: 'last'})).toBe('branch=(default), z=last');
});

describe('ProjectChatConfirmationSession', () => {
  it('cancel makes no consume request and clears the bearer', async () => {
    const transport = {
      issueProjectChatConfirmation: vi.fn().mockResolvedValue(issued()),
      consumeProjectChatConfirmation: vi.fn(),
    };
    const session = new ProjectChatConfirmationSession(transport, () =>
      Date.parse('2030-01-01T00:00:00Z'),
    );
    await session.issue('project-1', scope);
    session.cancel();
    expect(session.pending()).toBeNull();
    expect(transport.consumeProjectChatConfirmation).not.toHaveBeenCalled();
  });

  it('consumes once, preserves scope, and clears before success or replay', async () => {
    const transport = {
      issueProjectChatConfirmation: vi.fn().mockResolvedValue(issued()),
      consumeProjectChatConfirmation: vi.fn().mockResolvedValue({
        confirmation: {...issued().confirmation, consumed: true},
        result: {status: 'QUEUED'},
      }),
    };
    const session = new ProjectChatConfirmationSession(transport, () =>
      Date.parse('2030-01-01T00:00:00Z'),
    );
    await session.issue('project-1', scope);
    await session.consume();
    expect(transport.consumeProjectChatConfirmation).toHaveBeenCalledWith(
      'project-1',
      'confirmation-1',
      'es_chat_confirm_memory_only',
      scope,
    );
    expect(session.pending()).toBeNull();
    await expect(session.consume()).rejects.toThrow('No pending');
  });

  it('clears after consume failure and on expiry', async () => {
    const transport = {
      issueProjectChatConfirmation: vi.fn().mockResolvedValue(issued()),
      consumeProjectChatConfirmation: vi.fn().mockRejectedValue(new Error('403 viewer denied')),
    };
    const session = new ProjectChatConfirmationSession(transport, () =>
      Date.parse('2030-01-01T00:00:00Z'),
    );
    await session.issue('project-1', scope);
    await expect(session.consume()).rejects.toThrow('viewer denied');
    expect(session.pending()).toBeNull();

    transport.issueProjectChatConfirmation.mockResolvedValue(issued('2029-12-31T23:59:59Z'));
    await expect(session.issue('project-1', scope)).rejects.toThrow('expired');
    expect(session.pending()).toBeNull();
  });

  it('actively clears the in-memory bearer when its expiry timer fires', async () => {
    const soon = new Date(Date.now() + 100).toISOString();
    const transport = {
      issueProjectChatConfirmation: vi.fn().mockResolvedValue(issued(soon)),
      consumeProjectChatConfirmation: vi.fn(),
    };
    const session = new ProjectChatConfirmationSession(transport);
    await session.issue('project-1', scope);
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(session.pending()).toBeNull();
    expect(transport.consumeProjectChatConfirmation).not.toHaveBeenCalled();
  });
});

it('labels unsupported semantic search, subagents, and plan mutation honestly', () => {
  expect(PROJECT_CHAT_UNAVAILABLE_ACTIONS.SEMANTIC_SEARCH).toMatch(/no scoped confirmation/i);
  expect(PROJECT_CHAT_UNAVAILABLE_ACTIONS.SUBAGENT).toMatch(/unavailable/i);
  expect(PROJECT_CHAT_UNAVAILABLE_ACTIONS.PLAN_MUTATION).toMatch(/dedicated run gates/i);
});
