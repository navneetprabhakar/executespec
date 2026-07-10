import * as api from '@/lib/api';

export type AgentChatTarget = {
  label: string;
  href: string;
};

export const WEB_CHAT_CAPABILITIES: api.AgentClientCapabilities = {
  localTools: false,
  interactive: true,
  // Diff inspection/revert is not implemented in the chat surface yet.
  canRenderDiff: false,
  canRenderTui: false,
};

export function proposalText(actionType: string, target: AgentChatTarget): string {
  return `Action proposed: ${actionType}. Review it in the [${target.label}](${target.href}).`;
}

export async function streamWebAgentChatReply(
  sessionId: string,
  message: string,
  target: AgentChatTarget,
): Promise<string> {
  let reply = '';
  let proposed = false;
  await api.streamAgentTurn(
    sessionId,
    {
      message,
      clientCapabilities: WEB_CHAT_CAPABILITIES,
    },
    {
      onProposal: (proposal) => {
        proposed = true;
        reply += `\n\n${proposalText(proposal.action.type, target)}`;
      },
      onToken: (token) => {
        reply += token.delta;
      },
      onErrorEvent: (event) => {
        throw new Error(event.message || 'Agent stream failed.');
      },
    },
  );
  return (
    reply.trim() ||
    (proposed
      ? `Action proposed. Review it in the [${target.label}](${target.href}).`
      : '(no reply)')
  );
}
