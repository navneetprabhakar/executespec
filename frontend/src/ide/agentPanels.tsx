import {useEffect, useRef, useState, type Dispatch, type RefObject, type UIEvent} from 'react';
import * as api from '@/lib/api';
import {
  providerIsDispatchable,
  providerOptionLabel,
  providerReadinessLabel,
} from '@/ide/providerReadiness';
import {Markdown} from '@/signal/components/Markdown';
import {CommandIcon, ImageIcon, PlusIcon, RunsIcon, SettingsIcon, StatusDot} from '@/ide/IdeIcons';
import {IdeCodeViewer, type DiffLine} from '@/ide/IdeKit';
import {extractAgentArtifacts} from '@/ide/agentArtifacts';
import {ArtifactRail} from '@/ide/agentArtifactRail';
import {boundedInlineDiff} from '@/ide/agentDiff';
import {isCloudRunAction, isConfirmableCloudRun} from '@/ide/agentActionPolicy';
import {agentRunUnavailableMessage, type AgentRunUnavailableReason} from '@/ide/agentRunStream';
import {AgentBlockedActionCard} from '@/ide/AgentBlockedActionCard';
import {
  asText,
  deriveSessionTitle,
  isTerminalRunStatus,
  runOpenPath,
  runPhaseLabel,
  runProgress,
  sessionTitle,
  type AgentAction,
  type AgentAttachmentLite,
  type AgentUsageSummary,
  type AgentUiState,
  type BarTone,
  type LocalMessage,
  type PendingAction,
  type RoutingState,
  type StreamTimelineItem,
} from '@/ide/agentState';

import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {ArrowDown, ArrowUp, CloseIcon, FolderIcon, Paperclip, StopIcon} from '@/ide/agentIcons';
import {AgentToolActivity} from '@/ide/agentToolActivity';
import {GateCard, GateQueueRow, relTime} from '@/ide/AgentApprovalCards';
import {
  EmptyRuntimeState,
  OperatorIntro,
  RuntimeContractPanel,
  SANDBOX_PHASES,
  TrustBoundaryPanel,
} from '@/ide/agentRuntimeContract';
import {phaseState, sandboxPhaseKey} from '@/ide/agentSandboxPhase';
import {friendlyAiFailureMessage} from '@/ide/runResultUtils';
import {
  AgentRepositoryScope,
  repositoryConfirmationDisabled,
  type CloudRunRepoSelection,
} from '@/ide/AgentRepositoryScope';
import {ConversationHistoryPager, ConversationHistoryRow} from '@/ide/AgentConversationHistory';
import {AgentManagedAiBalance} from '@/ide/AgentManagedAiBalance';
import {executeRunMutation} from '@/lib/runMutationAttempt';

export type {CloudRunRepoChoice, CloudRunRepoSelection} from '@/ide/AgentRepositoryScope';
export {gateCostLabel} from '@/ide/AgentApprovalCards';

/** Mirrors the phone breakpoint that `surfaces/agent.css` compacts this surface at. */
const PHONE_LAYOUT_QUERY = '(max-width: 600px)';

function phoneLayoutNow(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia(PHONE_LAYOUT_QUERY).matches
  );
}

/**
 * True only on a genuinely phone-sized viewport.
 *
 * The run card's descriptive rows cost more vertical space than the whole
 * transcript has on a 375x667 screen, so the phone form moves them behind a
 * disclosure. That is a DOM decision, not a paint decision, so it cannot be
 * made in CSS — a closed `<details>` cannot be forced open by a media query in
 * every engine. Environments without `matchMedia` (jsdom) report false, so the
 * desktop form stays the default and nothing is hidden by accident.
 */
function usePhoneLayout(): boolean {
  const [phone, setPhone] = useState(phoneLayoutNow);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(PHONE_LAYOUT_QUERY);
    const onChange = () => setPhone(mql.matches);
    onChange();
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, []);
  return phone;
}

function tokenCount(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0';
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return `${Math.round(value)}`;
}

function totalTokens(usage?: AgentUsageSummary | null): number {
  if (!usage) return 0;
  return usage.inputTokens + usage.outputTokens;
}

function hasRenderableAssistantContent(content: string): boolean {
  const normalized = content.trim();
  if (!normalized) return false;
  // Provider disconnects can persist a partial Markdown marker (for example `1.`). Rendering it
  // through react-markdown produces an empty list that looks like a blank assistant response.
  return !/^(?:[-*+]\s*|\d+[.)]\s*)$/.test(normalized);
}

function usageLabel(usage?: AgentUsageSummary | null, isSuperAdmin = false): string {
  if (!usage || totalTokens(usage) <= 0) return '';
  const parts = [
    `${tokenCount(totalTokens(usage))} tokens`,
    `${tokenCount(usage.inputTokens)} in`,
    `${tokenCount(usage.outputTokens)} out`,
  ];
  if (usage.reasoningTokens > 0) parts.push(`${tokenCount(usage.reasoningTokens)} reasoning`);
  parts.push('chat included');
  if (isSuperAdmin && usage.costUsd > 0) parts.push(`$${usage.costUsd.toFixed(4)}`);
  return parts.join(' · ');
}

/** Top bar: Chats · scope chip · title · honest status · New chat · settings popover. */
export function AgentTopBar({
  selected,
  sessionDisplayTitle,
  barStatus,
  scopeLabel,
  busy,
  settingsOpen,
  managedAi,
  sessionUsage,
  routingState,
  capabilities,
  pendingGateCount,
  onNewChat,
  onOpenApprovals,
  onToggleChats,
  onToggleSettings,
  availableModels,
  providerReadiness,
  providerReadinessSupported,
  providerReadinessLoading,
  providerReadinessError,
  selectedProvider,
  selectedModel,
  onProviderChange,
  onModelChange,
  remoteSandboxEnabled = false,
}: {
  selected: api.AgentSessionResponse | null;
  /** Derived display title (project-aware / first-message-aware) for the selected session. */
  sessionDisplayTitle?: string | null;
  barStatus: {label: string; tone: BarTone};
  scopeLabel: string | null;
  busy: boolean;
  settingsOpen: boolean;
  managedAi: api.ManagedAiBalance | null;
  sessionUsage: AgentUsageSummary;
  routingState: RoutingState;
  capabilities: api.AgentCapabilityTool[];
  pendingGateCount: number;
  onNewChat: () => void;
  onOpenApprovals: () => void;
  onToggleChats: () => void;
  onToggleSettings: () => void;
  availableModels?: api.ProviderEntry[];
  providerReadiness?: ReadonlyMap<string, api.ProviderReadiness>;
  providerReadinessSupported?: boolean;
  providerReadinessLoading?: boolean;
  providerReadinessError?: boolean;
  selectedProvider?: string;
  selectedModel?: string;
  onProviderChange?: (provider: string) => void;
  onModelChange?: (model: string) => void;
  remoteSandboxEnabled?: boolean;
}) {
  return (
    <div className='agent-bar'>
      <button
        className='agent-bar-btn'
        onClick={onToggleChats}
        aria-label='Show conversation history'
      >
        <CommandIcon size={16} />
        <span>Conversations</span>
      </button>
      {scopeLabel && (
        <span className='agent-bar-scope'>
          <FolderIcon />
          {scopeLabel}
        </span>
      )}
      <div className='agent-bar-title'>
        {selected
          ? sessionDisplayTitle || sessionTitle(selected)
          : remoteSandboxEnabled
            ? 'Remote agent conversation'
            : 'Agent conversation'}
      </div>
      <span className='agent-bar-spacer' />
      <span
        className={`agent-bar-status ${barStatus.tone}`}
        role='status'
        aria-live='polite'
        aria-atomic='true'
      >
        <span className='agent-bar-dot' aria-hidden='true' />
        {barStatus.label}
      </span>
      {/* A pill that says "nothing to show yet" is chrome that costs width and
          carries no information; the usage reading appears the moment there is
          one to report. */}
      {usageLabel(sessionUsage) && (
        <span className='agent-usage-pill' title='Current chat session token usage'>
          {usageLabel(sessionUsage)}
        </span>
      )}
      {pendingGateCount > 0 && (
        <button className='agent-approval-pill' onClick={onOpenApprovals}>
          {pendingGateCount} approval{pendingGateCount === 1 ? '' : 's'}
        </button>
      )}
      <button
        className='agent-bar-btn'
        onClick={onNewChat}
        disabled={busy}
        aria-label='Start a new conversation'
      >
        <PlusIcon size={14} />
        <span>New conversation</span>
      </button>
      <div className='agent-bar-settings'>
        <button
          className='agent-bar-icon'
          onClick={onToggleSettings}
          aria-label='Agent settings and usage'
          aria-expanded={settingsOpen}
        >
          <SettingsIcon size={16} />
        </button>
        {settingsOpen && (
          <SettingsPopover
            selected={selected}
            managedAi={managedAi}
            routingState={routingState}
            capabilities={capabilities}
            availableModels={availableModels ?? []}
            providerReadiness={providerReadiness ?? new Map()}
            providerReadinessSupported={providerReadinessSupported ?? false}
            providerReadinessLoading={providerReadinessLoading ?? false}
            providerReadinessError={providerReadinessError ?? false}
            selectedProvider={selectedProvider ?? ''}
            selectedModel={selectedModel ?? ''}
            onProviderChange={onProviderChange ?? (() => {})}
            onModelChange={onModelChange ?? (() => {})}
            onClose={onToggleSettings}
            remoteSandboxEnabled={remoteSandboxEnabled}
          />
        )}
      </div>
    </div>
  );
}

function SettingsPopover({
  selected,
  managedAi,
  routingState,
  capabilities,
  onClose,
  availableModels,
  providerReadiness,
  providerReadinessSupported,
  providerReadinessLoading,
  providerReadinessError,
  selectedProvider,
  selectedModel,
  onProviderChange,
  onModelChange,
  remoteSandboxEnabled,
}: {
  selected: api.AgentSessionResponse | null;
  managedAi: api.ManagedAiBalance | null;
  routingState: RoutingState;
  capabilities: api.AgentCapabilityTool[];
  onClose: () => void;
  availableModels: api.ProviderEntry[];
  providerReadiness: ReadonlyMap<string, api.ProviderReadiness>;
  providerReadinessSupported: boolean;
  providerReadinessLoading: boolean;
  providerReadinessError: boolean;
  selectedProvider: string;
  selectedModel: string;
  onProviderChange: (provider: string) => void;
  onModelChange: (model: string) => void;
  remoteSandboxEnabled: boolean;
}) {
  const provider = availableModels.find(
    (entry) => entry.id.toUpperCase() === selectedProvider.toUpperCase(),
  );
  const models = provider?.models ?? availableModels.flatMap((entry) => entry.models);
  const selectedReadiness = selectedProvider
    ? providerReadiness.get(selectedProvider.toUpperCase())
    : null;
  return (
    <>
      <div
        className='agent-pop-backdrop'
        data-agent-overlay-backdrop='settings'
        onClick={onClose}
      />
      <div
        className='agent-pop'
        data-agent-overlay='settings'
        role='dialog'
        aria-modal='true'
        aria-labelledby='agent-settings-title'
        tabIndex={-1}
      >
        <div className='agent-pop-title' id='agent-settings-title'>
          {remoteSandboxEnabled ? 'Sandbox policy' : 'Execution policy'}
        </div>
        <button className='agent-pop-close' onClick={onClose} aria-label='Close agent settings'>
          <CloseIcon size={15} />
        </button>
        <div className='agent-pop-row'>
          <span>Execution target</span>
          <code>{remoteSandboxEnabled ? 'strict remote container' : 'server workspace'}</code>
        </div>
        <div className='agent-pop-row'>
          <span>Isolation</span>
          <code>{remoteSandboxEnabled ? 'container enforced' : 'sandbox disabled'}</code>
        </div>
        <div className='agent-pop-row'>
          <span>Plan gate</span>
          <code>{routingState?.runApiAllowed ? 'after confirm' : 'confirmation gated'}</code>
        </div>
        <div className='agent-pop-row'>
          <span>Local files</span>
          <code>not touched</code>
        </div>
        <div className='agent-pop-row'>
          <span>Run credits</span>
          <code>charged after approval</code>
        </div>
        <div className='agent-pop-row agent-model-controls'>
          <label htmlFor='agent-provider-select'>Chat provider</label>
          <select
            id='agent-provider-select'
            value={selectedProvider}
            onChange={(event) => onProviderChange(event.target.value)}
            disabled={availableModels.length === 0}
          >
            <option value=''>Default</option>
            {availableModels.map((entry) => (
              <option
                key={entry.id}
                value={entry.id}
                disabled={
                  providerReadinessSupported &&
                  !providerIsDispatchable(providerReadiness.get(entry.id.toUpperCase()))
                }
              >
                {providerOptionLabel(
                  entry.label || entry.id,
                  providerReadiness.get(entry.id.toUpperCase()),
                )}
              </option>
            ))}
          </select>
          <small
            role={
              selectedProvider && !providerIsDispatchable(selectedReadiness) ? 'alert' : 'status'
            }
          >
            {!providerReadinessSupported
              ? 'Provider readiness is not exposed by this server; dispatch policy still applies.'
              : !selectedProvider
                ? 'Account default; the server verifies its effective route.'
                : providerReadinessError
                  ? 'Provider readiness unavailable; explicit routing is disabled.'
                  : providerReadinessLoading
                    ? 'Checking provider readiness…'
                    : providerReadinessLabel(selectedReadiness)}
          </small>
          <label htmlFor='agent-model-select'>Chat model</label>
          <select
            id='agent-model-select'
            value={selectedModel}
            onChange={(event) => onModelChange(event.target.value)}
            disabled={models.length === 0}
          >
            <option value=''>Provider default</option>
            {models.map((model) => (
              <option key={model.id} value={model.id}>
                {model.label || model.id}
              </option>
            ))}
          </select>
          <small>
            Applies to chat replies only. Runs use the organization model matrix and per-task tier
            routing.
          </small>
        </div>
        {managedAi && <AgentManagedAiBalance balance={managedAi} />}
        <details className='agent-pop-diagnostics'>
          <summary>Operator diagnostics</summary>
          <div className='agent-pop-row'>
            <span>Provider</span>
            <code>{selected?.defaultProvider || 'account default'}</code>
          </div>
          {routingState && (
            <div className='agent-pop-row'>
              <span>Route confidence</span>
              <code>{Math.round(routingState.confidence * 100)}%</code>
            </div>
          )}
          <div className='agent-pop-caps-label'>Action contracts ({capabilities.length})</div>
          <div className='agent-pop-caps'>
            {capabilities.slice(0, 16).map((c) => (
              <span key={c.name}>
                <StatusDot kind={c.confirmationRequired ? 'warn' : 'ok'} size={6} />
                {c.name.replace(/_/g, ' ')}
              </span>
            ))}
          </div>
        </details>
      </div>
    </>
  );
}

/** Sessions as a slide-over drawer with auto-titled, time-stamped rows. */
export function AgentChatsDrawer({
  open,
  sessions,
  gates,
  selectedId,
  status,
  onSelect,
  onNewChat,
  onApproveGate,
  onRejectGate,
  onOpenGate,
  onCloseSession = () => {},
  closeDisabledReason,
  sessionLimitReached = false,
  onClose,
  remoteSandboxEnabled = false,
  projectNameFor,
}: {
  open: boolean;
  sessions: api.AgentSessionResponse[];
  gates: api.AgentGateDecisionCard[];
  selectedId: string | null;
  status: AgentUiState;
  onSelect: (id: string) => void;
  onNewChat: () => void;
  onApproveGate: (gate: api.AgentGateDecisionCard) => void;
  onRejectGate: (gate: api.AgentGateDecisionCard) => void;
  onOpenGate: (runId: string) => void;
  onCloseSession?: (session: api.AgentSessionResponse) => void;
  closeDisabledReason?: string | null;
  sessionLimitReached?: boolean;
  onClose: () => void;
  remoteSandboxEnabled?: boolean;
  /** Resolves a linked project's display name for derived conversation titles. */
  projectNameFor?: (projectId: string | null) => string | null;
}) {
  if (!open) return null;
  return (
    <>
      <div
        className='agent-drawer-backdrop'
        data-agent-overlay-backdrop='chats'
        onClick={onClose}
      />
      <aside
        className='agent-drawer'
        data-agent-overlay='chats'
        role='dialog'
        aria-modal='true'
        aria-labelledby='agent-chats-title'
        tabIndex={-1}
      >
        <div className='agent-drawer-head'>
          <strong id='agent-chats-title'>Conversations</strong>
          <button className='agent-drawer-close' onClick={onClose} aria-label='Close conversations'>
            <CloseIcon size={15} />
          </button>
          <button className='ide-btn ghost' onClick={onNewChat}>
            <PlusIcon size={13} />
            New conversation
          </button>
        </div>
        <div className='agent-drawer-summary'>
          <span>{gates.length} waiting approval</span>
          <span>{sessions.length} conversations</span>
        </div>
        <p className='agent-drawer-truth'>
          Restores messages and run links; the filesystem is not restorable.
        </p>
        {sessionLimitReached && (
          <p className='agent-drawer-truth' role='status'>
            Showing the 100 most recent.
          </p>
        )}
        {gates.length > 0 && (
          <div className='agent-drawer-section'>
            <div className='agent-drawer-label'>Approval inbox</div>
            <div className='agent-drawer-list'>
              {gates.map((gate) => (
                <GateQueueRow
                  key={gate.runId}
                  gate={gate}
                  remoteSandboxEnabled={remoteSandboxEnabled}
                  onApprove={onApproveGate}
                  onReject={onRejectGate}
                  onOpen={onOpenGate}
                />
              ))}
            </div>
          </div>
        )}
        {status === 'loadingSession' ? (
          /* Loading is a skeleton shaped like the rows that are coming, not a
             spinner or a word — the reader keeps the layout they are waiting for. */
          <div className='agent-drawer-section' aria-hidden='true'>
            <div className='agent-drawer-label'>Conversation history</div>
            <div className='agent-drawer-list'>
              {[0, 1, 2, 3].map((row) => (
                <div key={row} className='agent-row-skeleton'>
                  <span className='agent-skeleton-line title' />
                  <span className='agent-skeleton-line meta' />
                </div>
              ))}
            </div>
          </div>
        ) : sessions.length === 0 ? (
          <div className='agent-drawer-empty'>
            <strong>No conversations yet</strong>
            <span>Start one to plan, run, and review remote work.</span>
            <button className='ide-btn ghost' onClick={onNewChat}>
              <PlusIcon size={13} />
              New conversation
            </button>
          </div>
        ) : (
          <div className='agent-drawer-section'>
            <div className='agent-drawer-label'>Conversation history</div>
            <div className='agent-drawer-list'>
              {sessions.map((s) => (
                <ConversationHistoryRow
                  key={s.id}
                  title={deriveSessionTitle(s, projectNameFor?.(s.projectId) ?? null)}
                  meta={`${sessionDrawerMeta(s, remoteSandboxEnabled)} · ${relTime(s.updatedAt)}`}
                  active={s.id === selectedId}
                  archived={s.status === 'ARCHIVED'}
                  closeDisabledReason={
                    s.latestLinkedRunStatus && !isTerminalRunStatus(s.latestLinkedRunStatus)
                      ? `Run ${s.latestLinkedRunStatus.toLowerCase().split('_').join(' ')}; close it after the run finishes.`
                      : s.id === selectedId
                        ? closeDisabledReason
                        : null
                  }
                  onSelect={() => onSelect(s.id)}
                  onClose={() => onCloseSession(s)}
                />
              ))}
            </div>
          </div>
        )}
      </aside>
    </>
  );
}

function scopeBadge(s: api.AgentSessionResponse): string {
  if (s.runId) return 'Run · ';
  if (s.projectId) return 'Project · ';
  return '';
}

function sessionMode(s: api.AgentSessionResponse, remoteSandboxEnabled: boolean): string {
  if (s.runId) return 'run-scoped';
  if (s.projectId) return 'project-scoped';
  return remoteSandboxEnabled ? 'sandbox operator' : 'workspace operator';
}

function sessionDrawerMeta(s: api.AgentSessionResponse, remoteSandboxEnabled: boolean): string {
  if (s.latestLinkedRunId) {
    const title = s.latestLinkedRunTitle || `Run ${s.latestLinkedRunId.slice(0, 8)}`;
    const status = s.latestLinkedRunStatus ? ` · ${runPhaseLabel(s.latestLinkedRunStatus)}` : '';
    return `Latest ${remoteSandboxEnabled ? 'sandbox' : 'workspace'}: ${title}${status}`;
  }
  return `${scopeBadge(s)}${sessionMode(s, remoteSandboxEnabled)}`;
}

function MessageBubble({
  message,
  dispatch,
  remoteSandboxEnabled,
  streaming = false,
}: {
  message: LocalMessage;
  dispatch: Dispatch<AgentAction>;
  remoteSandboxEnabled: boolean;
  /** True only for the live edge of an in-flight assistant turn. */
  streaming?: boolean;
}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  if (message.role === 'user') {
    return (
      <div className='agent-msg user'>
        <div className='agent-msg-body'>{message.content}</div>
      </div>
    );
  }
  return (
    <div className={`agent-msg ${message.role}`}>
      <div className='agent-msg-label'>
        <span>{message.role === 'system' ? 'System' : 'Agent'}</span>
        {message.role === 'assistant' && message.usage && totalTokens(message.usage) > 0 && (
          <span className='agent-msg-usage'>{usageLabel(message.usage, isSuperAdmin)}</span>
        )}
      </div>
      <div className='agent-msg-body'>
        {message.role === 'assistant' && !hasRenderableAssistantContent(message.content) ? (
          <div className='agent-msg-empty' role='status'>
            No response arrived for this turn. You can send the message again.
          </div>
        ) : (
          <Markdown collapseCodeBlocks={message.role === 'assistant'}>
            {message.role === 'assistant'
              ? friendlyAiFailureMessage(message.content)
              : message.content}
          </Markdown>
        )}
        {/* The heartbeat. It blinks on its own clock, unconditionally while the
            turn is open, so a network stall or a model thinking-pause reads as
            "still with you" instead of a frozen app. It is a sibling of the
            markdown tree, so react-markdown remounting the edge span mid-stream
            cannot restart or duplicate it. */}
        {streaming && <span className='agent-caret' aria-hidden='true' />}
      </div>
      {message.role === 'assistant' && (
        <ArtifactRail
          artifacts={extractAgentArtifacts(message.content)}
          dispatch={dispatch}
          remoteSandboxEnabled={remoteSandboxEnabled}
        />
      )}
    </div>
  );
}

function RouteDecision({routingState}: {routingState: RoutingState}) {
  if (!routingState) return null;
  const runRoute = routingState.runApiAllowed;
  return (
    <div className='agent-route-decision' data-agent-region='route-decision' role='status'>
      <span className='agent-route-decision-label'>Next step</span>
      <strong>{runRoute ? 'Ready to run' : 'Answer from context'}</strong>
      <details className='agent-route-details'>
        <summary>Why this response?</summary>
        <span>
          {Math.round(routingState.confidence * 100)}% confidence ·{' '}
          {runRoute
            ? 'a run can be started after confirmation'
            : 'no files or commands will be changed'}
        </span>
      </details>
    </div>
  );
}

function Typing() {
  return (
    <div className='agent-typing' aria-label='Agent is working'>
      <span />
      <span />
      <span />
    </div>
  );
}

/** Live (or just-finished) run, pinned in the chat that spawned it. */
function SandboxRunCard({
  run,
  liveCostUsd,
  streamStatus,
  unavailable,
  busy,
  onOpen,
  onStop,
  remoteSandboxEnabled = false,
}: {
  run: api.RunDetail;
  liveCostUsd: number | null;
  streamStatus?: 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'CLOSED';
  unavailable?: AgentRunUnavailableReason | null;
  busy: boolean;
  onOpen: (path: string) => void;
  onStop: () => void;
  remoteSandboxEnabled?: boolean;
}) {
  const prog = runProgress(run.tasks);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const compact = usePhoneLayout();
  const terminal = isTerminalRunStatus(run.status);
  const failed = (run.status || '').toUpperCase() === 'FAILED';
  const cancelled = (run.status || '').toUpperCase() === 'CANCELLED';
  const cost = liveCostUsd ?? 0;
  const est = run.costEstimateHigh ?? 0;
  const variant = !terminal ? '' : failed ? ' failed' : cancelled ? ' cancelled' : ' done';
  const pillKind = !terminal ? 'live' : failed ? 'bad' : cancelled ? 'muted' : 'ok';
  const activePhase = sandboxPhaseKey(run.status);
  const score =
    run.verifiedScore == null ? 'Evidence pending' : `${run.verifiedScore}/100 · internal`;
  const provider = run.aiProvider || run.workerModel || 'default route';
  const streamLabel =
    agentRunUnavailableMessage(unavailable) ??
    (streamStatus === 'RECONNECTING'
      ? 'Reconnecting live events'
      : streamStatus === 'CONNECTING'
        ? 'Connecting live events'
        : streamStatus === 'CONNECTED'
          ? 'Live events connected'
          : streamStatus === 'CLOSED' && !terminal
            ? 'Snapshot only · live events unavailable'
            : null);
  const prUrl = run.scmPrUrl || run.githubPrUrl;
  const terminalLabel = failed
    ? 'Open failure evidence'
    : cancelled
      ? 'Open cancelled run'
      : 'Review evidence';
  // The two descriptive rows. Both stay in the DOM on every viewport; the phone
  // form only changes WHERE they sit, so no fact about execution target,
  // publication gating, task count, evidence state or route is ever dropped.
  const modeRow = (
    <div className='agent-runcard-mode'>
      {remoteSandboxEnabled ? 'Remote sandbox' : 'Remote server workspace'} · plan-gated ·
      publication-gated
    </div>
  );
  const kpiRow = (
    <div className='agent-runcard-kpis'>
      <span>
        <b>{prog.total || 'n/a'}</b>
        tasks
      </span>
      <span>
        <b>{score}</b>
        evidence
      </span>
      <span>
        <b>{provider}</b>
        route
      </span>
    </div>
  );
  return (
    <div
      className={`agent-runcard${variant}`}
      data-agent-region='run-card'
      data-run-status={run.status || 'UNKNOWN'}
      data-stream-status={streamStatus || 'IDLE'}
      data-run-unavailable={unavailable || undefined}
    >
      <div className='agent-runcard-head'>
        <RunsIcon size={16} />
        <span className='agent-runcard-title'>
          {run.runTitle || (remoteSandboxEnabled ? 'Remote sandbox run' : 'Remote workspace run')}
        </span>
        <span className={`agent-runcard-pill ${pillKind}`}>
          {!terminal && <span className='agent-runcard-dot' aria-hidden='true' />}
          {runPhaseLabel(run.status)}
        </span>
        <span className='agent-runcard-cost'>
          {run.aiCreditsCharged != null
            ? `${run.aiCreditsCharged} AI credits`
            : 'AI credits pending'}
          {isSuperAdmin && (
            <>
              {' · $'}
              {cost.toFixed(4)}
              {est ? ` / ~$${est.toFixed(4)}` : ''}
            </>
          )}
        </span>
      </div>
      {!compact && modeRow}
      {streamLabel && (
        <div
          /* `ok` means "the live channel is healthy" — an unavailable stream is
             never healthy, whatever the socket last reported. Only this state is
             quietened on a phone; every degraded one keeps its line. */
          className={`agent-runcard-stream ${
            streamStatus === 'CONNECTED' && !unavailable ? 'ok' : ''
          }`}
          role='status'
        >
          <span aria-hidden='true' />
          {streamLabel}
        </div>
      )}
      <div className='agent-runcard-phases' aria-label='Sandbox phase'>
        {SANDBOX_PHASES.map((phase) => (
          <span
            key={phase.key}
            className={phaseState(phase.key, activePhase, terminal, failed, cancelled)}
          >
            {phase.label}
          </span>
        ))}
      </div>
      {!terminal && prog.current && (
        <div className='agent-runcard-step'>
          {prog.total > 0 ? `Task ${Math.min(prog.done + 1, prog.total)} of ${prog.total} · ` : ''}
          {prog.current}
        </div>
      )}
      {!compact && kpiRow}
      {prog.total > 0 && (
        <div className='agent-runcard-bar'>
          {/* RR-1492 — the fill reflects the measured succeeded-task percentage.
              It is never forced to 100% on a terminal run, so a PARTIAL run with
              failed tasks does not render as fully done. Failed/blocked tasks are
              shown as a distinct trailing segment rather than counted as progress. */}
          <span style={{width: `${prog.pct}%`}} />
          {prog.failed > 0 && (
            <span
              className='agent-runcard-bar-failed'
              style={{width: `${Math.round((prog.failed / prog.total) * 100)}%`}}
              title={`${prog.failed} failed or blocked`}
            />
          )}
        </div>
      )}
      {/* Phone: the run stays pinned, but the rows that DESCRIBE it rather than
          report its live state move one tap away. Phase, progress, spend and
          Stop are never inside the disclosure — a running paid job must not be
          able to hide. */}
      {compact && (
        <details className='agent-runcard-detail'>
          <summary>Run details</summary>
          <div className='agent-runcard-detail-body'>
            {modeRow}
            {kpiRow}
          </div>
        </details>
      )}
      <div className='agent-runcard-actions'>
        {/* Navigation, not a commit — it stays a ghost so the surface keeps one
            accent-filled action (the composer's send, or a live proposal). */}
        <button
          className='ide-btn ghost'
          onClick={() => onOpen(runOpenPath(run.runId, run.status))}
        >
          {terminal
            ? terminalLabel
            : run.status === 'AWAITING_APPROVAL'
              ? 'Review plan'
              : 'Open live run'}
        </button>
        {terminal && (
          <button className='ide-btn ghost' onClick={() => onOpen(`/result/${run.runId}`)}>
            Changed files
          </button>
        )}
        {prUrl && (
          <a className='ide-btn ghost' href={prUrl} target='_blank' rel='noreferrer'>
            View PR
          </a>
        )}
        {!terminal && (
          <button className='ide-btn ghost' onClick={onStop} disabled={busy}>
            <StopIcon />
            Stop
          </button>
        )}
      </div>
      {terminal && <InlineRunDiff runId={run.runId} onOpen={onOpen} />}
    </div>
  );
}

/**
 * Bounded diff inspection for the chat-first run card.
 *
 * The viewer remains read-only for the current run. A requested hunk revert is
 * sent through the existing fork endpoint only after explicit confirmation,
 * then the user is taken to the normal plan gate for review and approval.
 */
function InlineRunDiff({runId, onOpen}: {runId: string; onOpen: (path: string) => void}) {
  const panelId = `agent-inline-diff-${runId}`;
  const headingId = `${panelId}-heading`;
  const [expanded, setExpanded] = useState(false);
  const [files, setFiles] = useState<api.FileWithStatus[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  // RR-624 — the rendered diff carries the path it was fetched for, so a slower
  // earlier response can never be shown (and reverted) under a later selection.
  const [diff, setDiff] = useState<{path: string; lines: DiffLine[]} | null>(null);
  // Monotonic request generation; only the newest `open()` may commit its result.
  const openGenRef = useRef(0);
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [message, setMessage] = useState('');
  const [pendingRevert, setPendingRevert] = useState<{
    filePath: string;
    hunkIndex: number;
    request: string;
  } | null>(null);
  const [revertBusy, setRevertBusy] = useState(false);

  async function toggle() {
    if (expanded) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if (status !== 'idle') return;
    setStatus('loading');
    try {
      const rows = await api.getFilesWithStatus(runId);
      const changed = (rows ?? [])
        .filter((row) => row.status === 'ADDED' || row.status === 'MODIFIED')
        .slice(0, 20);
      setFiles(changed);
      setStatus('ready');
      if (changed.length) {
        void open(changed[0].path);
      } else {
        setSelected(null);
        setMessage('No added or modified files were reported.');
      }
    } catch {
      setStatus('error');
      setMessage('Changed files are unavailable in this run snapshot.');
    }
  }

  async function open(path: string) {
    const gen = ++openGenRef.current;
    setSelected(path);
    setDiff(null);
    setMessage('Loading diff…');
    try {
      const payload = await api.getFileDiff(runId, path);
      // RR-624 — a superseded request (the user clicked another file, or an
      // earlier fetch resolved after a later one) must not publish its result.
      if (gen !== openGenRef.current) return;
      // Keep the chat surface responsive even if a backend returns a very large file.
      const shaped = boundedInlineDiff(payload);
      setDiff({path, lines: shaped.lines});
      // RR-1494 — surface the exact clip bound (source lines/chars or post-diff lines).
      setMessage(shaped.truncated ? shaped.note : '');
    } catch {
      if (gen !== openGenRef.current) return;
      setDiff(null);
      setMessage('This file diff is unavailable. Open Changed files for the full result.');
    }
  }

  async function confirmRevert() {
    if (!pendingRevert || revertBusy) return;
    setRevertBusy(true);
    setMessage('Creating a reviewable revert run…');
    try {
      const overrides = {userRequest: pendingRevert.request};
      const fork = await executeRunMutation(`fork:revert:${runId}`, overrides, (idempotencyKey) =>
        api.forkRun(runId, overrides, idempotencyKey),
      );
      setPendingRevert(null);
      onOpen(`/plan/${fork.runId}`);
    } catch (error: unknown) {
      setMessage(error instanceof Error ? error.message : 'Could not create the revert run.');
    } finally {
      setRevertBusy(false);
    }
  }

  return (
    <div className='agent-inline-diff' data-agent-region='inline-diff'>
      <button
        className='ide-btn ghost'
        type='button'
        onClick={() => void toggle()}
        aria-expanded={expanded}
        aria-controls={panelId}
      >
        {expanded ? 'Hide inline diff' : 'Inspect changed files'}
      </button>
      {expanded && (
        <div
          className='agent-inline-diff-panel'
          id={panelId}
          role='region'
          aria-labelledby={headingId}
        >
          <div className='agent-inline-diff-head'>
            <strong id={headingId}>Read-only diff</strong>
            <span>20 files · 2,500 lines max</span>
          </div>
          {status === 'loading' && (
            <div className='agent-inline-diff-note' role='status' aria-live='polite'>
              Loading changed files…
            </div>
          )}
          {files.length > 0 && (
            <div className='agent-inline-diff-grid'>
              <div className='agent-inline-diff-files' role='listbox' aria-label='Changed files'>
                {files.map((file) => (
                  <button
                    key={file.path}
                    type='button'
                    role='option'
                    aria-selected={selected === file.path}
                    className={selected === file.path ? 'active' : ''}
                    onClick={() => void open(file.path)}
                  >
                    <code>{file.path}</code>
                    <span>{file.status}</span>
                  </button>
                ))}
              </div>
              <div className='agent-inline-diff-view'>
                {/* RR-624 — render only when the loaded diff matches the current
                    selection, and bind the viewer + revert target to the diff's
                    own path so a hunk revert can never capture the wrong file. */}
                {diff && diff.path === selected ? (
                  <IdeCodeViewer
                    filename={diff.path}
                    content={diff.lines.map((line) => line.text).join('\n')}
                    diff={diff.lines}
                    onRevertHunk={(hunkIndex, request) =>
                      setPendingRevert({filePath: diff.path, hunkIndex, request})
                    }
                  />
                ) : (
                  <div className='agent-inline-diff-note'>Select a file to inspect its diff.</div>
                )}
              </div>
            </div>
          )}
          {message && (
            <div className='agent-inline-diff-note' role='status' aria-live='polite'>
              {message}
            </div>
          )}
          {pendingRevert && (
            <div
              className='agent-inline-diff-confirm'
              role='alertdialog'
              aria-label='Confirm hunk revert'
            >
              <strong>Prepare a revert run?</strong>
              <span>
                New plan for hunk {pendingRevert.hunkIndex + 1} in{' '}
                <code>{pendingRevert.filePath}</code>. This run and repository are not mutated.
              </span>
              <div className='agent-inline-diff-confirm-actions'>
                <button
                  className='ide-btn ghost'
                  type='button'
                  onClick={() => setPendingRevert(null)}
                  disabled={revertBusy}
                >
                  Cancel
                </button>
                <button
                  className='ide-btn'
                  type='button'
                  onClick={() => void confirmRevert()}
                  disabled={revertBusy}
                >
                  {revertBusy ? 'Creating…' : 'Create revert plan'}
                </button>
              </div>
            </div>
          )}
          <div className='agent-inline-diff-foot'>
            Revert creates a new plan; this run is never mutated directly.
          </div>
        </div>
      )}
    </div>
  );
}

function ActionCard({
  pending,
  onConfirm,
  onDismiss,
  busy,
  remoteSandboxEnabled,
  repoSelection,
}: {
  pending: PendingAction;
  onConfirm: (pending: PendingAction) => void;
  onDismiss: () => void;
  busy: boolean;
  remoteSandboxEnabled: boolean;
  repoSelection?: CloudRunRepoSelection;
}) {
  const isLocal =
    pending.action.type === 'local_tool_action' || pending.action.type === 'local_code_task';
  // The same predicate the confirmation dispatcher uses. A cloud-run proposal
  // that is not confirmable renders as a blocked explanation instead of a
  // confirm button that would be dropped on click.
  if (isConfirmableCloudRun(pending.action)) {
    return (
      <SandboxProposalCard
        pending={pending}
        busy={busy}
        onConfirm={onConfirm}
        onDismiss={onDismiss}
        remoteSandboxEnabled={remoteSandboxEnabled}
        repoSelection={repoSelection}
      />
    );
  }
  if (isCloudRunAction(pending.action)) {
    return <AgentBlockedActionCard action={pending.action} onDismiss={onDismiss} busy={busy} />;
  }
  if (isLocal) return <LocalHandoffCard pending={pending} onDismiss={onDismiss} />;
  const args = Object.entries(pending.action.arguments || {});
  return (
    <div className='agent-card'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>{pending.action.type.replace(/_/g, ' ')}</strong>
        {pending.action.billingImpact && pending.action.billingImpact !== 'none' && (
          <span className='agent-card-meta'>{pending.action.billingImpact.replace(/_/g, ' ')}</span>
        )}
      </div>
      {pending.action.confirmationPrompt && (
        <div className='agent-card-sub'>{pending.action.confirmationPrompt}</div>
      )}
      {args.length > 0 && (
        <div className='agent-card-args'>
          {args.map(([key, value]) => (
            <div key={key} className='agent-kv-row'>
              <span>{key}</span>
              <code>{asText(value)}</code>
            </div>
          ))}
        </div>
      )}
      <div className='agent-card-actions'>
        <button className='ide-btn ghost' onClick={onDismiss} disabled={busy}>
          Dismiss
        </button>
      </div>
    </div>
  );
}

function SandboxProposalCard({
  pending,
  onConfirm,
  onDismiss,
  busy,
  remoteSandboxEnabled,
  repoSelection,
}: {
  pending: PendingAction;
  onConfirm: (pending: PendingAction) => void;
  onDismiss: () => void;
  busy: boolean;
  remoteSandboxEnabled: boolean;
  repoSelection?: CloudRunRepoSelection;
}) {
  const args = pending.action.arguments || {};
  const spec = asText(args.spec || args.request || pending.action.confirmationPrompt || '');
  const project = asText(args.projectId || args.project || 'Current project or selected repo');
  const provider = asText(args.aiProvider || args.provider || 'Account default');
  const confirmationDisabled = repositoryConfirmationDisabled(repoSelection, busy);
  return (
    <div className='agent-card agent-sandbox-proposal waiting'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>Create remote {remoteSandboxEnabled ? 'sandbox' : 'workspace'} run</strong>
        <span className='agent-card-meta'>plan gate before spend</span>
      </div>
      {/* The consequence row below states plan-first, no-local-files,
          approval-before-credits and PR-after-gates. A sentence repeating all
          four is a paragraph the reader has to check against the chips. */}
      {pending.action.confirmationPrompt && (
        <div className='agent-card-sub'>{pending.action.confirmationPrompt}</div>
      )}
      <div className='agent-card-args'>
        <div className='agent-kv-row'>
          <span>Request</span>
          <code>{spec || 'Implementation request from this turn'}</code>
        </div>
        <div className='agent-kv-row'>
          <span>Context</span>
          <code>{project}</code>
        </div>
        <div className='agent-kv-row'>
          <span>Execution target</span>
          <code>{remoteSandboxEnabled ? 'Remote sandbox' : 'Remote server workspace'}</code>
        </div>
        <div className='agent-kv-row'>
          <span>Provider route</span>
          <code>{provider}</code>
        </div>
      </div>
      <AgentRepositoryScope selection={repoSelection} busy={busy} />
      <RuntimeContractPanel sandboxEnabled={remoteSandboxEnabled} />
      <TrustBoundaryPanel compact />
      <div className='agent-card-consequence-row'>
        <span>plan first</span>
        <span>no local files touched</span>
        <span>approval before credits</span>
        <span>PR when publication gates pass</span>
      </div>
      <div className='agent-card-actions'>
        <button
          className='ide-btn agent-btn-primary'
          onClick={() => onConfirm(pending)}
          disabled={confirmationDisabled}
        >
          Create {remoteSandboxEnabled ? 'sandbox' : 'workspace'} plan
        </button>
        <button className='ide-btn ghost' onClick={onDismiss} disabled={busy}>
          Edit request
        </button>
      </div>
    </div>
  );
}

function LocalHandoffCard({pending, onDismiss}: {pending: PendingAction; onDismiss: () => void}) {
  return (
    <div className='agent-card agent-local-handoff'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>Continue in local tools</strong>
        <span className='agent-card-meta'>{pending.action.type.replace(/_/g, ' ')}</span>
      </div>
      <div className='agent-card-sub'>
        Needs local file or shell authority; the web agent is server-side only.
      </div>
      <div className='agent-card-actions'>
        <button className='ide-btn' onClick={() => (window.location.href = '/cli-login')}>
          Continue in CLI
        </button>
        <button className='ide-btn ghost' onClick={onDismiss}>
          Keep as advice
        </button>
      </div>
    </div>
  );
}

function Composer({
  input,
  attachments,
  uploading,
  isBusy,
  onAddFiles,
  onRemoveAttachment,
  onSend,
  onStop,
  dispatch,
}: {
  input: string;
  attachments: AgentAttachmentLite[];
  uploading: boolean;
  isBusy: boolean;
  onAddFiles: (files: FileList | null) => void;
  onRemoveAttachment: (attachmentId: string) => void;
  onSend: () => void;
  onStop: () => void;
  dispatch: Dispatch<AgentAction>;
}) {
  return (
    <div
      className='agent-composer'
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        onAddFiles(e.dataTransfer.files);
      }}
    >
      {attachments.length > 0 && (
        <div className='agent-attachments'>
          {attachments.map((a) => (
            <div
              key={a.attachmentId}
              className='agent-attach-chip'
              title={`${a.mimeType} · ${a.extractedChars} chars extracted`}
            >
              {a.previewUrl ? (
                <img className='agent-attach-thumb' src={a.previewUrl} alt='' aria-hidden='true' />
              ) : (
                <ImageIcon size={14} />
              )}
              <span className='agent-attach-chip-name'>{a.filename}</span>
              <button
                onClick={() => onRemoveAttachment(a.attachmentId)}
                aria-label={`Remove ${a.filename}`}
              >
                <CloseIcon size={12} />
              </button>
            </div>
          ))}
        </div>
      )}
      <div className='agent-composer-box'>
        <label
          className='agent-attach-btn'
          title='Attach an image, PDF, Markdown, or text'
          tabIndex={uploading || isBusy ? -1 : 0}
          role='button'
          aria-label='Attach a file'
          onKeyDown={(e) => {
            if ((e.key === 'Enter' || e.key === ' ') && !uploading && !isBusy) {
              e.preventDefault();
              e.currentTarget.querySelector<HTMLInputElement>('input[type="file"]')?.click();
            }
          }}
        >
          {uploading ? <span className='agent-attach-spin' /> : <Paperclip />}
          <input
            type='file'
            accept='.png,.jpg,.jpeg,.gif,.webp,image/png,image/jpeg,image/gif,image/webp,.pdf,.md,.markdown,.txt,.text,.csv,.json,.log,text/plain,text/markdown,application/pdf,application/json'
            multiple
            style={{display: 'none'}}
            disabled={uploading || isBusy}
            onChange={(e) => {
              onAddFiles(e.target.files);
              e.currentTarget.value = '';
            }}
            aria-label='Attach a file'
          />
        </label>
        <textarea
          className='agent-input'
          rows={1}
          value={input}
          aria-label='Message the agent'
          placeholder='Message the agent…'
          onChange={(e) => dispatch({type: 'input:set', input: e.target.value})}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              onSend();
            }
          }}
          disabled={isBusy}
        />
        {isBusy ? (
          <button className='agent-send' onClick={onStop} aria-label='Stop'>
            <StopIcon />
          </button>
        ) : (
          <button
            className='agent-send'
            onClick={onSend}
            disabled={!input.trim()}
            aria-label='Send'
          >
            <ArrowUp />
          </button>
        )}
      </div>
      {/* Two rows of hint text on a phone is chrome eating the transcript. The
          brief variant is the same claim with the file list dropped — the exact
          accepted types stay on the attach control's own label, which is where
          they are actionable. Separators are drawn by CSS, so a hidden variant
          never leaves a stray dot behind. */}
      <div className='agent-composer-hint'>
        <span>Enter to send</span>
        <span className='agent-hint-full'>attach image, PDF, Markdown, text</span>
        <span className='agent-hint-brief'>attach files</span>
        <span>web search</span>
      </div>
    </div>
  );
}

/** The whole conversation surface: gates → messages → activity → run card → action card → composer. */
export function AgentConversation({
  messages,
  streamEvents,
  streamText,
  isBusy,
  visibleAction,
  gates,
  runCard,
  runCost,
  runStreamStatus,
  runUnavailable,
  routingState,
  remoteSandboxEnabled = false,
  repoSelection,
  hasNewActivity = false,
  input,
  attachments,
  uploading,
  endRef,
  dispatch,
  onScroll,
  onJumpToLatest,
  onSend,
  onStop,
  onAddFiles,
  onRemoveAttachment,
  onConfirmAction,
  onApproveGate,
  onRejectGate,
  onOpenGate,
  onOpenRun,
  onStopRun,
  hasEarlierTurns = false,
  loadingEarlierTurns = false,
  earlierTurnsError = null,
  onLoadEarlierTurns,
}: {
  messages: LocalMessage[];
  streamEvents: StreamTimelineItem[];
  streamText: string;
  isBusy: boolean;
  visibleAction: PendingAction | null;
  gates: api.AgentGateDecisionCard[];
  runCard: api.RunDetail | null;
  runCost: number | null;
  runStreamStatus?: 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'CLOSED';
  runUnavailable?: AgentRunUnavailableReason | null;
  routingState?: RoutingState;
  remoteSandboxEnabled?: boolean;
  repoSelection?: CloudRunRepoSelection;
  hasNewActivity?: boolean;
  input: string;
  attachments: AgentAttachmentLite[];
  uploading: boolean;
  endRef: RefObject<HTMLDivElement>;
  dispatch: Dispatch<AgentAction>;
  onScroll: (e: UIEvent<HTMLDivElement>) => void;
  onJumpToLatest?: () => void;
  onSend: () => void;
  onStop: () => void;
  onAddFiles: (files: FileList | null) => void;
  onRemoveAttachment: (attachmentId: string) => void;
  onConfirmAction: (pending: PendingAction) => void;
  onApproveGate: (gate: api.AgentGateDecisionCard) => void;
  onRejectGate: (gate: api.AgentGateDecisionCard) => void;
  onOpenGate: (runId: string) => void;
  onOpenRun: (path: string) => void;
  onStopRun: () => void;
  hasEarlierTurns?: boolean;
  loadingEarlierTurns?: boolean;
  earlierTurnsError?: string | null;
  onLoadEarlierTurns?: () => void;
}) {
  // A surface holding a pending approval is POPULATED, not empty. Rendering the
  // "nothing here yet" state underneath a gate the operator must act on was the
  // two-states-at-once defect.
  const empty =
    messages.length === 0 && !isBusy && !visibleAction && !runCard && gates.length === 0;
  const showOperatorIntro = !visibleAction && !runCard && gates.length === 0 && !isBusy;
  const showOperatorInScroll = showOperatorIntro && messages.length === 0;
  const showComposerContract =
    messages.length === 0 && !visibleAction && gates.length === 0 && !runCard;
  return (
    <section className='agent-chat' data-agent-surface='protected-transcript'>
      <h1 className='ide-sr-only'>Agent</h1>
      <div className='agent-chat-scroll' data-agent-region='transcript' onScroll={onScroll}>
        <div className='ide-sr-only' role='status' aria-live='polite' aria-atomic='true'>
          {runCard
            ? `${remoteSandboxEnabled ? 'Remote sandbox' : 'Remote workspace'} ${runPhaseLabel(runCard.status)}${
                runUnavailable
                  ? `. ${agentRunUnavailableMessage(runUnavailable)}.`
                  : runStreamStatus === 'RECONNECTING'
                    ? '. Reconnecting live events.'
                    : runStreamStatus === 'CLOSED' && !isTerminalRunStatus(runCard.status)
                      ? '. Live events unavailable; showing the latest snapshot.'
                      : ''
              }`
            : isBusy
              ? streamText
                ? 'Agent is responding.'
                : 'Agent is working.'
              : ''}
        </div>
        <div className='agent-chat-col'>
          <ConversationHistoryPager
            hasEarlier={hasEarlierTurns}
            loading={loadingEarlierTurns}
            error={earlierTurnsError}
            onLoad={onLoadEarlierTurns}
          />
          {showOperatorInScroll && (
            <OperatorIntro
              hasHistory={messages.length > 0}
              remoteSandboxEnabled={remoteSandboxEnabled}
              onPick={(s) => dispatch({type: 'input:set', input: s})}
            />
          )}
          <RouteDecision routingState={routingState ?? null} />
          {empty
            ? !showOperatorInScroll && (
                <EmptyRuntimeState
                  remoteSandboxEnabled={remoteSandboxEnabled}
                  onPick={(s) => dispatch({type: 'input:set', input: s})}
                />
              )
            : messages.map((message) => (
                <MessageBubble
                  key={message.id}
                  message={message}
                  dispatch={dispatch}
                  remoteSandboxEnabled={remoteSandboxEnabled}
                  streaming={message.id === 'pending:assistant'}
                />
              ))}
          <AgentToolActivity items={streamEvents} />
          {isBusy && !streamText && <Typing />}
          {/* Approvals are part of the RECORD, in the position they arrived —
              after the turn that produced them, not floated above the whole
              transcript where they read as a toast the conversation happened
              under. Same card anatomy as every other event. */}
          {gates.length > 0 && (
            <div className='agent-gates-inline' data-agent-region='approval-gates'>
              <div className='agent-gates-label'>
                {remoteSandboxEnabled
                  ? 'Remote sandboxes waiting for your approval'
                  : 'Remote workspaces waiting for your approval'}
              </div>
              {gates.map((g) => (
                <GateCard
                  key={g.runId}
                  gate={g}
                  busy={isBusy}
                  onApprove={onApproveGate}
                  onReject={onRejectGate}
                  onOpen={onOpenGate}
                  remoteSandboxEnabled={remoteSandboxEnabled}
                />
              ))}
            </div>
          )}
          {runCard && (
            <SandboxRunCard
              run={runCard}
              liveCostUsd={runCost}
              streamStatus={runStreamStatus}
              unavailable={runUnavailable}
              busy={isBusy}
              onOpen={onOpenRun}
              onStop={onStopRun}
              remoteSandboxEnabled={remoteSandboxEnabled}
            />
          )}
          {visibleAction && (
            <ActionCard
              pending={visibleAction}
              busy={isBusy}
              onConfirm={onConfirmAction}
              onDismiss={() => dispatch({type: 'action:clear'})}
              remoteSandboxEnabled={remoteSandboxEnabled}
              repoSelection={repoSelection}
            />
          )}
          <div ref={endRef} />
        </div>
        {/* Never float the scroll affordance over an approval gate. On short
            viewports it can otherwise cover the primary Approve action. */}
        {hasNewActivity && gates.length === 0 && onJumpToLatest && (
          <button
            className='agent-jump-latest'
            data-agent-region='new-activity'
            type='button'
            onClick={onJumpToLatest}
            aria-label='Jump to latest activity'
          >
            <ArrowDown size={13} /> New activity
          </button>
        )}
      </div>
      <div className='agent-composer-wrap'>
        <div className='agent-chat-col'>
          {showComposerContract && (
            <div className='agent-composer-contract'>
              <span>
                {remoteSandboxEnabled ? 'Remote sandbox only' : 'Remote server workspace'}
              </span>
              <span>Plan and cost first</span>
              <span>Branch, tests, publication policy and cost shown before run</span>
              <span>No local files touched</span>
            </div>
          )}
          <Composer
            input={input}
            attachments={attachments}
            uploading={uploading}
            isBusy={isBusy}
            onAddFiles={onAddFiles}
            onRemoveAttachment={onRemoveAttachment}
            onSend={onSend}
            onStop={onStop}
            dispatch={dispatch}
          />
        </div>
      </div>
    </section>
  );
}
