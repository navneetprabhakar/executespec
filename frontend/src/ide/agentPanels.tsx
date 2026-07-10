import {useState, type Dispatch, type RefObject, type UIEvent} from 'react';
import * as api from '@/lib/api';
import {Markdown} from '@/signal/components/Markdown';
import {CommandIcon, PlusIcon, RunsIcon, SearchIcon, SettingsIcon, StatusDot} from '@/ide/IdeIcons';
import {extractAgentArtifacts, type AgentArtifact} from '@/ide/agentArtifacts';
import {
  asText,
  friendlyTool,
  isTerminalRunStatus,
  pct,
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
import {hasUnlimitedAiCredits} from '@/ide/planUtils';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';

const Paperclip = () => (
  <svg
    width='17'
    height='17'
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48' />
  </svg>
);

const ArrowUp = () => (
  <svg
    width='16'
    height='16'
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2.5'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M12 19V5M5 12l7-7 7 7' />
  </svg>
);

const StopIcon = () => (
  <svg width='15' height='15' viewBox='0 0 24 24' aria-hidden='true'>
    <rect x='6' y='6' width='12' height='12' rx='2' fill='currentColor' />
  </svg>
);

const FolderIcon = () => (
  <svg
    width='13'
    height='13'
    viewBox='0 0 24 24'
    fill='none'
    stroke='currentColor'
    strokeWidth='2'
    strokeLinecap='round'
    strokeLinejoin='round'
    aria-hidden='true'
  >
    <path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z' />
  </svg>
);

const SUGGESTIONS = [
  'Create a remote sandbox to add audit logging',
  'Fix a failing test in a remote sandbox',
  'Review waiting sandbox approvals',
  'Explain this project before I start work',
];

const SANDBOX_PHASES = [
  {key: 'queued', label: 'Queued'},
  {key: 'planning', label: 'Plan'},
  {key: 'approval', label: 'Approval'},
  {key: 'coding', label: 'Code'},
  {key: 'building', label: 'Build'},
  {key: 'reviewing', label: 'Review'},
  {key: 'evidence', label: 'Evidence'},
  {key: 'done', label: 'Done'},
] as const;

function tokenCount(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0';
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return `${Math.round(value)}`;
}

function totalTokens(usage?: AgentUsageSummary | null): number {
  if (!usage) return 0;
  return usage.inputTokens + usage.outputTokens + usage.reasoningTokens;
}

function usageLabel(usage?: AgentUsageSummary | null, isSuperAdmin = false): string {
  if (!usage || totalTokens(usage) <= 0) return 'No token usage yet';
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
}) {
  return (
    <div className='agent-bar'>
      <button className='agent-bar-btn' onClick={onToggleChats} aria-label='Show chats'>
        <CommandIcon size={16} />
        <span>Chats</span>
      </button>
      {scopeLabel && (
        <span className='agent-bar-scope'>
          <FolderIcon />
          {scopeLabel}
        </span>
      )}
      <div className='agent-bar-title'>
        {selected ? sessionTitle(selected) : 'Remote sandbox operator'}
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
      <button className='agent-bar-btn' onClick={onNewChat} disabled={busy}>
        <PlusIcon size={14} />
        <span>New sandbox</span>
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
            onClose={onToggleSettings}
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
}: {
  selected: api.AgentSessionResponse | null;
  managedAi: api.ManagedAiBalance | null;
  routingState: RoutingState;
  capabilities: api.AgentCapabilityTool[];
  onClose: () => void;
}) {
  return (
    <>
      <div className='agent-pop-backdrop' onClick={onClose} />
      <div
        className='agent-pop'
        role='dialog'
        aria-modal='true'
        aria-labelledby='agent-settings-title'
        tabIndex={-1}
      >
        <div className='agent-pop-title' id='agent-settings-title'>
          Sandbox policy
        </div>
        <button className='agent-pop-close' onClick={onClose} aria-label='Close agent settings'>
          ×
        </button>
        <div className='agent-pop-row'>
          <span>Execution target</span>
          <code>remote only</code>
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
        {managedAi && <ManagedAiBalance balance={managedAi} />}
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

function ManagedAiBalance({balance}: {balance: api.ManagedAiBalance}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const unlimited = hasUnlimitedAiCredits(balance);
  const totalCredits = balance.includedAiCredits + (balance.purchasedAiCredits ?? 0);
  const configured = unlimited || totalCredits > 0 || balance.rawCogsCapUsd > 0;
  return (
    <div className='agent-pop-balance'>
      <span>Managed AI balance</span>
      {unlimited ? (
        <>
          <strong>Unlimited AI credits</strong>
          <small>This tier is not blocked by managed-AI credit limits.</small>
        </>
      ) : configured ? (
        <>
          <strong>
            {balance.aiCreditsRemaining} of {totalCredits} credits left
          </strong>
          <div className='agent-meter'>
            <span style={{width: `${pct(balance.aiCreditsUsed, totalCredits)}%`}} />
          </div>
          {isSuperAdmin && (
            <small>
              Raw spend ${balance.rawCogsUsedUsd.toFixed(2)} of ${balance.rawCogsCapUsd.toFixed(2)}{' '}
              monthly cap.
            </small>
          )}
        </>
      ) : (
        <>
          <strong>Managed credits not configured</strong>
          <small>Runs use the configured provider keys and still require plan approval.</small>
        </>
      )}
    </div>
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
  onClose,
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
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <>
      <div className='agent-drawer-backdrop' onClick={onClose} />
      <aside
        className='agent-drawer'
        role='dialog'
        aria-modal='true'
        aria-labelledby='agent-chats-title'
        tabIndex={-1}
      >
        <div className='agent-drawer-head'>
          <strong id='agent-chats-title'>Remote sandboxes</strong>
          <button className='agent-drawer-close' onClick={onClose} aria-label='Close chats'>
            ×
          </button>
          <button className='ide-btn ghost' onClick={onNewChat}>
            <PlusIcon size={13} />
            New sandbox
          </button>
        </div>
        <div className='agent-drawer-summary'>
          <span>{gates.length} waiting approval</span>
          <span>{sessions.length} conversations</span>
        </div>
        {gates.length > 0 && (
          <div className='agent-drawer-section'>
            <div className='agent-drawer-label'>Approval inbox</div>
            <div className='agent-drawer-list'>
              {gates.map((gate) => (
                <div key={gate.runId} className='agent-drawer-gate'>
                  <span className='agent-drawer-title'>
                    {gate.title || gate.summary || gate.runId}
                  </span>
                  <span className='agent-drawer-meta'>{gateQueueMeta(gate)}</span>
                  <span className='agent-drawer-riskline'>
                    <b>{(gate.risk || 'LOW').toLowerCase()} risk</b>
                    <span>{gate.tasks.length || 'n/a'} tasks</span>
                    <span>{gateCostLabel(gate)}</span>
                  </span>
                  <span className='agent-drawer-actions'>
                    <button
                      className='ide-btn agent-btn-primary'
                      onClick={() => onApproveGate(gate)}
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
          <div className='agent-muted'>No sandbox conversations yet.</div>
        ) : (
          <div className='agent-drawer-section'>
            <div className='agent-drawer-label'>Conversations</div>
            <div className='agent-drawer-list'>
              {sessions.map((s) => (
                <button
                  key={s.id}
                  className={s.id === selectedId ? 'active' : ''}
                  onClick={() => onSelect(s.id)}
                >
                  <span className='agent-drawer-title'>{sessionTitle(s)}</span>
                  <span className='agent-drawer-meta'>
                    {sessionDrawerMeta(s)}
                    {' · '}
                    {relTime(s.updatedAt)}
                  </span>
                </button>
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

function sessionMode(s: api.AgentSessionResponse): string {
  if (s.runId) return 'run-scoped';
  if (s.projectId) return 'project-scoped';
  return 'sandbox operator';
}

function sessionDrawerMeta(s: api.AgentSessionResponse): string {
  if (s.latestLinkedRunId) {
    const title = s.latestLinkedRunTitle || `Run ${s.latestLinkedRunId.slice(0, 8)}`;
    const status = s.latestLinkedRunStatus ? ` · ${runPhaseLabel(s.latestLinkedRunStatus)}` : '';
    return `Latest sandbox: ${title}${status}`;
  }
  return `${scopeBadge(s)}${sessionMode(s)}`;
}

function gateAge(gate: api.AgentGateDecisionCard): string {
  return relTime(gate.submittedAt);
}

function gateCostLabel(gate: api.AgentGateDecisionCard): string {
  const parts = [];
  if (gate.creditsRequired != null) parts.push(`${gate.creditsRequired} credits`);
  return parts.length ? parts.join(' / ') : 'cost pending';
}

function gateQueueMeta(gate: api.AgentGateDecisionCard): string {
  const project = gate.projectName || 'Standalone sandbox';
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
}: {
  message: LocalMessage;
  dispatch: Dispatch<AgentAction>;
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
        <Markdown collapseCodeBlocks={message.role === 'assistant'}>{message.content}</Markdown>
      </div>
      {message.role === 'assistant' && (
        <ArtifactRail artifacts={extractAgentArtifacts(message.content)} dispatch={dispatch} />
      )}
    </div>
  );
}

function ArtifactRail({
  artifacts,
  dispatch,
}: {
  artifacts: AgentArtifact[];
  dispatch: Dispatch<AgentAction>;
}) {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  if (!artifacts.length) return null;

  async function copyArtifact(artifact: AgentArtifact) {
    await navigator.clipboard?.writeText(artifact.content);
    setCopiedId(artifact.id);
    window.setTimeout(
      () => setCopiedId((current) => (current === artifact.id ? null : current)),
      1600,
    );
  }

  function downloadArtifact(artifact: AgentArtifact) {
    const blob = new Blob([artifact.content], {type: mimeForArtifact(artifact)});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = artifact.filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  function previewArtifact(artifact: AgentArtifact) {
    const blob = new Blob([artifact.content], {type: mimeForArtifact(artifact)});
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank', 'noopener,noreferrer');
    window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
  }

  function requestSandbox(artifact: AgentArtifact) {
    dispatch({
      type: 'input:set',
      input: `Create a remote sandbox from the generated artifact ${artifact.filename}. Preserve the artifact content from the previous assistant message and return evidence, changed files, and the handoff path.`,
    });
  }

  return (
    <div className='agent-artifact-rail' aria-label='Generated artifacts'>
      <div className='agent-artifact-head'>
        <span>Generated artifacts</span>
        <span>
          {artifacts.length} file{artifacts.length === 1 ? '' : 's'}
        </span>
      </div>
      <div className='agent-artifact-list'>
        {artifacts.map((artifact) => (
          <div className='agent-artifact-item' key={artifact.id}>
            <div className='agent-artifact-main'>
              <code>{artifact.filename}</code>
              <span>
                {artifact.language.toUpperCase()} · {artifact.lineCount} line
                {artifact.lineCount === 1 ? '' : 's'}
              </span>
            </div>
            <div className='agent-artifact-actions'>
              <button className='ide-btn ghost' onClick={() => void copyArtifact(artifact)}>
                {copiedId === artifact.id ? 'Copied' : 'Copy'}
              </button>
              <button className='ide-btn ghost' onClick={() => downloadArtifact(artifact)}>
                Download
              </button>
              <button
                className='ide-btn ghost'
                onClick={() => previewArtifact(artifact)}
                disabled={!artifact.previewable}
                title={
                  artifact.previewable
                    ? 'Open a browser preview'
                    : 'Preview is unavailable for this file type'
                }
              >
                Preview
              </button>
              <button className='ide-btn ghost' onClick={() => requestSandbox(artifact)}>
                Use in sandbox
              </button>
            </div>
          </div>
        ))}
      </div>
      <div className='agent-artifact-handoff'>
        <span>Remote sandbox keeps local files untouched.</span>
        <a href='/cli-login'>Open local handoff</a>
      </div>
    </div>
  );
}

function mimeForArtifact(artifact: AgentArtifact): string {
  const ext = artifact.filename.split('.').pop()?.toLowerCase();
  if (ext === 'html' || ext === 'htm') return 'text/html';
  if (ext === 'svg') return 'image/svg+xml';
  if (ext === 'json') return 'application/json';
  if (ext === 'md' || ext === 'markdown') return 'text/markdown';
  if (ext === 'css') return 'text/css';
  if (ext === 'js' || ext === 'ts' || ext === 'tsx' || ext === 'jsx') return 'text/javascript';
  return 'text/plain';
}

/** Friendly activity chips for tool/run events — no raw JSON. */
function Activity({items}: {items: StreamTimelineItem[]}) {
  const shown = items.filter((i) => i.type === 'tool' || i.type === 'run');
  if (!shown.length) return null;
  return (
    <div className='agent-activity'>
      {shown.map((i) => (
        <span key={i.id} className='agent-activity-chip'>
          {i.type === 'run' ? <RunsIcon size={13} /> : <SearchIcon size={13} />}
          {i.type === 'run' ? 'Run created' : friendlyTool(i.title)}
        </span>
      ))}
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
  busy,
  onOpen,
  onStop,
}: {
  run: api.RunDetail;
  liveCostUsd: number | null;
  streamStatus?: 'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'CLOSED';
  busy: boolean;
  onOpen: (path: string) => void;
  onStop: () => void;
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
  const score = run.verifiedScore == null ? 'Evidence pending' : `${run.verifiedScore}/100`;
  const provider = run.aiProvider || run.workerModel || 'default route';
  const streamLabel =
    streamStatus === 'RECONNECTING'
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
    <div className={`agent-runcard${variant}`}>
      <div className='agent-runcard-head'>
        <RunsIcon size={16} />
        <span className='agent-runcard-title'>{run.runTitle || 'Remote sandbox run'}</span>
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
              {cost.toFixed(2)}
              {est ? ` / ~$${est.toFixed(2)}` : ''}
            </>
          )}
        </span>
      </div>
      <div className='agent-runcard-mode'>Remote sandbox · plan-gated · PR-oriented</div>
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
          <span style={{width: `${terminal && !failed && !cancelled ? 100 : prog.pct}%`}} />
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
    </div>
  );
}

function ActionCard({
  pending,
  onConfirm,
  onDismiss,
  busy,
}: {
  pending: PendingAction;
  onConfirm: (pending: PendingAction) => void;
  onDismiss: () => void;
  busy: boolean;
}) {
  const isCloudRun = pending.action.type === 'create_cloud_run';
  const isLocal =
    pending.action.type === 'local_tool_action' || pending.action.type === 'local_code_task';
  if (isCloudRun) {
    return (
      <SandboxProposalCard
        pending={pending}
        busy={busy}
        onConfirm={onConfirm}
        onDismiss={onDismiss}
      />
    );
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
}: {
  pending: PendingAction;
  onConfirm: (pending: PendingAction) => void;
  onDismiss: () => void;
  busy: boolean;
}) {
  const args = pending.action.arguments || {};
  const spec = asText(args.spec || args.request || pending.action.confirmationPrompt || '');
  const project = asText(args.projectId || args.project || 'Current project or selected repo');
  const provider = asText(args.aiProvider || args.provider || 'Account default');
  return (
    <div className='agent-card agent-sandbox-proposal waiting'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>Create remote sandbox run</strong>
        <span className='agent-card-meta'>plan gate before spend</span>
      </div>
      <div className='agent-card-sub'>
        ExecuteSpec will create an isolated remote workspace, draft a plan, then stop for your
        approval before coding or charging run credits.
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
          <code>Remote sandbox</code>
        </div>
        <div className='agent-kv-row'>
          <span>Provider route</span>
          <code>{provider}</code>
        </div>
      </div>
      <SandboxContractPanel />
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
          disabled={busy}
        >
          Create sandbox plan
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
        This request needs local file or shell authority. The web agent stays remote-sandbox only;
        use the CLI or VS Code client when your machine must execute commands.
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
}: {
  gate: api.AgentGateDecisionCard;
  onApprove: (gate: api.AgentGateDecisionCard) => void;
  onReject: (gate: api.AgentGateDecisionCard) => void;
  onOpen: (runId: string) => void;
  busy: boolean;
}) {
  return (
    <div className='agent-card agent-gate-card waiting'>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>Sandbox plan awaiting approval</strong>
        {gate.risk && <span className='agent-card-meta'>risk {gate.risk.toLowerCase()}</span>}
      </div>
      <button className='agent-card-title' onClick={() => onOpen(gate.runId)}>
        {gate.title || gate.summary || gate.runId}
      </button>
      <div className='agent-card-consequence-row'>
        <span>{gate.projectName || 'standalone sandbox'}</span>
        {gate.tasks.length > 0 && <span>{gate.tasks.length} tasks</span>}
        {gate.creditsRequired != null && <span>~{gate.creditsRequired} credits</span>}
        {gate.provider && <span>{gate.provider}</span>}
        {gateAge(gate) && <span>{gateAge(gate)}</span>}
        <span>opens a PR</span>
      </div>
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
          disabled={busy}
        >
          Approve &amp; run
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

function SandboxContractPanel() {
  return (
    <div className='agent-contract-panel' aria-label='Sandbox contract'>
      <div className='agent-contract-title'>What will happen before spend</div>
      <div className='agent-contract-grid'>
        <span>
          <b>Workspace</b>
          isolated remote clone
        </span>
        <span>
          <b>Branch</b>
          shown in plan
        </span>
        <span>
          <b>Tests</b>
          detected command
        </span>
        <span>
          <b>PR</b>
          target confirmed
        </span>
        <span>
          <b>Cost</b>
          cap before run
        </span>
        <span>
          <b>Local files</b>
          never touched
        </span>
      </div>
    </div>
  );
}

function TrustBoundaryPanel({compact = false}: {compact?: boolean}) {
  return (
    <div className={compact ? 'agent-trust-panel compact' : 'agent-trust-panel'}>
      <span>Repo token scoped to selected project</span>
      <span>Secrets are not displayed in chat</span>
      <span>Run events stay in audit trail</span>
      <span>Approval required before code execution</span>
      <span>Retention follows workspace cleanup policy</span>
      <span>Outbound network follows sandbox policy</span>
    </div>
  );
}

function OperatorIntro({
  hasHistory,
  onPick,
}: {
  hasHistory: boolean;
  onPick: (text: string) => void;
}) {
  return (
    <div className='agent-operator-intro'>
      <div className='agent-operator-kicker'>Remote sandbox operator</div>
      <div className='agent-operator-grid'>
        <div>
          <h1>Run work in a remote sandbox.</h1>
          <p>
            Plan, code, test, review, and open a PR without touching your local machine. You approve
            the plan and cost before the sandbox starts coding.
          </p>
        </div>
        <div className='agent-operator-steps' aria-label='Remote sandbox workflow'>
          <span>Request</span>
          <span>Plan gate</span>
          <span>Sandbox work</span>
          <span>Evidence</span>
          <span>PR / deploy</span>
        </div>
      </div>
      <SandboxContractPanel />
      <TrustBoundaryPanel />
      <div className='agent-empty-chips'>
        {SUGGESTIONS.map((s) => (
          <button key={s} onClick={() => onPick(s)}>
            {s}
          </button>
        ))}
      </div>
      {hasHistory && (
        <div className='agent-history-note'>
          Recent transcript is below. Start a new sandbox from here or open Chats for older work.
        </div>
      )}
    </div>
  );
}

function EmptyState({onPick}: {onPick: (text: string) => void}) {
  return (
    <div className='agent-empty'>
      <div className='agent-empty-icon'>
        <CommandIcon size={26} />
      </div>
      <strong>Remote sandbox ready</strong>
      <p>
        Describe the change, review the generated plan, then let ExecuteSpec work in an isolated
        sandbox. Browser sessions never get local shell authority.
      </p>
      <div className='agent-empty-chips'>
        {SUGGESTIONS.map((s) => (
          <button key={s} onClick={() => onPick(s)}>
            {s}
          </button>
        ))}
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
            <span
              key={a.attachmentId}
              className='agent-attach-chip'
              title={`${a.mimeType} · ${a.extractedChars} chars extracted`}
            >
              <span className='agent-attach-chip-name'>{a.filename}</span>
              <button
                onClick={() => onRemoveAttachment(a.attachmentId)}
                aria-label={`Remove ${a.filename}`}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
      <div className='agent-composer-box'>
        <label className='agent-attach-btn' title='Attach PDF, Markdown, or text'>
          {uploading ? <span className='agent-attach-spin' /> : <Paperclip />}
          <input
            type='file'
            accept='.pdf,.md,.markdown,.txt,.text,.csv,.json,.log,text/plain,text/markdown,application/pdf,application/json'
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
        Enter to send · attach PDF, Markdown, or text · web search built in
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
  input,
  attachments,
  uploading,
  endRef,
  dispatch,
  onScroll,
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
  input: string;
  attachments: AgentAttachmentLite[];
  uploading: boolean;
  endRef: RefObject<HTMLDivElement>;
  dispatch: Dispatch<AgentAction>;
  onScroll: (e: UIEvent<HTMLDivElement>) => void;
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
}) {
  const empty = messages.length === 0 && !isBusy && !visibleAction && !runCard;
  const showOperatorIntro = !visibleAction && !runCard && gates.length === 0 && !isBusy;
  const showOperatorInScroll = showOperatorIntro && messages.length === 0;
  const showComposerContract =
    messages.length === 0 && !visibleAction && gates.length === 0 && !runCard;
  return (
    <section className='agent-chat'>
      <h1 className='ide-sr-only'>Agent</h1>
      <div className='agent-chat-scroll' onScroll={onScroll}>
        <div className='ide-sr-only' role='status' aria-live='polite' aria-atomic='true'>
          {runCard
            ? `Remote sandbox ${runPhaseLabel(runCard.status)}${
                runStreamStatus === 'RECONNECTING'
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
          {showOperatorInScroll && (
            <OperatorIntro
              hasHistory={messages.length > 0}
              onPick={(s) => dispatch({type: 'input:set', input: s})}
            />
          )}
          {gates.length > 0 && (
            <div className='agent-gates-inline'>
              <div className='agent-gates-label'>Remote sandboxes waiting for your approval</div>
              {gates.map((g) => (
                <GateCard
                  key={g.runId}
                  gate={g}
                  busy={isBusy}
                  onApprove={onApproveGate}
                  onReject={onRejectGate}
                  onOpen={onOpenGate}
                />
              ))}
            </div>
          )}
          {empty
            ? !showOperatorInScroll && (
                <EmptyState onPick={(s) => dispatch({type: 'input:set', input: s})} />
              )
            : messages.map((message) => (
                <MessageBubble key={message.id} message={message} dispatch={dispatch} />
              ))}
          <Activity items={streamEvents} />
          {isBusy && !streamText && <Typing />}
          {runCard && (
            <SandboxRunCard
              run={runCard}
              liveCostUsd={runCost}
              streamStatus={runStreamStatus}
              busy={isBusy}
              onOpen={onOpenRun}
              onStop={onStopRun}
            />
          )}
          {visibleAction && (
            <ActionCard
              pending={visibleAction}
              busy={isBusy}
              onConfirm={onConfirmAction}
              onDismiss={() => dispatch({type: 'action:clear'})}
            />
          )}
          <div ref={endRef} />
        </div>
      </div>
      <div className='agent-composer-wrap'>
        <div className='agent-chat-col'>
          {showComposerContract && (
            <div className='agent-composer-contract'>
              <span>Remote sandbox only</span>
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

function sandboxPhaseKey(status?: string | null): (typeof SANDBOX_PHASES)[number]['key'] {
  const s = (status || '').toUpperCase();
  if (['QUEUED', 'PENDING', 'SUBMITTED', 'QUEUED_BATCH'].includes(s)) return 'queued';
  if (['CLASSIFYING', 'PLANNING'].includes(s)) return 'planning';
  if (s === 'AWAITING_APPROVAL') return 'approval';
  if (['WORKING', 'IN_PROGRESS', 'IN_PROGRESS_BATCH', 'CODING', 'RUNNING'].includes(s))
    return 'coding';
  if (['COMPILING', 'BUILDING', 'TESTING'].includes(s)) return 'building';
  if (s === 'REVIEWING') return 'reviewing';
  if (['COMPLETE', 'COMPLETED', 'FAILED', 'CANCELLED', 'ERROR', 'PARTIAL'].includes(s))
    return 'done';
  return 'queued';
}

function phaseState(
  phase: (typeof SANDBOX_PHASES)[number]['key'],
  active: (typeof SANDBOX_PHASES)[number]['key'],
  terminal: boolean,
  failed: boolean,
  cancelled: boolean,
): string {
  const order = SANDBOX_PHASES.map((p) => p.key);
  const phaseIndex = order.indexOf(phase);
  const activeIndex = order.indexOf(active);
  const state =
    terminal && phase === 'done'
      ? failed
        ? 'bad'
        : cancelled
          ? 'muted'
          : 'done'
      : phaseIndex < activeIndex
        ? 'done'
        : phaseIndex === activeIndex
          ? 'active'
          : 'pending';
  return `agent-runcard-phase ${state}`;
}
