import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import IdeAgent from './IdeAgent';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  listAgentSessions: vi.fn(),
  listAgentGates: vi.fn(),
  listAgentCapabilities: vi.fn(),
  getManagedAiBalance: vi.fn(),
  listAgentTurns: vi.fn(),
  createAgentSession: vi.fn(),
  streamAgentTurn: vi.fn(),
  confirmAgentRunProposal: vi.fn(),
  approveRun: vi.fn(),
}));

vi.mock('@/lib/api', () => api);

const session = {
  id: 's1',
  projectId: null,
  runId: null,
  repoRemote: null,
  surface: 'WEB',
  title: 'Web agent',
  status: 'ACTIVE',
  defaultProvider: 'deepseek',
  defaultModel: 'deepseek-v4-pro-native',
  defaultExecutionMode: 'PRODUCTION',
  approvalPolicy: 'CONFIRM_MUTATIONS',
  createdAt: '2026-06-20T00:00:00Z',
  updatedAt: '2026-06-20T00:00:00Z',
};

const managedAi = {
  userId: 'u1',
  orgId: 'o1',
  tier: 'PRO',
  routingState: 'VALUE_MODE',
  periodStart: '2026-06-01T00:00:00Z',
  periodEnd: '2026-07-01T00:00:00Z',
  includedAiCredits: 2000,
  aiCreditsUsed: 410,
  aiCreditsRemaining: 1590,
  rawCogsCapUsd: 16,
  rawCogsUsedUsd: 4.1,
  rawCogsRemainingUsd: 11.9,
  frontierRawCapUsd: 4,
  frontierRawUsedUsd: 0.7,
  frontierRawRemainingUsd: 3.3,
  valueModeAfterRawUsd: 4,
  cheapOnlyAfterRawUsd: 12,
  hardStopAfterRawUsd: 16,
  managed: true,
  retailMultiplier: 1.25,
};

function setHappyPathMocks() {
  api.listAgentSessions.mockResolvedValue([session]);
  api.listAgentGates.mockResolvedValue([
    {
      runId: 'run-1',
      projectId: 'p1',
      projectName: 'ExecuteSpec',
      title: 'Add audit logs',
      status: 'AWAITING_APPROVAL',
      submittedAt: '2026-06-20T00:00:00Z',
      provider: 'deepseek',
      model: 'deepseek-v4-pro-native',
      estimatedCostUsd: 0.25,
      hardCapUsd: 5,
      creditsRequired: 2,
      risk: 'LOW',
      summary: 'Plan waiting',
      recommendedAction: 'Approve',
      tasks: ['Add audit table', 'Wire service'],
    },
  ]);
  api.listAgentCapabilities.mockResolvedValue([
    {
      name: 'chat_answer',
      description: 'Answer questions',
      inputSchema: {},
      outputSchema: {},
      requiredTier: 'FREE',
      requiredScopes: [],
      sideEffect: 'none',
      confirmationRequired: false,
      billingImpact: 'included',
      surfaces: ['WEB'],
    },
    {
      name: 'create_cloud_run',
      description: 'Create a cloud run',
      inputSchema: {},
      outputSchema: {},
      requiredTier: 'FREE',
      requiredScopes: [],
      sideEffect: 'billable',
      confirmationRequired: true,
      billingImpact: 'run_credit_on_plan_approval',
      surfaces: ['WEB'],
    },
  ]);
  api.getManagedAiBalance.mockResolvedValue(managedAi);
  api.listAgentTurns.mockResolvedValue([
    {
      turnId: 't0',
      sessionId: 's1',
      sequence: 1,
      userMessage: 'What plans are waiting?',
      assistantSummary: 'One plan is waiting for approval.',
      intentType: 'PLAN_GATE_QUERY',
      intentConfidence: 0.9,
      route: 'PLAN_ONLY',
      reasonCode: 'plan_gate_query',
      actionType: null,
      proposedActionJson: null,
      linkedRunId: null,
      inputTokens: 100,
      outputTokens: 40,
      reasoningTokens: 0,
      costUsd: 0.001,
      status: 'COMPLETED',
      createdAt: '2026-06-20T00:00:00Z',
    },
  ]);
  api.createAgentSession.mockResolvedValue(session);
  api.confirmAgentRunProposal.mockResolvedValue({
    sessionId: 's1',
    turnId: 't2',
    runId: 'run-2',
    route: 'CODE_RUN_REQUIRED',
    calledRunApi: true,
    traceId: 'orch_trace_1',
    status: 'SUBMITTED',
  });
  api.approveRun.mockResolvedValue({ok: true});
}

async function renderAgent(path = '/agent') {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const root = createRoot(host);
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path='/agent' element={<IdeAgent />} />
          <Route path='/projects/:projectId/agent' element={<IdeAgent />} />
          <Route path='/runs/:runId/agent' element={<IdeAgent />} />
        </Routes>
      </MemoryRouter>,
    );
  });
  await flush();
  return {host, root};
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('IdeAgent', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  beforeEach(() => {
    vi.clearAllMocks();
    setHappyPathMocks();
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
    }
    host?.remove();
    root = null;
    host = null;
  });

  it('renders chat-first: top bar + inline gate, with internals behind the settings popover', async () => {
    ({host, root} = await renderAgent('/agent'));

    // Chat-first chrome (no jargon strip / rails in the main view)
    expect(host.textContent).toContain('Chats');
    expect(host.textContent).toContain('New chat');
    expect(host.textContent).toContain('Web agent');
    // Pending gate surfaces inline as an action card
    expect(host.textContent).toContain('Waiting for your approval');
    expect(host.textContent).toContain('Add audit logs');
    // Internals are de-jargoned out of the main view
    expect(host.textContent).not.toContain('chat_answer');
    expect(host.textContent).not.toContain('ES AI credits');
    expect(api.listAgentCapabilities).toHaveBeenCalledWith('WEB');

    // Opening the settings popover reveals the internals (provider, credits, capabilities)
    const settingsBtn = [...host.querySelectorAll('button')].find(
      (b) => b.getAttribute('aria-label') === 'Agent settings and usage',
    );
    if (!settingsBtn) throw new Error('Settings button was not rendered.');
    await act(async () => {
      settingsBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    expect(host.textContent).toContain('chat answer');
    expect(host.textContent).toContain('create cloud run');
    expect(host.textContent).toContain('ES AI credits');
  });

  it('renders streamed route decisions and cloud-run proposal cards before execution', async () => {
    api.streamAgentTurn.mockImplementation(async (_sessionId, _body, handlers) => {
      handlers.onAck?.({session_id: 's1', turn_id: 't2', sequence: 2});
      handlers.onRouteDecision?.({
        session_id: 's1',
        turn_id: 't2',
        route: 'CODE_RUN_REQUIRED',
        confidence: 0.94,
        reason_code: 'explicit_code_mutation_request',
        run_api_allowed: true,
        requires_confirmation: true,
      });
      handlers.onProposal?.({
        session_id: 's1',
        turn_id: 't2',
        route: 'CODE_RUN_REQUIRED',
        action: {
          type: 'create_cloud_run',
          arguments: {spec: 'Add billing audit logs'},
          requiresConfirmation: true,
          billingImpact: 'run_credit_on_plan_approval',
          confirmationPrompt: 'Create a cloud run?',
        },
      });
      handlers.onDone?.({
        session_id: 's1',
        turn_id: 't2',
        turn_count: 2,
        input_tokens: 120,
        output_tokens: 35,
        cost_usd: 0.002,
      });
    });

    ({host, root} = await renderAgent('/agent'));

    const textarea = host.querySelector('textarea');
    if (!textarea) throw new Error('Agent composer textarea was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(textarea, 'Start a run to add billing audit logs');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });

    const send = [...host.querySelectorAll('button')].find(
      (button) => button.getAttribute('aria-label') === 'Send',
    );
    if (!send) throw new Error('Send button was not rendered.');

    await act(async () => {
      send.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await flush();

    // Proposal renders as a clean inline action card (no routing jargon in the main view)
    expect(host.textContent).toContain('Start a cloud run');
    expect(host.textContent).toContain('Create a cloud run?');
    expect(host.textContent).toContain('Add billing audit logs');
    expect(host.textContent).toContain('Approve');
    expect(api.confirmAgentRunProposal).not.toHaveBeenCalled();
  });
});
