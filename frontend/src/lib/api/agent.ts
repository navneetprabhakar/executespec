import {req} from './core';
export * from './agentStream';

export interface AgentRepoContext {
  cwdName?: string | null;
  gitRemote?: string | null;
  branch?: string | null;
  changedFiles?: string[] | null;
  detectedStack?: string[] | null;
}

export interface AgentClientCapabilities {
  localTools: boolean;
  interactive: boolean;
  canRenderDiff: boolean;
  canRenderTui: boolean;
}

export interface CreateAgentSessionRequest {
  id?: string | null;
  projectId?: string | null;
  runId?: string | null;
  repoRemote?: string | null;
  repoRootHash?: string | null;
  repoContext?: AgentRepoContext | null;
  surface?: 'WEB' | 'CLI' | 'VSCODE' | string | null;
  title?: string | null;
  defaultProvider?: string | null;
  defaultModel?: string | null;
  defaultExecutionMode?: string | null;
  approvalPolicy?: string | null;
}

export interface AgentSessionResponse {
  id: string;
  projectId: string | null;
  runId: string | null;
  repoRemote: string | null;
  surface: string;
  title: string | null;
  status: string;
  defaultProvider: string | null;
  defaultModel: string | null;
  defaultExecutionMode: string | null;
  approvalPolicy: string | null;
  latestLinkedRunId: string | null;
  latestLinkedRunStatus: string | null;
  latestLinkedRunTitle: string | null;
  latestLinkedRunStartedAt: string | null;
  latestLinkedRunCompletedAt: string | null;
  latestLinkedRunPrUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AgentTurnResponse {
  turnId: string;
  sessionId: string;
  sequence: number;
  userMessage: string | null;
  assistantSummary: string | null;
  intentType: string | null;
  intentConfidence: number;
  route: string | null;
  reasonCode: string | null;
  actionType: string | null;
  proposedActionJson: string | null;
  linkedRunId: string | null;
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  costUsd: number;
  status: string;
  createdAt: string;
}

export interface AgentActionDescriptor {
  type: string;
  description: string;
  requiresConfirmation: boolean;
  billingImpact: string;
  requiredFields: string[];
}

export interface AgentCapabilityTool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  requiredTier: string;
  requiredScopes: string[];
  sideEffect: string;
  confirmationRequired: boolean;
  billingImpact: string;
  surfaces: string[];
}

export interface AgentTypedAction {
  type: string;
  arguments: Record<string, unknown>;
  requiresConfirmation: boolean;
  billingImpact: string;
  confirmationPrompt: string | null;
}

export interface AgentIntentDecision {
  intent: string;
  confidence: number;
  needsConfirmation: boolean;
  confirmationPrompt: string | null;
  proposedAction: AgentTypedAction | null;
  response: string | null;
}

export interface AgentActionPlanRequest {
  message: string;
  projectId?: string | null;
  repoContext?: AgentRepoContext | null;
  clientCapabilities?: AgentClientCapabilities | null;
  surface?: 'WEB' | 'CLI' | 'VSCODE' | string | null;
}

export interface AgentActionPlanResponse {
  actionId: string;
  sessionId: string;
  decision: AgentIntentDecision;
  capabilities: AgentCapabilityTool[];
  createdAt: string;
}

export interface AgentActionEventRequest {
  type: string;
  payload?: Record<string, unknown> | null;
}

export interface AgentActionResultRequest {
  status: string;
  summary?: string | null;
  runId?: string | null;
  touchedFiles?: string[] | null;
  verification?: Record<string, unknown> | null;
}

export interface AgentActionRecordResponse {
  actionId: string;
  sessionId: string;
  status: string;
  summary: string | null;
  updatedAt: string;
}

export interface AgentProjectMatch {
  projectId: string;
  projectName: string;
  repoId: string | null;
  repoFullName: string | null;
}

export interface AgentContextResolveRequest {
  gitRemote?: string | null;
  repoName?: string | null;
  branch?: string | null;
  repoContext?: AgentRepoContext | null;
}

export interface AgentContextResolveResponse {
  project: AgentProjectMatch | null;
  reason: string | null;
}

export interface AgentGateDecisionCard {
  runId: string;
  projectId: string | null;
  projectName: string | null;
  title: string | null;
  status: string;
  submittedAt: string | null;
  provider: string | null;
  model: string | null;
  estimatedCostUsd: number | null;
  hardCapUsd: number | null;
  creditsRequired: number | null;
  risk: string | null;
  summary: string | null;
  recommendedAction: string | null;
  tasks: string[];
}

export interface AgentActionEvent {
  id: string;
  sessionId: string;
  actionId: string | null;
  type: string;
  payloadJson: string | null;
  createdAt: string;
}

export interface AgentRunConfirmationRequest {
  sessionId: string;
  turnId: string;
  confirmed: boolean;
  aiProvider?: string | null;
  modelName?: string | null;
  userCostCap?: number | null;
}

export interface AgentRunConfirmationResponse {
  sessionId: string;
  turnId: string;
  runId: string;
  route: string;
  calledRunApi: boolean;
  traceId: string;
  status: string;
}

export const listAgentActions = () => req<AgentActionDescriptor[]>('/agent/actions');

export const listAgentCapabilities = (surface: 'WEB' | 'CLI' | 'VSCODE' | string = 'WEB') =>
  req<AgentCapabilityTool[]>(`/agent/v2/capabilities?surface=${encodeURIComponent(surface)}`);

export const createAgentSession = (body: CreateAgentSessionRequest) =>
  req<AgentSessionResponse>('/agent/sessions', {method: 'POST', body: JSON.stringify(body)});

export interface AgentAttachment {
  attachmentId: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  extractedChars: number;
  preview: string;
}

/**
 * Upload a chat attachment. The server validates type (magic-byte), size, extracts + sanitizes the
 * text, runs the injection/abuse scan, and stores it for the next turn — returning metadata only.
 * Mirrors uploadProjectDocument's auth pattern (multipart + Bearer from access_token).
 */
export const uploadAgentAttachment = async (
  sessionId: string,
  file: File,
): Promise<AgentAttachment> => {
  const form = new FormData();
  form.append('file', file);
  const token = localStorage.getItem('access_token');
  const res = await fetch(`/api/agent/sessions/${encodeURIComponent(sessionId)}/attachments`, {
    method: 'POST',
    headers: token ? {Authorization: `Bearer ${token}`} : {},
    body: form,
  });
  if (!res.ok) {
    let msg = `Upload failed (${res.status})`;
    try {
      const body = await res.json();
      if (body && (body.message || body.error)) msg = body.message || body.error;
    } catch {
      // non-JSON error body — keep the status-based message
    }
    throw new Error(msg);
  }
  return res.json();
};

export const listAgentSessions = (limit = 20) =>
  req<AgentSessionResponse[]>(`/agent/sessions?limit=${encodeURIComponent(String(limit))}`);

export const getAgentSession = (sessionId: string) =>
  req<AgentSessionResponse>(`/agent/sessions/${encodeURIComponent(sessionId)}`);

export const listAgentTurns = (
  sessionId: string,
  options: {limit?: number; before?: string | null} = {},
) => {
  const params = new URLSearchParams();
  params.set('limit', String(options.limit ?? 50));
  if (options.before) params.set('before', options.before);
  return req<AgentTurnResponse[]>(
    `/agent/sessions/${encodeURIComponent(sessionId)}/turns?${params.toString()}`,
  );
};

export const listAgentEvents = (sessionId: string) =>
  req<AgentActionEvent[]>(`/agent/sessions/${encodeURIComponent(sessionId)}/events`);

export const planAgentAction = (sessionId: string, body: AgentActionPlanRequest) =>
  req<AgentActionPlanResponse>(`/agent/v2/sessions/${encodeURIComponent(sessionId)}/actions/plan`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const recordAgentActionEvent = (
  sessionId: string,
  actionId: string,
  body: AgentActionEventRequest,
) =>
  req<AgentActionRecordResponse>(
    `/agent/v2/sessions/${encodeURIComponent(sessionId)}/actions/${encodeURIComponent(
      actionId,
    )}/events`,
    {method: 'POST', body: JSON.stringify(body)},
  );

export const recordAgentActionResult = (
  sessionId: string,
  actionId: string,
  body: AgentActionResultRequest,
) =>
  req<AgentActionRecordResponse>(
    `/agent/v2/sessions/${encodeURIComponent(sessionId)}/actions/${encodeURIComponent(
      actionId,
    )}/result`,
    {method: 'POST', body: JSON.stringify(body)},
  );

export const resolveAgentContext = (body: AgentContextResolveRequest) =>
  req<AgentContextResolveResponse>('/agent/context/resolve', {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const listAgentGates = (limit = 20) =>
  req<AgentGateDecisionCard[]>(`/agent/gates?limit=${encodeURIComponent(String(limit))}`);

export const getAgentGate = (runId: string) =>
  req<AgentGateDecisionCard>(`/agent/gates/${encodeURIComponent(runId)}`);

export const confirmAgentRunProposal = (body: AgentRunConfirmationRequest) =>
  req<AgentRunConfirmationResponse>('/orchestrator/turn/confirm', {
    method: 'POST',
    body: JSON.stringify(body),
  });
