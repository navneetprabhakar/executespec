import {
  ApplyResult,
  AuthUser,
  ChatResponse,
  LaunchEstimate,
  PagedResponse,
  RunChatMessage,
  RunDetail,
  RunCostEvidence,
  RunReplayLog,
  RunSummary,
  TaskGraphNode,
  TestPlanData,
  UserRunsSummaryData,
  req,
} from './core';

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
  /**
   * When true, automatic model/provider continuation stops for confirmation before spending on the
   * rerun. This is a per-run tightening guard and does not override stricter server policy.
   */
  requireConfirmationBeforeExpensiveContinuation?: boolean;
  /**
   * When true, automatic model/provider continuation needs a compatible benchmark prior. When
   * false, the selected quality-ranked candidate may run even if unbenchmarked for that task type.
   */
  requireBenchmarkPriorForAutomaticContinuation?: boolean;
  /**
   * Optional LOW-task start tier. LIGHTWEIGHT starts simple worker tasks cheaper while preserving
   * automatic step-up; unlike modelName this is not a route pin.
   */
  lowComplexityWorkerStartTier?: 'WORKHORSE' | 'LIGHTWEIGHT';
}) => req<{runId: string}>('/runs', {method: 'POST', body: JSON.stringify(body)});

/**
 * RR-819 — one registered contract this plan intends to modify, as composed by the server while
 * the run parked at AWAITING_APPROVAL. `declaredConsumers` is the reason the friction exists: those
 * repositories declared themselves consumers of this contract, so the planned change breaks them.
 */
export interface PlanContractGateRow {
  contractId?: string | null;
  contractName?: string | null;
  type?: string | null;
  detectedPath?: string | null;
  declaredConsumers?: string[] | null;
  matchedPlanTasks?: string[] | null;
}

/**
 * RR-819 — the durable plan-approval contract gate report.
 *
 * `reportJson` is a serialized `PlanContractGateRow[]`. `reportDigest` is the server's SHA-256 over
 * that exact string; approval must echo it back so an acknowledgement collected against one set of
 * contracts cannot be replayed against a different one.
 */
export interface PlanContractGateReport {
  runId?: string | null;
  projectId?: string | null;
  reportJson?: string | null;
  reportDigest?: string | null;
  requiresTypedAck?: boolean | null;
  contractCount?: number | null;
  createdAt?: string | null;
  acknowledgedAt?: string | null;
  acknowledgedByUserId?: string | null;
  acknowledgementText?: string | null;
}

/**
 * RR-819 — what the approver must be shown before approving. The server answers 204 (no body) when
 * this plan touches no registered contract, which is the overwhelming majority of runs; `req`
 * surfaces that as `undefined`, and the gate UI stays absent.
 */
export const getPlanContractGate = (runId: string) =>
  req<PlanContractGateReport | undefined>(`/runs/${runId}/plan-contract-gate`);

export const approveRun = (
  id: string,
  approved: boolean,
  creditsConfirmed?: number,
  executionMode?: string,
  /**
   * RR-1459 — SHA-256 of the exact plan JSON the approver reviewed. `creditsConfirmed` is a price
   * check, not an identity check, so it passes whenever two runs happen to cost the same. The
   * server answers 409 on a mismatch; an omitted value is "not supplied", never "matched".
   */
  planFingerprint?: string,
  /**
   * RR-819 — the literal text the approver typed to acknowledge that this plan breaks contracts
   * other repositories declared themselves consumers of, together with the digest of the exact
   * report they were shown. The server refuses approval with 409 when the text is absent/wrong, and
   * with a distinct 409 when the digest is not the one currently on the run.
   */
  breakingChange?: {ack: string; reportDigest: string},
) =>
  req<void>(`/runs/${id}/approve`, {
    method: 'POST',
    body: JSON.stringify({
      approved,
      creditsConfirmed: creditsConfirmed ?? null,
      executionMode: executionMode ?? null,
      planFingerprint: planFingerprint ?? null,
      breakingChangeAck: breakingChange?.ack ?? null,
      breakingChangeReportDigest: breakingChange?.reportDigest ?? null,
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

export const approveContinuation = (runId: string, taskId: string) =>
  req<{
    status: string;
    runId: string;
    taskId: string;
    provider: string;
    model: string;
    postReview: boolean;
    message: string;
  }>(`/runs/${runId}/tasks/${taskId}/continuation/approve`, {method: 'POST'});

export const pauseRun = (runId: string) => req<void>(`/runs/${runId}/pause`, {method: 'POST'});

export const resumeRun = (runId: string) => req<void>(`/runs/${runId}/resume`, {method: 'POST'});

export const getUserRunsSummary = (days = 30) =>
  req<UserRunsSummaryData>(`/user-runs-summary?days=${days}`);

export const getLiveCost = (runId: string) => req<RunCostEvidence>(`/runs/${runId}/cost`);
export const getRunEventLog = (runId: string) =>
  req<RunReplayLog>(`/runs/${runId}/event-log/replay`);

export const submitContactForm = (subject: string, body: string) =>
  req<{message: string}>('/contact', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({subject, body}),
  });

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

/** V191 (PR-B-part-2) — reverse of run archive. Idempotent. */
export const unarchiveRun = (runId: string) =>
  req<{message: string}>(`/runs/${runId}/unarchive`, {method: 'POST'});

/**
 * V191 (PR-B-part-2) — soft-delete a run with 30-day undo window. Distinct from cancelling an
 * in-flight run (which uses DELETE /api/runs/{id}); soft-delete is for tombstoning terminal
 * runs. Only terminal runs (COMPLETE, FAILED, CANCELLED, PARTIAL) can be soft-deleted.
 */
export const softDeleteRun = (runId: string) =>
  req<{message: string}>(`/runs/${runId}/soft-delete`, {method: 'POST'});

/** V191 (PR-B-part-2) — undo a run soft-delete within the 30-day window. */
export const restoreRun = (runId: string) =>
  req<{message: string}>(`/runs/${runId}/restore`, {method: 'POST'});

export const editRunPlan = (runId: string, feedback: string) =>
  req<{status: string; version: number}>(`/runs/${runId}/edit-plan`, {
    method: 'POST',
    body: JSON.stringify({feedback}),
  });

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const getPlanVersions = (runId: string) => req<any[]>(`/runs/${runId}/plan-versions`);
export const getTaskGraph = (runId: string) => req<TaskGraphNode[]>(`/runs/${runId}/task-graph`);

export const getTestPlan = async (runId: string): Promise<TestPlanData | null> => {
  try {
    return await req<TestPlanData>(`/runs/${runId}/test-plan`);
  } catch {
    return null;
  }
};

export const runTests = (runId: string) =>
  req<{runId: string; testPlanId: number; previewUrl: string}>(`/runs/${runId}/run-tests`, {
    method: 'POST',
  });

/** Fetch a short-lived scoped SSE token for a specific run. */
export const getSseToken = (runId: string) => req<{token: string}>(`/runs/${runId}/sse-token`);

// Sync a terminal run's workspace from a branch (post-merge re-review).
export const syncRun = (runId: string, branch?: string) =>
  req<{status: string; reposSynced: number; details: string[]}>(
    `/runs/${runId}/sync${branch ? `?branch=${encodeURIComponent(branch)}` : ''}`,
    {method: 'POST'},
  );
export const estimateRun = (
  projectId: string,
  requirement: string,
  provider?: string,
  model?: string,
) => {
  const params = new URLSearchParams({requirement});
  if (provider) params.set('provider', provider);
  if (model) params.set('model', model);
  return req<LaunchEstimate>(`/projects/${projectId}/estimate?${params.toString()}`);
};

// v2.5.2 — reset workspace to the exact commit SHAs captured at run start.
// Returns { reposSynced: 0, details: ["no snapshot — run predates v2.5.2"] }
// for runs clone before the snapshot rollout.
export const syncRunToOriginal = (runId: string) =>
  req<{status: string; reposSynced: number; details: string[]}>(`/runs/${runId}/sync/original`, {
    method: 'POST',
  });

// PR list for a run (R5)
export const getRunPrs = (runId: string) =>
  req<import('@/types/project').PrSummary[]>(`/runs/${runId}/prs`);

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
  const hasByok = !!(
    user.hasOpenAiKey ||
    user.hasAnthropicKey ||
    user.hasGeminiKey ||
    user.hasDeepSeekKey ||
    user.hasFireworksKey
  );
  if (tier === 'PRO') return !hasByok;
  return true; // FREE
}
