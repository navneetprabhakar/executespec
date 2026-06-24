import * as api from '@/lib/api';

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
};

export type StreamTimelineItem = {
  id: string;
  type: 'route' | 'tool' | 'result' | 'run' | 'usage' | 'system';
  title: string;
  detail?: string;
};

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
  | {type: 'session:select'; sessionId: string | null}
  | {type: 'turns:set'; turns: api.AgentTurnResponse[]}
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
  if (['WORKING', 'IN_PROGRESS', 'IN_PROGRESS_BATCH', 'CODING', 'RUNNING'].includes(s))
    return 'Coding';
  if (['COMPILING', 'BUILDING'].includes(s)) return 'Building';
  if (s === 'REVIEWING') return 'Reviewing';
  if (s === 'TESTING') return 'Testing';
  if (['COMPLETE', 'COMPLETED'].includes(s)) return 'Done';
  if (s === 'FAILED') return 'Failed';
  if (s === 'CANCELLED') return 'Cancelled';
  if (s === 'PAUSED') return 'Paused';
  return s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : 'Running';
}

export function isTerminalRunStatus(status?: string | null): boolean {
  return ['COMPLETE', 'COMPLETED', 'FAILED', 'CANCELLED', 'ERROR'].includes(
    (status || '').toUpperCase(),
  );
}

export type RunProgress = {done: number; total: number; pct: number; current: string | null};

/** Done/total/percent + the title of the task currently in flight, for the run card. */
export function runProgress(
  tasks?: {status?: string | null; title?: string | null}[] | null,
): RunProgress {
  const list = tasks ?? [];
  const total = list.length;
  const isDone = (s?: string | null) =>
    ['COMPLETE', 'COMPLETED', 'DONE'].includes((s || '').toUpperCase());
  const isRunning = (s?: string | null) =>
    ['WORKING', 'IN_PROGRESS', 'RUNNING'].includes((s || '').toUpperCase());
  const done = list.filter((t) => isDone(t.status)).length;
  const current = list.find((t) => isRunning(t.status))?.title ?? null;
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;
  return {done, total, pct, current};
}

/** Route for the run card's "Open" action, by run status. */
export function runOpenPath(runId: string, status?: string | null): string {
  const s = (status || '').toUpperCase();
  if (s === 'AWAITING_APPROVAL') return `/plan/${runId}`;
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
  return `$${value.toFixed(2)}`;
}
