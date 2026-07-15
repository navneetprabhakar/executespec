import type {AgentConversationMessage, AgentTurnResponse} from '@/lib/api';
import type {AgentUsageSummary} from './agentState';

export const MAX_CONTEXT_TURNS = 8;

export function totalUsageTokens(usage: AgentUsageSummary): number {
  return usage.inputTokens + usage.outputTokens + usage.reasoningTokens;
}

export function usageFromTurns(turns: AgentTurnResponse[]): AgentUsageSummary {
  return turns.reduce(
    (acc, turn) => ({
      inputTokens: acc.inputTokens + (turn.inputTokens || 0),
      outputTokens: acc.outputTokens + (turn.outputTokens || 0),
      reasoningTokens: acc.reasoningTokens + (turn.reasoningTokens || 0),
      costUsd: acc.costUsd + (turn.costUsd || 0),
    }),
    {inputTokens: 0, outputTokens: 0, reasoningTokens: 0, costUsd: 0},
  );
}

export function sessionUsageContext(usage: AgentUsageSummary): AgentConversationMessage | null {
  const totalTokens = totalUsageTokens(usage);
  if (totalTokens <= 0) return null;
  return {
    role: 'system',
    content:
      `Conversation usage visible in the web UI before this turn: ${totalTokens} total tokens ` +
      `(${usage.inputTokens} input, ${usage.outputTokens} output, ${usage.reasoningTokens} reasoning). ` +
      'Current billing policy: chat turns are included conversation usage and have not charged AI credits. ' +
      'Run credits are charged separately only when a plan-gated run is approved. ' +
      'If the user asks what this chat or session cost in AI credits, answer from these facts; do not say you cannot access session usage.',
  };
}

export function conversationForStream(
  turns: AgentTurnResponse[],
  latestMessage: string,
): AgentConversationMessage[] {
  const usageContext = sessionUsageContext(usageFromTurns(turns));
  const conversation: AgentConversationMessage[] = [];
  if (usageContext) conversation.push(usageContext);
  for (const turn of turns.slice(-MAX_CONTEXT_TURNS)) {
    if (turn.userMessage?.trim()) {
      conversation.push({role: 'user', content: turn.userMessage.trim()});
    }
    if (turn.assistantSummary?.trim()) {
      conversation.push({role: 'assistant', content: turn.assistantSummary.trim()});
    }
  }
  conversation.push({role: 'user', content: latestMessage});
  return conversation;
}
