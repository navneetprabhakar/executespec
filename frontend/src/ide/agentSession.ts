import * as api from '@/lib/api';

export type AgentSessionScope = {
  projectId?: string | null;
  runId?: string | null;
};

export function agentSessionTitle(scope: AgentSessionScope): string {
  if (scope.runId) return `Run ${scope.runId} agent`;
  if (scope.projectId) return 'Project agent';
  return 'Web agent';
}

export function agentSessionMatchesScope(
  session: api.AgentSessionResponse,
  scope: AgentSessionScope,
): boolean {
  if (scope.runId) return session.runId === scope.runId;
  if (scope.projectId) return session.projectId === scope.projectId && !session.runId;
  return !session.projectId && !session.runId;
}

export function selectScopedAgentSession(
  sessions: api.AgentSessionResponse[],
  scope: AgentSessionScope,
): api.AgentSessionResponse | null {
  return sessions.find((session) => agentSessionMatchesScope(session, scope)) ?? null;
}

export function webAgentSessionRequest(scope: AgentSessionScope): api.CreateAgentSessionRequest {
  return {
    surface: 'WEB',
    projectId: scope.projectId ?? null,
    runId: scope.runId ?? null,
    title: agentSessionTitle(scope),
    defaultExecutionMode: 'PRODUCTION',
    approvalPolicy: 'CONFIRM_MUTATIONS',
  };
}
