import type {
  AgentStreamDone,
  AgentStreamEvent,
  AgentStreamProposal,
  AgentStreamRouteDecision,
  AgentStreamToolCall,
  AgentStreamToolResult,
} from './agentStream';

export type SemanticTranscriptItem =
  | {kind: 'route'; id: string; route: string; confidence?: number; reasonCode?: string}
  | {kind: 'assistant'; id: string; text: string}
  | {kind: 'tool_call'; id: string; tool: string; args: string}
  | {kind: 'tool_result'; id: string; result: string}
  | {kind: 'proposal'; id: string; action: string; summary?: string; requiresConfirmation: boolean}
  | {kind: 'verification'; id: string; status: 'pending' | 'passed' | 'failed'; detail?: string}
  | {kind: 'completion'; id: string; costUsd?: number; inputTokens?: number; outputTokens?: number}
  | {kind: 'error'; id: string; message: string};

/** Converts transport events into stable customer-facing semantic items. */
export function semanticTranscriptFromEvents(
  events: readonly AgentStreamEvent[],
): SemanticTranscriptItem[] {
  const items: SemanticTranscriptItem[] = [];
  let assistant = '';
  const flush = () => {
    if (assistant) {
      items.push({kind: 'assistant', id: `assistant:${items.length}`, text: assistant});
      assistant = '';
    }
  };
  for (const event of events) {
    switch (event.event) {
      case 'token':
        assistant += (event.data as {delta?: string}).delta ?? '';
        break;
      case 'route_decision': {
        flush();
        const data = event.data as AgentStreamRouteDecision;
        items.push({
          kind: 'route',
          id: `route:${items.length}`,
          route: data.route,
          confidence: data.confidence,
          reasonCode: data.reason_code,
        });
        break;
      }
      case 'tool_call': {
        flush();
        const data = event.data as AgentStreamToolCall;
        items.push({kind: 'tool_call', id: data.id, tool: data.tool, args: data.args});
        break;
      }
      case 'tool_result': {
        const data = event.data as AgentStreamToolResult;
        items.push({kind: 'tool_result', id: `result:${data.id}`, result: data.result});
        break;
      }
      case 'proposal': {
        flush();
        const data = event.data as AgentStreamProposal;
        items.push({
          kind: 'proposal',
          id: `proposal:${items.length}`,
          action: data.action.type,
          summary: data.action.confirmationPrompt ?? undefined,
          requiresConfirmation: data.action.requiresConfirmation,
        });
        break;
      }
      case 'done': {
        flush();
        const data = event.data as AgentStreamDone;
        items.push({
          kind: 'completion',
          id: `completion:${data.turn_id ?? items.length}`,
          costUsd: data.cost_usd,
          inputTokens: data.input_tokens,
          outputTokens: data.output_tokens,
        });
        break;
      }
      case 'error':
        flush();
        items.push({
          kind: 'error',
          id: `error:${items.length}`,
          message: (event.data as {message?: string}).message ?? 'Agent stream failed.',
        });
        break;
      default:
        break;
    }
  }
  flush();
  return items;
}
