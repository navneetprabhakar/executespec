import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AgentConversation} from './agentPanels';
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
  getRun: vi.fn(),
  getLiveCost: vi.fn(),
  cancelRun: vi.fn(),
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
  latestLinkedRunId: null,
  latestLinkedRunStatus: null,
  latestLinkedRunTitle: null,
  latestLinkedRunStartedAt: null,
  latestLinkedRunCompletedAt: null,
  latestLinkedRunPrUrl: null,
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
  purchasedAiCredits: 0,
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
  api.getRun.mockResolvedValue({
    runId: 'run-2',
    runTitle: 'Add billing audit logs',
    status: 'COMPLETE',
    aiProvider: 'deepseek',
    workerModel: 'deepseek-v4-pro-native',
    costEstimateHigh: 0.5,
    verifiedScore: 91,
    scmPrUrl: null,
    githubPrUrl: null,
    tasks: [
      {title: 'Add audit table', status: 'COMPLETE'},
      {title: 'Wire service', status: 'COMPLETE'},
    ],
  });
  api.getLiveCost.mockResolvedValue({costUsd: 0.18});
  api.cancelRun.mockResolvedValue({ok: true});
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

  it('renders remote-sandbox chrome: top bar + inline gate, with internals behind settings', async () => {
    ({host, root} = await renderAgent('/agent'));

    // Chat-first chrome (no jargon strip / rails in the main view)
    expect(host.textContent).toContain('Chats');
    expect(host.textContent).toContain('New sandbox');
    expect(host.textContent).toContain('Web agent');
    // Pending gate surfaces inline as an action card
    expect(host.textContent).toContain('Remote sandboxes waiting for your approval');
    expect(host.textContent).toContain('Add audit logs');
    expect(host.textContent).toContain('Repo token scoped to selected project');
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
    expect(host.textContent).toContain('Managed AI balance');
    expect(host.textContent).toContain('1590 of 2000 credits left');
    expect(host.textContent).not.toContain('0 of 0 credits left');
    expect(host.textContent).toContain('Run credits');
    expect(host.textContent).toContain('Operator diagnostics');
  });

  it('renders zero managed-credit policy as an explicit state, not a broken balance', async () => {
    api.getManagedAiBalance.mockResolvedValue({
      ...managedAi,
      includedAiCredits: 0,
      aiCreditsUsed: 0,
      aiCreditsRemaining: 0,
      rawCogsCapUsd: 0,
      rawCogsUsedUsd: 0,
      rawCogsRemainingUsd: 0,
    });

    ({host, root} = await renderAgent('/agent'));

    const settingsBtn = [...host.querySelectorAll('button')].find(
      (b) => b.getAttribute('aria-label') === 'Agent settings and usage',
    );
    if (!settingsBtn) throw new Error('Settings button was not rendered.');
    await act(async () => {
      settingsBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });

    expect(host.textContent).toContain('Managed AI balance');
    expect(host.textContent).toContain('Managed credits not configured');
    expect(host.textContent).toContain('Runs use the configured provider keys');
    expect(host.textContent).not.toContain('0 of 0 credits left');
  });

  it('turns the chats drawer into an operator queue for waiting sandbox plans', async () => {
    ({host, root} = await renderAgent('/agent'));

    const chatsBtn = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Chats'),
    );
    if (!chatsBtn) throw new Error('Chats button was not rendered.');
    await act(async () => {
      chatsBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });

    expect(host.textContent).toContain('Remote sandboxes');
    expect(host.textContent).toContain('1 waiting approval');
    expect(host.textContent).toContain('Approval inbox');
    expect(host.textContent).toContain('2 credits');
    expect(host.textContent).toContain('~$0.25');
    expect(host.textContent).toContain('cap $5.00');
    expect(host.textContent).toContain('low risk');
    expect(host.textContent).toContain('Approve & run');
    expect(host.textContent).toContain('Reject');
    expect(host.textContent).toContain('Full plan');
    expect(host.textContent).toContain('sandbox operator');
  });

  it('shows latest linked sandbox status in the drawer when the backend provides it', async () => {
    api.listAgentSessions.mockResolvedValue([
      {
        ...session,
        latestLinkedRunId: 'run-2',
        latestLinkedRunStatus: 'AWAITING_APPROVAL',
        latestLinkedRunTitle: 'Add billing audit logs',
      },
    ]);

    ({host, root} = await renderAgent('/agent'));

    const chatsBtn = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Chats'),
    );
    if (!chatsBtn) throw new Error('Chats button was not rendered.');
    await act(async () => {
      chatsBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });

    expect(host.textContent).toContain('Latest sandbox: Add billing audit logs');
    expect(host.textContent).toContain('Waiting for approval');
  });

  it('does not reuse an all-project session on a project-scoped agent route', async () => {
    api.listAgentSessions.mockResolvedValue([
      {
        ...session,
        id: 'global-session',
        projectId: null,
        title: 'Web agent',
      },
      {
        ...session,
        id: 'project-session',
        projectId: 'p1',
        title: 'Project agent',
      },
    ]);

    ({host, root} = await renderAgent('/projects/p1/agent'));

    expect(host.textContent).toContain('Project agent');
    expect(api.listAgentTurns).toHaveBeenCalledWith('project-session', {limit: 50});
    expect(api.listAgentTurns).not.toHaveBeenCalledWith('global-session', {limit: 50});
  });

  it('shows the remote sandbox launcher before any historical transcript', async () => {
    api.listAgentSessions.mockResolvedValue([]);
    api.listAgentGates.mockResolvedValue([]);
    api.listAgentTurns.mockResolvedValue([]);

    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('Remote sandbox operator');
    expect(host.textContent).toContain('Run work in a remote sandbox.');
    expect(host.textContent).toContain('Plan, code, test, review, and open a PR');
    expect(host.textContent).toContain('Create a remote sandbox to add audit logging');
    expect(host.textContent).toContain('What will happen before spend');
    expect(host.textContent).toContain('isolated remote clone');
    expect(host.textContent).toContain('Run events stay in audit trail');
    expect(host.textContent).toContain('Remote sandbox ready');
  });

  it('collapses historical transcript behind the operator launcher', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.listAgentTurns.mockResolvedValue([
      {
        turnId: 't0',
        sessionId: 's1',
        sequence: 1,
        userMessage: 'Explain the CLI repo',
        assistantSummary:
          'This is a long old transcript that should not dominate the remote sandbox launcher.',
        intentType: 'CHAT',
        intentConfidence: 0.9,
        route: 'PLAN_ONLY',
        reasonCode: 'chat',
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

    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('Run work in a remote sandbox.');
    expect(host.textContent).toContain('Recent answer');
    expect(host.textContent).toContain('Last agent response');
    expect(host.querySelectorAll('.agent-msg').length).toBe(0);
  });

  it('does not collapse a newly submitted user prompt behind the launcher', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;

    await act(async () => {
      localRoot.render(
        <AgentConversation
          messages={[
            {
              id: 'old:assistant',
              role: 'assistant',
              content: 'Old settled answer',
            },
            {
              id: 'pending:user',
              role: 'user',
              content: 'Create a remote sandbox plan for a README change',
              status: 'SENDING',
            },
          ]}
          streamEvents={[]}
          streamText=''
          isBusy={false}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={vi.fn()}
          onScroll={vi.fn()}
          onSend={vi.fn()}
          onStop={vi.fn()}
          onAddFiles={vi.fn()}
          onRemoveAttachment={vi.fn()}
          onConfirmAction={vi.fn()}
          onApproveGate={vi.fn()}
          onRejectGate={vi.fn()}
          onOpenGate={vi.fn()}
          onOpenRun={vi.fn()}
          onStopRun={vi.fn()}
        />,
      );
    });

    expect(host.textContent).toContain('Create a remote sandbox plan for a README change');
    expect(host.querySelectorAll('.agent-msg').length).toBe(2);
    expect(host.textContent).not.toContain('Recent answer');
  });

  it('does not collapse a user turn behind an empty persisted assistant summary', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;

    await act(async () => {
      localRoot.render(
        <AgentConversation
          messages={[
            {
              id: 'turn:user',
              role: 'user',
              content: 'Submit a coding run to add README launch notes',
              status: 'STREAMING',
            },
            {
              id: 'turn:assistant',
              role: 'assistant',
              content: '   ',
              status: 'STREAMING',
            },
          ]}
          streamEvents={[]}
          streamText=''
          isBusy={false}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={vi.fn()}
          onScroll={vi.fn()}
          onSend={vi.fn()}
          onStop={vi.fn()}
          onAddFiles={vi.fn()}
          onRemoveAttachment={vi.fn()}
          onConfirmAction={vi.fn()}
          onApproveGate={vi.fn()}
          onRejectGate={vi.fn()}
          onOpenGate={vi.fn()}
          onOpenRun={vi.fn()}
          onStopRun={vi.fn()}
        />,
      );
    });

    expect(host.textContent).toContain('Submit a coding run to add README launch notes');
    expect(host.querySelectorAll('.agent-msg').length).toBe(2);
    expect(host.textContent).not.toContain('Recent answer');
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
          confirmationPrompt: 'Create a remote sandbox?',
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
    expect(host.textContent).toContain('Create remote sandbox run');
    expect(host.textContent).toContain('Create a remote sandbox?');
    expect(host.textContent).toContain('Add billing audit logs');
    expect(host.textContent).toContain('Create sandbox plan');
    expect(host.textContent).toContain('approval before credits');
    expect(host.textContent).toContain('What will happen before spend');
    expect(host.textContent).toContain('Branch');
    expect(host.textContent).toContain('Secrets are not displayed in chat');
    expect(host.textContent).toContain('Outbound network follows sandbox policy');
    expect(api.confirmAgentRunProposal).not.toHaveBeenCalled();
  });

  it('hydrates the pinned sandbox run from durable linked turns after reload', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.listAgentTurns.mockResolvedValue([
      {
        turnId: 't2',
        sessionId: 's1',
        sequence: 2,
        userMessage: 'Start a run to add billing audit logs',
        assistantSummary: 'Remote sandbox run submitted.',
        intentType: 'CODE_RUN_REQUIRED',
        intentConfidence: 0.94,
        route: 'CODE_RUN_REQUIRED',
        reasonCode: 'explicit_code_mutation_request',
        actionType: 'create_cloud_run',
        proposedActionJson: null,
        linkedRunId: 'run-2',
        inputTokens: 120,
        outputTokens: 35,
        reasoningTokens: 0,
        costUsd: 0.002,
        status: 'COMPLETED',
        createdAt: '2026-06-20T00:00:00Z',
      },
    ]);

    ({host, root} = await renderAgent('/agent'));
    await flush();
    await flush();

    expect(api.getRun).toHaveBeenCalledWith('run-2');
    expect(host.textContent).toContain('Add billing audit logs');
    expect(host.textContent).toContain('Remote sandbox · plan-gated · PR-oriented');
    expect(host.textContent).toContain('91/100');
    expect(host.textContent).toContain('Review evidence');
  });
});
