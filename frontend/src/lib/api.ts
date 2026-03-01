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
  return res.json();
}

// ── Run types ───────────────────────────────────────────────────────────────

export interface RunSummary {
  runId: string;
  status: string;
  userRequest: string;
  provider: string;
  modelName: string;
  totalTokens: number;
  totalCostUsd: number;
  createdAt: string;
  updatedAt: string;
  skipApproval: boolean;
}

export interface TaskSummary {
  taskId: string;
  title: string;
  status: string;
  provider: string;
  modelName: string;
  tokensUsed: number;
  durationMs: number;
  complexity: string;
  dependencies: string[];
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
  totalTokens: number;
  totalCostUsd: number;
  avgCostPerRun: number;
  avgTokensPerRun: number;
  runsByStatus: Record<string, number>;
  recentCompletedRuns: RunSummary[];
}

export interface PricingRow {
  provider: string;
  model: string;
  inputCostPer1k: number;
  outputCostPer1k: number;
  active: boolean;
}

export interface FeasibilityReport {
  feasible: boolean;
  estimatedCostUsd: number;
  estimatedTokens: number;
  complexity: string;
  taskBreakdown: Array<{ title: string; complexity: string; estimatedTokens: number; estimatedCostUsd: number }>;
  warnings: string[];
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
  tasks: Array<{ taskId: string; title: string; startTime: string; endTime: string; status: string }>;
}

export interface ModelComparisonData {
  models: Array<{ provider: string; model: string; totalRuns: number; totalTokens: number; totalCostUsd: number; avgDurationMs: number }>;
}

// ── Runs ────────────────────────────────────────────────────────────────────

export const getRuns = (status?: string) =>
  req<RunSummary[]>(`/runs${status ? `?status=${status}` : ''}`);

export const getRun = (id: string) =>
  req<RunDetail>(`/runs/${id}`);

export const createRun = (body: { userRequest: string; provider?: string; modelName?: string; skipApproval?: boolean }) =>
  req<{ runId: string }>('/runs', { method: 'POST', body: JSON.stringify(body) });

export const approveRun = (id: string, approved: boolean) =>
  req<void>(`/runs/${id}/approve`, { method: 'POST', body: JSON.stringify({ approved }) });

export const mergeRun = (id: string, merge: boolean) =>
  req<void>(`/runs/${id}/merge`, { method: 'POST', body: JSON.stringify({ merge }) });

export const cancelRun = (id: string) =>
  req<void>(`/runs/${id}`, { method: 'DELETE' });

export const retryTask = (runId: string, taskId: string) =>
  req<void>(`/runs/${runId}/tasks/${taskId}/retry`, { method: 'POST' });

// ── Metrics ─────────────────────────────────────────────────────────────────

export const getMetrics = () => req<MetricsResponse>('/metrics');

export const getModelComparison = (days = 30) =>
  req<ModelComparisonData>(`/model-comparison?days=${days}`);

// ── Pricing ─────────────────────────────────────────────────────────────────

export const getPricing = () => req<PricingRow[]>('/pricing');

export const updatePricing = (provider: string, model: string, body: Partial<PricingRow>) =>
  req<PricingRow>(`/pricing/${provider}/${model}`, { method: 'PUT', body: JSON.stringify(body) });

// ── Feasibility ──────────────────────────────────────────────────────────────

export const feasibility = (body: { userRequest: string; provider?: string; modelName?: string; budgetUsd?: number }) =>
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

export const getFile = (runId: string, filePath: string) =>
  req<{ content: string }>(`/runs/${runId}/files/${encodeURIComponent(filePath)}`);
