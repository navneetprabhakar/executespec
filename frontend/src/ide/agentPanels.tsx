import type {Dispatch, RefObject, UIEvent} from 'react';
import * as api from '@/lib/api';
import {Markdown} from '@/signal/components/Markdown';
import {CommandIcon, PlusIcon, RunsIcon, SearchIcon, SettingsIcon, StatusDot} from '@/ide/IdeIcons';
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
  type AgentUiState,
  type BarTone,
  type LocalMessage,
  type PendingAction,
  type RoutingState,
  type StreamTimelineItem,
} from '@/ide/agentState';

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
  'What plans are waiting?',
  'Explain this project',
  'Search the web for the latest pgvector release',
  'Create a run to add audit logging',
];

/** Top bar: Chats · scope chip · title · honest status · New chat · settings popover. */
export function AgentTopBar({
  selected,
  barStatus,
  scopeLabel,
  busy,
  settingsOpen,
  managedAi,
  routingState,
  capabilities,
  onNewChat,
  onToggleChats,
  onToggleSettings,
}: {
  selected: api.AgentSessionResponse | null;
  barStatus: {label: string; tone: BarTone};
  scopeLabel: string | null;
  busy: boolean;
  settingsOpen: boolean;
  managedAi: api.ManagedAiBalance | null;
  routingState: RoutingState;
  capabilities: api.AgentCapabilityTool[];
  onNewChat: () => void;
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
      <div className='agent-bar-title'>{selected ? sessionTitle(selected) : 'New chat'}</div>
      <span className='agent-bar-spacer' />
      <span className={`agent-bar-status ${barStatus.tone}`}>
        <span className='agent-bar-dot' aria-hidden='true' />
        {barStatus.label}
      </span>
      <button className='agent-bar-btn' onClick={onNewChat} disabled={busy}>
        <PlusIcon size={14} />
        <span>New chat</span>
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
      <div className='agent-pop' role='dialog' aria-label='Agent settings and usage'>
        <div className='agent-pop-row'>
          <span>Provider</span>
          <code>{selected?.defaultProvider || 'account default'}</code>
        </div>
        {managedAi ? (
          <>
            <div className='agent-pop-row'>
              <span>ES AI credits</span>
              <code>
                {managedAi.aiCreditsUsed} / {managedAi.includedAiCredits}
              </code>
            </div>
            <div className='agent-meter'>
              <span
                style={{width: `${pct(managedAi.aiCreditsUsed, managedAi.includedAiCredits)}%`}}
              />
            </div>
          </>
        ) : (
          <div className='agent-pop-row'>
            <span>ES AI credits</span>
            <code>n/a</code>
          </div>
        )}
        {routingState && (
          <>
            <div className='agent-pop-row'>
              <span>Confidence</span>
              <code>{Math.round(routingState.confidence * 100)}%</code>
            </div>
            <div className='agent-pop-row'>
              <span>Run API</span>
              <code>{routingState.runApiAllowed ? 'after confirm' : 'blocked'}</code>
            </div>
          </>
        )}
        <div className='agent-pop-caps-label'>What it can do ({capabilities.length})</div>
        <div className='agent-pop-caps'>
          {capabilities.slice(0, 16).map((c) => (
            <span key={c.name}>
              <StatusDot kind={c.confirmationRequired ? 'warn' : 'ok'} size={6} />
              {c.name.replace(/_/g, ' ')}
            </span>
          ))}
        </div>
      </div>
    </>
  );
}

/** Sessions as a slide-over drawer with auto-titled, time-stamped rows. */
export function AgentChatsDrawer({
  open,
  sessions,
  selectedId,
  status,
  onSelect,
  onNewChat,
  onClose,
}: {
  open: boolean;
  sessions: api.AgentSessionResponse[];
  selectedId: string | null;
  status: AgentUiState;
  onSelect: (id: string) => void;
  onNewChat: () => void;
  onClose: () => void;
}) {
  if (!open) return null;
  return (
    <>
      <div className='agent-drawer-backdrop' onClick={onClose} />
      <aside className='agent-drawer' role='dialog' aria-label='Chats'>
        <div className='agent-drawer-head'>
          <strong>Chats</strong>
          <button className='ide-btn ghost' onClick={onNewChat}>
            <PlusIcon size={13} />
            New
          </button>
        </div>
        {status === 'loadingSession' ? (
          <div className='agent-muted'>Loading…</div>
        ) : sessions.length === 0 ? (
          <div className='agent-muted'>No chats yet.</div>
        ) : (
          <div className='agent-drawer-list'>
            {sessions.map((s) => (
              <button
                key={s.id}
                className={s.id === selectedId ? 'active' : ''}
                onClick={() => onSelect(s.id)}
              >
                <span className='agent-drawer-title'>{sessionTitle(s)}</span>
                <span className='agent-drawer-meta'>
                  {scopeBadge(s)}
                  {relTime(s.updatedAt)}
                </span>
              </button>
            ))}
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

function MessageBubble({message}: {message: LocalMessage}) {
  if (message.role === 'user') {
    return (
      <div className='agent-msg user'>
        <div className='agent-msg-body'>{message.content}</div>
      </div>
    );
  }
  return (
    <div className={`agent-msg ${message.role}`}>
      <div className='agent-msg-label'>{message.role === 'system' ? 'System' : 'Agent'}</div>
      <div className='agent-msg-body'>
        <Markdown>{message.content}</Markdown>
      </div>
    </div>
  );
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
function RunCard({
  run,
  liveCostUsd,
  busy,
  onOpen,
  onStop,
}: {
  run: api.RunDetail;
  liveCostUsd: number | null;
  busy: boolean;
  onOpen: (path: string) => void;
  onStop: () => void;
}) {
  const prog = runProgress(run.tasks);
  const terminal = isTerminalRunStatus(run.status);
  const failed = (run.status || '').toUpperCase() === 'FAILED';
  const cancelled = (run.status || '').toUpperCase() === 'CANCELLED';
  const cost = liveCostUsd ?? 0;
  const est = run.costEstimateHigh ?? 0;
  const variant = !terminal ? '' : failed ? ' failed' : cancelled ? ' cancelled' : ' done';
  const pillKind = !terminal ? 'live' : failed ? 'bad' : cancelled ? 'muted' : 'ok';
  return (
    <div className={`agent-runcard${variant}`}>
      <div className='agent-runcard-head'>
        <RunsIcon size={16} />
        <span className='agent-runcard-title'>{run.runTitle || 'Cloud run'}</span>
        <span className={`agent-runcard-pill ${pillKind}`}>
          {!terminal && <span className='agent-runcard-dot' aria-hidden='true' />}
          {runPhaseLabel(run.status)}
        </span>
        <span className='agent-runcard-cost'>
          ${cost.toFixed(2)}
          {est ? ` / ~$${est.toFixed(2)}` : ''}
        </span>
      </div>
      {!terminal && prog.current && (
        <div className='agent-runcard-step'>
          {prog.total > 0 ? `Task ${Math.min(prog.done + 1, prog.total)} of ${prog.total} · ` : ''}
          {prog.current}
        </div>
      )}
      {prog.total > 0 && (
        <div className='agent-runcard-bar'>
          <span style={{width: `${terminal && !failed && !cancelled ? 100 : prog.pct}%`}} />
        </div>
      )}
      <div className='agent-runcard-actions'>
        <button className='ide-btn' onClick={() => onOpen(runOpenPath(run.runId, run.status))}>
          {terminal ? 'Open result' : 'Open run'}
        </button>
        {run.githubPrUrl && (
          <a className='ide-btn ghost' href={run.githubPrUrl} target='_blank' rel='noreferrer'>
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
  const args = Object.entries(pending.action.arguments || {});
  return (
    <div className={`agent-card${isCloudRun ? ' waiting' : ''}`}>
      <div className='agent-card-head'>
        <RunsIcon size={16} />
        <strong>{isLocal ? 'Continue on your machine' : 'Start a cloud run'}</strong>
        {pending.action.billingImpact && pending.action.billingImpact !== 'none' && (
          <span className='agent-card-meta'>{pending.action.billingImpact.replace(/_/g, ' ')}</span>
        )}
      </div>
      {pending.action.confirmationPrompt && (
        <div className='agent-card-sub'>{pending.action.confirmationPrompt}</div>
      )}
      {isCloudRun && (
        <div className='agent-card-consequence'>
          You approve the plan and cost on the next screen — nothing bills until then.
        </div>
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
        {isLocal ? (
          <>
            <button className='ide-btn' onClick={() => (window.location.href = '/cli-login')}>
              Continue in CLI
            </button>
            <button className='ide-btn ghost' onClick={onDismiss}>
              Keep as advice
            </button>
          </>
        ) : (
          <>
            {isCloudRun && (
              <button
                className='ide-btn agent-btn-primary'
                onClick={() => onConfirm(pending)}
                disabled={busy}
              >
                Review plan &amp; cost
              </button>
            )}
            <button className='ide-btn ghost' onClick={onDismiss} disabled={busy}>
              Dismiss
            </button>
          </>
        )}
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
    <div className='agent-card waiting'>
      <button className='agent-card-title' onClick={() => onOpen(gate.runId)}>
        {gate.title || gate.summary || gate.runId}
      </button>
      <div className='agent-card-consequence-row'>
        {gate.tasks.length > 0 && <span>{gate.tasks.length} tasks</span>}
        {gate.creditsRequired != null && <span>~{gate.creditsRequired} credits</span>}
        <span>opens a PR</span>
      </div>
      {gate.tasks.length > 0 && (
        <ul className='agent-card-tasks'>
          {gate.tasks.slice(0, 4).map((task) => (
            <li key={task}>{task}</li>
          ))}
        </ul>
      )}
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
      </div>
    </div>
  );
}

function EmptyState({onPick}: {onPick: (text: string) => void}) {
  return (
    <div className='agent-empty'>
      <div className='agent-empty-icon'>
        <CommandIcon size={26} />
      </div>
      <strong>Ask the agent</strong>
      <p>
        Search the web, ask about your projects and runs, attach a file, or kick off a gated cloud
        run — the human approves before anything bills.
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
  return (
    <section className='agent-chat'>
      <div className='agent-chat-scroll' onScroll={onScroll}>
        <div className='agent-chat-col'>
          {gates.length > 0 && (
            <div className='agent-gates-inline'>
              <div className='agent-gates-label'>Waiting for your approval</div>
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
          {empty ? (
            <EmptyState onPick={(s) => dispatch({type: 'input:set', input: s})} />
          ) : (
            messages.map((message) => <MessageBubble key={message.id} message={message} />)
          )}
          <Activity items={streamEvents} />
          {isBusy && !streamText && <Typing />}
          {runCard && (
            <RunCard
              run={runCard}
              liveCostUsd={runCost}
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
