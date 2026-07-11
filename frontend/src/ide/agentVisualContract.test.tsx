import {act, type ComponentProps} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {afterEach, describe, expect, it, vi} from 'vitest';

import {AgentChatsDrawer, AgentConversation, AgentTopBar} from './agentPanels';

vi.mock('@/stores/useAuthStore', () => {
  const state = {user: {globalRole: 'DEVELOPER', orgRole: 'DEVELOPER'}};
  return {
    useAuthStore: (selector?: (value: typeof state) => unknown) =>
      typeof selector === 'function' ? selector(state) : state,
    selectIsSuperAdmin: () => false,
  };
});

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

/**
 * Credential-free visual QA for the protected transcript surface.
 *
 * This is deliberately a render contract, not an authenticated browser claim:
 * it locks the operator landmarks and state labels that a screenshot harness
 * must see for the run-card, activity timeline, and overlays.
 */
describe('protected transcript visual contract', () => {
  type RunCard = NonNullable<ComponentProps<typeof AgentConversation>['runCard']>;
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    root = null;
    host = null;
  });

  function mount(element: React.ReactNode) {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    act(() => root?.render(element));
    return host;
  }

  const baseConversation: Omit<ComponentProps<typeof AgentConversation>, 'runCard'> = {
    messages: [{id: 'm1', role: 'assistant', content: 'Working on the requested change.'}],
    streamEvents: [{id: 'tool-1', type: 'tool', title: 'searchFiles'}],
    streamText: '',
    isBusy: true,
    visibleAction: null,
    gates: [],
    runCost: 0.14,
    runStreamStatus: 'RECONNECTING',
    routingState: null,
    hasNewActivity: true,
    input: '',
    attachments: [],
    uploading: false,
    endRef: {current: null},
    dispatch: vi.fn(),
    onScroll: vi.fn(),
    onJumpToLatest: vi.fn(),
    onSend: vi.fn(),
    onStop: vi.fn(),
    onAddFiles: vi.fn(),
    onRemoveAttachment: vi.fn(),
    onConfirmAction: vi.fn(),
    onApproveGate: vi.fn(),
    onRejectGate: vi.fn(),
    onOpenGate: vi.fn(),
    onOpenRun: vi.fn(),
    onStopRun: vi.fn(),
  };

  const activeRun: RunCard = {
    runId: 'run-visual-1',
    status: 'WORKING',
    runTitle: 'Add audit logging',
    userRequest: 'Add audit logging',
    plannerModel: 'planner',
    workerModel: 'deepseek-v4-pro-native',
    reviewerModel: 'reviewer',
    totalInputTokens: 100,
    totalOutputTokens: 50,
    startedAt: '2026-07-10T00:00:00Z',
    completedAt: null,
    tasks: [
      {
        taskId: 'audit-service',
        title: 'Wire audit service',
        status: 'IN_PROGRESS',
        assignedModel: 'deepseek-v4-pro-native',
        inputTokens: 0,
        outputTokens: 0,
        durationMs: 0,
        branchName: null,
        startedAt: null,
        completedAt: null,
        readmeUpdated: false,
      },
      {
        taskId: 'audit-tests',
        title: 'Add tests',
        status: 'PENDING',
        assignedModel: 'deepseek-v4-pro-native',
        inputTokens: 0,
        outputTokens: 0,
        durationMs: 0,
        branchName: null,
        startedAt: null,
        completedAt: null,
        readmeUpdated: false,
      },
    ],
    planJson: '{}',
    reviewJson: '{}',
    integrationSource: null,
    aiProvider: 'deepseek',
    aiCreditsCharged: 3,
    verifiedScore: null,
  };

  it('keeps the transcript screenshot landmarks and reconnecting state explicit', () => {
    const rendered = mount(<AgentConversation {...baseConversation} runCard={activeRun} />);

    expect(rendered.querySelector('[data-agent-surface="protected-transcript"]')).not.toBeNull();
    expect(rendered.querySelector('[data-agent-region="transcript"]')).not.toBeNull();
    expect(rendered.querySelector('[data-agent-region="activity"]')).not.toBeNull();
    expect(rendered.querySelector('[data-agent-region="run-card"]')).not.toBeNull();
    expect(rendered.querySelector('[data-agent-region="new-activity"]')).not.toBeNull();

    const card = rendered.querySelector('[data-agent-region="run-card"]');
    expect(card?.getAttribute('data-run-status')).toBe('WORKING');
    expect(card?.getAttribute('data-stream-status')).toBe('RECONNECTING');
    expect(card?.textContent).toContain('Reconnecting live events');
    expect(card?.textContent).toContain('deepseek');
    expect(card?.querySelectorAll('[aria-label="Sandbox phase"] > *')).toHaveLength(8);
    expect(rendered.querySelector('[data-agent-region="new-activity"]')?.textContent).toContain(
      'New activity',
    );
  });

  it('marks terminal failure visually without losing the evidence action', () => {
    const failedRun: RunCard = {
      ...activeRun,
      status: 'FAILED',
      completedAt: '2026-07-10T00:10:00Z',
    };
    const rendered = mount(
      <AgentConversation
        {...baseConversation}
        isBusy={false}
        streamEvents={[]}
        hasNewActivity={false}
        runStreamStatus='CLOSED'
        runCard={failedRun}
      />,
    );

    const card = rendered.querySelector('[data-agent-region="run-card"]');
    expect(card?.classList.contains('failed')).toBe(true);
    expect(card?.getAttribute('data-run-status')).toBe('FAILED');
    expect(card?.textContent).toContain('Open failure evidence');
    expect(card?.textContent).toContain('Evidence pending');
    expect(card?.querySelector('button')).not.toBeNull();
  });

  it('keeps persisted verification evidence expandable in the transcript', () => {
    const rendered = mount(
      <AgentConversation
        {...baseConversation}
        isBusy={false}
        streamEvents={[
          {
            id: 'evidence-1',
            type: 'system',
            title: 'RUN VALIDATION EVIDENCE',
            detail: 'Build passed · tests 12/12 · reviewer CLEAN',
          },
        ]}
        hasNewActivity={false}
        runStreamStatus='CLOSED'
        runCard={null}
      />,
    );

    const evidence = rendered.querySelector('.agent-activity-evidence');
    expect(evidence).not.toBeNull();
    expect(evidence?.querySelector('summary')?.textContent).toContain('Verification evidence');
    expect(evidence?.querySelector('p')?.textContent).toContain('Build passed');
  });

  it('renders an image attachment preview and the semantic intent route inline', () => {
    const rendered = mount(
      <AgentConversation
        {...baseConversation}
        isBusy={false}
        streamEvents={[]}
        hasNewActivity={false}
        runStreamStatus='IDLE'
        routingState={{
          route: 'CODE_RUN_REQUIRED',
          confidence: 0.92,
          reasonCode: 'explicit_code_mutation_request',
          runApiAllowed: true,
        }}
        runCard={null}
        attachments={[
          {
            attachmentId: 'img-1',
            filename: 'screen.png',
            mimeType: 'image/png',
            extractedChars: 0,
            previewUrl: 'blob:test-image',
          },
        ]}
      />,
    );

    expect(rendered.querySelector('[data-agent-region="route-decision"]')?.textContent).toContain(
      'CODE RUN REQUIRED',
    );
    expect(rendered.querySelector('[data-agent-region="route-decision"]')?.textContent).toContain(
      '92% confidence',
    );
    expect(rendered.querySelector('.agent-attach-thumb')?.getAttribute('src')).toBe(
      'blob:test-image',
    );
    expect(rendered.querySelector('input[type="file"]')?.getAttribute('accept')).toContain(
      'image/png',
    );
  });

  it('exposes settings and chats as distinct modal screenshot surfaces', () => {
    const top = mount(
      <AgentTopBar
        selected={null}
        barStatus={{label: 'Ready', tone: 'ok'}}
        scopeLabel={null}
        busy={false}
        settingsOpen
        managedAi={null}
        sessionUsage={{inputTokens: 0, outputTokens: 0, reasoningTokens: 0, costUsd: 0}}
        routingState={null}
        capabilities={[]}
        pendingGateCount={0}
        onNewChat={vi.fn()}
        onOpenApprovals={vi.fn()}
        onToggleChats={vi.fn()}
        onToggleSettings={vi.fn()}
        availableModels={[
          {
            id: 'deepseek',
            label: 'DeepSeek',
            models: [
              {id: 'deepseek-v4-flash-native', tier: 'LIGHTWEIGHT', label: 'DeepSeek Flash'},
            ],
          },
        ]}
        selectedProvider='deepseek'
        selectedModel='deepseek-v4-flash-native'
        onProviderChange={vi.fn()}
        onModelChange={vi.fn()}
      />,
    );
    expect(top.querySelector('[data-agent-overlay="settings"]')).not.toBeNull();
    expect(top.querySelector('[data-agent-overlay-backdrop="settings"]')).not.toBeNull();
    expect(top.querySelector('#agent-provider-select')).not.toBeNull();
    expect(top.querySelector('#agent-model-select')).not.toBeNull();
    expect(top.querySelector('#agent-model-select')?.textContent).toContain('DeepSeek Flash');

    act(() => root?.unmount());
    host?.remove();
    root = null;
    host = null;
    const drawer = mount(
      <AgentChatsDrawer
        open
        sessions={[]}
        gates={[]}
        selectedId={null}
        status='idle'
        onSelect={vi.fn()}
        onNewChat={vi.fn()}
        onApproveGate={vi.fn()}
        onRejectGate={vi.fn()}
        onOpenGate={vi.fn()}
        onClose={vi.fn()}
      />,
    );
    expect(drawer.querySelector('[data-agent-overlay="chats"]')).not.toBeNull();
    expect(drawer.querySelector('[data-agent-overlay-backdrop="chats"]')).not.toBeNull();
    expect(drawer.querySelector('[role="dialog"]')?.getAttribute('aria-modal')).toBe('true');
  });
});
