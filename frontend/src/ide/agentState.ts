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
  | {type: 'error'; error: string};

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
    case 'error':
      return {...state, status: 'error', error: action.error};
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
