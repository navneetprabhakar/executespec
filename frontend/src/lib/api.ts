// Typed API client — all backend endpoints

const BASE = '/api';

/** Returns the stored access token for authenticated requests. */
function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? {Authorization: `Bearer ${token}`} : {};
}

/** Status code → human-readable fallback messages */
const STATUS_MESSAGES: Record<number, string> = {
  400: 'Invalid request. Please check your input and try again.',
  401: 'Your session has expired. Please log in again.',
  402: "You've reached your plan limit. Please upgrade or purchase additional runs.",
  403: "You don't have permission to perform this action.",
  404: 'The requested resource was not found.',
  409: 'This action conflicts with the current state. Please refresh and try again.',
  410: 'This feature is not available in the current harness. It may be coming back — reach out if you need it.',
  422: 'The request could not be processed. Please check your input.',
  429: 'Too many requests. Please wait a moment and try again.',
  500: 'Something went wrong on our end. Please try again later.',
  502: 'The server is temporarily unavailable. Please try again shortly.',
  503: 'The service is temporarily unavailable. Please try again shortly.',
};

/**
 * R-311 (Wave 7) — typed API error so consumers can branch on status.
 *
 * The pre-existing pattern threw `new Error(friendlyMessage)` and lost
 * the HTTP status. AuditPage / AccessDenied both need to render a
 * specific "requires admin role" component on 403 instead of the
 * generic toast. This subclass preserves the status while keeping the
 * existing `errMsg(e)` shape for every existing call site.
 */
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * Extracts a human-readable error message from a backend response.
 * Tries to parse JSON body for 'message' or 'error' fields,
 * falls back to known status messages, then to the raw body.
 */
function parseErrorMessage(status: number, raw: string): string {
  // Try to extract message from JSON body (Spring Boot error format)
  if (raw) {
    try {
      const json = JSON.parse(raw);
      // Spring's BillingException / ResponseStatusException include 'message'
      if (json.message && typeof json.message === 'string') return json.message;
      // Some endpoints return { error: "..." }
      if (
        json.error &&
        typeof json.error === 'string' &&
        json.error !== 'Payment Required' &&
        json.error !== 'Forbidden'
      )
        return json.error;
    } catch {
      // Not JSON — use raw text if it's short and readable
      if (raw.length < 200 && !raw.startsWith('<') && !raw.startsWith('{')) return raw;
    }
  }
  // Fall back to status-based friendly message
  return STATUS_MESSAGES[status] ?? `Request failed (${status}). Please try again.`;
}

export async function req<T>(url: string, opts?: RequestInit): Promise<T> {
  // Audit r5 (P0-2, 2026-05-06): a backend OOM crash during the new-user
  // walkthrough surfaced as `fetch()` rejecting with `TypeError: Failed
  // to fetch`. The catch site (RegisterPage / LoginPage / etc.) printed
  // a generic "Registration failed" instead of the actual root cause —
  // the user couldn't tell whether the product was broken or if they'd
  // mistyped. Wrap the fetch call so network errors surface as a real
  // ApiError with `status: 0` (a sentinel callers can branch on for
  // retry UI). Genuine 4xx/5xx responses still reach the original status
  // path.
  let res: Response;
  try {
    res = await fetch(BASE + url, {
      headers: {'Content-Type': 'application/json', ...authHeader(), ...opts?.headers},
      credentials: 'include', // send refresh_token cookie
      ...opts,
    });
  } catch (e) {
    const reason = e instanceof Error ? e.message : 'unknown';
    throw new ApiError(
      0,
      `Can't reach the server (${reason}). Check your connection and try again.`,
    );
  }
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  if (res.status === 204) return undefined as unknown as T;
  // Handle Spring returning 200 OK with an empty body (e.g. approve/merge endpoints)
  const text = await res.text();
  if (!text.trim()) return undefined as unknown as T;
  return JSON.parse(text);
}

/**
 * Retryable HTTP statuses — callers can use this to decide whether to
 * surface a "Retry" affordance instead of asking the user to reload the
 * page. Audit r5 (P0-2).
 */
export function isRetryableError(e: unknown): boolean {
  if (!(e instanceof ApiError)) return false;
  // 0 = network error (backend unreachable). 5xx = transient server error.
  return e.status === 0 || e.status >= 500;
}

/** Fetch a plain-text endpoint (e.g. file content served as text/plain). */
async function reqText(url: string): Promise<string> {
  const res = await fetch(BASE + url, {
    headers: {...authHeader()}, // include JWT so protected endpoints return 200 not 403
    credentials: 'include',
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  return res.text();
}

// ── Run types ───────────────────────────────────────────────────────────────

export interface RunSummary {
  runId: string;
  status: string;
  userRequest: string | null;
  plannerModel: string;
  workerModel: string;
  reviewerModel: string;
  totalInputTokens: number;
  totalOutputTokens: number;
  startedAt: string;
  completedAt: string | null;
  /** Project this run belongs to, or null for un-grouped runs. */
  projectId?: string | null;
  /** GitHub repo URL populated after a successful post-merge push; null otherwise. */
  repoUrl?: string | null;
  /** Whether this run was started by cloning an existing repo. */
  repoMode?: string | null;
  /** Billing flag: SYSTEM | OWN_KEYS | HYBRID. Null for pre-migration runs. */
  keySource?: string | null;
  /** Comma-separated tech stack IDs derived from the plan (e.g. "react,typescript,nodejs"). Null until planning completes. */
  techStack?: string | null;
  /** Human-readable project name. Null if projectId is null or project was deleted. */
  projectName?: string | null;
  /** Display name of the user who created this run. Null for pre-ownership runs. */
  createdByName?: string | null;
  /** Short 3-8 word title generated by the planner. Null until planning completes. */
  runTitle?: string | null;
  /** One-sentence plan summary from the planner. Null until planning completes. */
  runSummary?: string | null;
  /** "PROTOTYPE" | "PRODUCTION". Null for pre-V40 runs. */
  executionMode?: string | null;
  /** Timestamp when run was archived. Null for active runs. */
  archivedAt?: string | null;
  /** "REALTIME" | "BATCH". Null for pre-V61 runs or runs not yet approved. */
  approvedExecutionMode?: string | null;
  /** AI provider used for this run (ANTHROPIC, OPENAI, GOOGLE). */
  aiProvider?: string | null;
  /** True if this run is a retry of a previous run. */
  isRetry?: boolean;
  /** RunId of the original run this was retried from. Null for original runs. */
  sourceRunId?: string | null;
  /** Which harness produced this run: "v1" (default) or "v2". */
  harnessVersion?: string | null;
  /** v2.5.8 — total USD spent on LLM calls for this run. Null until terminal. */
  actualCostUsd?: number | null;
  /**
   * v2.5.10 — explicit reason for the terminal status so the UI can explain
   * "PARTIAL because X" instead of rendering a silent badge. Values:
   *   CLEAN_COMPLETION | CLEAN_WITH_INTERMEDIATE_WARNINGS |
   *   FINAL_BUILD_FAILED | TESTS_FAILED:<n>_OF_<m> |
   *   REVIEWER_UNRESOLVED | REVIEWER_FAILED |
   *   ALL_TASKS_FAILED | LEGACY_UNKNOWN
   * Null on in-flight runs and on pre-v2.5.10 terminal runs that were never backfilled.
   */
  statusReason?: string | null;
}

export interface TaskSummary {
  taskId: string;
  title: string;
  status: string;
  assignedModel: string;
  /** Actual AI provider used for this task (e.g. "anthropic", "openai"). Null if not yet run. */
  provider?: string | null;
  /** Actual model used for this task (e.g. "claude-sonnet-4-5"). Null if not yet run. */
  model?: string | null;
  inputTokens: number;
  outputTokens: number;
  durationMs: number;
  branchName: string | null;
  startedAt: string | null;
  completedAt: string | null;
  readmeUpdated: boolean;
  /** Present when the task ended in PARTIAL / BLOCKED / FAILED — explains why. */
  failureReason?: string | null;
  /** The planner's objective/description for this task — shown in the expanded card. */
  objective?: string | null;
  /** The worker's summary of what was implemented. */
  completionNotes?: string | null;
  /** Parent task ID if this is a subtask (null for top-level tasks). */
  parentTaskId?: string | null;
  /** 1-based index within parent's subtask list. */
  subtaskIndex?: number | null;
  /** Total number of subtasks in the parent. */
  subtaskTotal?: number | null;
}

// ── Review types (mirrors Java ReviewReport / TaskReview / ReviewIssue) ─────

export interface ReviewIssue {
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  file?: string | null;
  lineHint?: string | null;
  description: string;
}

export interface TaskReview {
  taskId: string;
  acceptanceCriteriaMet: boolean;
  boundaryViolations: string[];
  issues: ReviewIssue[];
  positives: string[];
}

export interface ReviewReport {
  overallConfidence: 'HIGH' | 'MEDIUM' | 'LOW';
  /** V1 field — absent in V2 reports. */
  recommendation?: 'MERGE' | 'REWORK' | 'PARTIAL_MERGE';
  /** V2 verdict. */
  verdict?: 'APPROVED' | 'APPROVED_WITH_NOTES' | 'NEEDS_FIXES' | 'REJECTED';
  /** V2 backward-compat: maps verdict to MERGE/REWORK. */
  recommendationCompat?: string;
  summary: string;
  taskReviews?: TaskReview[];
  issues?: {
    id: string;
    severity: string;
    category: string;
    file: string;
    description: string;
    fixInstruction: string;
    autoFixed: boolean;
    fixSucceeded: boolean;
  }[];
  mergeBlockers: string[];
  postMergeSuggestions: string[];
  readmeUpdated: boolean;
}

/** Token usage for a non-task agent (planner, reviewer, compiler). */
export interface AgentTokenEntry {
  role: string;
  provider: string | null;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
}

export interface RunDetail extends RunSummary {
  tasks: TaskSummary[];
  planJson: string;
  reviewJson: string;
  integrationSource: {sourceType: string; sourceId: string; sourceUrl: string} | null;
  failureReason?: string | null;
  currentGate?: string | null;
  /** Pre-run cost estimate range (USD). Set at plan creation. */
  costEstimateLow?: number | null;
  costEstimateHigh?: number | null;
  /** Actual cost in USD. Null until run completes. */
  actualCostUsd?: number | null;
  /** Number of credits charged for this run. */
  creditsCharged?: number | null;
  /** Hard cap in USD (safety net ceiling). */
  hardCapUsd?: number | null;
  /** v2.6.2-track — hierarchical-planner strategy chosen by SmartClassifierAgent. */
  planStrategy?: 'SINGLE_SHOT' | 'SMALL' | 'MEDIUM' | 'LARGE' | 'LEGACY' | null;
  /** v2.6.2-track — one-line classifier rationale. */
  classifierReasoning?: string | null;
  /** v2.6.2-track — phase-0 (classifier) cost. */
  classifierCostUsd?: number | null;
  classifierInputTokens?: number | null;
  classifierOutputTokens?: number | null;
  /** v2.6.2-track — phase-1 (outline) cost. */
  outlineCostUsd?: number | null;
  outlineInputTokens?: number | null;
  outlineOutputTokens?: number | null;
  /** v2.6.2-track — phase-2 (expansion) cost (sum across parallel calls). */
  expansionCostUsd?: number | null;
  expansionInputTokens?: number | null;
  expansionOutputTokens?: number | null;
  /** v2.6.2-track — phase-3 (coherence) cost. */
  coherenceCostUsd?: number | null;
  coherenceInputTokens?: number | null;
  coherenceOutputTokens?: number | null;
  /** Per-agent (planner/reviewer/compiler) token breakdown. Empty for pre-V19 runs. */
  agentTokens?: AgentTokenEntry[];
  /** Number of apply-suggestion actions used on this run. */
  applySuggestionCount?: number;
  /** URL of the pull request created for existing-repo runs. */
  githubPrUrl?: string | null;
  /** SCM-agnostic PR URL (Bitbucket or GitHub). Falls back to githubPrUrl for backward compat. */
  scmPrUrl?: string | null;
  /** SCM provider that created the PR: 'GITHUB' | 'BITBUCKET'. Null for legacy runs. */
  scmProvider?: string | null;
  /** Pilot R4 — JSON {summary, complexity, filesAffected, newFiles, risks, crossRepoDeps, repoFullNames}. */
  assessmentResult?: string | null;
  /** Pilot R7 — Figma URL captured at submission. */
  figmaUrl?: string | null;
  /** Codemap stats for paid existing-repo runs (null when not generated). */
  codemapFileCount?: number | null;
  codemapRouteCount?: number | null;
  codemapInputTokens?: number | null;
  codemapOutputTokens?: number | null;
  /** "REALTIME" or "BATCH". Null for pre-V61 runs. */
  approvedExecutionMode?: string | null;
  /** Batch credits (with 50% discount). Null if batch not available. */
  batchCredits?: number | null;
  /** True if this run qualifies for batch execution. */
  batchAvailable?: boolean | null;
  /** Credits saved by choosing batch over real-time. */
  creditSavings?: number | null;
  /** Batch progress: tasks completed so far. */
  batchProgressCompleted?: number | null;
  /** Batch progress: total tasks in batch. */
  batchProgressTotal?: number | null;
  /** Number of auto-retry attempts on this run chain (0 = original run). */
  runRetryCount?: number;
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
  cachedInputCostPer1m: number;
  outputCostPer1m: number;
  notes: string | null;
  /**
   * 2026-05-06 — chain-tier from YAML model-matrix (ARCHITECT / CAPABLE / UTILITY). Null for
   * legacy DB rows that pre-date the field. Replaces the hardcoded MODEL_ROLES map.
   */
  chainTier: string | null;
  /** Display label from YAML model-matrix display-label. Null fallback to model id. */
  displayLabel: string | null;
  updatedAt: string | null;
}

// v2.5.13 — TaskFeasibility + FeasibilityReport interfaces retired along with
// FeasibilityEstimator.tsx. Backend `/api/feasibility` returns 410 Gone on V2
// and will stay permanently 410 — use the plan-approval estimate instead.

export interface DeploymentSummary {
  id: string;
  runId: string;
  versionTag: string;
  status: string;
  strategy: string;
  serviceUrl: string;
  revisionName: string;
  createdAt: string;
  ttlExpiresAt: string;
}

// ── Deploy Engine Types ──────────────────────────────────────────────────────

export interface RequiredPermission {
  role: string;
  label: string;
  reason: string;
  required: boolean;
}

export interface PermissionCheck {
  role: string;
  label: string;
  required: boolean;
  granted: boolean;
  detail: string;
}

export interface CredentialSummary {
  id: string;
  gcpProjectId: string;
  serviceAccountEmail: string;
  projectId: string | null;
  createdAt: string;
  validatedAt: string | null;
}

export interface ValidationResult {
  valid: boolean;
  issues: string[];
  gcpProjectId: string | null;
  permissionChecks: PermissionCheck[];
}

export interface DeployAnalysisResult {
  manifest: {
    projectType: string;
    language: string;
    framework: string;
    buildTool: string;
    detectedPort: number;
    healthCheckPath: string;
    databases: string[];
    caches: string[];
    queues: string[];
    storageServices: string[];
    envVars: string[];
    isStateless: boolean;
    hasHealthCheck: boolean;
    hasDockerfile: boolean;
    dockerfilePath: string | null;
  };
  decision: {
    recommendedPath: string;
    availablePaths: Array<{
      path: string;
      label: string;
      description: string;
      available: boolean;
      reason: string;
    }>;
    reasoning: string;
  };
  hasGcpCredentials: boolean;
}

export interface TimelineData {
  tasks: Array<{
    taskId: string;
    title: string;
    startedAt: string;
    completedAt: string;
    status: string;
  }>;
}

export interface ModelComparisonData {
  models: Array<{
    provider: string;
    model: string;
    taskCount: number;
    totalInputTokens: number;
    totalOutputTokens: number;
    avgDurationMs: number;
  }>;
}

// ── Runs ────────────────────────────────────────────────────────────────────

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export const getRuns = (params?: {
  status?: string;
  projectId?: string;
  scope?: string;
  archived?: boolean;
  page?: number;
  size?: number;
}) => {
  const qs = new URLSearchParams();
  if (params?.status) qs.set('status', params.status);
  if (params?.projectId) qs.set('projectId', params.projectId);
  if (params?.scope) qs.set('scope', params.scope);
  if (params?.archived) qs.set('archived', 'true');
  if (params?.page !== undefined) qs.set('page', String(params.page));
  if (params?.size !== undefined) qs.set('size', String(params.size));
  const q = qs.toString();
  return req<PagedResponse<RunSummary>>(`/runs${q ? `?${q}` : ''}`);
};

export const archiveRun = (runId: string) =>
  req<{message: string}>(`/runs/${runId}/archive`, {method: 'POST'});

/**
 * Phase 1.3 #35 — fork a teammate's run. Creates a new run owned by the
 * caller with the source's spec / repo / provider settings copied.
 * Optional `overrides.userRequest` lets the user tweak the spec before
 * the fork lands; otherwise it's an exact copy of the source spec.
 */
export const forkRun = (
  runId: string,
  overrides?: {userRequest?: string; executionMode?: string},
) =>
  req<RunSummary>(`/runs/${runId}/fork`, {
    method: 'POST',
    body: JSON.stringify(overrides ?? {}),
  });

export const getRun = (id: string) => req<RunDetail>(`/runs/${id}`);

export const createRun = (body: {
  userRequest: string;
  executionMode?: 'PROTOTYPE' | 'PRODUCTION';
  skipApproval?: boolean;
  autoMerge?: boolean;
  modelName?: string;
  aiProvider?: string;
  projectId?: string;
  repoMode?: string | null;
  repoUrl?: string | null;
  /** Pilot v3.0 — project-linked repo IDs to clone into workspace/<runId>/repos/<name>/. */
  selectedRepoIds?: string[];
  /** Pilot R7 — Figma URL passed through to planner + scoring. */
  figmaUrl?: string;
  /** v2.5.2 (Phase 4.3) — when true, planner emits test tasks first. */
  tddMode?: boolean;
  /**
   * GA-readiness P3.2 — optional customer "Stop at $X" cost ceiling, USD. The platform
   * cap (credits × $5 × 1.5) still applies as the upper bound. Range $0.10–$1000;
   * server-side @DecimalMin/@DecimalMax rejects out-of-range values with 400.
   */
  userCostCap?: number;
  /**
   * Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode. When true, the run executes
   * normally but RunOrchestrator skips the trailing scmPushService.push call on success.
   * The user can inspect the diff in Run Detail and manually push via the existing
   * /runs/{id}/push-github endpoint if they want to ship. Backend reads this from a
   * 24h Redis flag; not persisted to pipeline_run.
   */
  skipAutoPush?: boolean;
}) => req<{runId: string}>('/runs', {method: 'POST', body: JSON.stringify(body)});

// ── Model matrix ────────────────────────────────────────────────────────────

export interface ModelEntry {
  id: string;
  tier: string;
  label: string;
}
export interface ProviderEntry {
  id: string;
  label: string;
  tag?: string;
  models: ModelEntry[];
}
export interface AvailableModelsResponse {
  providers: ProviderEntry[];
}

export const getAvailableModels = () => req<AvailableModelsResponse>('/models/available');

export const approveRun = (
  id: string,
  approved: boolean,
  creditsConfirmed?: number,
  executionMode?: string,
) =>
  req<void>(`/runs/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({
      approved,
      creditsConfirmed: creditsConfirmed ?? null,
      executionMode: executionMode ?? null,
    }),
  });

// v2.5.12 — V1-only endpoints removed (clarifyRun, mergeRun, retryRun,
// reopenMergeGate, implementSuggestions, reworkRun, editAndRestartRun).
// They returned HTTP 410 Gone on V2 runs per frontend/CLAUDE.md; surfacing
// "410" to users was worse UX than hiding the actions. pauseRun / resumeRun
// kept because V2 full-replay recovery is on the roadmap
// (docs/plans/harness-v2-remaining-work.md Gap 2) and they'll light back up
// when that lands.

export const cancelRun = (id: string) => req<void>(`/runs/${id}`, {method: 'DELETE'});

export const retryTask = (runId: string, taskId: string) =>
  req<void>(`/runs/${runId}/tasks/${taskId}/retry`, {method: 'POST'});

export const pauseRun = (runId: string) => req<void>(`/runs/${runId}/pause`, {method: 'POST'});

export const resumeRun = (runId: string) => req<void>(`/runs/${runId}/resume`, {method: 'POST'});

// ── Metrics ─────────────────────────────────────────────────────────────────

export const getMetrics = (days = 0) =>
  req<MetricsResponse>(`/metrics${days > 0 ? `?days=${days}` : ''}`);

export const getModelComparison = (days = 30) =>
  req<ModelComparisonData>(`/model-comparison?days=${days}`);

// ── User Runs Summary ───────────────────────────────────────────────────────

export interface UserRunStat {
  userId: string;
  displayName: string;
  runCount: number;
  completedCount: number;
  failedCount: number;
  totalInputTokens: number;
  totalOutputTokens: number;
}

export interface UserRunsSummaryData {
  users: UserRunStat[];
}

export const getUserRunsSummary = (days = 30) =>
  req<UserRunsSummaryData>(`/user-runs-summary?days=${days}`);

// ── Pricing ─────────────────────────────────────────────────────────────────

export const getPricing = () => req<PricingRow[]>('/pricing');

export const updatePricing = (provider: string, model: string, body: Partial<PricingRow>) =>
  req<PricingRow>(`/pricing/${provider}/${model}`, {method: 'PUT', body: JSON.stringify(body)});

// ── Live cost ───────────────────────────────────────────────────────────────

export const getLiveCost = (runId: string) =>
  req<{runId: string; costUsd: number; inputTokens?: number; outputTokens?: number}>(
    `/runs/${runId}/cost`,
  );

// v2.5.13 — feasibility() client retired; POST /api/feasibility is permanently
// 410 Gone on V2. Use the plan-approval cost estimate
// (RunDetail.costEstimateLow/High) instead.

// ── Deployments ──────────────────────────────────────────────────────────────

export const getDeployments = () => req<DeploymentSummary[]>('/deployments');

export const createDeployment = (body: {runId: string; strategy?: string}) =>
  req<DeploymentSummary>('/deployments', {method: 'POST', body: JSON.stringify(body)});

export const deleteDeployment = (id: string) => req<void>(`/deployments/${id}`, {method: 'DELETE'});

export const rollbackDeployment = (id: string) =>
  req<DeploymentSummary>(`/deployments/${id}/rollback`, {method: 'POST'});

// ── Deploy Engine ────────────────────────────────────────────────────────────

export const analyzeForDeploy = (runId: string) =>
  req<DeployAnalysisResult>(`/deployments/analyze?runId=${encodeURIComponent(runId)}`, {
    method: 'POST',
  });

export const confirmDeploy = (body: {
  runId: string;
  deploymentPath: string;
  region?: string;
  envVars?: Record<string, string>;
  manifestJson?: string;
  healthCheckPath?: string;
  appPort?: number;
}) => req<DeploymentSummary>('/deployments/confirm', {method: 'POST', body: JSON.stringify(body)});

export const engineRollback = (id: string) =>
  req<void>(`/deployments/${id}/engine-rollback`, {method: 'POST'});

export const engineDestroy = (id: string) =>
  req<void>(`/deployments/${id}/engine-destroy`, {method: 'DELETE'});

export const getDeploymentDetail = (id: string) => req<DeploymentSummary>(`/deployments/${id}`);

// ── GCP Credentials ──────────────────────────────────────────────────────────

export const getRequiredPermissions = () =>
  req<RequiredPermission[]>('/credentials/gcp/required-permissions');

export const storeGcpCredential = (json: string, projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<CredentialSummary>(`/credentials/gcp${qs}`, {
    method: 'POST',
    body: json,
  });
};

export const getGcpCredential = (projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<CredentialSummary | null>(`/credentials/gcp${qs}`);
};

export const listGcpCredentials = () => req<CredentialSummary[]>('/credentials/gcp/all');

export const validateGcpCredential = (projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<ValidationResult>(`/credentials/gcp/validate${qs}`);
};

export const deleteGcpCredential = (projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<{message: string}>(`/credentials/gcp${qs}`, {method: 'DELETE'});
};

// ── Integrations ─────────────────────────────────────────────────────────────

export const getIntegrationStatus = () => req<{enabledSources: string[]}>('/integrations/status');

export const getIntegrationRuns = (source?: string) =>
  req<RunSummary[]>(`/integrations/runs${source ? `?source=${source}` : ''}`);

export const triggerConfluence = (body: {pageId: string; goal: string}) =>
  req<{runId: string}>('/integrations/confluence/trigger', {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const triggerJira = (body: {issueKey: string; goal?: string}) =>
  req<{runId: string}>('/integrations/jira/trigger', {method: 'POST', body: JSON.stringify(body)});

// ── Slack integration ─────────────────────────────────────────────────────────

export interface SlackStatus {
  connected: boolean;
  teamName: string | null;
  teamId: string | null;
  installedBy: string | null;
  channelCount: number;
  installedAt: string | null;
}

export interface SlackChannel {
  channelId: string;
  channelName: string;
}

export interface ProjectSlackChannelDto {
  channelId: string;
  channelName: string;
  verbosity: 'MINIMAL' | 'STANDARD' | 'VERBOSE';
}

export const getSlackStatus = () => req<SlackStatus>('/integrations/slack/status');

export const getSlackInstallUrl = () => req<{installUrl: string}>('/integrations/slack/install');

export const disconnectSlack = () =>
  req<void>('/integrations/slack/disconnect', {method: 'DELETE'});

export const listSlackChannels = () => req<SlackChannel[]>('/integrations/slack/channels');

export const getProjectSlackChannels = (projectId: string) =>
  req<ProjectSlackChannelDto[]>(`/integrations/slack/projects/${projectId}/channels`);

export const addProjectSlackChannel = (
  projectId: string,
  channelId: string,
  channelName: string,
  verbosity: string,
) =>
  req<ProjectSlackChannelDto>(`/integrations/slack/projects/${projectId}/channels`, {
    method: 'POST',
    body: JSON.stringify({channelId, channelName, verbosity}),
  });

export const removeProjectSlackChannel = (projectId: string, channelId: string) =>
  req<void>(`/integrations/slack/projects/${projectId}/channels/${channelId}`, {method: 'DELETE'});

export const updateSlackChannelVerbosity = (
  projectId: string,
  channelId: string,
  verbosity: string,
) =>
  req<void>(`/integrations/slack/projects/${projectId}/channels/${channelId}/verbosity`, {
    method: 'PUT',
    body: JSON.stringify({verbosity}),
  });

export const sendSlackTestMessage = (channelId: string) =>
  req<{ok: boolean; message: string}>('/integrations/slack/test', {
    method: 'POST',
    body: JSON.stringify({channelId}),
  });

// ── Timeline ──────────────────────────────────────────────────────────────────

export const getTimeline = (runId: string) => req<TimelineData>(`/runs/${runId}/timeline`);

// ── Files ─────────────────────────────────────────────────────────────────────

export const getFiles = (runId: string) => req<string[]>(`/runs/${runId}/files`);

/**
 * 2026-05-02 — file listing with per-path git-derived change status. Multi-repo aware (the
 * backend runs `git status --porcelain` per `repos/<name>/` checkout). Lets the run-detail
 * file tree decorate paths with A/M badges so the user can see at a glance which files the
 * worker has added or modified.
 */
export type FileChangeStatus = 'ADDED' | 'MODIFIED' | 'UNCHANGED';

export interface FileWithStatus {
  path: string;
  status: FileChangeStatus;
}

export const getFilesWithStatus = (runId: string) =>
  req<FileWithStatus[]>(`/runs/${runId}/files/status`);

/**
 * Trigger a browser download of the workspace zip.
 * Uses fetch with auth header, then creates an object URL for the browser download.
 */
export async function downloadWorkspace(runId: string): Promise<void> {
  const res = await fetch(`${BASE}/runs/${runId}/download`, {
    headers: {...authHeader()},
  });
  if (!res.ok) throw new Error(`Download failed: ${res.status}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `workspace-${runId.slice(0, 8)}.zip`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export const pushToGitHub = (runId: string, repoMode = 'NEW') =>
  req<{status: string; repoUrl: string}>(`/runs/${runId}/push-github?repoMode=${repoMode}`, {
    method: 'POST',
  });

/** SCM-agnostic push — routes to the user's configured SCM provider (GitHub or Bitbucket). */
export const pushToScm = (runId: string) =>
  req<{status: string; repoUrl: string}>(`/runs/${runId}/push-scm`, {method: 'POST'});

/** File content is served as text/plain — must NOT use res.json(). */
export const getFile = (runId: string, filePath: string) =>
  reqText(`/runs/${runId}/file?path=${encodeURIComponent(filePath)}`);

/**
 * 2026-05-02 — base + head content for a single file so the run-detail UI can render a Monaco
 * `DiffEditor`. ADDED files come back with empty `base`; deleted files have empty `head`.
 * `status` mirrors `FileChangeStatus` (ADDED/MODIFIED/UNCHANGED).
 */
export interface FileDiffPayload {
  path: string;
  base: string;
  head: string;
  status: FileChangeStatus;
}

export const getFileDiff = (runId: string, filePath: string) =>
  req<FileDiffPayload>(`/runs/${runId}/file/diff?path=${encodeURIComponent(filePath)}`);

// ── Messages (historical log) ────────────────────────────────────────────────

export interface MessageResponse {
  fromAgent: string;
  contentSummary: string;
  sentAt: string;
}

export const getMessages = (runId: string) => req<MessageResponse[]>(`/runs/${runId}/messages`);

// ── Contact ─────────────────────────────────────────────────────────────────

export const submitContactForm = (subject: string, body: string) =>
  req<{message: string}>('/contact', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({subject, body}),
  });

// ── OpenAPI spec ─────────────────────────────────────────────────────────────

/** Fetch the generated OpenAPI YAML for a run (served as text/plain). */
export const getOpenApiSpec = (runId: string) => reqText(`/runs/${runId}/openapi.yaml`);

/** Probe the preview container for API documentation endpoints (server-side proxy). */
export interface ApiDocsProbeResult {
  found: boolean;
  path: string | null;
  contentType: string | null; // 'json' | 'html' | 'other'
}
export const probeApiDocs = (runId: string): Promise<ApiDocsProbeResult> =>
  req<ApiDocsProbeResult>(`/runs/${runId}/preview/probe-api-docs`);

// ── Cloud Run Preview ─────────────────────────────────────────────────────────

export interface LocalPreviewStatus {
  runId: string;
  status:
    | 'IDLE'
    | 'ANALYZING'
    | 'BUILDING'
    | 'PUSHING'
    | 'DEPLOYING'
    | 'STARTING'
    | 'RUNNING'
    | 'FAILED'
    | 'STOPPED'
    | 'TIMED_OUT';
  url: string | null;
  projectType: string | null;
  databaseType: string | null;
  errorMessage: string | null;
  backendUrl: string | null;
  frontendUrl: string | null;
  expiresAt: string | null;
  ttlRemainingSeconds: number;
  previewStartCount: number;
  maxPreviewStarts: number;
  buildOutput: string | null;
  dockerfile: string | null;
}

export interface PreviewLogs {
  runId: string;
  lines: string[];
}

export interface PreviewAnalysis {
  databaseType: string | null;
  requiredEnvVars: {
    name: string;
    category: 'SECRET' | 'DATABASE' | 'STANDARD';
    source: string;
    defaultValue: string | null;
  }[];
}

export const analyzePreview = (runId: string) =>
  req<PreviewAnalysis>(`/runs/${runId}/preview/analyze`);

export const getPreviewStatus = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/status`);

export const startPreview = (runId: string, envVars?: Record<string, string>) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/start`, {
    method: 'POST',
    body: envVars && Object.keys(envVars).length > 0 ? JSON.stringify({envVars}) : undefined,
  });

export const stopPreview = (runId: string) =>
  req<void>(`/runs/${runId}/preview/stop`, {method: 'DELETE'});

export const rebuildPreview = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/rebuild`, {method: 'POST'});

export const getPreviewLogs = (
  runId: string,
  lines = 0,
  filter: 'all' | 'backend' | 'frontend' | 'db' = 'all',
) => req<PreviewLogs>(`/runs/${runId}/preview/logs?lines=${lines}&filter=${filter}`);

export interface ExecResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

export const execPreviewCommand = (runId: string, cmd: string, args: string[] = []) =>
  req<ExecResult>(`/runs/${runId}/preview/exec`, {
    method: 'POST',
    body: JSON.stringify({cmd, args}),
  });

// ── Conversation (pre-flight chat) ────────────────────────────────────────────

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export const conversationChat = (messages: ConversationMessage[]) =>
  req<{reply: string}>('/conversation', {
    method: 'POST',
    body: JSON.stringify({messages}),
  });

// ── Post-run conversation & iteration ──────────────────────────────────────────

/** A single message in a post-run conversation. */
export interface RunChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/** Response from POST /api/runs/{id}/chat */
export interface ChatResponse {
  reply: string;
  /** Number of user messages sent so far. */
  messageCount: number;
  /** Maximum user messages allowed. */
  maxMessages: number;
}

/** Returns the full conversation history for a completed run. */
export const getRunChat = (runId: string) => req<RunChatMessage[]>(`/runs/${runId}/chat`);

/** Sends a user message and returns the assistant reply. */
export const sendRunChatMessage = (runId: string, message: string) =>
  req<ChatResponse>(`/runs/${runId}/chat`, {
    method: 'POST',
    body: JSON.stringify({message}),
  });

/** Creates a new iteration run that starts from the source run's codebase. */
export const iterateRun = (runId: string, userRequest: string) =>
  req<{runId: string}>(`/runs/${runId}/iterate`, {
    method: 'POST',
    body: JSON.stringify({userRequest}),
  });

// ── Apply Suggestion (Chat Copilot / Review) ──────────────────────────────

export interface ApplyResult {
  taskCount: number;
  tasks: {taskId: string; title: string}[];
}

/** Apply a chat copilot suggestion or review fixes to an existing run. */
export const applyToRun = (
  runId: string,
  suggestion: string,
  source: 'COPILOT' | 'REVIEW' = 'COPILOT',
) =>
  req<ApplyResult>(`/runs/${runId}/apply`, {
    method: 'POST',
    body: JSON.stringify({suggestion, source}),
  });

// ── Auth types & endpoints ────────────────────────────────────────────────────

export interface AuthUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  globalRole: 'SUPER_ADMIN' | 'FINANCE' | 'USER';
  // Tier-coherence: 4-tier ladder. Legacy MAX was renamed to TEAM in V141
  // and the JAR no longer emits MAX. Type narrowed to match backend reality.
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';
  status: string;
  mustChangePassword: boolean;
  emailVerified: boolean;
  orgId: string | null;
  orgRole: 'ORG_ADMIN' | 'DEVELOPER' | 'BILLING_ADMIN' | null;
  /** GitHub username or org name the user wants repos pushed under. */
  githubOwner?: string | null;
  /** True if the user has a GitHub PAT saved (raw PAT is never returned). */
  hasGithubPat?: boolean;
  // AI provider key flags
  hasOpenAiKey?: boolean;
  hasAnthropicKey?: boolean;
  hasGeminiKey?: boolean;
  // JIRA
  jiraBaseUrl?: string | null;
  jiraEmail?: string | null;
  hasJiraToken?: boolean;
  // Confluence
  confluenceBaseUrl?: string | null;
  confluenceEmail?: string | null;
  hasConfluenceToken?: boolean;
  // Bitbucket
  bitbucketUsername?: string | null;
  hasBitbucketAppPassword?: boolean;
  bitbucketWorkspace?: string | null;
  bitbucketRepoSlug?: string | null;
  // UI preference
  // R-190 (post-cutover) — Vibe view-mode removed. Type narrowed to
  // 'pro' | 'project'. Any persisted 'vibe' value from V48-era users is
  // normalized to 'pro' at the parse boundary by `normalizeAuthUser` so
  // existing rows stay readable without a backend migration. The backend
  // column still accepts 'vibe' for write-compat (no V145 migration is
  // shipped here per the explicit "frontend-normalize only" decision).
  viewMode?: 'pro' | 'project';
}

/**
 * R-190 (post-cutover) — coerce legacy `view_mode='vibe'` rows to 'pro'.
 * Call this on every AuthUser DTO that crosses the API boundary so the
 * narrowed `viewMode?: 'pro' | 'project'` type matches what the rest of
 * the frontend ever sees.
 */
export function normalizeAuthUser<T extends {viewMode?: unknown}>(u: T): T {
  if (u && (u as {viewMode?: unknown}).viewMode === 'vibe') {
    return {...u, viewMode: 'pro' as const};
  }
  return u;
}

export interface LoginResponse {
  accessToken: string;
  user: AuthUser;
}

/** Wave 2.1 — registration payload with optional attribution fields. */
export interface RegisterPayload {
  email: string;
  firstName: string;
  lastName: string;
  password: string;
  signupSource?: string;
  utmSource?: string;
  utmCampaign?: string;
}

export const authRegister = (body: RegisterPayload) =>
  req<AuthUser>('/auth/register', {method: 'POST', body: JSON.stringify(body)});

// Wave 2.2 — onboarding (sandbox + sample specs on signup)
export interface SampleSpecDto {
  id: string;
  title: string;
  category: string;
  complexity: string;
  preferredProvider: string;
  projectType: string;
}

export interface PickSampleResponse {
  projectId: string;
  spec: string;
  projectType: string;
  preferredProvider: string;
}

export interface SandboxStatus {
  sandboxProjectId: string;
  hasSandbox: boolean;
}

export const listOnboardingSamples = () => req<SampleSpecDto[]>('/onboarding/sample-specs');

export const pickOnboardingSample = (sampleId: string) =>
  req<PickSampleResponse>('/onboarding/pick-sample', {
    method: 'POST',
    body: JSON.stringify({sampleId}),
  });

export const getSandboxStatus = () => req<SandboxStatus>('/onboarding/sandbox-status');

export const authLogin = (body: {email: string; password: string}) =>
  req<LoginResponse>('/auth/login', {method: 'POST', body: JSON.stringify(body)});

export const authRefresh = () => req<LoginResponse>('/auth/refresh', {method: 'POST'});

export const authLogout = () => req<void>('/auth/logout', {method: 'DELETE'});

export const authMe = () => req<AuthUser>('/auth/me');

export const getMyPermissions = () =>
  req<import('@/hooks/useTierPermissions').TierPermissions>('/auth/me/permissions');

// [F-29] (audit Sprint 1 PR-2, 2026-04-30) — verify-email returns
// LoginResponse-shaped payload now (auto-session). Caller stores
// `accessToken` and redirects, no password re-entry.
export const authVerifyEmail = (token: string) =>
  req<{accessToken: string; user: AuthUser}>(`/auth/verify-email?token=${token}`);

export const authResendVerification = (email: string) =>
  req<{message: string}>('/auth/resend-verification', {
    method: 'POST',
    body: JSON.stringify({email}),
  });

export const authForgotPassword = (email: string) =>
  req<{message: string}>('/auth/forgot-password', {method: 'POST', body: JSON.stringify({email})});

export const authResetPassword = (token: string, newPassword: string) =>
  req<{message: string}>('/auth/reset-password', {
    method: 'POST',
    body: JSON.stringify({token, newPassword}),
  });

export const authChangePassword = (currentPassword: string | null, newPassword: string) =>
  req<{message: string}>('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({currentPassword, newPassword}),
  });

/**
 * Saves the user's GitHub PAT and owner (username or org).
 * Pass empty strings to clear the credentials.
 */
export const updateGithubSettings = (githubPat: string, githubOwner: string) =>
  req<AuthUser>('/auth/settings/github', {
    method: 'PUT',
    body: JSON.stringify({githubPat, githubOwner}),
  });

/** Saves per-user AI provider API keys. Pass empty string to clear. */
export const updateAiKeySettings = (
  openAiApiKey: string,
  anthropicApiKey: string,
  geminiApiKey: string,
) =>
  req<AuthUser>('/auth/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({openAiApiKey, anthropicApiKey, geminiApiKey}),
  });

/** Saves per-user JIRA credentials. Pass empty strings to clear. */
export const updateJiraSettings = (jiraBaseUrl: string, jiraEmail: string, jiraApiToken: string) =>
  req<AuthUser>('/auth/settings/jira', {
    method: 'PUT',
    body: JSON.stringify({jiraBaseUrl, jiraEmail, jiraApiToken}),
  });

/** Saves per-user Confluence credentials. Pass empty strings to clear. */
export const updateConfluenceSettings = (
  confluenceBaseUrl: string,
  confluenceEmail: string,
  confluenceApiToken: string,
) =>
  req<AuthUser>('/auth/settings/confluence', {
    method: 'PUT',
    body: JSON.stringify({confluenceBaseUrl, confluenceEmail, confluenceApiToken}),
  });

/** Saves per-user Bitbucket credentials. Pass empty strings to clear. */
export const updateBitbucketSettings = (
  username: string,
  appPassword: string,
  workspace: string,
  repoSlug: string,
) =>
  req<AuthUser>('/auth/settings/bitbucket', {
    method: 'PUT',
    body: JSON.stringify({username, appPassword, workspace, repoSlug}),
  });

/** Tests Bitbucket credentials by calling the Bitbucket API. */
export const validateBitbucket = (
  username: string,
  appPassword: string,
  workspace: string,
  repoSlug: string,
) =>
  req<ValidationResult>('/auth/settings/bitbucket/validate', {
    method: 'POST',
    body: JSON.stringify({username, appPassword, workspace, repoSlug}),
  });

/** Updates the user's UI view mode preference. R-190 (post-cutover) —
 *  the legacy 'vibe' option is gone; only 'pro' and 'project' are valid
 *  client-side. Backend still accepts 'vibe' for back-compat. */
export const updateViewMode = (viewMode: 'pro' | 'project') =>
  req<AuthUser>('/auth/settings/view-mode', {
    method: 'PUT',
    body: JSON.stringify({viewMode}),
  });

// ── Credential Validation ──────────────────────────────────────────────────────

export interface ValidationResult {
  valid: boolean;
  message: string;
}

/** Tests a GitHub PAT by calling the GitHub API. */
export const validateGitHub = (pat: string) =>
  req<ValidationResult>('/auth/settings/github/validate', {
    method: 'POST',
    body: JSON.stringify({pat}),
  });

/** Phase 1.2 #24 — Tests a BYOK AI provider key by pinging the provider's models endpoint. */
export const validateAiKey = (provider: 'OPENAI' | 'ANTHROPIC' | 'GEMINI', apiKey: string) =>
  req<ValidationResult>('/auth/settings/ai-keys/validate', {
    method: 'POST',
    body: JSON.stringify({provider, apiKey}),
  });

/** M-005 (Wave 1) — GitHub App install URL + status. Org-admin gated. */
export interface GitHubAppStatus {
  installed: boolean;
  installationId: string;
  installedAt: string;
}
export interface GitHubAppInstallUrl {
  installUrl: string;
  appName: string;
}
export const getGitHubAppStatus = () => req<GitHubAppStatus>('/auth/github-app/status');
export const getGitHubAppInstallUrl = () =>
  req<GitHubAppInstallUrl>('/auth/github-app/install-url');
export const uninstallGitHubApp = () => req<void>('/auth/github-app/install', {method: 'DELETE'});

/** Tests JIRA credentials by calling the JIRA REST API. */
export const validateJira = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/auth/settings/jira/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Tests Confluence credentials by calling the Confluence REST API. */
export const validateConfluence = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/auth/settings/confluence/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

// ── Org endpoints ─────────────────────────────────────────────────────────────

export interface OrgDto {
  id: string;
  name: string;
  domainPatterns: string;
  tier: string;
  status: string;
  monthlyRunCount: number;
  /** v2.6.0 — TEAM-tier seat count (shared pool). 1 for non-TEAM orgs. */
  seatCount?: number;
  isPaymentVerified: boolean;
  monthlyRunLimitOverride: number | null;
  apiKeysProvided: boolean;
  planExpiresAt: string | null;
  createdAt: string;
  // Admin contact
  adminEmail?: string | null;
  // Per-org AI provider key flags (raw keys never returned)
  hasAnthropicKey?: boolean;
  hasOpenAiKey?: boolean;
  hasGeminiKey?: boolean;
  // Key source mode & provider restrictions
  keySourceMode?: string;
  enabledProviders?: string[] | null;
  // JIRA integration flags (raw credentials never returned)
  hasJiraCredentials?: boolean;
  jiraBaseUrl?: string | null;
  // Confluence integration flags (raw credentials never returned)
  hasConfluenceCredentials?: boolean;
  confluenceBaseUrl?: string | null;
}

export interface OrgMemberDto {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  orgRole: string;
  joinedAt: string;
}

export interface OrgInvitation {
  id: string;
  orgId: string;
  invitedEmail: string;
  orgRole: string;
  status: string;
  expiresAt: string;
  createdAt: string;
}

export const getMyOrg = () => req<OrgDto>('/org/me');

export const createOrg = (body: {name: string; domains: string[]; adminEmail: string}) =>
  req<OrgDto>('/org', {method: 'POST', body: JSON.stringify(body)});

export const updateOrg = (body: {name?: string; domains?: string[]}) =>
  req<OrgDto>('/org/me', {method: 'PUT', body: JSON.stringify(body)});

// v2.6.0 — TEAM tier seat management
export interface SeatsAdjustResponse {
  previousSeats: number;
  newSeats: number;
  activeMembers: number;
  message: string;
}

// v2.6.0 — tier lifecycle (admin-only)
export type LifecycleState = 'LISTED' | 'HIDDEN' | 'SUNSETTING';

export interface TierLifecycleDto {
  tier: string;
  displayName: string;
  state: LifecycleState;
  changedAt: string | null;
  changedBy: string | null;
  reason: string | null;
  activeSubscribers: number;
  /** Monthly recurring revenue in USD cents. Divide by 100 for dollar amount. */
  monthlyRecurringRevenueUsdCents: number;
}

export interface TierLifecycleUpdateRequest {
  state: LifecycleState;
  reason: string;
  /** Required when state='SUNSETTING' — must match tier name. */
  confirmTierName?: string;
}

export const fetchTiersLifecycle = () => req<TierLifecycleDto[]>('/admin/tiers');

export const updateTierLifecycle = (tier: string, body: TierLifecycleUpdateRequest) =>
  req<unknown>(`/admin/tiers/${tier}/lifecycle`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

/**
 * Adjust seat count on the caller's TEAM-tier org (ORG_ADMIN / SUPER_ADMIN).
 * Pushes new quantity to Stripe (prorated) and syncs org.seat_count locally.
 * Rejects reducing below current active member count (400).
 * Range: 2-20 (self-serve).
 */
export const adjustOrgSeats = (newSeats: number) =>
  req<SeatsAdjustResponse>('/org/me/seats/adjust', {
    method: 'POST',
    body: JSON.stringify({newSeats}),
  });

/** Saves org-wide AI provider API keys (ORG_ADMIN only). Pass empty string to clear. */
export const updateOrgAiKeySettings = (
  anthropicApiKey: string,
  openAiApiKey: string,
  geminiApiKey: string,
) =>
  req<OrgDto>('/org/me/settings/ai-keys', {
    method: 'PUT',
    body: JSON.stringify({anthropicApiKey, openAiApiKey, geminiApiKey}),
  });

/** Updates org key source mode and provider restrictions (ORG_ADMIN only). */
export const updateOrgKeySourceSettings = (
  keySourceMode: string,
  enabledProviders: string[] | null,
) =>
  req<OrgDto>('/org/me/settings/key-source', {
    method: 'PUT',
    body: JSON.stringify({keySourceMode, enabledProviders}),
  });

/** Saves org-level JIRA credentials (ORG_ADMIN only). Pass empty strings to clear. */
export const updateOrgJiraSettings = (baseUrl: string, email: string, apiToken: string) =>
  req<OrgDto>('/org/me/settings/jira', {
    method: 'PUT',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Tests JIRA credentials at org level. */
export const validateOrgJira = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/org/me/settings/jira/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Saves org-level Confluence credentials (ORG_ADMIN only). Pass empty strings to clear. */
export const updateOrgConfluenceSettings = (baseUrl: string, email: string, apiToken: string) =>
  req<OrgDto>('/org/me/settings/confluence', {
    method: 'PUT',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Tests Confluence credentials at org level. */
export const validateOrgConfluence = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/org/me/settings/confluence/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

export const getOrgMembers = () => req<OrgMemberDto[]>('/org/me/members');

export const removeMember = (userId: string) =>
  req<void>(`/org/me/members/${userId}`, {method: 'DELETE'});

export const changeMemberRole = (userId: string, role: string) =>
  req<void>(`/org/me/members/${userId}/role`, {method: 'PUT', body: JSON.stringify({role})});

export const inviteMember = (body: {email: string; orgRole?: string; projectIds?: string[]}) =>
  req<{message: string; status: string}>('/org/invite', {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const getOrgInvitations = () => req<OrgInvitation[]>('/org/me/invitations');

export const resendInvitation = (id: string) =>
  req<{message: string}>(`/org/me/invitations/${id}/resend`, {method: 'POST'});

export const acceptInvite = (token: string) =>
  req<{message: string}>(`/org/accept-invite?token=${token}`, {method: 'POST'});

// ── Project endpoints ─────────────────────────────────────────────────────────

export interface ProjectDto {
  id: string;
  orgId: string;
  name: string;
  description: string | null;
  status: string;
  aiProvider: string;
  createdAt: string;
  /** Pilot R5 — git workflow defaults. */
  branchNamingPattern?: string | null;
  targetBranch?: string | null;
  defaultReviewers?: string | null;
  /** v2.5.7 — default GitHub org/owner for repos created under this project. */
  githubOwner?: string | null;
}

export interface ProjectMemberDto {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  projectRole: string;
  assignedAt: string;
}

export const getProjects = () => req<ProjectDto[]>('/projects');

export const getProject = (id: string) => req<ProjectDto>(`/projects/${id}`);

export const createProject = (body: {
  name: string;
  description?: string;
  aiProvider?: string;
  githubOwner?: string;
}) => req<ProjectDto>('/projects', {method: 'POST', body: JSON.stringify(body)});

export const updateProjectSettings = (
  id: string,
  body: {aiProvider?: string; githubOwner?: string},
) => req<ProjectDto>(`/projects/${id}/settings`, {method: 'PATCH', body: JSON.stringify(body)});

export const archiveProject = (id: string) =>
  req<ProjectDto>(`/projects/${id}/archive`, {method: 'PUT'});

/** v2.5.7 — Fetch PR review comments (inline + top-level reviews) for the Address Review flow. */
export const fetchPrReviewComments = (projectId: string, prNumber: number, repoFullName: string) =>
  req<{comments: string}>(
    `/projects/${projectId}/prs/${prNumber}/reviews?repo=${encodeURIComponent(repoFullName)}`,
  );

export const getProjectMembers = (projectId: string) =>
  req<ProjectMemberDto[]>(`/projects/${projectId}/members`);

export const addProjectMember = (projectId: string, userId: string, role: string) =>
  req<void>(`/projects/${projectId}/members`, {
    method: 'POST',
    body: JSON.stringify({userId, role}),
  });

export const removeProjectMember = (projectId: string, userId: string) =>
  req<void>(`/projects/${projectId}/members/${userId}`, {method: 'DELETE'});

// ── Admin endpoints ───────────────────────────────────────────────────────────

export const adminListUsers = () => req<AuthUser[]>('/admin/users');
export const adminGetUser = (id: string) => req<AuthUser>(`/admin/users/${id}`);
export const adminUpdateUserStatus = (id: string, status: string) =>
  req<AuthUser>(`/admin/users/${id}/status`, {method: 'PUT', body: JSON.stringify({status})});
export const adminUpdateUserTier = (id: string, tier: string) =>
  req<AuthUser>(`/admin/users/${id}/tier`, {method: 'PUT', body: JSON.stringify({tier})});
export const adminUpdateUserRole = (id: string, role: string) =>
  req<AuthUser>(`/admin/users/${id}/role`, {method: 'PUT', body: JSON.stringify({role})});

// M-010 (Wave 2) — emergency halt-all-runs button on AdminPage.
export interface HaltAllRunsResult {
  haltedCount: number;
  haltedRunIds: string[];
  reason: string;
}
export const haltAllActiveRuns = (reason?: string) =>
  req<HaltAllRunsResult>('/admin/runs/halt-all', {
    method: 'POST',
    body: JSON.stringify(reason ? {reason} : {}),
  });

// M-019 (Wave 3) + M-042 (Backlog) — activity_audit hash-chain verifier.
export interface AuditChainBreak {
  rowId: string;
  chainSeq: number;
  reason: string;
}
export interface AuditChainVerifyReport {
  totalChained: number;
  verified: number;
  clean: boolean;
  firstBreak: AuditChainBreak | null;
}
export const verifyAuditChain = () => req<AuditChainVerifyReport>('/admin/audit-chain/verify');

// M-049 (Backlog) — activation funnel headline counts (V125 ledger).
export interface ActivationStageCounts {
  total: number;
  windowed: number;
}
export interface ActivationFunnelReport {
  windowDays: number;
  windowStart: string;
  stages: Record<string, ActivationStageCounts>;
}
export const getActivationFunnel = (sinceDays = 30) =>
  req<ActivationFunnelReport>(`/admin/activation-funnel?sinceDays=${sinceDays}`);

export const adminListOrgs = () => req<OrgDto[]>('/admin/orgs');
export const adminUpdateOrgTier = (id: string, tier: string) =>
  req<OrgDto>(`/admin/orgs/${id}/tier`, {method: 'PUT', body: JSON.stringify({tier})});
export const adminUpdateOrgStatus = (id: string, status: string) =>
  req<OrgDto>(`/admin/orgs/${id}/status`, {method: 'PUT', body: JSON.stringify({status})});

export const adminListEscalated = () => req<OrgInvitation[]>('/admin/invitations/escalated');
export const adminApproveInvite = (id: string) =>
  req<{message: string}>(`/admin/invitations/${id}/approve`, {method: 'POST'});
export const adminRejectInvite = (id: string) =>
  req<{message: string}>(`/admin/invitations/${id}/reject`, {method: 'POST'});

// Tier config
export interface TierConfig {
  tier: string;
  displayName: string;
  monthlyRunLimit: number;
  isFixed: boolean;
  description: string;
}
export const adminGetTierConfig = () => req<TierConfig[]>('/admin/tier-config');
export const adminUpdateTierConfig = (tier: string, monthlyRunLimit: number) =>
  req<TierConfig>(`/admin/tier-config/${tier}`, {
    method: 'PUT',
    body: JSON.stringify({monthlyRunLimit}),
  });

// Per-org overrides
export const adminUpdateOrgRunLimit = (id: string, limit: number | null) =>
  req<OrgDto>(`/admin/orgs/${id}/run-limit`, {method: 'PUT', body: JSON.stringify({limit})});
export const adminUpdateOrgApiKeys = (id: string, provided: boolean) =>
  req<OrgDto>(`/admin/orgs/${id}/api-keys`, {method: 'PUT', body: JSON.stringify({provided})});

// Admin invite
export interface AdminInviteRequest {
  email: string;
  firstName: string;
  lastName: string;
  tier: string;
  orgId?: string;
  newOrgName?: string;
  orgRole?: string;
}
export const adminInviteUser = (body: AdminInviteRequest) =>
  req<{message: string}>('/admin/invite', {method: 'POST', body: JSON.stringify(body)});

// Org member suspend
export const orgSuspendMember = (userId: string, suspended: boolean) =>
  req<void>(`/org/me/members/${userId}/suspend`, {
    method: 'PUT',
    body: JSON.stringify({suspended}),
  });

// ── Admin Grant Runs & Top-Up Packages ────────────────────────────────────

export const adminGrantUserRuns = (
  userId: string,
  quantity: number,
  expiryDays?: number | null,
  reason?: string,
) =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  req<any>(`/admin/users/${userId}/grant-runs`, {
    method: 'POST',
    body: JSON.stringify({quantity, expiryDays, reason}),
  });

export const adminGrantOrgRuns = (
  orgId: string,
  quantity: number,
  expiryDays?: number | null,
  reason?: string,
) =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  req<any>(`/admin/orgs/${orgId}/grant-runs`, {
    method: 'POST',
    body: JSON.stringify({quantity, expiryDays, reason}),
  });

export interface TopupPackageAdmin {
  id: string;
  name: string;
  runCount: number;
  amountInr: number;
  amountUsd: number;
  active: boolean;
  sortOrder: number;
}

export const adminListTopupPackages = () => req<TopupPackageAdmin[]>('/admin/topup-packages');
export const adminCreateTopupPackage = (body: {
  name: string;
  runCount: number;
  amountInr: number;
  amountUsd?: number;
  sortOrder?: number;
}) => req<TopupPackageAdmin>('/admin/topup-packages', {method: 'POST', body: JSON.stringify(body)});
export const adminUpdateTopupPackage = (id: string, body: Partial<TopupPackageAdmin>) =>
  req<TopupPackageAdmin>(`/admin/topup-packages/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
export const adminDeleteTopupPackage = (id: string) =>
  req<{message: string}>(`/admin/topup-packages/${id}`, {method: 'DELETE'});

// ── Usage Reports ─────────────────────────────────────────────────────────
export const adminTriggerUsageReport = (period: 'WEEKLY' | 'MONTHLY') =>
  req<{emailsSent: number; period: string}>('/admin/usage-reports/trigger', {
    method: 'POST',
    body: JSON.stringify({period}),
  });

// ── Feature Flags (Admin) ────────────────────────────────────────────────────

export interface FeatureFlagDto {
  id: number;
  flagKey: string;
  flagValue: string;
  valueType: 'BOOLEAN' | 'STRING' | 'INTEGER' | 'PERCENTAGE';
  scope: 'PLATFORM' | 'ORG';
  description: string | null;
  updatedBy: number | null;
  updatedAt: string | null;
}

export const adminListFeatureFlags = () => req<FeatureFlagDto[]>('/admin/feature-flags');

/** v2.5.8 — public feature-flag map for UI gating. Keys whitelisted to
 *  integrations.*, billing.*, pipeline.*, project.*, execution.view.*. */
export const getPublicFeatureFlags = () =>
  req<Record<string, boolean>>('/admin/feature-flags/public');
export const adminUpdateFeatureFlag = (key: string, value: string) =>
  req<FeatureFlagDto>(`/admin/feature-flags/${encodeURIComponent(key)}`, {
    method: 'PUT',
    body: JSON.stringify({value}),
  });

// ── Plan Editing & Versioning ──────────────────────────────────────────────

export const editRunPlan = (runId: string, feedback: string) =>
  req<{status: string; version: number}>(`/runs/${runId}/edit-plan`, {
    method: 'POST',
    body: JSON.stringify({feedback}),
  });

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const getPlanVersions = (runId: string) => req<any[]>(`/runs/${runId}/plan-versions`);

// Task execution graph
export interface TaskGraphNode {
  taskId: string;
  title: string;
  status: string;
  complexity: string | null;
  assignedModel: string | null;
  dependsOn: string[];
  level: number;
  parentTaskId: string | null;
  subtaskIndex: number | null;
  subtaskTotal: number | null;
}
export const getTaskGraph = (runId: string) => req<TaskGraphNode[]>(`/runs/${runId}/task-graph`);

/** Update or remove the project association of an existing run. Pass null to un-assign. */
export const updateRunProject = (runId: string, projectId: string | null) =>
  req<RunSummary>(`/runs/${runId}/project`, {
    method: 'PATCH',
    body: JSON.stringify({projectId: projectId ?? ''}),
  });

// ── Audit Logs ────────────────────────────────────────────────────────────
export interface AuditLogEntry {
  id: string;
  eventType: string;
  entityType: string;
  entityId: string;
  orgId: string | null;
  actorId: string | null;
  actorEmail: string | null;
  oldValue: string | null;
  newValue: string | null;
  metadata: string | null;
  createdAt: string;
}

export interface AuditLogPage {
  content: AuditLogEntry[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

export const getAuditLogs = (params: {
  orgScoped?: boolean;
  entityType?: string;
  eventType?: string;
  actorId?: string;
  entityId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}) => {
  const qs = new URLSearchParams();
  if (params.entityType) qs.set('entityType', params.entityType);
  if (params.eventType) qs.set('eventType', params.eventType);
  if (params.actorId) qs.set('actorId', params.actorId);
  if (params.entityId) qs.set('entityId', params.entityId);
  if (params.from) qs.set('from', params.from);
  if (params.to) qs.set('to', params.to);
  qs.set('page', String(params.page ?? 0));
  qs.set('size', String(params.size ?? 50));
  const base = params.orgScoped ? '/org/me/audit-logs' : '/admin/audit-logs';
  return req<AuditLogPage>(`${base}?${qs.toString()}`);
};

// ── Validation Reports ──────────────────────────────────────────────────

export interface ReportIssue {
  description: string;
  severity: 'WOULD_BREAK_BUILD' | 'WOULD_FAIL_REVIEW' | 'WOULD_CAUSE_BUG' | 'WOULD_FAIL_LINT';
  fix: string | null;
  stage: 'CODE_VALIDATOR' | 'COMPILER_AGENT' | 'REVIEWER_AGENT';
  filePath: string | null;
}

export interface ValidationReportData {
  id: string;
  runId: string;
  filesGenerated: number;
  languages: string[];
  pipelineResult: 'PASSED' | 'PASSED_WITH_FIXES' | 'NEEDS_ATTENTION' | 'FAILED';
  issues: ReportIssue[];
  checksPassed: string[];
  reportMarkdown: string;
  modelUsed: string;
  createdAt: string;
  issuesCaught: number;
  buildBreakers: number;
  reviewFailures: number;
  productionBugs: number;
  lintFailures: number;
  isShared: boolean;
  shareUrl: string | null;
  generatedAfterImprovements: boolean;
  accuracyScore?: number | null;
  accuracyGrade?: string | null;
  /** Pilot R6 — per-dimension scores (nullable for runs before V83). */
  codeQualityScore?: number | null;
  testCoverageScore?: number | null;
  architectureScore?: number | null;
  securityScore?: number | null;
  performanceScore?: number | null;
  designComplianceScore?: number | null;
  /** v2.5.10 (V106) — verified signals from the pipeline itself.
   *  finalBuildPassed + tests + verdict are the inputs to verifiedScore, which
   *  anchors the 6 dimensions above when scoring.v2-verified flag is on. */
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsTotal?: number | null;
  reviewerVerdict?: 'CLEAN' | 'FIXED_ISSUES' | 'UNRESOLVED_ISSUES' | 'REVIEW_FAILED' | null;
  verifiedScore?: number | null;
}

export interface SharedReportData {
  filesGenerated: number;
  languages: string[];
  pipelineResult: 'PASSED' | 'PASSED_WITH_FIXES' | 'NEEDS_ATTENTION' | 'FAILED';
  issues: ReportIssue[];
  checksPassed: string[];
  reportMarkdown: string;
  createdAt: string;
}

export const getValidationReport = async (runId: string): Promise<ValidationReportData | null> => {
  try {
    return await req<ValidationReportData>(`/runs/${runId}/report`);
  } catch {
    return null;
  }
};

export const shareValidationReport = (runId: string) =>
  req<{shareToken: string; shareUrl: string}>(`/runs/${runId}/report/share`, {method: 'POST'});

/**
 * Kick the background validation-report regeneration for a terminal run.
 * Useful when the first attempt crashed (NPE / LLM hiccup) and the panel
 * shows no report. Backend returns 202 — poll `getValidationReport` a few
 * seconds later to see the fresh row.
 */
export const retryValidationReport = (runId: string) =>
  req<void>(`/runs/${runId}/retry-validation`, {method: 'POST'});

export const getSharedReport = async (shareToken: string): Promise<SharedReportData | null> => {
  try {
    const res = await fetch(`${BASE}/reports/shared/${shareToken}`);
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
};

// ── Test Generation Pipeline ─────────────────────────────────────────────────

export interface TestResultDetail {
  testResultId: number;
  testClass: string;
  testMethod: string;
  status: 'PASSED' | 'FAILED' | 'SKIPPED' | 'ERROR';
  failureMessage: string | null;
  expected: string | null;
  actual: string | null;
  durationMs: number;
}

export interface FailureSuggestion {
  testClass: string;
  testMethod: string;
  failureMessage: string;
  suggestion: string;
}

export interface RootCauseGroup {
  signature: string;
  count: number;
  testMethods: string[];
  testResultIds: number[];
  suggestion: string;
  hasSuggestion: boolean;
}

export interface TestReportData {
  testPlanId: number;
  runId: string;
  status: string;
  totalTests: number;
  passed: number;
  failed: number;
  skipped: number;
  errors: number;
  results: TestResultDetail[];
  rootCauseGroups: RootCauseGroup[];
  suggestions: FailureSuggestion[];
  executionDurationMs: number;
  previewUrl: string | null;
}

export interface TestTaskDto {
  id: number;
  taskName: string;
  taskDescription: string;
  targetEntity: string;
  testCategory: string;
  status: 'PENDING' | 'GENERATING' | 'COMPILED' | 'FAILED';
  compilationError: string;
}

export interface TestPlanData {
  testPlanId: number;
  runId: string;
  status: string;
  tasks: TestTaskDto[];
}

export interface TestGraphData {
  planStatus: string;
  nodes: Array<{id: string; label: string; type: string; status: string; category?: string}>;
  edges: Array<{from: string; to: string}>;
  totalTasks: number;
  completedTasks: number;
  failedTasks: number;
  testResults: {total: number; passed: number; failed: number};
}

export const getTestReport = async (runId: string): Promise<TestReportData | null> => {
  try {
    return await req<TestReportData>(`/runs/${runId}/test-report`);
  } catch {
    return null;
  }
};

export const getTestPlan = async (runId: string): Promise<TestPlanData | null> => {
  try {
    return await req<TestPlanData>(`/runs/${runId}/test-plan`);
  } catch {
    return null;
  }
};

export const getTestGraph = async (runId: string): Promise<TestGraphData | null> => {
  try {
    return await req<TestGraphData>(`/runs/${runId}/test-graph`);
  } catch {
    return null;
  }
};

export const markTestInvalid = (testResultId: number) =>
  req<void>(`/test-results/${testResultId}/mark-invalid`, {method: 'PUT'});

export const applyTestFixes = (runId: string, signature?: string) =>
  req<void>(
    `/runs/${runId}/apply-test-fixes${signature ? `?signature=${encodeURIComponent(signature)}` : ''}`,
    {method: 'POST'},
  );

export const runTests = (runId: string) =>
  req<{runId: string; testPlanId: number; previewUrl: string}>(`/runs/${runId}/run-tests`, {
    method: 'POST',
  });

// ── Admin Analytics ──────────────────────────────────────────────────────────

export interface UsageMetrics {
  totalRuns: number;
  successfulRuns: number;
  failedRuns: number;
  partialRuns: number;
  runsByDay: {date: string; count: number; successRate: number}[];
  runsByProject: Record<string, number>;
  avgTimeToCompleteSeconds: number;
  totalCost: number;
  costByModel: Record<string, number>;
}

export interface MemberUsage {
  userId: string;
  firstName: string;
  lastName: string;
  email: string;
  runCount: number;
  successRate: number;
  lastRunDate: string | null;
}

export interface BillingOverviewData {
  billingEntityType: string;
  billingEntityName: string;
  currentPlan: string;
  subscriptionStatus: string;
  nextBillingDate: string | null;
  periodUsage: number;
  remainingRuns: number;
  runLimit: number;
  gateway: string | null;
  topupCredits: number;
  recentInvoices: {
    invoiceNumber: string;
    amount: number;
    currency: string;
    status: string;
    issuedAt: string;
  }[];
}

export const getAnalyticsUsage = (entityType: string, entityId: string, period = 30) =>
  req<UsageMetrics>(
    `/admin/analytics/usage?entityType=${entityType}&entityId=${entityId}&period=${period}`,
  );

export const getAnalyticsMembers = (orgId: string, period = 30) =>
  req<MemberUsage[]>(`/admin/analytics/members?orgId=${orgId}&period=${period}`);

export const getAnalyticsBilling = (entityType: string, entityId: string) =>
  req<BillingOverviewData>(
    `/admin/analytics/billing?entityType=${entityType}&entityId=${entityId}`,
  );

export const updateModelPricing = (
  provider: string,
  model: string,
  data: {inputCostPer1m: number; outputCostPer1m: number; notes?: string},
) => req<PricingRow>(`/pricing/${provider}/${model}`, {method: 'PUT', body: JSON.stringify(data)});

export const getAllPricing = () => req<PricingRow[]>('/pricing');

export const downloadValidationReportPdf = async (runId: string): Promise<void> => {
  const res = await fetch(`${BASE}/runs/${runId}/report/pdf`, {
    headers: {...authHeader()},
    credentials: 'include',
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `validation-report-${runId.substring(0, 8)}.pdf`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};

// ── Payment Audit (Admin) ────────────────────────────────────────────────────

export interface PaymentAuditEntry {
  id: string;
  entityType: string;
  entityId: string;
  eventType: string;
  oldTier: string | null;
  newTier: string | null;
  performedBy: string | null;
  notes: string | null;
  gatewayProvider: string | null;
  gatewayEventId: string | null;
  gatewayPaymentId: string | null;
  currency: string | null;
  amountMinor: number | null;
  userId: string | null;
  orgId: string | null;
  createdAt: string;
}

export interface WebhookEventEntry {
  id: number;
  gateway: string;
  eventId: string;
  eventType: string;
  payloadHash: string | null;
  processingStatus: string;
  errorMessage: string | null;
  attempts: number;
  createdAt: string;
  processedAt: string | null;
}

export const adminListPaymentAudit = (page = 0, size = 50, orgId?: string, userId?: string) => {
  const params = new URLSearchParams({page: String(page), size: String(size)});
  if (orgId) params.set('orgId', orgId);
  if (userId) params.set('userId', userId);
  return req<PagedResponse<PaymentAuditEntry>>(`/admin/payment-audit?${params}`);
};

export const adminExportPaymentAuditCsv = async () => {
  const res = await fetch(`${BASE}/admin/payment-audit/export`, {
    headers: {...authHeader()},
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'payment-audit.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};

export const adminListWebhookEvents = (page = 0, size = 50, gateway?: string) => {
  const params = new URLSearchParams({page: String(page), size: String(size)});
  if (gateway) params.set('gateway', gateway);
  return req<PagedResponse<WebhookEventEntry>>(`/admin/webhook-events?${params}`);
};

// ── Notifications ─────────────────────────────────────────────────────────

export interface NotificationItem {
  id: string;
  userId: string;
  type: string;
  title: string;
  body: string | null;
  link: string | null;
  read: boolean;
  createdAt: string;
}

export const getNotifications = (page = 0, size = 20) =>
  req<PagedResponse<NotificationItem>>(`/notifications?page=${page}&size=${size}`);

export const getUnreadCount = () => req<{count: number}>('/notifications/unread-count');

export const markNotificationRead = (id: string) =>
  req<void>(`/notifications/${id}/read`, {method: 'POST'});

export const markAllNotificationsRead = () =>
  req<void>('/notifications/read-all', {method: 'POST'});

// ── Notification preferences (M-003 Wave 2) ─────────────────────────────────

export interface NotificationPrefRow {
  eventType: string; // canonical ActivityEventType name
  tier: 'HIGH_SIGNAL' | 'ADMIN_TOGGLE';
  channel: 'IN_APP' | 'EMAIL';
  enabled: boolean; // effective (override → override; else default)
  defaultEnabled: boolean;
  override: boolean; // true if the user has an explicit row
}

export const getNotificationPreferences = () =>
  req<NotificationPrefRow[]>('/notifications/preferences');

export const updateNotificationPreference = (
  eventType: string,
  channel: 'IN_APP' | 'EMAIL',
  enabled: boolean,
) =>
  req<NotificationPrefRow>('/notifications/preferences', {
    method: 'PUT',
    body: JSON.stringify({eventType, channel, enabled}),
  });

// ── SSO config (M-004 Wave 2) ───────────────────────────────────────────────

export type SsoProvider = 'GOOGLE_OIDC' | 'OKTA_SAML' | 'AZURE_AD_SAML';

export interface SsoConfig {
  provider: SsoProvider | null;
  enforced: boolean;
  metadataUrl: string | null;
  domain: string | null;
  configured: boolean;
}

export const getSsoConfig = () => req<SsoConfig>('/auth/sso/config');

export const updateSsoConfig = (
  body: Partial<{
    provider: SsoProvider | null;
    enforced: boolean;
    metadataUrl: string | null;
    domain: string | null;
  }>,
) =>
  req<SsoConfig>('/auth/sso/config', {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const clearSsoConfig = () => req<void>('/auth/sso/config', {method: 'DELETE'});

// ── SSE ────────────────────────────────────────────────────────────────────

/** Fetch a short-lived scoped SSE token for a specific run. */
export const getSseToken = (runId: string) => req<{token: string}>(`/runs/${runId}/sse-token`);

// ── Pilot v3.0: Project workspace, repos, architecture, scoring ───────────
import {} from '@/lib/utils';
import type {
  ProjectDashboardDTO,
  ProjectRepo,
  ProjectRepoLink,
  ArchitectureDoc,
  ScoreEntry,
  LinkRepoRequest,
  RepoScope,
} from '@/types/project';

// Project dashboard
export const getProjectDashboard = (projectId: string) =>
  req<ProjectDashboardDTO>(`/projects/${projectId}/dashboard`);

export const getProjectScores = (projectId: string) =>
  req<ScoreEntry[]>(`/projects/${projectId}/scores`);

// Repos — lifecycle (scoped ORG | USER)
export const listRepos = (scope: RepoScope | 'ALL' = 'ALL') =>
  req<ProjectRepo[]>(`/repos?scope=${scope}`);

export interface GitHubRemoteRepo {
  fullName: string;
  defaultBranch: string;
  isPrivate: boolean;
}

export const listGitHubRepos = (owner?: string) =>
  req<GitHubRemoteRepo[]>(`/repos/github${owner ? `?owner=${encodeURIComponent(owner)}` : ''}`);

export const createRepo = (data: LinkRepoRequest) =>
  req<ProjectRepo>('/repos', {method: 'POST', body: JSON.stringify(data)});

export const deleteRepo = (repoId: string) => req<void>(`/repos/${repoId}`, {method: 'DELETE'});

export const syncRepo = (repoId: string) =>
  req<ProjectRepo>(`/repos/${repoId}/sync`, {method: 'POST'});

// Repos — project link
export const getProjectRepos = (projectId: string) =>
  req<ProjectRepoLink[]>(`/projects/${projectId}/repos`);

export const linkProjectRepo = (projectId: string, repoId: string) =>
  req<ProjectRepoLink>(`/projects/${projectId}/repos/${repoId}`, {method: 'POST'});

export const unlinkProjectRepo = (projectId: string, repoId: string) =>
  req<void>(`/projects/${projectId}/repos/${repoId}`, {method: 'DELETE'});

export const updateRepoRelationships = (
  projectId: string,
  repoId: string,
  relationships: string[],
) =>
  req<ProjectRepoLink>(`/projects/${projectId}/repos/${repoId}/relationships`, {
    method: 'PUT',
    body: JSON.stringify({relationships}),
  });

// Architecture docs (R3)
export const generateArchitecture = (projectId: string) =>
  req<ArchitectureDoc>(`/projects/${projectId}/architecture/generate`, {method: 'POST'});

// Project workspace analyse (regenerates codemaps + architecture.md + summary.md on disk)
export const analyseProject = (projectId: string, branch?: string) =>
  req<ArchitectureDoc>(
    `/projects/${projectId}/analyse${branch ? `?branch=${encodeURIComponent(branch)}` : ''}`,
    {method: 'POST'},
  );

// Sync a terminal run's workspace from a branch (post-merge re-review).
export const syncRun = (runId: string, branch?: string) =>
  req<{status: string; reposSynced: number; details: string[]}>(
    `/runs/${runId}/sync${branch ? `?branch=${encodeURIComponent(branch)}` : ''}`,
    {method: 'POST'},
  );

// v2.5.2 — Spec templates (Phase 4.2 / UX-2.3).
export interface SpecTemplate {
  id: string;
  ownerType: 'PROJECT' | 'ORG';
  ownerId: string;
  name: string;
  description: string | null;
  requirementTemplate: string;
  createdAt: string;
}
export const listSpecTemplates = (projectId: string) =>
  req<SpecTemplate[]>(`/projects/${projectId}/templates`);
export const createProjectSpecTemplate = (
  projectId: string,
  body: {name: string; description?: string; requirementTemplate: string},
) =>
  req<SpecTemplate>(`/projects/${projectId}/templates`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
export const createOrgSpecTemplate = (
  orgId: string,
  body: {name: string; description?: string; requirementTemplate: string},
) =>
  req<SpecTemplate>(`/orgs/${orgId}/templates`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
export const deleteSpecTemplate = (projectId: string, templateId: string) =>
  req<void>(`/projects/${projectId}/templates/${templateId}`, {method: 'DELETE'});

// v2.5.5 (UX-4.2) — architecture diff source-file correlation.
// The text diff of architecture.md stays client-side (LCS); this call
// returns per-repo added/modified/removed/renamed files between the two
// versions by hitting GitHub's compare API against the SHAs captured at
// generate-time on each ProjectArchitectureDoc row.
export interface ArchChangedFile {
  filename: string;
  status: string; // added | modified | removed | renamed
  additions: number;
  deletions: number;
}
export interface ArchDiffReport {
  fromVersion: number;
  toVersion: number;
  perRepo: {repoFullName: string; files: ArchChangedFile[]}[];
  totalFiles: number;
}
export const getArchitectureDiff = (projectId: string, from: number, to: number) =>
  req<ArchDiffReport>(`/projects/${projectId}/architecture/diff?from=${from}&to=${to}`);

// v2.5.2 — Tech Debt rollup for Project Overview. All DB-derived, no LLM.
export interface TechDebtReport {
  issuesByCategory: Record<string, number>;
  issuesBySeverity: Record<string, number>;
  topFiles: {filePath: string; issueCount: number}[];
  scoreTrend: {dimension: string; score: number; at: string}[];
  averageAccuracyScore: number;
  totalRunsAnalyzed: number;
}
export const getTechDebt = (projectId: string, sinceDays?: number) =>
  req<TechDebtReport>(`/projects/${projectId}/tech-debt${sinceDays ? `?since=${sinceDays}` : ''}`);

// v2.5.2 — heuristic pre-submit Launch estimate (no LLM call).
export interface LaunchEstimate {
  estimatedTasksLow: number;
  estimatedTasksHigh: number;
  estimatedCostLow: number;
  estimatedCostHigh: number;
  basis: string;
}
export const estimateRun = (projectId: string, requirement: string) =>
  req<LaunchEstimate>(
    `/projects/${projectId}/estimate?requirement=${encodeURIComponent(requirement)}`,
  );

// v2.5.2 — "Continue building" banner after a PR merge. Absent (null body)
// means no banner; entry lives 10 min in Redis post-merge.
export interface PostMergeBar {
  repoFullName: string;
  prNumber: number;
  mergedAt: string | null;
}
export const getPostMergeBar = (projectId: string) =>
  req<PostMergeBar[]>(`/projects/${projectId}/post-merge-bar`);
export const dismissPostMergeBar = (projectId: string) =>
  req<void>(`/projects/${projectId}/post-merge-bar`, {method: 'DELETE'});

// v2.5.2 — per-repo workspace health for the Project Overview tab.
export interface RepoHealth {
  repoId: string;
  repoName: string;
  defaultBranch: string;
  lastCodeMapSha: string | null;
  currentOriginSha: string | null;
  isStale: boolean;
  webhookInstalled: boolean;
  openPrCount: number;
  lastAnalysedAt: string | null;
}
export interface WorkspaceHealthReport {
  repos: RepoHealth[];
  lastRefreshedAt: string;
}
export const getWorkspaceHealth = (projectId: string) =>
  req<WorkspaceHealthReport>(`/projects/${projectId}/workspace-health`);

// E2.3 — contract coverage panel (UX-preserved: drives ContractCoveragePanel.tsx
// which reuses the WorkspaceHealthPanel layout + 4-band semantic tokens).
export type ContractType =
  | 'OPENAPI'
  | 'PROTO'
  | 'GRAPHQL'
  | 'JSON_SCHEMA'
  | 'ASYNCAPI'
  | 'EVENT_SCHEMA';
export type DetectorStatus = 'SCANNED' | 'FAILED' | 'SKIPPED';
export interface DetectorSummary {
  detector: string;
  type: ContractType;
  status: DetectorStatus;
  contractIds: string[];
  failureReason?: string | null;
}
export interface KnownGap {
  category: string;
  explanation: string;
  actionable: string;
}
export interface ContractCoverageResponse {
  projectId: string;
  repoId: string;
  githubRepoFullName: string;
  scans: DetectorSummary[];
  knownGaps: KnownGap[];
}
export const getContractCoverage = (projectId: string, repoId: string) =>
  req<ContractCoverageResponse>(`/projects/${projectId}/repos/${repoId}/contract-coverage`);
export const rescanContractCoverage = (projectId: string, repoId: string) =>
  req<ContractCoverageResponse>(`/projects/${projectId}/repos/${repoId}/contract-coverage/rescan`, {
    method: 'POST',
  });

// E2.8 — contract drift detection
export interface DriftCheckResult {
  contractId: string;
  hasDrift: boolean;
  summary: string;
  eventId?: string | null;
}
export const checkContractDrift = (projectId: string, contractId: string) =>
  req<DriftCheckResult>(`/projects/${projectId}/contracts/${contractId}/check-drift`, {
    method: 'POST',
  });
export const checkRepoDrift = (projectId: string, repoId: string) =>
  req<DriftCheckResult[]>(`/projects/${projectId}/repos/${repoId}/check-drift`, {method: 'POST'});
export const checkProjectDrift = (projectId: string) =>
  req<DriftCheckResult[]>(`/projects/${projectId}/check-drift`, {method: 'POST'});
export const refreshContractFromSource = (projectId: string, contractId: string) =>
  req<{prUrl: string; branchName: string}>(
    `/projects/${projectId}/contracts/${contractId}/refresh-from-source`,
    {method: 'POST'},
  );
export const dismissContractDrift = (projectId: string, contractId: string, rationale: string) =>
  req<{dismissedCount: number}>(`/projects/${projectId}/contracts/${contractId}/dismiss-drift`, {
    method: 'POST',
    body: JSON.stringify({rationale}),
  });

// E3.16 — standards compliance report
export interface StandardEntry {
  id: string;
  severity: 'BLOCKING' | 'WARNING' | 'ADVISORY';
  source: 'ORG' | 'PROJECT';
  overridden: boolean;
  originalSeverity: string;
  orgLevelLocked: boolean;
  enforcementType: string;
  description: string;
}
export interface ViolationEntry {
  id: string;
  standardId: string;
  enforcementType: string;
  severity: string;
  summary: string;
  detectedAt: string;
  overrideApplied: boolean;
}
export interface OverrideEntry {
  id: string;
  standardId: string;
  overrideType: string;
  newSeverity?: string | null;
  rationale: string;
  createdByUserId?: string | null;
  createdAt?: string | null;
}
export interface ComplianceReport {
  projectId: string;
  orgId: string;
  sinceDays: number;
  standards: StandardEntry[];
  recentViolations: ViolationEntry[];
  persistentOverrides: OverrideEntry[];
}
export const getStandardsReport = (projectId: string, orgId: string, sinceDays = 30) =>
  req<ComplianceReport>(
    `/projects/${projectId}/standards/report?orgId=${encodeURIComponent(orgId)}&sinceDays=${sinceDays}`,
  );

// E4.1 — activity feed
export interface ActivityEntry {
  id: string;
  occurredAt: string;
  actorUserId?: string | null;
  actorType: 'USER' | 'SYSTEM';
  eventType: string;
  tier: 'HIGH_SIGNAL' | 'ADMIN_TOGGLE' | 'AUDIT_ONLY';
  targetType?: string | null;
  targetId?: string | null;
  summary: string;
  payload?: string | null;
  schemaVersion: number;
}
export interface ActivityPage {
  projectId: string;
  from: string;
  to: string;
  entries: ActivityEntry[];
  hasMore: boolean;
}
export const getActivityFeed = (
  projectId: string,
  opts: {
    sinceDays?: number;
    eventTypes?: string[];
    actorId?: string;
    targetType?: string;
    targetId?: string;
    includeAdminEvents?: boolean;
    limit?: number;
  } = {},
) => {
  const params = new URLSearchParams();
  if (opts.sinceDays != null) params.set('sinceDays', String(opts.sinceDays));
  if (opts.eventTypes && opts.eventTypes.length)
    opts.eventTypes.forEach((t) => params.append('eventTypes', t));
  if (opts.actorId) params.set('actorId', opts.actorId);
  if (opts.targetType) params.set('targetType', opts.targetType);
  if (opts.targetId) params.set('targetId', opts.targetId);
  if (opts.includeAdminEvents) params.set('includeAdminEvents', 'true');
  if (opts.limit != null) params.set('limit', String(opts.limit));
  const qs = params.toString() ? `?${params.toString()}` : '';
  return req<ActivityPage>(`/projects/${projectId}/activity${qs}`);
};

// M-014 (Wave 2) — Architecture Decision Records (V137)
export interface AdrSummary {
  id: string;
  adrIdInRepo: number;
  title: string;
  status: string; // PROPOSED | ACCEPTED | DEPRECATED | SUPERSEDED
  decisionDate: string | null;
  repoId: string | null;
  filePath: string | null;
}

export interface AdrDetail extends AdrSummary {
  deciders: string | null;
  supersedes: string | null;
  supersededBy: string | null;
  relatedAdrs: string | null;
  relatedStandards: string | null;
  tags: string | null;
  body: string | null;
}

export const listProjectAdrs = (projectId: string, acceptedOnly = false) =>
  req<AdrSummary[]>(`/projects/${projectId}/adrs${acceptedOnly ? '?acceptedOnly=true' : ''}`);

export const getProjectAdr = (projectId: string, id: string) =>
  req<AdrDetail>(`/projects/${projectId}/adrs/${id}`);

// M-015 (Wave 2) — V138 architectural invariants
export interface InvariantSummary {
  id: string;
  invariantId: string; // human-readable id from invariants.yaml
  status: string; // ACTIVE | RETIRED
  invariantYaml: string;
  createdAt: string;
}

export interface InvariantViolation {
  id: string;
  invariantUuid: string | null;
  detectedAt: string;
  phase: string; // PLAN | ARCH_REGEN
  detail: string | null; // jsonb body
}

export const listProjectInvariants = (projectId: string) =>
  req<InvariantSummary[]>(`/projects/${projectId}/invariants`);

export const listProjectInvariantViolations = (projectId: string) =>
  req<InvariantViolation[]>(`/projects/${projectId}/invariants/violations`);

// M-024 (Wave 2) — org-level standards CRUD
export interface OrgStandardDto {
  id: string;
  standardId: string;
  standardYaml: string;
  orgLevelLocked: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface OrgStandardUpsert {
  standardId?: string;
  standardYaml?: string;
  orgLevelLocked?: boolean;
}

export const listOrgStandards = (orgId: string) =>
  req<OrgStandardDto[]>(`/orgs/${orgId}/standards`);

export const createOrgStandard = (orgId: string, body: OrgStandardUpsert) =>
  req<OrgStandardDto>(`/orgs/${orgId}/standards`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const updateOrgStandard = (orgId: string, standardId: string, body: OrgStandardUpsert) =>
  req<OrgStandardDto>(`/orgs/${orgId}/standards/${encodeURIComponent(standardId)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const deleteOrgStandard = (orgId: string, standardId: string) =>
  req<void>(`/orgs/${orgId}/standards/${encodeURIComponent(standardId)}`, {
    method: 'DELETE',
  });

// v2.5.2 — install a PR webhook on a repo the user linked (rather than created).
export const installRepoWebhook = (repoId: string) =>
  req<{installed: boolean; reason?: string}>(`/repos/${repoId}/webhook`, {method: 'POST'});

// v2.5.2 — reset workspace to the exact commit SHAs captured at run start.
// Returns { reposSynced: 0, details: ["no snapshot — run predates v2.5.2"] }
// for runs clone before the snapshot rollout.
export const syncRunToOriginal = (runId: string) =>
  req<{status: string; reposSynced: number; details: string[]}>(`/runs/${runId}/sync/original`, {
    method: 'POST',
  });

export const getProjectSummary = (projectId: string) =>
  req<{content: string}>(`/projects/${projectId}/summary`);

// v2.5.12 — project-level generated docs (EXPLANATION + SETUP)
export type GeneratedDocType = 'EXPLANATION' | 'SETUP';

export interface GeneratedDocRow {
  id: string;
  docType: GeneratedDocType;
  version: number;
  content: string;
  generatedBy: string | null;
  generatedAt: string;
  modelUsed: string | null;
  inputTokens: number;
  outputTokens: number;
}

export const generateProjectDoc = (projectId: string, docType: GeneratedDocType) =>
  req<GeneratedDocRow>(`/projects/${projectId}/docs/${docType}/generate`, {method: 'POST'});

/**
 * Returns null (204) when the doc hasn't been generated yet. Callers should
 * render a "Generate" CTA in that case. req<T>() returns undefined on 204,
 * which we normalise to null at the call site.
 */
export const getProjectDoc = async (
  projectId: string,
  docType: GeneratedDocType,
): Promise<GeneratedDocRow | null> => {
  const row = await req<GeneratedDocRow | undefined>(`/projects/${projectId}/docs/${docType}`);
  return row ?? null;
};

/**
 * Triggers a browser download of the doc as .md. Uses raw fetch (not req)
 * because the endpoint returns text/markdown, not JSON.
 */
export const downloadProjectDoc = async (
  projectId: string,
  docType: GeneratedDocType,
): Promise<void> => {
  const token = localStorage.getItem('access_token');
  const res = await fetch(`/api/projects/${projectId}/docs/${docType}/download`, {
    headers: token ? {Authorization: `Bearer ${token}`} : {},
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => res.statusText);
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${docType.toLowerCase()}.md`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

// Project documents (RAG)
export interface ProjectDocumentRow {
  id: string;
  projectId: string;
  pathRelative: string;
  filename: string | null;
  mimeType: string | null;
  sizeBytes: number | null;
  status: 'UPLOADED' | 'EXTRACTING' | 'EMBEDDING' | 'READY' | 'FAILED';
  failureReason: string | null;
  createdAt: string;
}

export const listProjectDocuments = (projectId: string) =>
  req<ProjectDocumentRow[]>(`/projects/${projectId}/documents`);

export const uploadProjectDocument = async (
  projectId: string,
  file: File,
): Promise<ProjectDocumentRow> => {
  const form = new FormData();
  form.append('file', file);
  const token = localStorage.getItem('token');
  const res = await fetch(`/api/projects/${projectId}/documents`, {
    method: 'POST',
    headers: token ? {Authorization: `Bearer ${token}`} : {},
    body: form,
  });
  if (!res.ok) throw new Error(`Upload failed: ${res.status}`);
  return res.json();
};

export const deleteProjectDocument = (projectId: string, documentId: string) =>
  req<{status: string}>(`/projects/${projectId}/documents/${documentId}`, {method: 'DELETE'});

// PR refresh — polls GitHub for the latest state of every project PR.
export const refreshProjectPrs = (projectId: string) =>
  req<{checked: number; updated: number; newlyMerged: number}>(
    `/projects/${projectId}/prs/refresh`,
    {method: 'POST'},
  );

export const getArchitectureDoc = (projectId: string) =>
  req<ArchitectureDoc>(`/projects/${projectId}/architecture`);

export const getArchitectureVersions = (projectId: string) =>
  req<ArchitectureDoc[]>(`/projects/${projectId}/architecture/versions`);

export const annotateArchitecture = (projectId: string, annotation: string) =>
  req<ArchitectureDoc>(`/projects/${projectId}/architecture/annotate`, {
    method: 'PUT',
    body: JSON.stringify({annotation}),
  });

export const getRepoCodeMap = (projectId: string, repoId: string) =>
  req<{content: string}>(`/projects/${projectId}/repos/${repoId}/codemap`);

// PR list for a run (R5)
export const getRunPrs = (runId: string) =>
  req<import('@/types/project').PrSummary[]>(`/runs/${runId}/prs`);

// Architecture staleness (deferral #4)
export interface RepoStaleness {
  repoFullName: string;
  generatedFromSha: string | null;
  currentSha: string | null;
  stale: boolean;
}
export interface StalenessReport {
  stale: boolean;
  staleCount: number;
  totalCount: number;
  repos: RepoStaleness[];
}
export const getArchitectureStaleness = (projectId: string) =>
  req<StalenessReport>(`/projects/${projectId}/architecture/staleness`);

// ── Project Memory (v2.2.0) ──────────────────────────────────────────────
export interface MemoryStats {
  totalRuns: number;
  snapshotVersion: number;
  snapshotCoversUpTo: number;
  recentEntryCount: number;
  currentTokens: number;
  tokenBudget: number;
}
export interface RunLedgerEntry {
  id: string;
  runId: string;
  runNumber: number;
  entry: string;
  tokenCount: number | null;
  compactedInto: string | null;
  createdAt: string;
}
export interface ProjectMemoryResponse {
  fullContext: string;
  stats: MemoryStats;
  recentEntries: RunLedgerEntry[];
}
export const getProjectMemory = (projectId: string) =>
  req<ProjectMemoryResponse>(`/projects/${projectId}/memory`);
export const getProjectLedger = (projectId: string) =>
  req<RunLedgerEntry[]>(`/projects/${projectId}/memory/ledger`);

export const compactProjectMemory = (projectId: string) =>
  req<{snapshotVersion: number; compacted: boolean}>(`/projects/${projectId}/memory/compact`, {
    method: 'POST',
  });

export interface PullRequestSnapshot {
  title: string;
  body: string;
  state: string;
  additions: number;
  deletions: number;
  changedFiles: number;
  headBranch: string;
  baseBranch: string;
  diffPreview: string;
}

/**
 * 2026-05-04 — bumped default maxChars 4_000 → 200_000. The backend concatenates
 * every file's patch into one diff blob and truncates at the byte limit BEFORE
 * the frontend's `splitDiffByFile` parser sees it. With the previous 4 KB cap,
 * a typical multi-file PR (e.g. one with a 2-3 KB README change near the top of
 * the diff) had all subsequent file patches silently dropped — symptom on dev
 * 2026-05-04 was "every PR only shows README.md". 200 KB covers virtually every
 * realistic PR from V2 runs (the per-task scope is bounded), keeps responses
 * gzip'd under 50 KB on the wire, and falls back to a "truncated, N chars
 * omitted" footer when even that's exceeded so the user can click through to
 * GitHub for the full diff. Caller can still pass a smaller value if they need
 * a tight summary (no current call site does).
 */
export const getPullRequestSnapshot = (
  repoFullName: string,
  prNumber: number,
  maxChars = 200_000,
) =>
  req<PullRequestSnapshot>(
    `/prs/snapshot?repo=${encodeURIComponent(repoFullName)}&number=${prNumber}&maxChars=${maxChars}`,
  );

export const compileProjectChatToSpec = (projectId: string) =>
  req<{spec: string}>(`/projects/${projectId}/chat/compile`, {method: 'POST'});

export const clearProjectChat = (projectId: string) =>
  req<{deleted: number}>(`/projects/${projectId}/chat`, {method: 'DELETE'});

export const compactProjectChat = (projectId: string) =>
  req<{compactedFrom: number; kept: number; totalAfter: number}>(
    `/projects/${projectId}/chat/compact`,
    {method: 'POST'},
  );

/**
 * 2026-05-02 — chat-resolution metadata for the project's current AI provider. The header chip
 * uses {@code contextWindow} so the "200k window" / "1M window" segment matches the actual
 * model the chat resolves to (gpt-4o = 128k vs gpt-5.4 = 256k vs gemini = 1M).
 */
export interface ProjectChatInfo {
  model: string;
  provider: string;
  contextWindow: number;
}

export const getProjectChatInfo = (projectId: string) =>
  req<ProjectChatInfo>(`/projects/${projectId}/chat/info`);

// ── Migrations product (R-336..R-339) ─────────────────────────────────────

export interface MigrationRecipeSummary {
  id: string;
  slug: string;
  name: string;
  tagline: string | null;
  description: string | null;
  sourceStack: string;
  targetStack: string;
  affectedFilesGlob: string;
  category: string;
  tags: string | null;
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';
  source: 'FIRST_PARTY' | 'COMMUNITY';
  status: 'LISTED' | 'HIDDEN' | 'ARCHIVED';
  estFilesLow: number | null;
  estFilesHigh: number | null;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
  iconUrl: string | null;
  thumbnailUrl: string | null;
  samplePrUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MigrationListItem {
  recipe: MigrationRecipeSummary;
  locked: boolean;
  unlockTier: string | null;
}

export interface MigrationVariant {
  id: string;
  recipeId: string;
  optionsSignature: string;
  options: string;
  specTemplate: string;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
}

export interface MigrationSlot {
  id: string;
  recipeId: string;
  name: string;
  displayName: string;
  type: 'TEXT' | 'SELECT' | 'SECRET' | 'REPO_PICKER' | 'FILE_PATH' | 'NUMBER' | 'BOOLEAN';
  required: boolean;
  defaultValue: string | null;
  options: string | null;
  description: string | null;
  displayOrder: number;
}

export interface MigrationDependency {
  recipeId: string;
  dependsOnId: string;
  kind: 'PREREQUISITE' | 'RECOMMENDED_AFTER';
}

export interface MigrationDetail {
  recipe: MigrationRecipeSummary;
  variants: MigrationVariant[];
  slots: MigrationSlot[];
  dependencies: MigrationDependency[];
  locked: boolean;
  unlockTier: string | null;
}

export interface MigrationDryRunRepoApplicability {
  repoId: string;
  fullName: string | null;
  status: 'APPLICABLE' | 'INCOMPATIBLE_STACK' | 'UNKNOWN_STACK' | 'NOT_LINKED';
  reason: string | null;
  estFilesLow: number | null;
  estFilesHigh: number | null;
  estCostLow: string | null;
  estCostHigh: string | null;
}

export interface MigrationDryRunResult {
  slug: string;
  name: string;
  estCostLowTotal: string | null;
  estCostHighTotal: string | null;
  estFilesLowTotal: number | null;
  estFilesHighTotal: number | null;
  sampleDiff: string | null;
  perRepo: MigrationDryRunRepoApplicability[];
}

export interface MigrationApplyResponse {
  executionRunId: string;
  status: string;
  message: string;
}

export const listMigrations = (
  filters: {category?: string; tier?: string; search?: string} = {},
) => {
  const qs = new URLSearchParams();
  if (filters.category) qs.set('category', filters.category);
  if (filters.tier) qs.set('tier', filters.tier);
  if (filters.search) qs.set('search', filters.search);
  const q = qs.toString();
  return req<MigrationListItem[]>(`/migrations${q ? `?${q}` : ''}`);
};

export const getMigrationDetail = (slug: string) =>
  req<MigrationDetail>(`/migrations/${encodeURIComponent(slug)}`);

export const migrationDryRun = (slug: string, body: {projectId: string; repoIds: string[]}) =>
  req<MigrationDryRunResult>(`/migrations/${encodeURIComponent(slug)}/dry-run`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const migrationApply = (
  slug: string,
  body: {
    projectId: string;
    repoIds: string[];
    slotValues?: Record<string, string>;
    optionsSignature?: string;
    branch?: string;
  },
) =>
  req<MigrationApplyResponse>(`/migrations/${encodeURIComponent(slug)}/apply`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ── Readymade Solutions library (R-327..R-331) ────────────────────────────

export interface ReadymadeSolutionSummary {
  id: string;
  slug: string;
  name: string;
  tagline: string | null;
  description: string | null;
  category: string;
  macroGroup: string;
  tags: string | null;
  kind: 'PRIMITIVE' | 'STACK' | 'INTEGRATION';
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';
  source: 'FIRST_PARTY' | 'COMMUNITY';
  status: 'LISTED' | 'HIDDEN' | 'ARCHIVED';
  iconUrl: string | null;
  thumbnailUrl: string | null;
  samplePrUrl: string | null;
  estFilesLow: number | null;
  estFilesHigh: number | null;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
  authorUserId: string | null;
  authorOrgId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReadymadeListItem {
  solution: ReadymadeSolutionSummary;
  locked: boolean;
  unlockTier: string | null;
}

export interface ReadymadeVariant {
  id: string;
  solutionId: string;
  language: string;
  framework: string;
  options: string | null;
  specTemplate: string;
  scaffoldKey: string | null;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
  createdAt: string;
}

export interface ReadymadeSlot {
  id: string;
  solutionId: string;
  name: string;
  displayName: string;
  type: 'TEXT' | 'SELECT' | 'SECRET' | 'REPO_PICKER' | 'FILE_PATH' | 'NUMBER' | 'BOOLEAN';
  required: boolean;
  defaultValue: string | null;
  options: string | null;
  description: string | null;
  displayOrder: number;
}

export interface ReadymadeDependency {
  solutionId: string;
  dependsOnId: string;
  required: boolean;
}

export interface ReadymadeDetail {
  solution: ReadymadeSolutionSummary;
  variants: ReadymadeVariant[];
  slots: ReadymadeSlot[];
  dependencies: ReadymadeDependency[];
  locked: boolean;
  unlockTier: string | null;
}

export interface VariantResolutionResult {
  bestMatch: ReadymadeVariant | null;
  availableVariants: ReadymadeVariant[];
  detectedLanguage: string | null;
  detectedFramework: string | null;
  reason: 'OK' | 'NO_VARIANT_FOR_STACK' | 'STACK_NOT_DETECTED' | 'REPO_NOT_LINKED';
}

export interface LibraryFacets {
  categories: Record<string, number>;
  macroGroups: Record<string, number>;
}

export const listLibrary = (
  filters: {category?: string; macroGroup?: string; tier?: string; search?: string} = {},
) => {
  const qs = new URLSearchParams();
  if (filters.category) qs.set('category', filters.category);
  if (filters.macroGroup) qs.set('macro_group', filters.macroGroup);
  if (filters.tier) qs.set('tier', filters.tier);
  if (filters.search) qs.set('search', filters.search);
  const q = qs.toString();
  return req<ReadymadeListItem[]>(`/library${q ? `?${q}` : ''}`);
};

export const getLibraryDetail = (slug: string) =>
  req<ReadymadeDetail>(`/library/${encodeURIComponent(slug)}`);

export const getLibraryFacets = () => req<LibraryFacets>(`/library/categories`);

export const resolveLibraryVariant = (slug: string, projectId: string, repoId: string) =>
  req<VariantResolutionResult>(
    `/library/${encodeURIComponent(slug)}/resolve?projectId=${encodeURIComponent(projectId)}&repoId=${encodeURIComponent(repoId)}`,
    {method: 'POST'},
  );

/** R-331 — instantiate a solution into a V2 run (slot-substituted spec → ExecutionOrchestrator). */
export const instantiateLibrarySolution = (
  slug: string,
  body: {
    projectId: string;
    repoIds: string[];
    slotValues?: Record<string, string>;
    variantId?: string;
    branch?: string;
  },
) =>
  req<{executionRunId: string; status: string; message: string}>(
    `/library/${encodeURIComponent(slug)}/instantiate`,
    {method: 'POST', body: JSON.stringify(body)},
  );

export const favouriteLibrarySolution = (slug: string) =>
  req<void>(`/library/favourite/${encodeURIComponent(slug)}`, {method: 'POST'});

export const unfavouriteLibrarySolution = (slug: string) =>
  req<void>(`/library/favourite/${encodeURIComponent(slug)}`, {method: 'DELETE'});

/**
 * R-341 — tier-stratified cost-cap predicate.  Mirrors the backend's
 * {@code TierEnforcementService.requiresCostCap}.  Drives the
 * Readymades + Migrations cost-UI: FREE/PRO without BYOK get the
 * cost-cap input; TEAM/ENTERPRISE (and PRO with BYOK) get the
 * read-only "Est. $X on your keys" chip instead.
 */
export function requiresCostCap(user: AuthUser | null | undefined): boolean {
  if (!user) return true; // anonymous → treat as FREE-equivalent
  const tier = user.tier;
  if (tier === 'ENTERPRISE' || tier === 'TEAM') return false;
  const hasByok = !!(user.hasOpenAiKey || user.hasAnthropicKey || user.hasGeminiKey);
  if (tier === 'PRO') return !hasByok;
  return true; // FREE
}

// ── v2.6.4 — PR Review + Code Review agents (paid tier only) ─────────────────
//
// Both agents are gated on backend at PRO/TEAM/MAX/ENTERPRISE; FREE → 403, quota
// exhausted → 429. The frontend treats both shapes identically — an error-shaped
// response (toast / disabled button) — and surfaces the message verbatim.

export interface PrReviewDto {
  id: string;
  projectId: string;
  repoFullName: string;
  prNumber: number;
  headSha: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETE' | 'FAILED';
  verdict: 'APPROVE' | 'REQUEST_CHANGES' | 'COMMENT' | null;
  summary: string | null;
  costUsd: number | null;
  durationMs: number | null;
  startedAt: string;
  finishedAt: string | null;
  failureReason: string | null;
}

export interface PrReviewCommentDto {
  id: string;
  filePath: string;
  lineNumber: number;
  side: 'LEFT' | 'RIGHT';
  category: string | null;
  severity: 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO' | null;
  body: string;
}

export interface PrReviewWithComments {
  review: PrReviewDto;
  comments: PrReviewCommentDto[];
}

export const submitPrReview = (body: {projectId: string; repoFullName: string; prNumber: number}) =>
  req<PrReviewDto>('/pr-reviews', {method: 'POST', body: JSON.stringify(body)});

export const getPrReview = (id: string) => req<PrReviewWithComments>(`/pr-reviews/${id}`);

export const listPrReviewsForPr = (repoFullName: string, prNumber: number) =>
  req<PrReviewDto[]>(`/pr-reviews?repo=${encodeURIComponent(repoFullName)}&number=${prNumber}`);

export const listPrReviewsForProject = (projectId: string) =>
  req<PrReviewDto[]>(`/projects/${projectId}/pr-reviews`);

export interface CodeReviewScanDto {
  id: string;
  projectId: string;
  repoId: string | null;
  repoFullName: string;
  scannedSha: string;
  status: 'QUEUED' | 'CLONING' | 'STATIC_RUNNING' | 'LLM_RUNNING' | 'COMPLETE' | 'FAILED';
  triggerSource: 'MANUAL' | 'SCHEDULED' | 'POST_MERGE';
  totalFilesScanned: number | null;
  totalLoc: number | null;
  staticFindingsCount: number | null;
  llmFindingsCount: number | null;
  llmFilteredOutCount: number | null;
  costUsd: number | null;
  durationMs: number | null;
  startedAt: string;
  finishedAt: string | null;
  failureReason: string | null;
}

export interface CodeReviewIssueDto {
  id: string;
  fingerprint: string;
  severity: 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO';
  category: string;
  source: 'STATIC' | 'LLM' | 'HYBRID';
  ruleId: string | null;
  filePath: string;
  lineStart: number;
  lineEnd: number;
  message: string;
  suggestedFix: string | null;
  status: 'OPEN' | 'RESOLVED' | 'DISMISSED' | 'FALSE_POSITIVE';
}

export interface CodeReviewScanWithIssues {
  scan: CodeReviewScanDto;
  reportMarkdown: string | null;
  issues: CodeReviewIssueDto[];
}

export const submitCodeReview = (body: {
  projectId: string;
  repoId?: string;
  repoFullName: string;
}) => req<CodeReviewScanDto>('/code-reviews', {method: 'POST', body: JSON.stringify(body)});

export const getCodeReview = (id: string) => req<CodeReviewScanWithIssues>(`/code-reviews/${id}`);

export const listCodeReviewsForProject = (projectId: string) =>
  req<CodeReviewScanDto[]>(`/projects/${projectId}/code-reviews`);

export const getLatestCodeReviewForRepo = (projectId: string, repoFullName: string) =>
  req<CodeReviewScanWithIssues | null>(
    `/projects/${projectId}/code-reviews/latest?repo=${encodeURIComponent(repoFullName)}`,
  );

/**
 * Direct-link to the markdown download — handy for the chat surface to embed an
 * "Open report" link and for the Code Quality tab's Download button.
 */
export const codeReviewReportDownloadUrl = (id: string) => `${BASE}/code-reviews/${id}/report`;

// ── V175 — external project sharing (invites + public share links) ──────────────

export type ProjectInviteRole = 'PROJECT_ADMIN' | 'PROJECT_DEVELOPER' | 'PROJECT_VIEWER';
export type ProjectInviteStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';

export interface ProjectInviteDto {
  id: string;
  projectId: string;
  email: string;
  projectRole: ProjectInviteRole;
  status: ProjectInviteStatus;
  expiresAt: string;
  createdAt: string;
  invitedByUserId: string;
}

export interface InvitePreview {
  email: string;
  projectId: string;
  projectRole: ProjectInviteRole;
  status: ProjectInviteStatus;
  expiresAt: string;
}

export const createProjectInvite = (
  projectId: string,
  body: {email: string; role: ProjectInviteRole},
) =>
  req<ProjectInviteDto>(`/projects/${projectId}/invites`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const listProjectInvites = (projectId: string) =>
  req<ProjectInviteDto[]>(`/projects/${projectId}/invites`);

export const revokeProjectInvite = (projectId: string, inviteId: string) =>
  req<void>(`/projects/${projectId}/invites/${inviteId}`, {method: 'DELETE'});

export const previewProjectInvite = (token: string) =>
  req<InvitePreview>(`/invites/preview?token=${encodeURIComponent(token)}`);

export const acceptProjectInvite = (token: string) =>
  req<{projectId: string}>(`/invites/accept?token=${encodeURIComponent(token)}`, {method: 'POST'});

export type ShareLinkSurface =
  | 'PROJECT_OVERVIEW'
  | 'RUN_DETAIL'
  | 'CODE_REVIEW'
  | 'PR_REVIEW'
  | 'VALIDATION_REPORT'
  | 'ARCHITECTURE_DOC';

export interface ShareLinkDto {
  id: string;
  projectId: string;
  token: string;
  surface: ShareLinkSurface;
  scopeId: string | null;
  label: string | null;
  createdByUserId: string;
  expiresAt: string | null;
  revokedAt: string | null;
  views: number;
  lastViewedAt: string | null;
  createdAt: string;
  active: boolean;
}

export interface ShareLinkListResponse {
  links: ShareLinkDto[];
  activeCount: number;
}

export const createShareLink = (
  projectId: string,
  body: {surface: ShareLinkSurface; scopeId?: string; label?: string; expiresInDays?: number},
) =>
  req<ShareLinkDto>(`/projects/${projectId}/share-links`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const listShareLinks = (projectId: string) =>
  req<ShareLinkListResponse>(`/projects/${projectId}/share-links`);

export const revokeShareLink = (projectId: string, linkId: string) =>
  req<void>(`/projects/${projectId}/share-links/${linkId}`, {method: 'DELETE'});

/**
 * Public share-link URL for the dashboard owner to copy. Frontend lives at
 * `/shared/:token` (added in v2.6.4) which calls these GETs to render the surface.
 */
export const shareLinkPublicUrl = (token: string) =>
  `${window.location.origin}/shared/${encodeURIComponent(token)}`;
