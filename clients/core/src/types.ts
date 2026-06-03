/**
 * Wire types mirrored from the backend contract (api/dto, sse/RunEvent, auth/dto). Kept
 * deliberately close to the server records so the client is a faithful, thin projection — no
 * business logic, just shapes.
 */

// ── Auth / PAT ──────────────────────────────────────────────────────────────

export interface Me {
  id: string;
  email: string;
  tier?: string;
  globalRole?: string;
  orgId?: string | null;
}

export interface CreatePatRequest {
  name: string;
  /** null/undefined ⇒ never expires. */
  expiresInDays?: number | null;
}

export interface PatDto {
  id: string;
  name: string;
  tokenPrefix: string;
  scopes: string;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  revoked: boolean;
}

export interface CreatePatResponse {
  /** The plaintext `es_pat_…` credential — returned exactly once. */
  token: string;
  meta: PatDto;
}

// ── Runs ──────────────────────────────────────────────────────────────────

export type RunStatus =
  | 'PLANNING'
  | 'AWAITING_APPROVAL'
  | 'WORKING'
  | 'REVIEWING'
  | 'COMPLETE'
  | 'FAILED'
  | 'PARTIAL'
  | 'CANCELLED'
  | 'QUEUED';

export type AiProvider = 'ANTHROPIC' | 'OPENAI' | 'GEMINI' | 'GOOGLE' | 'OPENROUTER';

export type ExecutionMode = 'PRODUCTION' | 'PROTOTYPE';

/** Body for `POST /api/runs`. Only `userRequest` is required. */
export interface RunRequest {
  userRequest: string;
  source?: string;
  repoUrl?: string;
  repoMode?: 'NEW' | 'EXISTING';
  skipApproval?: boolean;
  executionMode?: ExecutionMode;
  aiProvider?: AiProvider;
  modelName?: string;
  projectId?: string;
  selectedRepoIds?: string[];
  tddMode?: boolean;
  branch?: string;
  userCostCap?: number;
  skipAutoPush?: boolean;
  // NOTE: run LOCALITY (REMOTE/LOCAL) is intentionally NOT a wire field yet. Shape B converges it
  // on the server's PipelineRun.executionLocality, which lands in Phase 3 (migration V223+). Until
  // the server accepts it a client field would be silently dropped (the old `localExecution`
  // boolean was exactly that dead field — removed here, design §10 #3). Re-added in Phase 3.
}

export interface RunSummary {
  runId: string;
  status: RunStatus;
  userRequest?: string;
  currentGate?: string | null;
  plannerModel?: string | null;
  workerModel?: string | null;
  reviewerModel?: string | null;
  repoUrl?: string | null;
  repoMode?: 'NEW' | 'EXISTING' | null;
  projectId?: string | null;
  executionMode?: ExecutionMode;
  aiProvider?: AiProvider;
  actualCostUsd?: number | null;
  statusReason?: string | null;
  scmPrUrl?: string | null;
  githubPrUrl?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export type TaskStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'WORKING'
  | 'REVIEWING'
  | 'COMPLETE'
  | 'FAILED'
  | 'PARTIAL'
  | 'BLOCKED';

export interface TaskSummary {
  taskId: string;
  title: string;
  complexity?: string;
  status: TaskStatus;
  provider?: string;
  model?: string;
  inputTokens?: number;
  outputTokens?: number;
  durationMs?: number;
  failureReason?: string | null;
}

export interface RunDetail extends RunSummary {
  failureReason?: string | null;
  tasks?: TaskSummary[];
  costEstimateLow?: number | null;
  costEstimateHigh?: number | null;
  hardCapUsd?: number | null;
  creditsCharged?: number | null;
  validationStatus?: string | null;
  verifiedScore?: number | null;
  accuracyScore?: number | null;
  reviewerVerdict?: string | null;
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsTotal?: number | null;
  batchAvailable?: boolean | null;
}

/** Body for `POST /api/runs/{id}/approve`. `creditsConfirmed` must match the run's charged credits. */
export interface ApprovalRequest {
  approved: boolean;
  comment?: string;
  creditsConfirmed?: number | null;
  executionMode?: 'REALTIME' | 'BATCH';
}

export interface RunCost {
  runId: string;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface RunListQuery {
  status?: RunStatus;
  projectId?: string;
  scope?: 'mine' | 'org' | 'all';
  archived?: boolean;
  page?: number;
  size?: number;
}

// ── SSE events ──────────────────────────────────────────────────────────────

/**
 * The server's `RunEvent` envelope. `payload` is a string (often pipe-delimited, e.g.
 * `"PLAN|1|0.50"`), or — for the local-execution `TOOL_CALL` event — a JSON-serialized
 * {@link ToolCallFrame}.
 */
export interface RunEvent {
  type: string;
  runId?: string;
  taskId?: string | null;
  payload?: string | null;
  timestamp?: string;
}

/** Known event types (the server may emit others; treat `RunEvent.type` as open). */
export const RunEventType = {
  RUN_STATUS: 'RUN_STATUS',
  GATE_OPENED: 'GATE_OPENED',
  GATE_RESOLVED: 'GATE_RESOLVED',
  COMPLETE: 'COMPLETE',
  COST_UPDATE: 'COST_UPDATE',
  TOKEN_USAGE: 'TOKEN_USAGE',
  RUN_DETAIL: 'RUN_DETAIL',
  TASK_STATUS: 'TASK_STATUS',
  LOG: 'LOG',
  FILE_WRITTEN: 'FILE_WRITTEN',
  /** Local-hands: server asks the client to run a tool against the local tree. */
  TOOL_CALL: 'TOOL_CALL',
} as const;

// ── Tool-proxy protocol (local-hands / remote-brain) ──────────────────────────

/**
 * One tool the server-side agent wants executed on the client's machine. Delivered as the
 * `payload` of a `TOOL_CALL` SSE event; the client runs it and POSTs a {@link ToolResultFrame}
 * to `/api/runs/{id}/tool-results`.
 */
export interface ToolCallFrame {
  callId: string;
  runId: string;
  taskId?: string | null;
  /** Tool name as the LLM sees it, e.g. `"writeFile"`, `"shell"`. */
  name: string;
  /** JSON arguments matching the server tool's input schema. */
  args: unknown;
}

/**
 * Result envelope — byte-compatible with the server's `ToolResult` serialization
 * (`{ ok, content }` on success; `{ ok:false, kind, message }` on error) so the agent loop sees
 * identical tool output whether execution was local or server-side.
 */
export interface ToolEnvelope {
  ok: boolean;
  content?: string;
  kind?: string;
  message?: string;
}

export interface ToolResultFrame {
  callId: string;
  result: ToolEnvelope;
}
