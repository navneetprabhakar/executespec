// Typed API client — all backend endpoints

export const BASE = '/api';

/** Returns the stored access token for authenticated requests. */
export function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? {Authorization: `Bearer ${token}`} : {};
}

/** Status code → human-readable fallback messages */
export const STATUS_MESSAGES: Record<number, string> = {
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
export function parseErrorMessage(status: number, raw: string): string {
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

let refreshInFlight: Promise<boolean> | null = null;

function shouldAttemptRefresh(url: string): boolean {
  return (
    !!localStorage.getItem('access_token') &&
    !url.startsWith('/auth/login') &&
    !url.startsWith('/auth/register') &&
    !url.startsWith('/auth/refresh') &&
    !url.startsWith('/auth/logout')
  );
}

async function refreshAccessTokenOnce(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = fetch(BASE + '/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: {'Content-Type': 'application/json'},
    })
      .then(async (res) => {
        if (!res.ok) return false;
        const text = await res.text();
        if (!text.trim()) return false;
        const json = JSON.parse(text) as {accessToken?: string};
        if (!json.accessToken) return false;
        localStorage.setItem('access_token', json.accessToken);
        return true;
      })
      .catch(() => false)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

async function fetchJson(url: string, opts?: RequestInit): Promise<Response> {
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
    const headers = {'Content-Type': 'application/json', ...authHeader(), ...opts?.headers};
    res = await fetch(BASE + url, {
      ...opts,
      headers,
      credentials: 'include', // send refresh_token cookie
    });
  } catch (e) {
    const reason = e instanceof Error ? e.message : 'unknown';
    throw new ApiError(
      0,
      `Can't reach the server (${reason}). Check your connection and try again.`,
    );
  }
  return res;
}

async function fetchText(url: string): Promise<Response> {
  try {
    return await fetch(BASE + url, {
      headers: {...authHeader()},
      credentials: 'include',
    });
  } catch (e) {
    const reason = e instanceof Error ? e.message : 'unknown';
    throw new ApiError(
      0,
      `Can't reach the server (${reason}). Check your connection and try again.`,
    );
  }
}

export async function req<T>(url: string, opts?: RequestInit): Promise<T> {
  let res = await fetchJson(url, opts);
  if (res.status === 401 && shouldAttemptRefresh(url) && (await refreshAccessTokenOnce())) {
    res = await fetchJson(url, opts);
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
export async function reqText(url: string): Promise<string> {
  let res = await fetchText(url);
  if (res.status === 401 && shouldAttemptRefresh(url) && (await refreshAccessTokenOnce())) {
    res = await fetchText(url);
  }
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  return res.text();
}

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
  /** V183 — 'BUILD' (default planner+executor+reviewer flow) or 'PATCH' (thin single-agent loop, autofix / bench runs). */
  runMode?: 'BUILD' | 'PATCH' | null;
  /** V183 — finding source key for autofix-triggered patch runs. `<TYPE>:<id>`. */
  triggeredByFinding?: string | null;
  /** v2.5.8 — total USD spent on LLM calls for this run. Null until terminal. */
  actualCostUsd?: number | null;
  /**
   * v2.5.10 — explicit reason for the terminal status so the UI can explain
   * "PARTIAL because X" instead of rendering a silent badge. Values:
   *   CLEAN_COMPLETION | CLEAN_WITH_INTERMEDIATE_WARNINGS |
   *   FINAL_BUILD_FAILED | TESTS_FAILED:<n>_OF_<m> |
   *   REVIEWER_UNRESOLVED | REVIEWER_FAILED |
   *   ALL_TASKS_FAILED | LEGACY_UNKNOWN | NO_REVIEW_RESULT (PR #120)
   * Null on in-flight runs and on pre-v2.5.10 terminal runs that were never backfilled.
   */
  statusReason?: string | null;
  /**
   * PR-B (2026-05-13) — SCM-agnostic pull-request URL raised by this run (GitHub or Bitbucket).
   * Prefer this over {@link githubPrUrl}. Null when no PR has been opened yet.
   */
  scmPrUrl?: string | null;
  /**
   * PR-B (2026-05-13) — legacy GitHub-only PR URL. Identical to {@link scmPrUrl} on runs written
   * after the v2.5.x SCM split; retained for back-compat with pre-split runs that never got
   * migrated. UI should fall back to this when scmPrUrl is null.
   */
  githubPrUrl?: string | null;
  /**
   * RL-2026-06-019 — headline verified score (0-100), hydrated by the list endpoint from the
   * validation report in one batched query. Null until the run is terminal and scored.
   */
  verifiedScore?: number | null;
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
  /** Structured goal-doneness decision from the executor task boundary. */
  goalDoneness?: GoalDonenessDecisionData | null;
  /** Structured continuation / step-up / provider-change advice from the executor. */
  continuationDecision?: {
    decision?: string;
    selectedCandidate?: {
      provider?: string;
      model?: string;
      taskCategory?: string;
      qualityScore?: number;
      requiredQualityThreshold?: number;
      costPerMillionTokens?: number;
      latencyP50Ms?: number;
      latencyP95Ms?: number;
      benchmarkQualityPriorAvailable?: boolean;
      reasons?: string[];
    } | null;
    policy?: {
      requiredQualityThreshold?: number;
      maxProviderSwitches?: number;
      maxModelStepUps?: number;
      maxModelStepDowns?: number;
      requireConfirmationBeforeExpensiveContinuation?: boolean;
      maxTotalCostUsd?: number;
      maxTotalTokens?: number;
      maxTaskWallClockMs?: number;
      actingEnabled?: boolean;
      maxRerunsPerTask?: number;
    };
    reasons?: string[];
  } | null;
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

export interface GoalDonenessDecisionData {
  state?: string;
  recommendedDecision?: string;
  reason?: string;
  recommendedNextStep?: string;
  goalSummary?: string;
  completedItems?: string[];
  incompleteItems?: string[];
  evidence?: string[];
  risks?: string[];
  budgetCeilingReason?: string;
  inputTokens?: number;
  outputTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  reasoningTokens?: number;
  totalTokens?: number;
  costUsd?: number;
  wallClockMs?: number;
  userInputRequired?: boolean;
  anotherModelProviderMightHelp?: boolean;
}

// Review types (mirrors Java ReviewReport / TaskReview / ReviewIssue)

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
  /** Durable final run-level proof derived from the validation-report ledger. */
  runGoalDoneness?: GoalDonenessDecisionData | null;
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

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

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

// RL-2026-05-046 — the run's live-log events (persisted SSE replay, 24h). Each entry is a flat
// RunEvent object: {type, timestamp, status?, taskId?, message?/line?, relativePath?, ...}.
export interface RunLogEvent {
  type: string;
  timestamp?: number;
  [k: string]: unknown;
}

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

// Messages (historical log)

export interface MessageResponse {
  fromAgent: string;
  contentSummary: string;
  sentAt: string;
}

/** Probe the preview container for API documentation endpoints (server-side proxy). */
export interface ApiDocsProbeResult {
  found: boolean;
  path: string | null;
  contentType: string | null; // 'json' | 'html' | 'other'
}

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

export interface ExecResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

// Conversation (pre-flight chat)

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

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

// Apply Suggestion (Chat Copilot / Review)

export interface ApplyResult {
  taskCount: number;
  tasks: {taskId: string; title: string}[];
}

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
  hasDeepSeekKey?: boolean;
  hasFireworksKey?: boolean;
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
  /** User-level default provider shared by web, CLI, and VS Code. */
  preferredAiProvider?: string | null;
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

export interface ValidationResult {
  valid: boolean;
  message: string;
}

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
  /** Enterprise OpenRouter gateway key flag. */
  hasOpenRouterKey?: boolean;
  hasDeepSeekKey?: boolean;
  hasFireworksKey?: boolean;
  // Key source mode & provider restrictions
  keySourceMode?: string;
  enabledProviders?: string[] | null;
  // JIRA integration flags (raw credentials never returned)
  hasJiraCredentials?: boolean;
  jiraBaseUrl?: string | null;
  // Confluence integration flags (raw credentials never returned)
  hasConfluenceCredentials?: boolean;
  confluenceBaseUrl?: string | null;
  /** True for the auto-provisioned solo workspace created at signup. */
  isPersonal?: boolean;
  /**
   * V198 — when true, ORG_ADMIN / SUPER_ADMIN may invite any email domain
   * (gmail, outlook, etc.) as project-only collaborators. Defaults
   * to false on new orgs. Toggle via {@link updateExternalSharing}.
   */
  externalSharingEnabled?: boolean;
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

/**
 * F3 — per-org model-matrix overrides. `defaults` + `overrides` are keyed by
 * `"<provider>:<tier>"` (e.g. `"anthropic:planning"`); the effective model for a
 * cell is `overrides[key] ?? defaults[key]`. `modelsByProvider` lists the models
 * selectable per provider. `editable` is true only for ENTERPRISE orgs.
 */
export interface ModelMatrixView {
  tier: string;
  editable: boolean;
  providers: string[];
  tiers: string[];
  defaults: Record<string, string>;
  overrides: Record<string, string>;
  modelsByProvider: Record<string, string[]>;
  /** Display labels keyed by canonical model id. Falls back to the id when absent. */
  modelLabels?: Record<string, string>;
}

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
  /** V191 (PR-B-part-2, 2026-05-13) — when this project was archived (null = ACTIVE). */
  archivedAt?: string | null;
  /**
   * V191 (PR-B-part-2) — when this project was soft-deleted (null = not deleted). Soft-deleted
   * rows are normally filtered server-side; this field is non-null only when an admin tool
   * fetches the tombstone explicitly.
   */
  deletedAt?: string | null;
}

export interface ProjectMemberDto {
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  projectRole: string;
  assignedAt: string;
}

// M-010 (Wave 2) — emergency halt-all-runs button on AdminPage.
export interface HaltAllRunsResult {
  haltedCount: number;
  haltedRunIds: string[];
  reason: string;
}

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

// Tier config
export interface TierConfig {
  tier: string;
  displayName: string;
  monthlyRunLimit: number;
  isFixed: boolean;
  description: string;
}

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

export interface TopupPackageAdmin {
  id: string;
  name: string;
  runCount: number;
  amountInr: number;
  amountUsd: number;
  active: boolean;
  sortOrder: number;
}

// Feature Flags (Admin)

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

export interface ReportIssue {
  description: string;
  severity: 'WOULD_BREAK_BUILD' | 'WOULD_FAIL_REVIEW' | 'WOULD_CAUSE_BUG' | 'WOULD_FAIL_LINT';
  fix: string | null;
  /**
   * Source of the issue. `ACCURACY_LLM` flags entries derived from
   * AccuracyAssessmentService's missingRequirements / partialRequirements lists —
   * these are LLM-inferred and may include hallucinations (Layer 1+3 mitigations
   * in place but not perfect). The UI groups these separately + labels them
   * advisory so users don't conflate them with reviewer-flagged findings.
   */
  stage: 'CODE_VALIDATOR' | 'COMPILER_AGENT' | 'REVIEWER_AGENT' | 'ACCURACY_LLM';
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
  rubricScore?: number | null;
  /** Auditable evidence behind verifiedScore. Source/confidence make self-scored signals explicit. */
  verificationLedger?: VerificationEvidence[];
  /** Conservative run-level proof derived from the final validation ledger. */
  runGoalDecision?: GoalDonenessDecisionData | null;
}

export interface VerificationEvidence {
  id: string;
  label: string;
  category: string;
  status: 'PASS' | 'WARN' | 'FAIL' | 'UNKNOWN' | 'NOT_APPLICABLE' | string;
  source: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | string;
  claim: string;
  summary: string;
  artifact: string;
  observed?: number | null;
  total?: number | null;
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

// Phase P1: PR review comment inbox

/**
 * 2026-05-07 — Phase P1: incoming PR review comments on PRs ExecuteSpec opened. Webhook
 * ingestion populates `pr_comment_inbox` server-side; the v3 RunDetail page polls these to
 * drive the action-row chip + side-panel ChatPane "pending comments" section.
 */
export interface PrCommentInboxDto {
  id: number;
  runId: string;
  repoFullName: string;
  prNumber: number;
  commentId: number;
  filePath: string | null;
  lineNumber: number | null;
  body: string;
  author: string;
  githubUrl: string;
  status: 'PENDING' | 'ADDRESSED' | 'DISMISSED';
  addressedRunId: string | null;
  createdAt: string;
}

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

// Payment Audit (Admin)

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

// Notification preferences (M-003 Wave 2)

export interface NotificationPrefRow {
  eventType: string; // canonical ActivityEventType name
  tier: 'HIGH_SIGNAL' | 'ADMIN_TOGGLE';
  channel: 'IN_APP' | 'EMAIL';
  enabled: boolean; // effective (override → override; else default)
  defaultEnabled: boolean;
  override: boolean; // true if the user has an explicit row
}

// SSO config (M-004 Wave 2)

export type SsoProvider = 'GOOGLE_OIDC' | 'OKTA_SAML' | 'AZURE_AD_SAML';

export interface SsoConfig {
  provider: SsoProvider | null;
  enforced: boolean;
  metadataUrl: string | null;
  domain: string | null;
  configured: boolean;
}

export interface GitHubRemoteRepo {
  fullName: string;
  defaultBranch: string;
  isPrivate: boolean;
}

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

// v2.5.2 — Tech Debt rollup for Project Overview. All DB-derived, no LLM.
export interface TechDebtReport {
  issuesByCategory: Record<string, number>;
  issuesBySeverity: Record<string, number>;
  topFiles: {filePath: string; issueCount: number}[];
  scoreTrend: {dimension: string; score: number; at: string}[];
  averageAccuracyScore: number;
  totalRunsAnalyzed: number;
}

export interface TechDebtFixResponse {
  runId: string;
  findingKey: string;
  outcome: 'DISPATCHED' | 'REUSED_ACTIVE';
}

// v2.5.2 — heuristic pre-submit Launch estimate (no LLM call).
export interface LaunchEstimate {
  estimatedTasksLow: number;
  estimatedTasksHigh: number;
  estimatedCostLow: number;
  estimatedCostHigh: number;
  basis: string;
}

// v2.5.2 — "Continue building" banner after a PR merge. Absent (null body)
// means no banner; entry lives 10 min in Redis post-merge.
export interface PostMergeBar {
  repoFullName: string;
  prNumber: number;
  mergedAt: string | null;
}

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

// E2.8 — contract drift detection
export interface DriftCheckResult {
  contractId: string;
  hasDrift: boolean;
  summary: string;
  eventId?: string | null;
}

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

// 2026-05-16 — Browse tab — list the actual files in a linked repo via the
// backend's GitHub-proxied endpoint. The backend uses the caller's stored
// PAT, so file reads stay within the user's GitHub permissions.
export interface RepoFileList {
  repoFullName: string;
  defaultBranch: string;
  files: string[];
}

// 2026-05-16 — Browse tab — fetch the raw text of one file. Cap-enforced
// to 1 MB on the backend; longer content is truncated with a trailing
// marker so the UI can warn the user without bailing entirely.
export interface RepoFile {
  repoFullName: string;
  defaultBranch: string;
  path: string;
  content: string;
  sizeBytes: number;
}

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

// Project Memory (v2.2.0)
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
 * 2026-05-02 — chat-resolution metadata for the project's current AI provider. The header chip
 * uses {@code contextWindow} so the "200k window" / "1M window" segment matches the actual
 * model the chat resolves to (gpt-4o = 128k vs gpt-5.4 = 256k vs gemini = 1M).
 */
export interface ProjectChatInfo {
  model: string;
  provider: string;
  contextWindow: number;
}

export interface ProjectChatMessageDto {
  id: string;
  projectId: string;
  orgId: string;
  userId: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  tokensIn?: number | null;
  tokensOut?: number | null;
  createdAt: string;
}

// Migrations product (R-336..R-339)

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

// Readymade Solutions library (R-327..R-331)

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

// v2.6.4 — PR Review + Code Review agents (paid tier only)
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

export interface PrReviewAutofixResponse {
  runId: string;
  findingKey: string;
  outcome: 'DISPATCHED' | 'REUSED_ACTIVE';
}

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

export interface CodeReviewAutofixResponse {
  runId: string;
  findingKey: string;
  outcome: 'DISPATCHED' | 'REUSED_ACTIVE';
}

// V175 — external project sharing (invites + public share links)

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
