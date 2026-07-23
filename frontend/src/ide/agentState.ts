import * as api from '@/lib/api';
import {
  isAwaitingApprovalRunStatus,
  isTerminalRunStatus as isTerminalRunStatusShared,
} from '@/lib/runStatus';

export type AgentUiState =
  | 'idle'
  | 'loadingSession'
  | 'composing'
  | 'streaming'
  | 'toolRunning'
  | 'actionProposed'
  | 'confirmationRequired'
  | 'gateDecision'
  | 'runStreaming'
  | 'error'
  | 'reconnecting';

export type LocalMessage = {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  status?: string;
  usage?: AgentUsageSummary;
};

export type AgentUsageSummary = {
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  costUsd: number;
};

export type StreamTimelineItem = {
  id: string;
  streamSequence?: number;
  type: 'route' | 'tool' | 'result' | 'run' | 'usage' | 'system';
  title: string;
  detail?: string;
};

/**
 * Convert the server's semantic tool-progress event into the same compact timeline item used by
 * live and replayed Web streams.  The CLI already preserves these handoff/provider events in its
 * surface-neutral transcript; keeping the conversion here prevents the Web adapter from silently
 * dropping them on either the live POST-SSE path or durable replay.
 */
export function toolEventTimelineItem(
  event: api.AgentStreamToolEvent,
  id: string,
  streamSequence?: number,
): StreamTimelineItem {
  const semantic = String(event.semantic ?? '').toLowerCase();
  const role = event.role ?? event.agent_role;
  const provider = event.provider ?? event.provider_name;
  const model = event.model ?? event.model_name;
  const semanticTitle: Record<string, string> = {
    handoff: 'Provider/model handoff',
    verification: 'Verification update',
    plan: 'Plan update',
    diff: 'Diff update',
    permission: 'Permission update',
  };
  const title =
    semanticTitle[semantic] ??
    (role || provider || model
      ? 'Provider/model handoff'
      : (event.name ?? event.type ?? event.kind ?? 'Agent update'));
  const detail = [
    role,
    provider && model ? `${provider}/${model}` : (provider ?? model),
    event.status ?? event.state,
    event.detail ?? event.message ?? event.reason,
  ]
    .filter(Boolean)
    .join(' · ');
  return {id, streamSequence, type: 'system', title, detail: detail || undefined};
}

export type PendingAction = {
  sessionId: string;
  turnId: string;
  route: string;
  action: api.AgentTypedAction;
  source: 'stream' | 'history';
};

export type RoutingState = {
  route: string;
  confidence: number;
  reasonCode: string;
  runApiAllowed: boolean;
} | null;

export type AgentAttachmentLite = {
  attachmentId: string;
  filename: string;
  mimeType: string;
  extractedChars: number;
  /** Local-only preview; the server remains authoritative for attachment bytes. */
  previewUrl?: string;
};

export type AgentState = {
  status: AgentUiState;
  sessions: api.AgentSessionResponse[];
  selectedId: string | null;
  turns: api.AgentTurnResponse[];
  gates: api.AgentGateDecisionCard[];
  capabilities: api.AgentCapabilityTool[];
  input: string;
  pendingUser: string;
  streamText: string;
  streamEvents: StreamTimelineItem[];
  pendingAction: PendingAction | null;
  activeRun: string | null;
  routingState: RoutingState;
  managedAi: api.ManagedAiBalance | null;
  attachments: AgentAttachmentLite[];
  sessionsCollapsed: boolean;
  error: string | null;
};

export type AgentAction =
  | {type: 'load:start'}
  | {
      type: 'load:success';
      sessions: api.AgentSessionResponse[];
      gates: api.AgentGateDecisionCard[];
      capabilities: api.AgentCapabilityTool[];
      selectedId: string | null;
    }
  | {type: 'load:error'; error: string}
  | {type: 'sessions:set'; sessions: api.AgentSessionResponse[]}
  | {type: 'session:created'; session: api.AgentSessionResponse}
  | {type: 'session:select'; sessionId: string | null}
  | {type: 'turns:set'; turns: api.AgentTurnResponse[]}
  | {type: 'events:set'; events: StreamTimelineItem[]}
  | {type: 'events:append'; events: StreamTimelineItem[]}
  | {type: 'events:hydrate'; events: api.AgentActionEvent[]; append?: boolean}
  | {type: 'gates:set'; gates: api.AgentGateDecisionCard[]}
  | {type: 'managed-ai:set'; balance: api.ManagedAiBalance | null}
  | {type: 'input:set'; input: string}
  | {type: 'stream:start'; message: string}
  | {type: 'stream:ack'; turnId: string}
  | {type: 'stream:route'; route: api.AgentStreamRouteDecision}
  | {type: 'stream:proposal'; proposal: api.AgentStreamProposal}
  | {type: 'stream:token'; token: string}
  | {type: 'stream:event'; item: StreamTimelineItem}
  | {type: 'stream:done'}
  | {type: 'stream:stop'}
  | {type: 'action:clear'}
  | {type: 'run:submitted'; runId: string}
  | {type: 'attachment:add'; attachment: AgentAttachmentLite}
  | {type: 'attachment:remove'; attachmentId: string}
  | {type: 'sessions:toggle'}
  | {type: 'error'; error: string}
  | {type: 'error:clear'};

export const initialAgentState: AgentState = {
  status: 'idle',
  sessions: [],
  selectedId: null,
  turns: [],
  gates: [],
  capabilities: [],
  input: '',
  pendingUser: '',
  streamText: '',
  streamEvents: [],
  pendingAction: null,
  activeRun: null,
  routingState: null,
  managedAi: null,
  attachments: [],
  sessionsCollapsed:
    typeof localStorage !== 'undefined' &&
    localStorage.getItem('es:agent:sessions-collapsed') === '1',
  error: null,
};

export function agentReducer(state: AgentState, action: AgentAction): AgentState {
  switch (action.type) {
    case 'load:start':
      return {...state, status: 'loadingSession', error: null};
    case 'load:success':
      return {
        ...state,
        status: 'idle',
        sessions: action.sessions,
        gates: action.gates,
        capabilities: action.capabilities,
        selectedId: action.selectedId,
        error: null,
      };
    case 'load:error':
      return {...state, status: 'error', error: action.error};
    case 'sessions:set':
      return {...state, sessions: action.sessions};
    // Canonical creation result. Deduping by id means an idempotent re-create
    // (same client request id) selects the existing session instead of
    // appending a duplicate row to the drawer.
    case 'session:created':
      return agentReducer(
        {
          ...state,
          sessions: [action.session, ...state.sessions.filter((s) => s.id !== action.session.id)],
        },
        {type: 'session:select', sessionId: action.session.id},
      );
    case 'session:select':
      return {
        ...state,
        selectedId: action.sessionId,
        turns: [],
        pendingAction: null,
        streamEvents: [],
        routingState: null,
        attachments: [],
        activeRun: null,
      };
    case 'turns:set':
      return {...state, turns: action.turns};
    case 'events:set':
      return {...state, streamEvents: action.events};
    case 'events:append': {
      const seen = new Set(state.streamEvents.map((event) => event.id));
      const appended = action.events.filter((event) => !seen.has(event.id));
      return {...state, streamEvents: [...state.streamEvents, ...appended]};
    }
    case 'events:hydrate':
      return hydratePersistedEvents(state, action.events, action.append === true);
    case 'gates:set':
      return {...state, gates: action.gates};
    case 'managed-ai:set':
      return {...state, managedAi: action.balance};
    case 'input:set':
      return {...state, status: 'composing', input: action.input};
    case 'stream:start':
      return {
        ...state,
        status: 'streaming',
        input: '',
        pendingUser: action.message,
        streamText: '',
        streamEvents: [],
        pendingAction: null,
        routingState: null,
        attachments: [],
        error: null,
      };
    case 'stream:ack':
      return {
        ...state,
        streamEvents: [
          ...state.streamEvents,
          {
            id: `ack:${action.turnId}`,
            type: 'system',
            title: 'Turn accepted',
            detail: action.turnId,
          },
        ],
      };
    case 'stream:route':
      return {
        ...state,
        routingState: {
          route: action.route.route,
          confidence: action.route.confidence,
          reasonCode: action.route.reason_code,
          runApiAllowed: action.route.run_api_allowed,
        },
        streamEvents: [
          ...state.streamEvents,
          {
            id: `route:${action.route.turn_id}`,
            type: 'route',
            title: action.route.route,
            detail: `${Math.round(action.route.confidence * 100)}% · ${action.route.reason_code}`,
          },
        ],
      };
    case 'stream:proposal':
      return {
        ...state,
        status: action.proposal.action.requiresConfirmation
          ? 'confirmationRequired'
          : 'actionProposed',
        pendingAction: {
          sessionId: action.proposal.session_id,
          turnId: action.proposal.turn_id,
          route: action.proposal.route,
          action: action.proposal.action,
          source: 'stream',
        },
        streamEvents: [
          ...state.streamEvents,
          {
            id: `proposal:${action.proposal.turn_id}`,
            type: 'system',
            title: action.proposal.action.type,
            detail: action.proposal.action.confirmationPrompt ?? action.proposal.route,
          },
        ],
      };
    case 'stream:token':
      return {...state, streamText: state.streamText + action.token};
    case 'stream:event':
      return {
        ...state,
        status: action.item.type === 'tool' ? 'toolRunning' : state.status,
        streamEvents: [...state.streamEvents, action.item],
      };
    case 'stream:done':
      return {
        ...state,
        status: state.pendingAction ? 'confirmationRequired' : 'idle',
        pendingUser: '',
        streamText: '',
      };
    case 'stream:stop':
      return {...state, status: 'idle', pendingUser: '', streamText: ''};
    case 'action:clear':
      return {...state, pendingAction: null, status: 'idle'};
    case 'run:submitted':
      return {...state, activeRun: action.runId, pendingAction: null, status: 'runStreaming'};
    case 'attachment:add':
      return {...state, attachments: [...state.attachments, action.attachment]};
    case 'attachment:remove':
      return {
        ...state,
        attachments: state.attachments.filter((a) => a.attachmentId !== action.attachmentId),
      };
    case 'sessions:toggle': {
      const next = !state.sessionsCollapsed;
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('es:agent:sessions-collapsed', next ? '1' : '0');
      }
      return {...state, sessionsCollapsed: next};
    }
    case 'error':
      return {...state, status: 'error', error: action.error};
    case 'error:clear':
      return {...state, error: null, status: state.status === 'error' ? 'idle' : state.status};
    default:
      return state;
  }
}

/**
 * Replays the semantic events emitted by the POST-SSE agent stream through the same state shape
 * as live events. This is intentionally reducer-owned: a browser reconnect must restore routing,
 * proposals, tools, and terminal usage rather than only append opaque activity rows.
 */
function hydratePersistedEvents(
  state: AgentState,
  events: api.AgentActionEvent[],
  append: boolean,
): AgentState {
  let next = state;
  if (!append) next = {...state, streamEvents: []};
  const seenIds = new Set(next.streamEvents.map((item) => item.id));
  const seenSequences = new Set(
    next.streamEvents
      .map((item) => item.streamSequence)
      .filter((sequence): sequence is number => sequence != null),
  );
  for (const event of events ?? []) {
    const payload = parsePersistedPayload(event.payloadJson);
    const streamSequence = event.streamSequence ?? undefined;
    if (streamSequence != null && seenSequences.has(streamSequence)) continue;
    const type = event.type.toUpperCase();
    const turnId = event.turnId ?? String(payload.turn_id ?? payload.turnId ?? '');
    const id = persistedTimelineId(type, event, payload);
    if (seenIds.has(id)) continue;
    if (streamSequence != null) seenSequences.add(streamSequence);
    seenIds.add(id);
    if (type === 'ROUTE_DECISION') {
      next = agentReducer(next, {
        type: 'stream:route',
        route: {
          session_id: event.sessionId,
          turn_id: turnId,
          route: String(payload.route ?? ''),
          confidence: numberValue(payload.confidence),
          reason_code: String(payload.reason_code ?? payload.reasonCode ?? ''),
          run_api_allowed: Boolean(payload.run_api_allowed ?? payload.runApiAllowed),
          requires_confirmation: Boolean(
            payload.requires_confirmation ?? payload.requiresConfirmation,
          ),
        },
      });
      next = replaceLastTimelineId(next, id, streamSequence);
    } else if (type === 'PROPOSAL' && payload.action && typeof payload.action === 'object') {
      next = agentReducer(next, {
        type: 'stream:proposal',
        proposal: {
          session_id: event.sessionId,
          turn_id: turnId,
          route: String(payload.route ?? ''),
          action: payload.action as api.AgentTypedAction,
        },
      });
      next = replaceLastTimelineId(next, id, streamSequence);
    } else if (type === 'TOOL_CALL') {
      next = appendTimeline(next, {
        id,
        streamSequence,
        type: 'tool',
        title: String(payload.tool ?? 'Tool call'),
        detail: String(payload.args ?? ''),
      });
    } else if (type === 'TOOL_RESULT') {
      next = appendTimeline(next, {
        id,
        streamSequence,
        type: 'result',
        title: 'Tool result',
        detail: String(payload.result ?? ''),
      });
    } else if (type === 'TOOL_EVENT') {
      next = appendTimeline(
        next,
        toolEventTimelineItem(payload as unknown as api.AgentStreamToolEvent, id, streamSequence),
      );
    } else if (type === 'TURN_TEXT') {
      next = {...next, streamText: next.streamText + String(payload.text ?? '')};
      next = appendTimeline(next, {
        id,
        streamSequence,
        type: 'system',
        title: 'Assistant text recovered',
        detail: String(payload.text ?? ''),
      });
    } else if (type === 'TURN_DONE') {
      next = appendTimeline(next, {
        id,
        streamSequence,
        type: 'usage',
        title: 'Usage',
        detail: `$${numberValue(payload.cost_usd ?? payload.costUsd).toFixed(4)}`,
      });
      next = {
        ...next,
        status: next.pendingAction ? 'confirmationRequired' : 'idle',
        pendingUser: '',
        streamText: '',
      };
    } else if (
      type === 'STREAM_STARTED' ||
      type === 'STREAM_COMPLETED' ||
      type === 'STREAM_FAILED'
    ) {
      next = appendTimeline(next, {
        id,
        streamSequence,
        type: 'system',
        title: type.split('_').join(' '),
        detail: payload.summary ? String(payload.summary) : undefined,
      });
    } else if (isPersistedActivityType(type)) {
      next = appendTimeline(next, {
        id,
        streamSequence,
        type: 'system',
        title: type.split('_').join(' '),
        detail: String(
          payload.summary ?? payload.reason ?? payload.status ?? event.payloadJson ?? '',
        ),
      });
    }
  }
  return next;
}

function isPersistedActivityType(type: string): boolean {
  return (
    ['SUBAGENT', 'HANDOFF', 'PROVIDER_SWITCH', 'MODEL_SWITCH', 'CONTINUATION'].includes(type) ||
    [
      'SUBAGENT_',
      'HANDOFF_',
      'PROVIDER_',
      'MODEL_',
      'CONTINUATION_',
      'RUN_TASK_WAVE_PLAN',
      'RUN_GOAL_DECISION',
      'RUN_CONTINUATION_DECISION',
      'RUN_CONTINUATION_ACTION',
      'RUN_CONTINUATION_ACTION_SKIPPED',
      'RUN_VALIDATION_EVIDENCE',
    ].some((prefix) => type.startsWith(prefix))
  );
}

function parsePersistedPayload(raw: string | null): Record<string, unknown> {
  if (!raw) return {};
  try {
    const value = JSON.parse(raw);
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}

function numberValue(value: unknown): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function persistedTimelineId(
  type: string,
  event: api.AgentActionEvent,
  payload: Record<string, unknown>,
): string {
  const turnId = event.turnId ?? String(payload.turn_id ?? payload.turnId ?? '');
  const itemId = String(payload.id ?? event.id);
  if (type === 'ROUTE_DECISION') return `route:${turnId}`;
  if (type === 'PROPOSAL') return `proposal:${turnId}`;
  if (type === 'TOOL_CALL') return `tool:${itemId}`;
  if (type === 'TOOL_RESULT') return `result:${itemId}`;
  if (type === 'TOOL_EVENT') return `event:${itemId}`;
  if (type === 'TURN_TEXT') return `text:${turnId}`;
  if (type === 'TURN_DONE') return `usage:${turnId}`;
  return `persisted:${event.streamSequence ?? event.id}`;
}

function appendTimeline(state: AgentState, item: StreamTimelineItem): AgentState {
  if (state.streamEvents.some((existing) => existing.id === item.id)) return state;
  return {...state, streamEvents: [...state.streamEvents, item]};
}

function replaceLastTimelineId(state: AgentState, id: string, streamSequence?: number): AgentState {
  if (state.streamEvents.length === 0) return state;
  const last = state.streamEvents[state.streamEvents.length - 1];
  return {
    ...state,
    streamEvents: [...state.streamEvents.slice(0, -1), {...last, id, streamSequence}],
  };
}

export function sessionTitle(s: api.AgentSessionResponse): string {
  return (s.title || s.projectId || s.id).slice(0, 54);
}

export function actionFromTurn(turn: api.AgentTurnResponse): PendingAction | null {
  if (!turn.proposedActionJson || !turn.actionType) return null;
  if (turn.status !== 'NEEDS_CONFIRMATION') return null;
  try {
    const action = JSON.parse(turn.proposedActionJson) as api.AgentTypedAction;
    return {
      sessionId: turn.sessionId,
      turnId: turn.turnId,
      route: turn.route || turn.intentType || 'IMPLEMENTATION_PROPOSAL',
      action,
      source: 'history',
    };
  } catch {
    return null;
  }
}

export function toMessages(turns: api.AgentTurnResponse[]): LocalMessage[] {
  return turns.flatMap((turn) => {
    const items: LocalMessage[] = [];
    if (turn.userMessage) {
      items.push({
        id: `${turn.turnId}:user`,
        role: 'user',
        content: turn.userMessage,
        status: turn.status,
      });
    }
    const reply = turn.assistantSummary || '';
    if (reply) {
      items.push({
        id: `${turn.turnId}:assistant`,
        role: 'assistant',
        content: reply,
        status: turn.status,
        usage: {
          inputTokens: turn.inputTokens,
          outputTokens: turn.outputTokens,
          reasoningTokens: turn.reasoningTokens,
          costUsd: turn.costUsd,
        },
      });
    }
    return items;
  });
}

export function actionLabel(action: api.AgentTypedAction): string {
  return action.type.replace(/_/g, ' ');
}

export type BarTone = 'ok' | 'working' | 'waiting' | 'error';

/**
 * Honest top-bar status. A pending action or open gate means the agent is
 * blocked on the human ("Waiting for you"), NOT idle — the old statusLabel
 * showed a green "Ready" in exactly that moment, which was misleading.
 */
export function deriveBarStatus(args: {
  status: AgentUiState;
  hasPendingAction: boolean;
  pendingGateCount: number;
  runActive: boolean;
}): {label: string; tone: BarTone} {
  if (args.status === 'error') return {label: 'Error', tone: 'error'};
  if (args.hasPendingAction || args.pendingGateCount > 0)
    return {label: 'Waiting for you', tone: 'waiting'};
  if (args.runActive) return {label: 'Running', tone: 'working'};
  if (
    args.status === 'streaming' ||
    args.status === 'toolRunning' ||
    args.status === 'runStreaming'
  )
    return {label: 'Working', tone: 'working'};
  if (args.status === 'loadingSession') return {label: 'Loading', tone: 'working'};
  if (args.status === 'reconnecting') return {label: 'Reconnecting', tone: 'working'};
  return {label: 'Ready', tone: 'ok'};
}

/** Friendly phase for a run's backend status (transport jargon → plain English). */
export function runPhaseLabel(status?: string | null): string {
  const s = (status || '').toUpperCase();
  if (['QUEUED', 'PENDING', 'SUBMITTED', 'QUEUED_BATCH'].includes(s)) return 'Queued';
  if (['CLASSIFYING', 'PLANNING'].includes(s)) return 'Planning';
  if (s === 'AWAITING_APPROVAL') return 'Waiting for approval';
  // RR-1463 — the state between plan approval and the first worker turn. Without this the
  // card fell through to the default branch and showed the raw enum.
  if (s === 'APPROVAL_RESUMING') return 'Resuming';
  if (['WORKING', 'IN_PROGRESS', 'IN_PROGRESS_BATCH', 'CODING', 'RUNNING'].includes(s))
    return 'Coding';
  if (['COMPILING', 'BUILDING'].includes(s)) return 'Building';
  if (s === 'REVIEWING') return 'Reviewing';
  if (s === 'TESTING') return 'Testing';
  if (['COMPLETE', 'COMPLETED'].includes(s)) return 'Done';
  if (s === 'PARTIAL') return 'Done with warnings';
  if (s === 'FAILED') return 'Failed';
  if (s === 'CANCELLED') return 'Cancelled';
  if (s === 'PAUSED') return 'Paused';
  return s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : 'Running';
}

// RR-1536 — delegates to the one shared run-status vocabulary so this panel cannot drift
// from the plan page and the project rollup again.
export function isTerminalRunStatus(status?: string | null): boolean {
  return isTerminalRunStatusShared(status);
}

export type RunProgress = {
  /** Settled tasks (any terminal task status) — the cursor for "Task N of M". */
  done: number;
  /** Tasks that actually succeeded — the honest progress-bar numerator. */
  succeeded: number;
  /** Tasks that failed or were blocked — a distinct, non-progress outcome. */
  failed: number;
  total: number;
  /** Percent complete computed from SUCCEEDED tasks only. */
  pct: number;
  current: string | null;
};

/**
 * Done/total/percent + the title of the task currently in flight, for the run card.
 *
 * RR-1492 — a FAILED or BLOCKED task is settled but is NOT progress. The bar's
 * percentage is therefore computed from succeeded tasks only, and failed/blocked
 * are exposed separately so a PARTIAL run with failures cannot render as a full,
 * green bar. `done` remains the settled count so the "Task N of M" step cursor
 * still advances past a finished-but-failed task.
 */
export function runProgress(
  tasks?: {status?: string | null; title?: string | null}[] | null,
): RunProgress {
  const list = tasks ?? [];
  const total = list.length;
  const norm = (s?: string | null) => (s || '').toUpperCase();
  const isSucceeded = (s?: string | null) => ['COMPLETE', 'COMPLETED', 'DONE'].includes(norm(s));
  const isFailed = (s?: string | null) => ['FAILED', 'BLOCKED'].includes(norm(s));
  const isSettled = (s?: string | null) => isSucceeded(s) || isFailed(s) || norm(s) === 'PARTIAL';
  const isRunning = (s?: string | null) => ['WORKING', 'IN_PROGRESS', 'RUNNING'].includes(norm(s));
  const succeeded = list.filter((t) => isSucceeded(t.status)).length;
  const failed = list.filter((t) => isFailed(t.status)).length;
  const done = list.filter((t) => isSettled(t.status)).length;
  const current = list.find((t) => isRunning(t.status))?.title ?? null;
  const pct = total > 0 ? Math.round((succeeded / total) * 100) : 0;
  return {done, succeeded, failed, total, pct, current};
}

/** Route for the run card's "Open" action, by run status. */
export function runOpenPath(runId: string, status?: string | null): string {
  const s = (status || '').toUpperCase();
  if (isAwaitingApprovalRunStatus(s)) return `/plan/${runId}`;
  if (isTerminalRunStatus(s) || s === 'PAUSED') return `/result/${runId}`;
  return `/live/${runId}`;
}

const TOOL_LABELS: Record<string, string> = {
  web_search: 'Searched the web',
  web_fetch: 'Read a web page',
  list_recent_runs: 'Listed recent runs',
  get_run_report: 'Read a run report',
  search_repo_files: 'Searched the repo',
  search_code: 'Searched the code',
  list_project_documents: 'Listed documents',
  read_generated_doc: 'Read a document',
  search_project_docs: 'Searched documents',
  get_latest_architecture: 'Read the architecture',
  get_project_memory: 'Read project memory',
  get_tech_debt_summary: 'Checked tech debt',
  list_repos: 'Listed repos',
  list_pull_requests: 'Listed pull requests',
  get_latest_code_review: 'Read the code review',
  submit_run: 'Started a run',
  approve_plan: 'Approved a plan',
};

/** Friendly, human label for a tool name (replaces raw tool/JSON in the chat). */
export function friendlyTool(name: string): string {
  if (!name) return 'Ran a tool';
  return TOOL_LABELS[name] ?? `Ran ${name.replace(/_/g, ' ')}`;
}

export function asText(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

export function pct(used: number, total: number): number {
  if (!Number.isFinite(total) || total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((used / total) * 100)));
}

export function currency(value: number): string {
  if (!Number.isFinite(value) || value < 0) return 'n/a';
  return `$${value.toFixed(4)}`;
}
