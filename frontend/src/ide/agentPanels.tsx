import {useRef, useState, type Dispatch, type RefObject, type UIEvent} from 'react';
import * as api from '@/lib/api';
import {
  providerIsDispatchable,
  providerOptionLabel,
  providerReadinessLabel,
} from '@/ide/providerReadiness';
import {Markdown} from '@/signal/components/Markdown';
import {
  CommandIcon,
  ImageIcon,
  PlusIcon,
  RunsIcon,
  SearchIcon,
  SettingsIcon,
  StatusDot,
} from '@/ide/IdeIcons';
import {IdeCodeViewer, type DiffLine} from '@/ide/IdeKit';
import {extractAgentArtifacts} from '@/ide/agentArtifacts';
import {ArtifactRail} from '@/ide/agentArtifactRail';
import {boundedInlineDiff} from '@/ide/agentDiff';
import {isCloudRunAction, isConfirmableCloudRun} from '@/ide/agentActionPolicy';
import {AgentBlockedActionCard} from '@/ide/AgentBlockedActionCard';
import {
  asText,
  friendlyTool,
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
import {Paperclip, ArrowUp, StopIcon, FolderIcon} from '@/ide/agentIcons';
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

export type {CloudRunRepoChoice, CloudRunRepoSelection} from '@/ide/AgentRepositoryScope';

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
  if (!usage || totalTokens(usage) <= 0) return 'Usage appears after the first response';
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
          ? sessionTitle(selected)
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
      <span className='agent-usage-pill' title='Current chat session token usage'>
        {usageLabel(sessionUsage)}
      </span>
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
          ×
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
            These settings control conversation replies only. Generated-code runs use the
            organization model matrix and per-task tier routing. Web Agent does not turn the chat
            model into a run-wide pin.
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
            ×
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
          Conversation history restores messages and run links; its filesystem is not restorable.
        </p>
        {sessionLimitReached && (
          <p className='agent-drawer-truth' role='status'>
            Showing the 100 most recent conversations.
          </p>
        )}
        {gates.length > 0 && (
          <div className='agent-drawer-section'>
            <div className='agent-drawer-label'>Approval inbox</div>
            <div className='agent-drawer-list'>
              {gates.map((gate) => (
                <div key={gate.runId} className='agent-drawer-gate'>
                  <span className='agent-drawer-title'>
                    {gate.title || gate.summary || gate.runId}
                  </span>
                  <span className='agent-drawer-meta'>
                    {gateQueueMeta(gate, remoteSandboxEnabled)}
                  </span>
                  <span className='agent-drawer-riskline'>
                    <b>{(gate.risk || 'LOW').toLowerCase()} risk</b>
                    <span>{gate.tasks.length || 'n/a'} tasks</span>
                    <span>{gateCostLabel(gate)}</span>
                  </span>
                  <span className='agent-drawer-actions'>
                    <button
                      className='ide-btn agent-btn-primary'
                      onClick={() => onApproveGate(gate)}
                      // RR-1489 — no one-click approval while cost is unknown; a null
                      // credit value also disables the server's stale-approval guard.
                      disabled={gate.creditsRequired == null}
                    >
                      Approve &amp; run
                    </button>
                    <button className='ide-btn ghost' onClick={() => onRejectGate(gate)}>
                      Reject
                    </button>
                    <button className='ide-btn ghost' onClick={() => onOpenGate(gate.runId)}>
                      Full plan
                    </button>
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
        {status === 'loadingSession' ? (
          <div className='agent-muted'>Loading…</div>
        ) : sessions.length === 0 ? (
          <div className='agent-muted'>No conversations yet.</div>
        ) : (
          <div className='agent-drawer-section'>
            <div className='agent-drawer-label'>Conversation history</div>
            <div className='agent-drawer-list'>
              {sessions.map((s) => (
                <ConversationHistoryRow
                  key={s.id}
                  title={sessionTitle(s)}
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

function gateAge(gate: api.AgentGateDecisionCard): string {
  return relTime(gate.submittedAt);
}

// RR-1489 — the gate authorizes provider spend, so the card must show the USD
// estimate and the hard cap that bounds the run, not credits alone.
export function gateCostLabel(gate: api.AgentGateDecisionCard): string {
  const parts: string[] = [];
  if (gate.estimatedCostUsd != null) parts.push(`~$${gate.estimatedCostUsd.toFixed(2)}`);
  if (gate.creditsRequired != null) parts.push(`${gate.creditsRequired} credits`);
  if (gate.hardCapUsd != null) parts.push(`cap $${gate.hardCapUsd.toFixed(2)}`);
  return parts.length ? parts.join(' / ') : 'cost pending';
}

function gateQueueMeta(gate: api.AgentGateDecisionCard, remoteSandboxEnabled: boolean): string {
  const project =
    gate.projectName || `Standalone ${remoteSandboxEnabled ? 'sandbox' : 'workspace'}`;
  const route = gate.provider || gate.model || 'default route';
  const age = gateAge(gate);
  return `${project} · ${route}${age ? ` · ${age}` : ''}`;
}

function relTime(iso?: string | null): string {
  if (!iso) return '';
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return '';
  const mins = Math.floor((Date.now() - then) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

function MessageBubble({
  message,
  dispatch,
  remoteSandboxEnabled,
}: {
  message: LocalMessage;
  dispatch: Dispatch<AgentAction>;
  remoteSandboxEnabled: boolean;
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

/** Friendly activity chips for tool/run events — no raw JSON. */
function Activity({items}: {items: StreamTimelineItem[]}) {
  const shown = items.filter(
    (i) =>
      i.type === 'tool' ||
      i.type === 'run' ||
      (i.type === 'system' &&
        /SUBAGENT|HANDOFF|PROVIDER|MODEL|CONTINUATION|RUN[ _]VALIDATION[ _]EVIDENCE/i.test(
          i.title,
        )),
  );
  if (!shown.length) return null;
  return (
    <div className='agent-activity' data-agent-region='activity'>
      {shown.map((i) =>
        i.type === 'system' && /RUN[ _]VALIDATION[ _]EVIDENCE/i.test(i.title) && i.detail ? (
          <details key={i.id} className='agent-activity-evidence'>
            <summary className='agent-activity-chip'>
              <SearchIcon size={13} />
              <span>Verification evidence</span>
            </summary>
            <p>{i.detail}</p>
          </details>
        ) : (
          <span key={i.id} className='agent-activity-chip'>
            {i.type === 'run' ? <RunsIcon size={13} /> : <SearchIcon size={13} />}
            <span>
              {i.type === 'run'
                ? 'Run created'
                : i.type === 'system'
                  ? i.title
                  : friendlyTool(i.title)}
              {i.type === 'system' && i.detail ? ` · ${i.detail}` : ''}
            </span>
          </span>
        ),
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
  unavailable?: 'RUN_NOT_FOUND' | 'AUTH_REQUIRED' | null;
  busy: boolean;
  onOpen: (path: string) => void;
  onStop: () => void;
  remoteSandboxEnabled?: boolean;
}) {
  const prog = runProgress(run.tasks);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
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
    unavailable === 'RUN_NOT_FOUND'
      ? 'Run no longer available'
      : unavailable === 'AUTH_REQUIRED'
        ? 'Sign in again to view this run'
        : streamStatus === 'RECONNECTING'
          ? 'Reconnecting live events'
          : streamStatus === 'CONNECTING'
            ? 'Connecting live events'
            : streamStatus === 'CONNECTED'
              ? 'Live events connected'
              : streamStatus === 'CLOSED' && !terminal
                ? 'Snapshot only · live events unavailable'
                : null;
  const prUrl = run.scmPrUrl || run.githubPrUrl;
  const terminalLabel = failed
    ? 'Open failure evidence'
    : cancelled
      ? 'Open cancelled run'
      : 'Review evidence';
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
      <div className='agent-runcard-mode'>
        {remoteSandboxEnabled ? 'Remote sandbox' : 'Remote server workspace'} · plan-gated ·
        PR-oriented
      </div>
      {streamLabel && (
        <div
          className={`agent-runcard-stream ${streamStatus === 'CONNECTED' ? 'ok' : ''}`}
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
      <div className='agent-runcard-actions'>
        <button
          className='ide-btn agent-btn-primary'
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
      const fork = await api.forkRun(runId, {userRequest: pendingRevert.request});
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
                This creates a new plan for hunk {pendingRevert.hunkIndex + 1} in{' '}
                <code>{pendingRevert.filePath}</code>. The current run and repository are not
                mutated.
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
            Read-only preview. Revert creates a new plan and never mutates this run directly.
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
      <div className='agent-card-sub'>
        ExecuteSpec will create a remote workspace, draft a plan, then stop for your approval before
        coding or charging run credits.
      </div>
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
        <span>PR-oriented result</span>
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
        This request needs local file or shell authority. The web agent stays server-side only; use
        the CLI or VS Code client when your machine must execute commands.
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

function GateCard({
  gate,
  onApprove,
  onReject,
  onOpen,
  busy,
  remoteSandboxEnabled,
}: {
  gate: api.AgentGateDecisionCard;
  onApprove: (gate: api.AgentGateDecisionCard) => void;
  onReject: (gate: api.AgentGateDecisionCard) => void;
  onOpen: (runId: string) => void;
  busy: boolean;
  remoteSandboxEnabled: boolean;
}) {
  return (
    <div className='agent-card agent-gate-card waiting'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>
          {remoteSandboxEnabled ? 'Sandbox' : 'Remote workspace'} plan awaiting approval
        </strong>
        {gate.risk && <span className='agent-card-meta'>risk {gate.risk.toLowerCase()}</span>}
      </div>
      <button className='agent-card-title' onClick={() => onOpen(gate.runId)}>
        {gate.title || gate.summary || gate.runId}
      </button>
      <div className='agent-card-consequence-row'>
        <span>{gate.projectName || 'standalone run'}</span>
        {gate.tasks.length > 0 && <span>{gate.tasks.length} tasks</span>}
        {/* RR-1489 — render the USD estimate and the hard cap that bound the spend. */}
        {gate.estimatedCostUsd != null && <span>~${gate.estimatedCostUsd.toFixed(2)}</span>}
        {gate.creditsRequired != null && <span>~{gate.creditsRequired} credits</span>}
        {gate.hardCapUsd != null && <span>cap ${gate.hardCapUsd.toFixed(2)}</span>}
        {gate.provider && <span>{gate.provider}</span>}
        {gateAge(gate) && <span>{gateAge(gate)}</span>}
        <span>opens a PR</span>
      </div>
      {gate.creditsRequired == null && (
        <div className='agent-card-sub' role='status'>
          Cost is still being estimated. Approval is unavailable until the run credit count is
          known.
        </div>
      )}
      {gate.summary && <div className='agent-card-sub'>{gate.summary}</div>}
      {gate.tasks.length > 0 && (
        <ul className='agent-card-tasks'>
          {gate.tasks.slice(0, 4).map((task) => (
            <li key={task}>{task}</li>
          ))}
        </ul>
      )}
      <TrustBoundaryPanel compact />
      <div className='agent-card-actions'>
        <button
          className='ide-btn agent-btn-primary'
          onClick={() => onApprove(gate)}
          // RR-1489 — cost must be known before spend is authorized.
          disabled={busy || gate.creditsRequired == null}
        >
          Review &amp; approve
        </button>
        <button className='ide-btn ghost' onClick={() => onReject(gate)} disabled={busy}>
          Reject
        </button>
        <button className='ide-btn ghost' onClick={() => onOpen(gate.runId)} disabled={busy}>
          Open full plan
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
                ×
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
      <div className='agent-composer-hint'>
        Enter to send · attach image, PDF, Markdown, or text · web search built in
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
  runUnavailable?: 'RUN_NOT_FOUND' | 'AUTH_REQUIRED' | null;
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
  const empty = messages.length === 0 && !isBusy && !visibleAction && !runCard;
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
                runUnavailable === 'RUN_NOT_FOUND'
                  ? '. Run no longer available.'
                  : runUnavailable === 'AUTH_REQUIRED'
                    ? '. Sign in again to view this run.'
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
                />
              ))}
          <Activity items={streamEvents} />
          {isBusy && !streamText && <Typing />}
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
            <span aria-hidden='true'>↓</span> New activity
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
              <span>Branch, tests, PR, and cap shown before run</span>
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
