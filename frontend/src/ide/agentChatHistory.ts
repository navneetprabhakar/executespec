import type * as api from '@/lib/api';

export type AgentChatBubble = {role: 'user' | 'assistant'; content: string; ts?: string};

export function agentTurnToChatBubbles(turn: api.AgentTurnResponse): AgentChatBubble[] {
  const rows: AgentChatBubble[] = [];
  if (turn.userMessage) {
    rows.push({role: 'user', content: turn.userMessage, ts: turn.createdAt});
  }
  if (turn.assistantSummary) {
    rows.push({role: 'assistant', content: turn.assistantSummary, ts: turn.createdAt});
  }
  return rows;
}

export function agentTurnToRunChatMessages(turn: api.AgentTurnResponse): api.RunChatMessage[] {
  return agentTurnToChatBubbles(turn).map(({role, content}) => ({role, content}));
}

export function sortAgentChatBubbles(a: AgentChatBubble, b: AgentChatBubble): number {
  return (a.ts || '').localeCompare(b.ts || '');
}
