import type {Dispatch, RefObject} from 'react';
import * as api from '@/lib/api';
import {IdeBadge} from '@/ide/IdeKit';
import {CommandIcon, PlusIcon, StatusDot} from '@/ide/IdeIcons';
import {
  actionLabel,
  asText,
  currency,
  pct,
  sessionTitle,
  type AgentAction,
  type AgentUiState,
  type LocalMessage,
  type PendingAction,
  type RoutingState,
  type StreamTimelineItem,
} from '@/ide/agentState';

type BadgeKind = 'ok' | 'warn' | 'bad';

function managedKind(balance: api.ManagedAiBalance | null): BadgeKind {
  if (!balance || balance.routingState === 'FULL') return 'ok';
  return balance.routingState === 'EXHAUSTED' ? 'bad' : 'warn';
}

function routeKind(routingState: RoutingState): BadgeKind {
  return routingState?.runApiAllowed ? 'warn' : 'ok';
}

function statusKind(status: AgentUiState): BadgeKind {
  return status === 'error' ? 'bad' : status === 'idle' ? 'ok' : 'warn';
}

function MessageBubble({message}: {message: LocalMessage}) {
  const kind =
    message.role === 'user' ? 'warn' : message.status === 'FAILED' ? 'bad' : ('ok' as const);
  return (
    <div className={`agent-msg ${message.role}`}>
      <div className='agent-msg-meta'>
        <span>
          {message.role === 'user' ? 'You' : message.role === 'system' ? 'System' : 'Agent'}
        </span>
        {message.status && <IdeBadge kind={kind}>{message.status}</IdeBadge>}
      </div>
      <div className='agent-msg-body'>{message.content}</div>
    </div>
  );
}

function Timeline({items}: {items: StreamTimelineItem[]}) {
  if (!items.length) return null;
  return (
    <div className='agent-timeline'>
      {items.slice(-8).map((item) => (
        <div key={item.id} className={`agent-event ${item.type}`}>
          <StatusDot
            kind={item.type === 'tool' ? 'warn' : item.type === 'run' ? 'ok' : 'idle'}
            size={7}
          />
          <span>{item.title}</span>
          {item.detail && <small>{item.detail}</small>}
        </div>
      ))}
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
  return (
    <div className='agent-action-card'>
      <div className='agent-action-head'>
        <div>
          <strong>{isLocal ? 'Continue with local hands' : actionLabel(pending.action)}</strong>
          <span>{pending.action.confirmationPrompt || pending.route}</span>
        </div>
        <IdeBadge kind={pending.action.requiresConfirmation ? 'warn' : 'ok'}>
          {pending.action.billingImpact || 'none'}
        </IdeBadge>
      </div>
      <div className='agent-action-body'>
        {Object.entries(pending.action.arguments || {}).map(([key, value]) => (
          <div key={key} className='agent-kv-row'>
            <span>{key}</span>
            <code>{asText(value)}</code>
          </div>
        ))}
      </div>
      {isLocal ? (
        <div className='agent-action-actions'>
          <button className='ide-btn ghost' onClick={() => (window.location.href = '/cli-login')}>
            Continue in CLI
          </button>
          <button className='ide-btn ghost' onClick={onDismiss}>
            Keep as advice
          </button>
        </div>
      ) : (
        <div className='agent-action-actions'>
          {isCloudRun && (
            <button className='ide-btn' onClick={() => onConfirm(pending)} disabled={busy}>
              Create cloud run
            </button>
          )}
          <button className='ide-btn ghost' onClick={onDismiss} disabled={busy}>
            Dismiss
          </button>
        </div>
      )}
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
    <div className='agent-gate-card'>
      <button className='agent-gate-title' onClick={() => onOpen(gate.runId)}>
        {gate.title || gate.summary || gate.runId}
      </button>
      <div className='agent-gate-meta'>
        <span>{gate.provider || 'provider n/a'}</span>
        <span>{gate.model || 'model n/a'}</span>
        <span>
          {gate.creditsRequired != null
            ? `${gate.creditsRequired} ES AI credit${gate.creditsRequired === 1 ? '' : 's'}`
            : 'credits n/a'}
        </span>
      </div>
      {gate.tasks.length > 0 && (
        <ul className='agent-gate-tasks'>
          {gate.tasks.slice(0, 4).map((task) => (
            <li key={task}>{task}</li>
          ))}
        </ul>
      )}
      <div className='agent-action-actions'>
        <button className='ide-btn' onClick={() => onApprove(gate)} disabled={busy}>
          Approve
        </button>
        <button className='ide-btn ghost' onClick={() => onReject(gate)} disabled={busy}>
          Reject
        </button>
      </div>
    </div>
  );
}

export function AgentHeader({onCreate, busy}: {onCreate: () => void; busy: boolean}) {
  return (
    <div className='agent-head'>
      <div>
        <div className='agent-kicker'>
          <CommandIcon size={15} />
          Agent
        </div>
        <h1>Control plane</h1>
        <p>
          Ask questions, create gated cloud runs, inspect plan gates, and keep decisions replayable.
        </p>
      </div>
      <button className='ide-btn' onClick={onCreate} disabled={busy}>
        <PlusIcon size={14} />
        New session
      </button>
    </div>
  );
}

export function AgentTopStrip({
  selected,
  projectId,
  runId,
  activeRun,
  managedAi,
  routingState,
}: {
  selected: api.AgentSessionResponse | null;
  projectId?: string;
  runId?: string;
  activeRun: string | null;
  managedAi: api.ManagedAiBalance | null;
  routingState: RoutingState;
}) {
  return (
    <div className='agent-top-strip'>
      <span>Provider {selected?.defaultProvider || 'account default'}</span>
      <span>
        {projectId
          ? `Project ${projectId}`
          : selected?.projectId
            ? `Project ${selected.projectId}`
            : 'No project scope'}
      </span>
      <span>
        {runId ? `Run ${runId}` : activeRun ? `Active run ${activeRun}` : 'No active run'}
      </span>
      <span>
        {managedAi ? `${managedAi.aiCreditsRemaining} ES AI credits left` : 'ES AI credits n/a'}
      </span>
      {managedAi && <IdeBadge kind={managedKind(managedAi)}>{managedAi.routingState}</IdeBadge>}
      {routingState && <IdeBadge kind={routeKind(routingState)}>{routingState.route}</IdeBadge>}
    </div>
  );
}

export function AgentLeftRail({
  sessions,
  selectedId,
  status,
  dispatch,
}: {
  sessions: api.AgentSessionResponse[];
  selectedId: string | null;
  status: AgentUiState;
  dispatch: Dispatch<AgentAction>;
}) {
  return (
    <aside className='agent-rail'>
      <div className='ide-card agent-panel'>
        <div className='agent-panel-head'>
          <span>Sessions</span>
          <IdeBadge kind='warn'>{sessions.length}</IdeBadge>
        </div>
        {status === 'loadingSession' ? (
          <div className='agent-muted'>Loading sessions...</div>
        ) : sessions.length === 0 ? (
          <div className='agent-muted'>No agent sessions yet.</div>
        ) : (
          <div className='agent-list'>
            {sessions.map((session) => (
              <button
                key={session.id}
                className={session.id === selectedId ? 'active' : ''}
                onClick={() => dispatch({type: 'session:select', sessionId: session.id})}
              >
                <span>{sessionTitle(session)}</span>
                <small>{session.status}</small>
              </button>
            ))}
          </div>
        )}
      </div>

      <div className='ide-card agent-panel'>
        <div className='agent-panel-head'>
          <span>Command palette</span>
          <IdeBadge kind='ok'>WEB</IdeBadge>
        </div>
        <div className='agent-command-grid'>
          {[
            'What plans are waiting?',
            'List recent runs',
            'Explain current project',
            'Create a cloud run',
          ].map((cmd) => (
            <button key={cmd} onClick={() => dispatch({type: 'input:set', input: cmd})}>
              {cmd}
            </button>
          ))}
        </div>
      </div>
    </aside>
  );
}

export function AgentMainPanel({
  selected,
  status,
  messages,
  visibleAction,
  isBusy,
  streamText,
  streamEvents,
  input,
  projectId,
  runId,
  endRef,
  dispatch,
  onConfirmAction,
  onSend,
  onStop,
}: {
  selected: api.AgentSessionResponse | null;
  status: AgentUiState;
  messages: LocalMessage[];
  visibleAction: PendingAction | null;
  isBusy: boolean;
  streamText: string;
  streamEvents: StreamTimelineItem[];
  input: string;
  projectId?: string;
  runId?: string;
  endRef: RefObject<HTMLDivElement>;
  dispatch: Dispatch<AgentAction>;
  onConfirmAction: (pending: PendingAction) => void;
  onSend: () => void;
  onStop: () => void;
}) {
  return (
    <section className='ide-card agent-main'>
      <div className='agent-main-head'>
        <div>
          <strong>{selected ? sessionTitle(selected) : 'No session selected'}</strong>
          <span>{selected?.id ?? 'Create a session to start.'}</span>
        </div>
        <IdeBadge kind={statusKind(status)}>{status}</IdeBadge>
      </div>

      <div className='agent-transcript'>
        {messages.length === 0 && !isBusy ? (
          <div className='agent-empty'>
            <CommandIcon size={22} />
            <strong>Ask the web agent</strong>
            <span>Try "What plans are waiting?" or "Create a run to add audit logs."</span>
          </div>
        ) : (
          messages.map((message) => <MessageBubble key={message.id} message={message} />)
        )}
        {visibleAction && (
          <ActionCard
            pending={visibleAction}
            busy={isBusy}
            onConfirm={onConfirmAction}
            onDismiss={() => dispatch({type: 'action:clear'})}
          />
        )}
        {isBusy && !streamText && (
          <div className='agent-muted' style={{padding: '0 4px 8px'}}>
            Waiting for model/tool response...
          </div>
        )}
        <Timeline items={streamEvents} />
        <div ref={endRef} />
      </div>

      <div className='agent-composer'>
        <div className='agent-context-chips'>
          {projectId && <span>Project</span>}
          {runId && <span>Run</span>}
          <span>Architecture</span>
          <span>Memory</span>
          <span>Docs</span>
          <span>Gate</span>
        </div>
        <textarea
          className='ide-input'
          rows={3}
          value={input}
          placeholder='Ask, inspect gates, or create a gated cloud run...'
          onChange={(e) => dispatch({type: 'input:set', input: e.target.value})}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              onSend();
            }
          }}
          disabled={isBusy}
        />
        <div className='agent-composer-actions'>
          <span>Enter sends. Mutations render cards before execution.</span>
          {isBusy ? (
            <button className='ide-btn' onClick={onStop}>
              Stop
            </button>
          ) : (
            <button className='ide-btn' onClick={onSend} disabled={!input.trim()}>
              Send
            </button>
          )}
        </div>
      </div>
    </section>
  );
}

export function AgentRightRail({
  managedAi,
  routingState,
  gates,
  capabilities,
  busy,
  onApproveGate,
  onRejectGate,
  onOpenGate,
}: {
  managedAi: api.ManagedAiBalance | null;
  routingState: RoutingState;
  gates: api.AgentGateDecisionCard[];
  capabilities: api.AgentCapabilityTool[];
  busy: boolean;
  onApproveGate: (gate: api.AgentGateDecisionCard) => void;
  onRejectGate: (gate: api.AgentGateDecisionCard) => void;
  onOpenGate: (runId: string) => void;
}) {
  const managedBadge = managedKind(managedAi);
  const routeBadge = routeKind(routingState);
  return (
    <aside className='agent-rail right'>
      <div className='ide-card agent-panel'>
        <div className='agent-panel-head'>
          <span>Managed AI</span>
          <IdeBadge kind={managedBadge}>{managedAi?.routingState || 'n/a'}</IdeBadge>
        </div>
        {managedAi ? (
          <>
            <div className='agent-kv-row'>
              <span>Credits</span>
              <code>
                {managedAi.aiCreditsUsed} / {managedAi.includedAiCredits}
              </code>
            </div>
            <div className='agent-meter'>
              <span
                style={{width: `${pct(managedAi.aiCreditsUsed, managedAi.includedAiCredits)}%`}}
              />
            </div>
            <div className='agent-kv-row'>
              <span>Raw COGS</span>
              <code>
                {currency(managedAi.rawCogsUsedUsd)} / {currency(managedAi.hardStopAfterRawUsd)}
              </code>
            </div>
            <div className='agent-kv-row'>
              <span>Frontier cap</span>
              <code>
                {currency(managedAi.frontierRawUsedUsd)} / {currency(managedAi.frontierRawCapUsd)}
              </code>
            </div>
          </>
        ) : (
          <div className='agent-muted'>Usage unavailable.</div>
        )}
      </div>

      <div className='ide-card agent-panel'>
        <div className='agent-panel-head'>
          <span>Routing state</span>
          <IdeBadge kind={routeBadge}>{routingState?.route || 'idle'}</IdeBadge>
        </div>
        <div className='agent-kv-row'>
          <span>Confidence</span>
          <code>{routingState ? `${Math.round(routingState.confidence * 100)}%` : 'n/a'}</code>
        </div>
        <div className='agent-kv-row'>
          <span>Run API</span>
          <code>{routingState?.runApiAllowed ? 'allowed after confirmation' : 'blocked'}</code>
        </div>
      </div>

      <div className='ide-card agent-panel'>
        <div className='agent-panel-head'>
          <span>Pending gates</span>
          <IdeBadge kind={gates.length ? 'warn' : 'ok'}>{gates.length}</IdeBadge>
        </div>
        {gates.length === 0 ? (
          <div className='agent-muted'>No plans waiting.</div>
        ) : (
          <div className='agent-gate-list'>
            {gates.map((gate) => (
              <GateCard
                key={gate.runId}
                gate={gate}
                busy={busy}
                onApprove={onApproveGate}
                onReject={onRejectGate}
                onOpen={onOpenGate}
              />
            ))}
          </div>
        )}
      </div>

      <div className='ide-card agent-panel'>
        <div className='agent-panel-head'>
          <span>WEB capabilities</span>
          <IdeBadge kind='ok'>{capabilities.length}</IdeBadge>
        </div>
        <div className='agent-capabilities'>
          {capabilities.slice(0, 12).map((capability) => (
            <span key={capability.name}>
              <StatusDot kind={capability.confirmationRequired ? 'warn' : 'ok'} size={7} />
              {capability.name}
            </span>
          ))}
        </div>
      </div>
    </aside>
  );
}
