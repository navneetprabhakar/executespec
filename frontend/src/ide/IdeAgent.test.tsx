import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {AgentConversation, AgentTopBar} from './agentPanels';
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
  getRuntimeClientCapabilities: vi.fn(),
  getManagedAiBalance: vi.fn(),
  getAvailableModels: vi.fn(),
  getMyModels: vi.fn(),
  getProviderReadiness: vi.fn(),
  listAgentTurns: vi.fn(),
  listAgentEvents: vi.fn(),
  replayAgentEvents: vi.fn(),
  createAgentSession: vi.fn(),
  streamAgentTurn: vi.fn(),
  confirmAgentRunProposal: vi.fn(),
  approveRun: vi.fn(),
  getRun: vi.fn(),
  getLiveCost: vi.fn(),
  cancelRun: vi.fn(),
  getProjectRepos: vi.fn(),
  getProjects: vi.fn(),
  closeAgentSession: vi.fn(),
  updateAgentSessionRoute: vi.fn(),
}));

vi.mock('@/lib/api', () => api);

vi.mock('@/stores/useAuthStore', () => {
  const authState = {
    user: {globalRole: 'DEVELOPER', orgRole: 'DEVELOPER'},
  };
  return {
    useAuthStore: (selector?: (state: typeof authState) => unknown) =>
      typeof selector === 'function' ? selector(authState) : authState,
    selectIsSuperAdmin: (state: typeof authState) => state.user?.globalRole === 'SUPER_ADMIN',
  };
});

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
  unlimitedAiCredits: false,
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
  api.getProjects.mockResolvedValue([
    {
      id: 'p1',
      orgId: 'o1',
      name: 'ExecuteSpec',
      description: null,
      status: 'ACTIVE',
      aiProvider: 'DEEPSEEK',
      createdAt: '2026-06-19T00:00:00Z',
      archivedAt: null,
    },
  ]);
  api.closeAgentSession.mockResolvedValue(undefined);
  api.updateAgentSessionRoute.mockResolvedValue(session);
  api.getRuntimeClientCapabilities.mockResolvedValue({
    remoteSandbox: {
      supported: true,
      enabled: true,
      lifecycleEnabled: true,
      dispatchEnabled: true,
      strictContainerEnabled: true,
      isolationProfile: 'CONTAINER_STRICT_V1',
      note: 'enabled',
    },
  });
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
  api.getAvailableModels.mockResolvedValue({
    providers: [
      {
        id: 'deepseek',
        label: 'DeepSeek',
        models: [{id: 'deepseek-v4-pro-native', tier: 'WORKHORSE', label: 'DeepSeek V4'}],
      },
      {
        id: 'openai',
        label: 'OpenAI',
        models: [{id: 'gpt-5.4', tier: 'WORKHORSE', label: 'GPT-5.4'}],
      },
    ],
  });
  api.getMyModels.mockResolvedValue([
    {
      id: 'deepseek-v4-pro-native',
      provider: 'deepseek',
      label: 'DeepSeek V4',
      minTier: 'FREE',
      available: true,
    },
    {
      id: 'gpt-5.4',
      provider: 'openai',
      label: 'GPT-5.4',
      minTier: 'PRO',
      available: true,
    },
  ]);
  api.getProviderReadiness.mockResolvedValue({
    schemaVersion: 1,
    generatedAt: '2026-07-29T00:00:00Z',
    providers: [
      {
        provider: 'DEEPSEEK',
        state: 'AVAILABLE_MANAGED',
        dispatchable: true,
        keySourceMode: 'SYSTEM',
        reason: 'ready',
      },
      {
        provider: 'OPENAI',
        state: 'AVAILABLE_MANAGED',
        dispatchable: true,
        keySourceMode: 'SYSTEM',
        reason: 'ready',
      },
    ],
  });
  api.listAgentEvents.mockResolvedValue([]);
  api.replayAgentEvents.mockResolvedValue({events: [], lastSequence: 0, snapshot: true});
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
  api.getProjectRepos.mockResolvedValue([]);
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
          <Route path='/plan/:runId' element={<div>Full plan review</div>} />
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
      writable: true,
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
    expect(host.textContent).toContain('Conversations');
    expect(host.textContent).toContain('New conversation');
    expect(host.textContent).toContain('Web agent');
    expect(host.textContent).toContain('140 tokens');
    expect(host.textContent).toContain('chat included');
    // Pending gate surfaces inline as an action card
    expect(host.textContent).toContain('Remote sandboxes waiting for your approval');
    expect(host.textContent).toContain('Add audit logs');
    // RR-839 — the trust panel states only enforced properties, not an unproven
    // credential-scope guarantee.
    expect(host.textContent).toContain('Runs on a policy-guarded server workspace');
    expect(host.textContent).not.toContain('Repo token scoped to selected project');
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
    expect(host.textContent).toContain('These settings control conversation replies only');
    expect(host.textContent).toContain('does not turn the chat model into a run-wide pin');
  });

  it('does not advertise an isolated sandbox when the runtime gates are off', async () => {
    api.getRuntimeClientCapabilities.mockResolvedValue({
      remoteSandbox: {
        supported: true,
        enabled: false,
        lifecycleEnabled: false,
        dispatchEnabled: false,
        strictContainerEnabled: false,
        isolationProfile: 'NOT_ENABLED',
        note: 'disabled',
      },
    });

    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('New conversation');
    expect(host.textContent).toContain('Remote workspaces waiting for your approval');
    expect(host.textContent).not.toContain('Remote agent conversation');
    expect(host.textContent).not.toContain('Remote sandboxes waiting for your approval');
  });

  it('persists a Web Agent conversation provider instead of keeping transient UI state', async () => {
    ({host, root} = await renderAgent());
    const settingsBtn = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.getAttribute('aria-label') === 'Agent settings and usage',
    );
    if (!settingsBtn) throw new Error('Settings button was not rendered.');
    await act(async () => settingsBtn.click());
    const provider = host.querySelector<HTMLSelectElement>('#agent-provider-select');
    if (!provider) throw new Error('Provider selector was not rendered.');
    const nextProvider =
      [...provider.options]
        .map((option) => option.value)
        .find((value) => value && value !== 'deepseek') ?? '';
    if (!nextProvider) throw new Error('A second provider option was not rendered.');

    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set;
      setter?.call(provider, nextProvider);
      provider.dispatchEvent(new Event('change', {bubbles: true}));
    });
    await flush();

    expect(api.updateAgentSessionRoute).toHaveBeenCalledWith('s1', {
      provider: nextProvider,
      model: null,
    });
  });

  it('does not expose a dispatch exception as an agent answer', async () => {
    api.listAgentTurns.mockResolvedValue([
      {
        turnId: 't-budget',
        sessionId: 's1',
        sequence: 1,
        userMessage: 'Tell me about the run',
        assistantSummary:
          'LlmDispatchPolicyException: Durable LLM execution-context budget is exhausted',
        intentType: 'QUESTION',
        intentConfidence: 0.9,
        route: 'CHAT_ONLY',
        reasonCode: 'question',
        actionType: null,
        proposedActionJson: null,
        linkedRunId: null,
        inputTokens: 100,
        outputTokens: 0,
        reasoningTokens: 0,
        costUsd: 0,
        status: 'FAILED',
        createdAt: '2026-06-20T00:00:00Z',
      },
    ]);

    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('used its available AI context');
    expect(host.textContent).not.toContain('LlmDispatchPolicyException');
  });

  it('fails closed to remote-workspace language when capability discovery fails', async () => {
    api.getRuntimeClientCapabilities.mockRejectedValueOnce(new Error('capability unavailable'));

    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('New conversation');
    expect(host.textContent).toContain('Remote workspaces waiting for your approval');
    expect(host.textContent).not.toContain('Remote agent conversation');
    expect(host.textContent).not.toContain('Remote sandboxes waiting for your approval');
  });

  it('exposes agent status as an accessible live region for screen-reader operators', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <AgentTopBar
          selected={session}
          barStatus={{label: 'Reconnecting', tone: 'waiting'}}
          scopeLabel='ExecuteSpec'
          busy={false}
          settingsOpen={false}
          managedAi={null}
          sessionUsage={{inputTokens: 0, outputTokens: 0, reasoningTokens: 0, costUsd: 0}}
          routingState={null}
          capabilities={[]}
          pendingGateCount={0}
          onNewChat={vi.fn()}
          onOpenApprovals={vi.fn()}
          onToggleChats={vi.fn()}
          onToggleSettings={vi.fn()}
        />,
      );
    });

    const status = host.querySelector('.agent-bar-status');
    expect(status?.getAttribute('role')).toBe('status');
    expect(status?.getAttribute('aria-live')).toBe('polite');
    expect(status?.textContent).toContain('Reconnecting');
  });

  it('names the mobile new-conversation control when its visible label collapses', async () => {
    ({host, root} = await renderAgent('/agent'));

    const newSandbox = [...host.querySelectorAll<HTMLButtonElement>('.agent-bar-btn')].find(
      (button) => button.textContent?.includes('New conversation'),
    );
    expect(newSandbox?.getAttribute('aria-label')).toBe('Start a new conversation');
  });

  it('retries new-session creation with the same idempotency key', async () => {
    api.createAgentSession.mockRejectedValueOnce(new Error('session service unavailable'));
    ({host, root} = await renderAgent('/agent'));

    const newSandbox = [...host.querySelectorAll<HTMLButtonElement>('.agent-bar-btn')].find(
      (button) => button.textContent?.includes('New conversation'),
    );
    if (!newSandbox) throw new Error('New conversation control was not rendered.');
    await act(async () => {
      newSandbox.dispatchEvent(new MouseEvent('click', {bubbles: true}));
      await Promise.resolve();
    });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      'session service unavailable',
    );
    const firstRequest = api.createAgentSession.mock.calls[0]?.[0];
    const retry = [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('Retry new session'),
    );
    if (!retry) throw new Error('Retry new session control was not rendered.');

    await act(async () => retry.click());
    await flush();

    expect(api.createAgentSession).toHaveBeenCalledTimes(2);
    expect(api.createAgentSession.mock.calls[1]?.[0]?.id).toBe(firstRequest?.id);
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('keeps a newly created workspace selected instead of reloading the previous session', async () => {
    const freshSession = {...session, id: 's2', title: 'Fresh workspace'};
    api.createAgentSession.mockResolvedValueOnce(freshSession);
    ({host, root} = await renderAgent('/agent'));
    const initialLoads = api.listAgentSessions.mock.calls.length;

    const newSandbox = [...host.querySelectorAll<HTMLButtonElement>('.agent-bar-btn')].find(
      (button) => button.textContent?.includes('New conversation'),
    );
    if (!newSandbox) throw new Error('New conversation control was not rendered.');
    await act(async () => {
      newSandbox.click();
      await Promise.resolve();
    });
    await flush();

    expect(host.textContent).toContain('Fresh workspace');
    expect(api.listAgentSessions).toHaveBeenCalledTimes(initialLoads);
    expect(api.listAgentTurns).toHaveBeenCalledWith('s2', {limit: 100});
  });

  it('allows a new workspace while a hydrated run waits for approval without unlocking its composer', async () => {
    const waitingSession = {
      ...session,
      latestLinkedRunId: 'run-awaiting',
      latestLinkedRunStatus: 'AWAITING_APPROVAL',
    };
    const freshSession = {...session, id: 's2', title: 'Independent workspace'};
    api.listAgentSessions.mockResolvedValue([waitingSession]);
    api.listAgentTurns.mockResolvedValue([
      {
        turnId: 't-awaiting',
        sessionId: 's1',
        sequence: 1,
        userMessage: 'Implement the approved plan',
        assistantSummary: 'The plan is waiting for approval.',
        intentType: 'CODE_RUN_REQUIRED',
        intentConfidence: 0.99,
        route: 'CODE_RUN_REQUIRED',
        reasonCode: 'code_run_required',
        actionType: 'create_cloud_run',
        proposedActionJson: null,
        linkedRunId: 'run-awaiting',
        inputTokens: 100,
        outputTokens: 40,
        reasoningTokens: 0,
        costUsd: 0.001,
        status: 'COMPLETED',
        createdAt: '2026-06-20T00:00:00Z',
      },
    ]);
    api.getRun.mockResolvedValue({
      runId: 'run-awaiting',
      runTitle: 'Plan awaiting approval',
      status: 'AWAITING_APPROVAL',
      aiProvider: 'deepseek',
      workerModel: 'deepseek-v4-pro-native',
      costEstimateHigh: 0.5,
      verifiedScore: null,
      scmPrUrl: null,
      githubPrUrl: null,
      tasks: [{title: 'Implement plan', status: 'PENDING'}],
    });
    api.createAgentSession.mockResolvedValueOnce(freshSession);

    ({host, root} = await renderAgent('/agent'));
    await flush();

    const newWorkspace = host.querySelector<HTMLButtonElement>(
      'button.agent-bar-btn[aria-label="Start a new conversation"]',
    );
    const composer = host.querySelector<HTMLTextAreaElement>('[aria-label="Message the agent"]');
    expect(newWorkspace?.disabled).toBe(false);
    expect(composer?.disabled).toBe(true);

    await act(async () => newWorkspace?.click());
    await flush();

    expect(api.createAgentSession).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('Independent workspace');
  });

  it('reconciles the transcript and approval inbox when a suspended mobile tab resumes', async () => {
    ({host, root} = await renderAgent('/agent'));
    const baseline = api.listAgentSessions.mock.calls.length;
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await flush();

    expect(api.listAgentSessions.mock.calls.length).toBeGreaterThan(baseline);
    expect(api.listAgentGates.mock.calls.length).toBeGreaterThan(1);
    expect(api.listAgentTurns.mock.calls.length).toBeGreaterThan(1);
    expect(host.textContent).toContain('Web agent');
  });

  it('renders zero managed-credit policy as an explicit state, not a broken balance', async () => {
    api.getManagedAiBalance.mockResolvedValue({
      ...managedAi,
      managed: false,
      tier: 'ENTERPRISE',
      includedAiCredits: 0,
      aiCreditsUsed: 0,
      aiCreditsRemaining: -1,
      unlimitedAiCredits: true,
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
    expect(host.textContent).toContain('Unlimited AI credits');
    expect(host.textContent).toContain('This tier is not blocked by managed-AI credit limits.');
    expect(host.textContent).not.toContain('0 of 0 credits left');
  });

  it('turns the chats drawer into an operator queue for waiting sandbox plans', async () => {
    ({host, root} = await renderAgent('/agent'));

    const chatsBtn = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Conversations'),
    );
    if (!chatsBtn) throw new Error('Conversations button was not rendered.');
    await act(async () => {
      chatsBtn.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });

    expect(host.textContent).toContain('Conversation history');
    expect(host.textContent).toContain('filesystem is not restorable');
    expect(host.textContent).toContain('1 waiting approval');
    expect(host.textContent).toContain('Approval inbox');
    expect(host.textContent).toContain('2 credits');
    // RR-1489 — the drawer approval inbox must also disclose the USD estimate and
    // the hard cap that bound the spend, not credits alone.
    expect(host.textContent).toContain('~$0.25');
    expect(host.textContent).toContain('cap $5.00');
    expect(host.textContent).toContain('low risk');
    expect(host.textContent).toContain('Review & approve');
    expect(host.textContent).toContain('Reject');
    expect(host.textContent).toContain('Full plan');
    expect(host.textContent).toContain('sandbox operator');
  });

  it('routes positive gate decisions through the full plan without approving inline', async () => {
    ({host, root} = await renderAgent('/agent'));

    const review = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Review & approve'),
    );
    if (!review) throw new Error('Review action was not rendered.');
    await act(async () => review.click());

    expect(host.textContent).toContain('Full plan review');
    expect(api.approveRun).not.toHaveBeenCalled();
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
      button.textContent?.includes('Conversations'),
    );
    if (!chatsBtn) throw new Error('Conversations button was not rendered.');
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
    expect(api.listAgentTurns).toHaveBeenCalledWith('project-session', {limit: 100});
    expect(api.listAgentTurns).not.toHaveBeenCalledWith('global-session', {limit: 100});
  });

  it('shows the remote sandbox launcher before any historical transcript', async () => {
    api.listAgentSessions.mockResolvedValue([]);
    api.listAgentGates.mockResolvedValue([]);
    api.listAgentTurns.mockResolvedValue([]);

    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('Remote sandbox operator');
    expect(host.textContent).toContain('Run work in a remote sandbox.');
    expect(host.textContent).toContain('prepare a PR when publication gates pass');
    expect(host.textContent).toContain('Create a remote sandbox to add audit logging');
    expect(host.textContent).toContain('What will happen before spend');
    expect(host.textContent).toContain('remote clone');
    // RR-839 — honest trust copy: enforced properties only.
    expect(host.textContent).toContain(
      'Run actions and evidence are recorded subject to retention policy',
    );
    expect(host.textContent).toContain('BYOK cost caps are optional');
    expect(host.querySelectorAll('.agent-operator-intro').length).toBe(1);
    expect(host.querySelectorAll('.agent-empty').length).toBe(0);
    expect(host.querySelectorAll('.agent-composer-contract').length).toBe(1);
  });

  it('shows historical transcript as the primary agent response', async () => {
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

    expect(host.textContent).toContain('Explain the CLI repo');
    expect(host.textContent).toContain(
      'This is a long old transcript that should not dominate the remote sandbox launcher.',
    );
    expect(host.querySelectorAll('.agent-msg').length).toBe(2);
    expect(host.querySelectorAll('.agent-composer-contract').length).toBe(0);
    expect(host.textContent).not.toContain('Recent answer');
    expect(host.textContent).not.toContain('Run work in a remote sandbox.');
  });

  it('does not collapse a newly submitted user prompt behind the launcher', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;
    const dispatch = vi.fn();

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
          isBusy={true}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={dispatch}
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
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Agent is working.');
  });

  it('hydrates persisted subagent activity inline without a new route', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.replayAgentEvents.mockResolvedValue({
      events: [
        {
          id: 'event-1',
          sessionId: 's1',
          actionId: null,
          type: 'SUBAGENT_COMPLETED',
          payloadJson: JSON.stringify({summary: 'SCOUT returned cited files'}),
          createdAt: '2026-06-20T00:00:02Z',
        },
      ],
      lastSequence: 1,
      snapshot: true,
    });

    ({host, root} = await renderAgent('/agent'));
    await flush();
    await flush();

    expect(api.replayAgentEvents).toHaveBeenCalledWith('s1');
    expect(host.textContent).toContain('SUBAGENT COMPLETED');
    expect(host.textContent).toContain('SCOUT returned cited files');
  });

  it('drains paginated replay pages so older activity is not lost on reload', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.replayAgentEvents.mockImplementation(async (_sessionId: string, after?: number | null) =>
      after == null
        ? {
            events: [
              {
                id: 'event-page-1',
                sessionId: 's1',
                actionId: null,
                type: 'SUBAGENT_STARTED',
                payloadJson: JSON.stringify({summary: 'SCOUT started'}),
                createdAt: '2026-06-20T00:00:01Z',
                streamSequence: 1,
              },
            ],
            lastSequence: 1,
            snapshot: true,
            hasMore: true,
            nextSequence: 1,
          }
        : {
            events: [
              {
                id: 'event-page-2',
                sessionId: 's1',
                actionId: null,
                type: 'SUBAGENT_COMPLETED',
                payloadJson: JSON.stringify({summary: 'SCOUT returned cited files'}),
                createdAt: '2026-06-20T00:00:02Z',
                streamSequence: 2,
              },
            ],
            lastSequence: 2,
            snapshot: false,
            hasMore: false,
            nextSequence: null,
          },
    );

    ({host, root} = await renderAgent('/agent'));
    await flush();
    await flush();

    expect(api.replayAgentEvents).toHaveBeenNthCalledWith(1, 's1');
    expect(api.replayAgentEvents).toHaveBeenNthCalledWith(2, 's1', 1);
    expect(host.textContent).toContain('SUBAGENT STARTED');
    expect(host.textContent).toContain('SCOUT returned cited files');
  });

  it('keeps the replay page already fetched when a later page is interrupted', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.replayAgentEvents.mockImplementation(async (_sessionId: string, after?: number | null) => {
      if (after != null) throw new Error('replay page unavailable');
      return {
        events: [
          {
            id: 'event-partial-page',
            sessionId: 's1',
            actionId: null,
            type: 'SUBAGENT_COMPLETED',
            payloadJson: JSON.stringify({summary: 'Recovered first page'}),
            createdAt: '2026-06-20T00:00:02Z',
            streamSequence: 1,
          },
        ],
        lastSequence: 1,
        snapshot: true,
        hasMore: true,
        nextSequence: 1,
      };
    });

    ({host, root} = await renderAgent('/agent'));
    await flush();
    await flush();

    expect(host.textContent).toContain('Recovered first page');
  });

  it('offers a keyboard-accessible jump when activity arrives off-screen', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;
    const jumpToLatest = vi.fn();

    await act(async () => {
      localRoot.render(
        <AgentConversation
          messages={[{id: 'assistant:1', role: 'assistant', content: 'A long-running answer'}]}
          streamEvents={[]}
          streamText=''
          isBusy={true}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          hasNewActivity
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={vi.fn()}
          onScroll={vi.fn()}
          onJumpToLatest={jumpToLatest}
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

    const jump = host.querySelector<HTMLButtonElement>('.agent-jump-latest');
    expect(jump?.getAttribute('aria-label')).toBe('Jump to latest activity');
    await act(async () => jump?.click());
    expect(jumpToLatest).toHaveBeenCalledTimes(1);
  });

  it('does not collapse a user turn behind an empty persisted assistant summary', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;
    const dispatch = vi.fn();

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
          dispatch={dispatch}
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
    expect(host.textContent).toContain(
      'No response arrived for this turn. You can send the message again.',
    );
  });

  it('replaces a marker-only Markdown fragment with an explicit response state', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;

    await act(async () => {
      localRoot.render(
        <AgentConversation
          streamText=''
          visibleAction={null}
          gates={[]}
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
          messages={[{id: 'turn:assistant', role: 'assistant', content: '1.'}]}
          isBusy={false}
          streamEvents={[]}
          runCard={null}
          routingState={null}
        />,
      );
    });

    expect(host.textContent).toContain(
      'No response arrived for this turn. You can send the message again.',
    );
    expect(host.querySelector('.signal-md-list')).toBeNull();
  });

  it('collapses generated code blocks by default and shows assistant turn usage', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;
    const dispatch = vi.fn();

    await act(async () => {
      localRoot.render(
        <AgentConversation
          messages={[
            {
              id: 'turn:assistant',
              role: 'assistant',
              content: 'Here is the file:\n\n```html\n<div>One</div>\n<div>Two</div>\n```',
              usage: {
                inputTokens: 1200,
                outputTokens: 400,
                reasoningTokens: 50,
                costUsd: 0.01,
              },
            },
          ]}
          streamEvents={[]}
          streamText=''
          isBusy={false}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          remoteSandboxEnabled
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={dispatch}
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

    const codeFold = host.querySelector('details.signal-md-codefold');
    expect(codeFold).not.toBeNull();
    expect(codeFold?.hasAttribute('open')).toBe(false);
    expect(codeFold?.querySelector('summary')?.textContent).toContain('HTML');
    expect(host.textContent).toContain('Generated artifacts');
    expect(host.textContent).toContain('artifact-1.html');
    expect(host.textContent).toContain('Copy');
    expect(host.textContent).toContain('Download');
    expect(host.textContent).toContain('Preview');
    expect(host.textContent).toContain('Draft sandbox request');
    expect(host.textContent).toContain('Open local handoff');
    expect(host.textContent).toContain('1.6k tokens');
    expect(host.textContent).toContain('chat included');

    const useInSandbox = [...host.querySelectorAll('button')].find(
      (button) => button.textContent === 'Draft sandbox request',
    );
    if (!useInSandbox) throw new Error('Draft sandbox request action was not rendered.');
    await act(async () => {
      useInSandbox.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    expect(dispatch).toHaveBeenCalledWith({
      type: 'input:set',
      input: expect.stringContaining('artifact-1.html'),
    });

    const originalClipboard = navigator.clipboard;
    const originalOpen = window.open;
    const originalCreateObjectUrl = URL.createObjectURL;
    const originalRevokeObjectUrl = URL.revokeObjectURL;
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {writeText: vi.fn().mockRejectedValue(new Error('clipboard denied'))},
    });
    Object.defineProperty(window, 'open', {
      configurable: true,
      value: vi.fn(() => null),
    });
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:test-artifact'),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: vi.fn(),
    });
    try {
      const copy = [...host.querySelectorAll('button')].find(
        (button) => button.textContent === 'Copy',
      );
      const preview = [...host.querySelectorAll('button')].find(
        (button) => button.textContent === 'Preview',
      );
      if (!copy || !preview) throw new Error('Artifact actions were not rendered.');
      await act(async () => {
        copy.dispatchEvent(new MouseEvent('click', {bubbles: true}));
        preview.dispatchEvent(new MouseEvent('click', {bubbles: true}));
        await Promise.resolve();
      });
      expect(host.textContent).toContain('Clipboard access was denied. Use Download instead.');
      // RR-623: preview must NOT open a blob: URL in a window. A blob URL inherits the creator's
      // origin, so model-authored HTML would run as first-party and could read localStorage (where
      // access_token lives). It renders into a sandboxed iframe with an opaque origin instead.
      expect(window.open).not.toHaveBeenCalled();
      expect(URL.createObjectURL).not.toHaveBeenCalled();
      const frame = host.querySelector('iframe.agent-artifact-preview-frame');
      if (!frame) throw new Error('Sandboxed preview frame was not rendered.');
      const sandbox = frame.getAttribute('sandbox') ?? '';
      expect(sandbox.split(/\s+/)).toContain('allow-scripts');
      // allow-same-origin alongside allow-scripts would let the document remove its own sandbox.
      expect(sandbox).not.toContain('allow-same-origin');
      expect(frame.getAttribute('srcdoc') ?? '').toContain('Content-Security-Policy');
    } finally {
      Object.defineProperty(navigator, 'clipboard', {configurable: true, value: originalClipboard});
      Object.defineProperty(window, 'open', {configurable: true, value: originalOpen});
      Object.defineProperty(URL, 'createObjectURL', {
        configurable: true,
        value: originalCreateObjectUrl,
      });
      Object.defineProperty(URL, 'revokeObjectURL', {
        configurable: true,
        value: originalRevokeObjectUrl,
      });
    }
  });

  it('keeps artifact sandbox actions disabled until the server enables the runtime', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;
    const dispatch = vi.fn();

    await act(async () => {
      localRoot.render(
        <AgentConversation
          messages={[
            {
              id: 'turn:assistant',
              role: 'assistant',
              content: 'Generated output:\n\n```html\n<main>Release candidate</main>\n```',
            },
          ]}
          streamEvents={[]}
          streamText=''
          isBusy={false}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          remoteSandboxEnabled={false}
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={dispatch}
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

    const sandboxAction = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Sandbox unavailable',
    );
    expect(sandboxAction?.disabled).toBe(true);
    expect(sandboxAction?.title).toBe('Remote sandbox is unavailable in this environment.');
    expect(host.textContent).toContain('Remote sandbox is unavailable in this environment.');
    expect(host.textContent).not.toContain('Draft sandbox request');

    await act(async () => {
      sandboxAction?.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    expect(dispatch).not.toHaveBeenCalled();
  });

  it('renders multi-file generated output as separate artifact actions', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const localRoot = createRoot(host);
    root = localRoot;
    const dispatch = vi.fn();

    await act(async () => {
      localRoot.render(
        <AgentConversation
          messages={[
            {
              id: 'turn:assistant',
              role: 'assistant',
              content:
                'Here are the files:\n\n`src/App.tsx`:\n```tsx\nexport function App() { return <main />; }\n```\n\n```css filename=src/app.css\nmain { display: grid; }\n```',
            },
          ]}
          streamEvents={[]}
          streamText=''
          isBusy={false}
          visibleAction={null}
          gates={[]}
          runCard={null}
          runCost={null}
          remoteSandboxEnabled
          input=''
          attachments={[]}
          uploading={false}
          endRef={{current: null}}
          dispatch={dispatch}
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

    expect(host.textContent).toContain('2 files');
    expect(host.textContent).toContain('src/App.tsx');
    expect(host.textContent).toContain('src/app.css');
    expect([...host.querySelectorAll('.agent-artifact-item')]).toHaveLength(2);
    expect(
      [...host.querySelectorAll('button')].filter((b) => b.textContent === 'Copy'),
    ).toHaveLength(2);
    expect(
      [...host.querySelectorAll('button')].filter((b) => b.textContent === 'Download'),
    ).toHaveLength(2);
    expect(
      [...host.querySelectorAll('button')].filter((b) => b.textContent === 'Draft sandbox request'),
    ).toHaveLength(2);
  });

  it('renders streamed route decisions and cloud-run proposal cards before execution', async () => {
    api.getProjectRepos.mockResolvedValue([
      {
        id: 'link-1',
        repoId: 'repo-1',
        repo: {
          id: 'repo-1',
          githubRepoFullName: 'roundel/audit-service',
          scmProvider: 'GITHUB',
          sourceCapabilities: ['CLOUD_RUN'],
        },
      },
    ]);
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
          arguments: {spec: 'Add billing audit logs', projectId: 'p1'},
          requiresConfirmation: true,
          billingImpact: 'run_credit_on_plan_approval',
          confirmationPrompt: 'Create a remote sandbox?',
          actionContext: {
            requiredContext: [],
            blockingReasons: [],
            confirmable: true,
            recommendedAction: 'CONFIRM_ACTION',
            projectSelectionRequired: false,
          },
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

    expect(api.streamAgentTurn).toHaveBeenCalledWith(
      's1',
      expect.objectContaining({
        message: 'Start a run to add billing audit logs',
        conversation: [
          {
            role: 'system',
            content: expect.stringContaining(
              'chat turns are included conversation usage and have not charged AI credits',
            ),
          },
          {role: 'user', content: 'What plans are waiting?'},
          {role: 'assistant', content: 'One plan is waiting for approval.'},
          {role: 'user', content: 'Start a run to add billing audit logs'},
        ],
      }),
      expect.any(Object),
      expect.any(AbortSignal),
    );
    // Proposal renders as a clean inline action card (no routing jargon in the main view)
    expect(host.textContent).toContain('Create remote sandbox run');
    expect(host.textContent).toContain('Create a remote sandbox?');
    expect(host.textContent).toContain('Add billing audit logs');
    expect(host.textContent).toContain('Create sandbox plan');
    expect(host.textContent).toContain('approval before credits');
    expect(host.textContent).toContain('What will happen before spend');
    expect(host.textContent).toContain('Branch');
    // RR-839 — the trust panel must NOT claim a secret-hiding or outbound-network
    // policy: neither is enforced by the current policy-guarded host workspace.
    // Re-add only when the strict sandbox runtime lands with a persisted isolation DTO.
    expect(host.textContent).not.toContain('Secrets are not displayed in chat');
    expect(host.textContent).not.toContain('Outbound network');
    expect(host.textContent).toContain('Approval required before any code runs');
    expect(api.getProjectRepos).toHaveBeenCalledWith('p1');
    expect(host.textContent).toContain('Repository scope');
    expect(host.textContent).toContain('roundel/audit-service');
    expect(host.textContent).toContain('Nothing is selected automatically.');
    const createPlan = [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('Create sandbox plan'),
    );
    if (!createPlan) throw new Error('Create sandbox plan button was not rendered.');
    expect(createPlan.disabled).toBe(true);

    const repoChoice = host.querySelector<HTMLInputElement>('input[type="checkbox"]');
    if (!repoChoice) throw new Error('Repository checkbox was not rendered.');
    await act(async () => {
      repoChoice.click();
    });
    expect(createPlan.disabled).toBe(false);
    await act(async () => {
      createPlan.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await flush();

    expect(api.confirmAgentRunProposal).toHaveBeenCalledWith({
      sessionId: 's1',
      turnId: 't2',
      confirmed: true,
      selectedRepoIds: ['repo-1'],
    });
  });

  it('does not reclassify a completed turn when the post-turn refresh fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    api.streamAgentTurn.mockImplementation(async (_sessionId, _body, handlers) => {
      handlers.onToken?.({delta: 'Completed response'});
      handlers.onDone?.({session_id: 's1', turn_id: 't2', turn_count: 2});
    });

    ({host, root} = await renderAgent('/agent'));
    api.listAgentSessions.mockRejectedValueOnce(new Error('refresh broke'));
    const textarea = host.querySelector('textarea');
    if (!textarea) throw new Error('Agent composer textarea was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(textarea, 'Complete this turn');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const send = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.getAttribute('aria-label') === 'Send',
    );
    if (!send) throw new Error('Send button was not rendered.');

    await act(async () => send.click());
    await flush();

    expect(warn).toHaveBeenCalledWith('Completed agent turn refresh failed', expect.any(Error));
    expect(host.textContent).not.toContain('refresh broke');
    expect(host.textContent).not.toContain('Agent turn failed');
    expect(textarea.disabled).toBe(false);
  });

  it('offers Refresh after proposal confirmation fails without replaying the mutation', async () => {
    api.getProjectRepos.mockResolvedValue([
      {
        repoId: 'repo-1',
        repo: {
          id: 'repo-1',
          githubRepoFullName: 'roundel/audit-service',
          scmProvider: 'GITHUB',
          sourceCapabilities: ['CLOUD_RUN'],
        },
      },
    ]);
    api.listAgentTurns.mockResolvedValue([
      {
        turnId: 't-confirm',
        sessionId: 's1',
        sequence: 2,
        userMessage: 'Add billing audit logs',
        assistantSummary: 'Ready to create the plan.',
        intentType: 'CODE_RUN_REQUIRED',
        intentConfidence: 0.94,
        route: 'CODE_RUN_REQUIRED',
        reasonCode: 'explicit_code_mutation_request',
        actionType: 'create_cloud_run',
        proposedActionJson: JSON.stringify({
          type: 'create_cloud_run',
          arguments: {spec: 'Add billing audit logs', projectId: 'p1'},
          requiresConfirmation: true,
          billingImpact: 'run_credit_on_plan_approval',
          confirmationPrompt: 'Create a remote sandbox?',
          actionContext: {
            requiredContext: [],
            blockingReasons: [],
            confirmable: true,
            recommendedAction: 'CONFIRM_ACTION',
            projectSelectionRequired: false,
          },
        }),
        linkedRunId: null,
        inputTokens: 10,
        outputTokens: 4,
        reasoningTokens: 0,
        costUsd: 0.001,
        status: 'NEEDS_CONFIRMATION',
        createdAt: '2026-06-20T00:00:00Z',
      },
    ]);
    api.confirmAgentRunProposal.mockRejectedValueOnce(new Error('confirmation unavailable'));

    ({host, root} = await renderAgent('/agent'));
    await vi.waitFor(() => expect(host?.querySelector('input[type="checkbox"]')).toBeTruthy());
    await act(async () => {
      host?.querySelector<HTMLInputElement>('input[type="checkbox"]')?.click();
    });
    const createPlan = [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('Create sandbox plan'),
    );
    if (!createPlan) throw new Error('Create sandbox plan button was not rendered.');
    await act(async () => createPlan.click());
    await flush();

    expect(host.querySelector('[role="alert"]')?.textContent).toContain('confirmation unavailable');
    const refresh = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Refresh',
    );
    if (!refresh) throw new Error('Refresh control was not rendered.');
    const confirmationCalls = api.confirmAgentRunProposal.mock.calls.length;

    await act(async () => refresh.click());
    await flush();

    expect(api.confirmAgentRunProposal).toHaveBeenCalledTimes(confirmationCalls);
    expect(api.listAgentSessions.mock.calls.length).toBeGreaterThan(1);
  });

  // Phase 4 invariant: a proposal without project scope, or with a server-sent
  // blocked context, must never render a confirmable sandbox card. Before the
  // shared predicate, rendering keyed only on action.type, so this payload
  // produced a confirm button that the dispatcher would have submitted.
  it('renders a cloud-run proposal without project scope as non-confirmable', async () => {
    api.streamAgentTurn.mockImplementation(async (_sessionId, _body, handlers) => {
      handlers.onAck?.({session_id: 's1', turn_id: 't9', sequence: 2});
      handlers.onProposal?.({
        session_id: 's1',
        turn_id: 't9',
        route: 'CODE_RUN_REQUIRED',
        action: {
          type: 'create_cloud_run',
          arguments: {spec: 'Add billing audit logs'},
          requiresConfirmation: true,
          billingImpact: 'run_credit_on_plan_approval',
          confirmationPrompt: 'Create a remote sandbox?',
          actionContext: {
            requiredContext: ['project'],
            blockingReasons: ['MISSING_PROJECT_CONTEXT'],
            confirmable: false,
            recommendedAction: 'SELECT_PROJECT',
            projectSelectionRequired: true,
          },
        },
      });
      handlers.onDone?.({
        session_id: 's1',
        turn_id: 't9',
        turn_count: 2,
        input_tokens: 10,
        output_tokens: 4,
        cost_usd: 0.001,
      });
    });

    ({host, root} = await renderAgent('/agent'));

    const textarea = host.querySelector('textarea');
    if (!textarea) throw new Error('Agent composer textarea was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(textarea, 'Implement OAuth login');
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

    expect(host.textContent).toContain('Remote run not ready');
    expect(host.textContent).toContain('Select a project or repository first.');
    expect(host.textContent).not.toContain('Create sandbox plan');
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
    expect(host.textContent).toContain('Remote sandbox · plan-gated · publication-gated');
    expect(host.textContent).toContain('91/100');
    expect(host.textContent).toContain('Review evidence');
  });

  it('replays durable turns when the POST-SSE body ends without a terminal event', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.streamAgentTurn.mockRejectedValue(
      new Error(
        'Agent stream ended before the turn completed. Refresh the conversation and retry.',
      ),
    );
    api.listAgentTurns.mockResolvedValueOnce([]).mockResolvedValue([
      {
        turnId: 't3',
        sessionId: 's1',
        sequence: 3,
        userMessage: 'Inspect the current test failure',
        assistantSummary: 'The server persisted this turn before the stream disconnected.',
        intentType: 'CHAT_ONLY',
        intentConfidence: 0.9,
        route: 'CHAT_ONLY',
        reasonCode: 'question',
        actionType: null,
        proposedActionJson: null,
        linkedRunId: null,
        inputTokens: 10,
        outputTokens: 4,
        reasoningTokens: 0,
        costUsd: 0.001,
        status: 'COMPLETED',
        createdAt: '2026-06-20T00:00:00Z',
      },
    ]);

    ({host, root} = await renderAgent('/agent'));
    const textarea = host.querySelector('textarea');
    if (!textarea) throw new Error('Agent composer textarea was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(textarea, 'Inspect the current test failure');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const send = [...host.querySelectorAll('button')].find(
      (button) => button.getAttribute('aria-label') === 'Send',
    );
    if (!send) throw new Error('Agent send button was not rendered.');
    await act(async () => {
      send.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
    await flush();
    await flush();

    expect(api.listAgentTurns.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(host.textContent).toContain(
      'The server persisted this turn before the stream disconnected.',
    );
    expect(host.textContent).toContain('Agent stream ended before the turn completed');
    const refresh = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Refresh',
    );
    if (!refresh) throw new Error('Refresh control was not rendered.');
    const streamCalls = api.streamAgentTurn.mock.calls.length;
    const sessionLoads = api.listAgentSessions.mock.calls.length;

    await act(async () => refresh.click());
    await flush();

    expect(api.streamAgentTurn).toHaveBeenCalledTimes(streamCalls);
    expect(api.listAgentSessions.mock.calls.length).toBeGreaterThan(sessionLoads);
  });

  it('shows project recovery, searches existing projects, and binds the selected project', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.createAgentSession.mockImplementation(async (request) => ({
      ...session,
      id: request.id,
      projectId: request.projectId,
      title: 'Project agent',
    }));
    ({host, root} = await renderAgent('/agent'));

    expect(host.textContent).toContain('No project linked');
    expect(host.textContent).toContain('continue a general conversation');
    const composer = host.querySelector<HTMLTextAreaElement>('.agent-composer textarea');
    if (!composer) throw new Error('Agent composer was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(composer, 'Keep this unsent draft');
      composer.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const search = host.querySelector<HTMLInputElement>('#agent-project-search');
    if (!search) throw new Error('Project search was not rendered.');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(search, 'execute');
      search.dispatchEvent(new Event('input', {bubbles: true}));
    });
    const project = [
      ...host.querySelectorAll<HTMLButtonElement>('.agent-project-results button'),
    ].find((button) => button.textContent?.includes('ExecuteSpec'));
    if (!project) throw new Error('Matching project was not rendered.');
    await act(async () => project.click());
    await flush();

    expect(api.createAgentSession).toHaveBeenCalledWith(
      expect.objectContaining({projectId: 'p1', surface: 'WEB'}),
    );
    expect(host.textContent).toContain('Current project');
    expect(host.textContent).toContain('ExecuteSpec');
    expect(host.querySelector<HTMLTextAreaElement>('.agent-composer textarea')?.value).toBe(
      'Keep this unsent draft',
    );
  });

  it('locks project switch and unlink controls while an execution waits at its gate', async () => {
    api.listAgentTurns.mockResolvedValue([
      {
        turnId: 't-run',
        sessionId: 's1',
        sequence: 1,
        userMessage: 'Run it',
        assistantSummary: 'Waiting for approval.',
        intentType: 'RUN',
        intentConfidence: 1,
        route: 'CODE_RUN_REQUIRED',
        reasonCode: 'run',
        actionType: null,
        proposedActionJson: null,
        linkedRunId: 'run-awaiting',
        inputTokens: 1,
        outputTokens: 1,
        reasoningTokens: 0,
        costUsd: 0,
        status: 'COMPLETED',
        createdAt: '2026-06-20T00:00:00Z',
      },
    ]);
    api.getRun.mockResolvedValue({
      runId: 'run-awaiting',
      runTitle: 'Waiting run',
      status: 'AWAITING_APPROVAL',
      tasks: [],
    });
    ({host, root} = await renderAgent('/agent'));
    await flush();

    const createProject = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Create project',
    );
    const switchProject = host.querySelector<HTMLButtonElement>('.agent-project-results button');
    expect(createProject?.disabled).toBe(true);
    expect(switchProject?.disabled).toBe(true);
    expect(host.textContent).toContain(
      'cannot change while this conversation has an active execution',
    );
  });

  it('shows project-loading errors and retries without hiding the conversation', async () => {
    api.listAgentGates.mockResolvedValue([]);
    api.getProjects.mockRejectedValueOnce(new Error('project service unavailable'));
    ({host, root} = await renderAgent('/agent'));

    expect(host.querySelector('.agent-project-error[role="alert"]')?.textContent).toContain(
      'project service unavailable',
    );
    expect(host.textContent).toContain('One plan is waiting for approval.');
    const retry = host.querySelector<HTMLButtonElement>('.agent-project-error button');
    await act(async () => retry?.click());
    await flush();

    expect(api.getProjects).toHaveBeenCalledTimes(2);
    expect(host.querySelector('.agent-project-error[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('ExecuteSpec');
  });

  it('loads earlier transcript pages using the oldest turn id cursor', async () => {
    api.listAgentGates.mockResolvedValue([]);
    const page = Array.from({length: 100}, (_, index) => ({
      turnId: `turn-${index + 1}`,
      sessionId: 's1',
      sequence: index + 1,
      userMessage: `Message ${index + 1}`,
      assistantSummary: `Answer ${index + 1}`,
      intentType: 'QUESTION',
      intentConfidence: 1,
      route: 'CHAT_ONLY',
      reasonCode: 'question',
      actionType: null,
      proposedActionJson: null,
      linkedRunId: null,
      inputTokens: 1,
      outputTokens: 1,
      reasoningTokens: 0,
      costUsd: 0,
      status: 'COMPLETED',
      createdAt: '2026-06-20T00:00:00Z',
    }));
    api.listAgentTurns.mockImplementation(async (_id, options) =>
      options.before ? [{...page[0], turnId: 'turn-0', sequence: 0, userMessage: 'Oldest'}] : page,
    );
    ({host, root} = await renderAgent('/agent'));

    const earlier = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Load earlier messages',
    );
    if (!earlier) throw new Error('Load earlier control was not rendered.');
    await act(async () => earlier.click());
    await flush();

    expect(api.listAgentTurns).toHaveBeenCalledWith('s1', {limit: 100, before: 'turn-1'});
    expect(host.textContent).toContain('Oldest');
    expect(host.textContent).not.toContain('Load earlier messages');
  });

  it('supports keyboard cancel and visible server errors when closing a conversation', async () => {
    api.listAgentGates.mockResolvedValue([]);
    ({host, root} = await renderAgent('/agent'));
    const conversations = [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
      button.textContent?.includes('Conversations'),
    );
    await act(async () => conversations?.click());
    const close = host.querySelector<HTMLButtonElement>(
      '[aria-label="Close conversation Web agent"]',
    );
    await act(async () => close?.click());
    expect(host.querySelector('[role="dialog"][aria-label="Close conversation?"]')).not.toBeNull();
    expect(document.activeElement?.getAttribute('tabindex')).toBe('-1');

    await act(async () => window.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'})));
    expect(host.querySelector('[role="dialog"][aria-label="Close conversation?"]')).toBeNull();
    expect(api.closeAgentSession).not.toHaveBeenCalled();

    await act(async () => conversations?.click());
    const closeAgain = host.querySelector<HTMLButtonElement>(
      '[aria-label="Close conversation Web agent"]',
    );
    await act(async () => closeAgain?.click());
    api.closeAgentSession.mockRejectedValueOnce(new Error('archive service unavailable'));
    const confirm = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (button) => button.textContent === 'Close conversation',
    );
    await act(async () => confirm?.click());
    await flush();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      'archive service unavailable',
    );
  });
});
