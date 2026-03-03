// Typed API client — all backend endpoints

const BASE = '/api';

async function req<T>(url: string, opts?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, {
    headers: { 'Content-Type': 'application/json', ...opts?.headers },
    ...opts,
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${msg}`);
  }
  if (res.status === 204) return undefined as unknown as T;
  // Handle Spring returning 200 OK with an empty body (e.g. approve/merge endpoints)
  const text = await res.text();
  if (!text.trim()) return undefined as unknown as T;
  return JSON.parse(text);
}

/** Fetch a plain-text endpoint (e.g. file content served as text/plain). */
async function reqText(url: string): Promise<string> {
  const res = await fetch(BASE + url);
  if (!res.ok) {
    const msg = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status}: ${msg}`);
  }
  return res.text();
}

// ── Run types ───────────────────────────────────────────────────────────────

export interface RunSummary {
  runId: string;
  status: string;
  userRequest: string;
  plannerModel: string;
  workerModel: string;
  reviewerModel: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  startedAt: string;
  completedAt: string | null;
}

export interface TaskSummary {
  taskId: string;
  title: string;
  status: string;
  assignedModel: string;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  branchName: string | null;
  startedAt: string | null;
  completedAt: string | null;
  readmeUpdated: boolean;
}

export interface RunDetail extends RunSummary {
  tasks: TaskSummary[];
  planJson: string;
  reviewJson: string;
  integrationSource: { sourceType: string; sourceId: string; sourceUrl: string } | null;
}

export interface MetricsResponse {
  totalRuns: number;
  completedRuns: number;
  failedRuns: number;
  activeRuns: number;
  queuedRuns: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

export interface PricingRow {
  provider: string;
  model: string;
  inputCostPer1m: number;
  outputCostPer1m: number;
  notes: string | null;
  updatedAt: string | null;
}

export interface TaskFeasibility {
  taskId: string;
  title: string;
  complexity: string;
  minIterations: number;
  maxIterations: number;
  estimatedLowUsd: number;
  estimatedHighUsd: number;
}

export interface FeasibilityReport {
  verdict: string;  // "FEASIBLE" | "MARGINAL" | "INFEASIBLE" | "ESTIMATED"
  budgetUsd: number | null;
  estimatedLowUsd: number;
  estimatedHighUsd: number;
  tasks: TaskFeasibility[];
}

export interface DeploymentSummary {
  id: number;
  runId: string;
  status: string;
  strategy: string;
  serviceUrl: string;
  createdAt: string;
  expiresAt: string;
}

export interface TimelineData {
  tasks: Array<{ taskId: string; title: string; startedAt: string; completedAt: string; status: string }>;
}

export interface ModelComparisonData {
  models: Array<{ provider: string; model: string; taskCount: number; totalInputTokens: number; totalOutputTokens: number; avgDurationMs: number }>;
}

// ── Runs ────────────────────────────────────────────────────────────────────

export const getRuns = (status?: string) =>
  req<RunSummary[]>(`/runs${status ? `?status=${status}` : ''}`);

export const getRun = (id: string) =>
  req<RunDetail>(`/runs/${id}`);

export const createRun = (body: { userRequest: string; skipApproval?: boolean; modelName?: string }) =>
  req<{ runId: string }>('/runs', { method: 'POST', body: JSON.stringify(body) });

export const approveRun = (id: string, approved: boolean) =>
  req<void>(`/runs/${id}/approve`, { method: 'POST', body: JSON.stringify({ approved }) });

export const mergeRun = (id: string, merge: boolean) =>
  req<void>(`/runs/${id}/merge`, { method: 'POST', body: JSON.stringify({ merge }) });

export const cancelRun = (id: string) =>
  req<void>(`/runs/${id}`, { method: 'DELETE' });

export const retryTask = (runId: string, taskId: string) =>
  req<void>(`/runs/${runId}/tasks/${taskId}/retry`, { method: 'POST' });

export const retryRun = (runId: string) =>
  req<RunSummary>(`/runs/${runId}/retry`, { method: 'POST' });

// ── Metrics ─────────────────────────────────────────────────────────────────

export const getMetrics = () => req<MetricsResponse>('/metrics');

export const getModelComparison = (days = 30) =>
  req<ModelComparisonData>(`/model-comparison?days=${days}`);

// ── Pricing ─────────────────────────────────────────────────────────────────

export const getPricing = () => req<PricingRow[]>('/pricing');

export const updatePricing = (provider: string, model: string, body: Partial<PricingRow>) =>
  req<PricingRow>(`/pricing/${provider}/${model}`, { method: 'PUT', body: JSON.stringify(body) });

// ── Feasibility ──────────────────────────────────────────────────────────────

export const feasibility = (body: { goal: string; provider?: string; model?: string; budgetUsd?: number }) =>
  req<FeasibilityReport>('/feasibility', { method: 'POST', body: JSON.stringify(body) });

// ── Deployments ──────────────────────────────────────────────────────────────

export const getDeployments = () => req<DeploymentSummary[]>('/deployments');

export const createDeployment = (body: { runId: string; strategy?: string }) =>
  req<DeploymentSummary>('/deployments', { method: 'POST', body: JSON.stringify(body) });

export const deleteDeployment = (id: number) =>
  req<void>(`/deployments/${id}`, { method: 'DELETE' });

export const rollbackDeployment = (id: number) =>
  req<DeploymentSummary>(`/deployments/${id}/rollback`, { method: 'POST' });

// ── Integrations ─────────────────────────────────────────────────────────────

export const getIntegrationStatus = () => req<{ enabledSources: string[] }>('/integrations/status');

export const getIntegrationRuns = (source?: string) =>
  req<RunSummary[]>(`/integrations/runs${source ? `?source=${source}` : ''}`);

export const triggerConfluence = (body: { pageId: string; goal: string }) =>
  req<{ runId: string }>('/integrations/confluence/trigger', { method: 'POST', body: JSON.stringify(body) });

// ── Timeline ──────────────────────────────────────────────────────────────────

export const getTimeline = (runId: string) =>
  req<TimelineData>(`/runs/${runId}/timeline`);

// ── Files ─────────────────────────────────────────────────────────────────────

export const getFiles = (runId: string) =>
  req<string[]>(`/runs/${runId}/files`);

/**
 * Trigger a browser download of the workspace zip.
 * Uses a hidden anchor so the browser handles the Content-Disposition header.
 */
export function downloadWorkspace(runId: string): void {
  const a = document.createElement('a');
  a.href = `${BASE}/runs/${runId}/download`;
  a.download = `workspace-${runId.slice(0, 8)}.zip`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

/** File content is served as text/plain — must NOT use res.json(). */
export const getFile = (runId: string, filePath: string) =>
  reqText(`/runs/${runId}/file?path=${encodeURIComponent(filePath)}`);

// ── Messages (historical log) ────────────────────────────────────────────────

export interface MessageResponse {
  fromAgent: string;
  contentSummary: string;
  sentAt: string;
}

export const getMessages = (runId: string) =>
  req<MessageResponse[]>(`/runs/${runId}/messages`);

// ── OpenAPI spec ─────────────────────────────────────────────────────────────

/** Fetch the generated OpenAPI YAML for a run (served as text/plain). */
export const getOpenApiSpec = (runId: string) =>
  reqText(`/runs/${runId}/openapi.yaml`);

// ── Local Docker Preview ──────────────────────────────────────────────────────

export interface LocalPreviewStatus {
  runId: string;
  status: 'IDLE' | 'BUILDING' | 'RUNNING' | 'FAILED' | 'STOPPED';
  url: string | null;
  projectType: string | null;
  errorMessage: string | null;
}

export const getPreviewStatus = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview`);

export const startPreview = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/start`, { method: 'POST' });

export const stopPreview = (runId: string) =>
  req<void>(`/runs/${runId}/preview/stop`, { method: 'DELETE' });

// ── Conversation (pre-flight chat) ────────────────────────────────────────────

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export const conversationChat = (messages: ConversationMessage[]) =>
  req<{ reply: string }>('/conversation', {
    method: 'POST',
    body: JSON.stringify({ messages }),
  });
