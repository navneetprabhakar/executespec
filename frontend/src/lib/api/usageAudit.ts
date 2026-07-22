import {ApiError, BASE, authHeader, parseErrorMessage, req} from './core';

export type UsageAuditDimension =
  | 'user'
  | 'userRole'
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
  userRole: string | null;
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
  userRole: string | null;
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

// RR-1369 — export truncation must not be discarded. The CSV endpoint returns
// X-Export-Truncated / X-Export-Row-Count / X-Export-Row-Limit headers; surface
// them so a partial export is disclosed instead of saved as if it were complete.
export interface ExportMeta {
  truncated: boolean | null;
  rowCount: number | null;
  rowLimit: number | null;
}

function numHeader(response: Response, name: string): number | null {
  const raw = response.headers.get(name);
  if (raw == null) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

async function download(path: string, fallbackName: string): Promise<ExportMeta> {
  const response = await fetch(`${BASE}${path}`, {headers: {...authHeader()}});
  if (!response.ok) {
    const raw = await response.text().catch(() => response.statusText);
    throw new ApiError(response.status, parseErrorMessage(response.status, raw));
  }
  const truncatedHeader = response.headers.get('X-Export-Truncated');
  const meta: ExportMeta = {
    truncated: truncatedHeader == null ? null : truncatedHeader === 'true',
    rowCount: numHeader(response, 'X-Export-Row-Count'),
    rowLimit: numHeader(response, 'X-Export-Row-Limit'),
  };
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
  return meta;
}

export const downloadLlmUsageAuditCsv = (params: LlmUsageAuditParams) =>
  download(
    `/admin/analytics/llm-usage-audit.csv?${query(params, false)}`,
    `llm-usage-${params.from}-to-${params.to}.csv`,
  );

// RR-1369 — the JSON export always requests only page 0 / size 500, so it is a
// bounded "first 500" export, not a full one. Exported so the UI can label it.
export const JSON_EXPORT_ROW_LIMIT = 500;

export const downloadLlmUsageAuditJson = (params: LlmUsageAuditParams) =>
  download(
    `/admin/analytics/llm-usage-audit.json?${query({...params, page: 0, size: JSON_EXPORT_ROW_LIMIT})}`,
    `llm-usage-${params.from}-to-${params.to}.json`,
  );
