import {ApiError, BASE, authHeader, parseErrorMessage, req} from './core';

export type UsageAuditDimension =
  | 'user'
  | 'project'
  | 'feature'
  | 'provider'
  | 'model'
  | 'role'
  | 'run'
  | 'session'
  | 'keySource'
  | 'status';

export interface LlmUsageTotals {
  attempts: number;
  successfulAttempts: number;
  failedAttempts: number;
  missingTerminalAttempts: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  reasoningTokens: number;
  knownCostUsdLowerBound: number;
  unknownCostAttempts: number;
  hasUnknownProviderCost: boolean;
}

export interface LlmUsageRollup {
  userId: string | null;
  userEmail: string | null;
  provider: string | null;
  model: string | null;
  agentRole: string | null;
  feature: string | null;
  projectId: string | null;
  projectName: string | null;
  runId: string | null;
  sessionId: string | null;
  sourceType: string | null;
  sourceId: string | null;
  keySource: string | null;
  attemptStatus: string | null;
  attempts: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  reasoningTokens: number;
  knownCostUsdLowerBound: number;
  unknownCostAttempts: number;
}

export interface LlmUsageAttempt {
  id: string;
  createdAt: string;
  llmExecutionContextId: string | null;
  userId: string | null;
  userEmail: string | null;
  projectId: string | null;
  projectName: string | null;
  runId: string | null;
  sessionId: string | null;
  sourceType: string | null;
  sourceId: string | null;
  taskId: string | null;
  agentRole: string | null;
  feature: string | null;
  stepId: string | null;
  retryOf: string | null;
  correlationId: string;
  modelTier: string;
  logicalCallId: string;
  physicalAttemptId: string;
  parentAttemptId: string | null;
  attemptKind: string;
  attemptOrdinal: number;
  provider: string | null;
  model: string | null;
  keySource: string | null;
  attemptStatus: string | null;
  attemptNumber: number;
  latencyMs: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  reasoningTokens: number;
  knownCostUsd: number | null;
  costStatus: string;
  managedAiCredits: number;
  managedAiProjectionStatus: string;
  errorCode: string | null;
}

export interface LlmUsageAuditReport {
  schemaVersion: number;
  source: string;
  orgId: string;
  window: {
    from: string;
    to: string;
    timezone: string;
    fromInclusive: string;
    toExclusive: string;
  };
  limits: {
    maximumWindowDays: number;
    maximumPageSize: number;
    rollupRowLimit: number;
    rollupsTruncated: boolean;
    csvRowLimit: number;
  };
  page: {number: number; size: number; totalRows: number; totalPages: number; returnedRows: number};
  totals: LlmUsageTotals;
  rollups: LlmUsageRollup[];
  attempts: LlmUsageAttempt[];
  reconciliation: {
    runTotals: {status: string; explanation: string};
    managedAiEvents: {status: string; explanation: string};
    providerInvoiceStatus: string;
  };
}

export interface LlmUsageAuditParams {
  orgId: string;
  from: string;
  to: string;
  timezone: string;
  page?: number;
  size?: number;
}

function query(params: LlmUsageAuditParams, includePage = true): string {
  const search = new URLSearchParams({
    orgId: params.orgId,
    from: params.from,
    to: params.to,
    timezone: params.timezone,
  });
  if (includePage) {
    search.set('page', String(params.page ?? 0));
    search.set('size', String(params.size ?? 100));
  }
  return search.toString();
}

export const getLlmUsageAudit = (params: LlmUsageAuditParams) =>
  req<LlmUsageAuditReport>(`/admin/analytics/llm-usage-audit?${query(params)}`);

async function download(path: string, fallbackName: string): Promise<void> {
  const response = await fetch(`${BASE}${path}`, {headers: {...authHeader()}});
  if (!response.ok) {
    const raw = await response.text().catch(() => response.statusText);
    throw new ApiError(response.status, parseErrorMessage(response.status, raw));
  }
  const disposition = response.headers.get('content-disposition') ?? '';
  const filename = /filename="?([^";]+)"?/i.exec(disposition)?.[1] ?? fallbackName;
  const href = URL.createObjectURL(await response.blob());
  const anchor = document.createElement('a');
  anchor.href = href;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(href);
}

export const downloadLlmUsageAuditCsv = (params: LlmUsageAuditParams) =>
  download(
    `/admin/analytics/llm-usage-audit.csv?${query(params, false)}`,
    `llm-usage-${params.from}-to-${params.to}.csv`,
  );

export const downloadLlmUsageAuditJson = (params: LlmUsageAuditParams) =>
  download(
    `/admin/analytics/llm-usage-audit.json?${query({...params, page: 0, size: 500})}`,
    `llm-usage-${params.from}-to-${params.to}.json`,
  );
