import {beforeEach, describe, expect, it, vi} from 'vitest';
import * as api from '@/lib/api';
import {proposalText, streamWebAgentChatReply, WEB_CHAT_CAPABILITIES} from './agentChatStream';

vi.mock('@/lib/api', () => ({
  streamAgentTurn: vi.fn(),
}));

describe('agentChatStream', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('streams token text with standard WEB capabilities', async () => {
    expect(WEB_CHAT_CAPABILITIES).toMatchObject({
      localTools: false,
      interactive: true,
      canRenderDiff: true,
      canRenderTui: false,
    });
    vi.mocked(api.streamAgentTurn).mockImplementation(async (_sessionId, _body, handlers = {}) => {
      handlers.onToken?.({delta: 'hello'});
      handlers.onToken?.({delta: ' world'});
    });

    const reply = await streamWebAgentChatReply('s1', 'question', {
      label: 'project agent',
      href: '/projects/p1/agent',
    });

    expect(reply).toBe('hello world');
    expect(api.streamAgentTurn).toHaveBeenCalledWith(
      's1',
      {message: 'question', clientCapabilities: WEB_CHAT_CAPABILITIES},
      expect.any(Object),
    );
  });

  it('adds proposal text and fallback when no token text arrives', async () => {
    vi.mocked(api.streamAgentTurn).mockImplementation(async (_sessionId, _body, handlers = {}) => {
      handlers.onProposal?.({
        session_id: 's1',
        turn_id: 't1',
        route: 'CODE_RUN_REQUIRED',
        action: {
          type: 'create_cloud_run',
          arguments: {},
          requiresConfirmation: true,
          billingImpact: 'run_credit_on_plan_approval',
          confirmationPrompt: 'Create a run?',
        },
      });
    });

    await expect(
      streamWebAgentChatReply('s1', 'change code', {
        label: 'run agent',
        href: '/runs/r1/agent',
      }),
    ).resolves.toBe(
      'Action proposed: create_cloud_run. Review it in the [run agent](/runs/r1/agent).',
    );
  });

  it('throws typed stream errors', async () => {
    vi.mocked(api.streamAgentTurn).mockImplementation(async () => {
      throw new Error('blocked');
    });

    await expect(
      streamWebAgentChatReply('s1', 'question', {
        label: 'project agent',
        href: '/projects/p1/agent',
      }),
    ).rejects.toThrow('blocked');
  });

  it('preserves the action from a structured projected-budget terminal error', async () => {
    vi.mocked(api.streamAgentTurn).mockImplementation(async (_sessionId, _body, handlers = {}) => {
      handlers.onErrorEvent?.({
        code: 'PROJECTED_CALL_BUDGET_EXCEEDED',
        category: 'ACTION_REQUIRED',
        message: 'The next model call would exceed this session budget.',
        retryable: true,
        next_action: 'Increase the session cap, then retry.',
      });
      throw new Error(
        'The next model call would exceed this session budget. Increase the session cap, then retry.',
      );
    });

    await expect(
      streamWebAgentChatReply('s1', 'continue', {
        label: 'project agent',
        href: '/projects/p1/agent',
      }),
    ).rejects.toThrow(
      'The next model call would exceed this session budget. Increase the session cap, then retry.',
    );
  });

  it('formats proposal text consistently', () => {
    expect(
      proposalText('local_tool_action', {label: 'project agent', href: '/projects/p1/agent'}),
    ).toBe(
      'Action proposed: local_tool_action. Review it in the [project agent](/projects/p1/agent).',
    );
  });
});
