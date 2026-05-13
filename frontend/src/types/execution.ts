export type ExecutionRunStatus =
  | 'SETUP'
  | 'ANALYSING'
  | 'ARCH_REVIEW'
  | 'PLANNING'
  | 'EXECUTING'
  | 'PAUSED'
  | 'COMPLETE'
  | 'ABORTED'
  | 'FAILED';

export type SolutionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'VALIDATING'
  | 'PR_RAISED'
  | 'DONE'
  | 'FAILED'
  | 'SKIPPED';

export type FailurePhase = 'RUNNING' | 'VALIDATING' | 'PR';

export type DecisionAction = 'SKIP' | 'RETRY' | 'FIX' | 'ATTEMPT_ANYWAY';

export interface SolutionRepoDto {
  repoId: string | null;
  executionBranch: string | null;
  targetFiles: string | null;
  actualFiles: string | null;
  codemapVersion: number;
}

export interface SolutionDto {
  id: string;
  sequenceNumber: number;
  title: string;
  description: string | null;
  status: SolutionStatus;
  failurePhase: FailurePhase | null;
  failureReason: string | null;
  retryCount: number;
  complexity: string | null;
  estimatedTokens: number | null;
  actualTokens: number;
  costUsd: number;
  pipelineRunId: string | null;
  prNumber: number | null;
  prUrl: string | null;
  prStatus: string | null;
  jiraTicketId: string | null;
  jiraEnabled: boolean;
  startedAt: string | null;
  completedAt: string | null;
  dependencyIds: string[];
  repos: SolutionRepoDto[];
}

export interface ExecutionRunDto {
  id: string;
  projectId: string;
  status: ExecutionRunStatus;
  requirement: string;
  unifiedArchDoc: string | null;
  archApprovedAt: string | null;
  /** JSON-stringified ExecutionPlan from the backend. */
  executionPlan: string | null;
  planApprovedAt: string | null;
  totalEstimatedTokens: number | null;
  totalActualTokens: number;
  totalCostUsd: number;
  jiraEnabled: boolean;
  confluenceUrl: string | null;
  figmaUrl: string | null;
  solutions: SolutionDto[];
  createdAt: string;
  updatedAt: string;
  /**
   * V188 (2026-05-13) — unified-dispatch link. When set, the entire execution runs as one V2
   * pipeline_run (multi-repo, hierarchical-planned). The V3 page should render the V2 run's plan +
   * task progress instead of per-Solution rows. NULL for legacy executions that fanned out into
   * per-Solution V2 runs.
   */
  pipelineRunId?: string | null;
  /**
   * PR-B (2026-05-13) — primary SCM PR URL for this launch. Populated for {@code
   * kind=PIPELINE_RUN} entries from the underlying {@code pipeline_run.scm_pr_url}. EXECUTION-kind
   * entries leave it null — they aggregate multiple PRs across solutions; drill into the execution
   * detail page for the full list. UI surfaces this as a click-through pill on the launches list.
   */
  scmPrUrl?: string | null;
  /**
   * PR-B (2026-05-13) — legacy githubPrUrl fallback. Use {@link scmPrUrl} first; fall back to this
   * for pre-SCM-split rows.
   */
  githubPrUrl?: string | null;
  /**
   * 2026-05-04 — discriminator for the merged Launches list.
   * - `EXECUTION` — real `execution_run` row, viewable at `/projects/{id}/executions/{id}`.
   * - `PIPELINE_RUN` — V2 `pipeline_run` synthesised into the same list (project chat / V3
   *   composer launches), viewable at `/projects/{id}/runs/{id}` (the V3 RunDetailPage).
   *
   * Optional for back-compat with older API responses; treat missing as `EXECUTION` to preserve
   * existing routing for ExecutionRun-only deployments. ProjectLaunchesList uses this to pick
   * the right destination route — without it, every click landed on `/executions/{id}` which
   * 404s for pipeline_run ids and falls through to the new-launch setup form.
   */
  kind?: 'EXECUTION' | 'PIPELINE_RUN';
}

export interface SolutionSpec {
  sequenceNumber: number;
  title: string;
  description: string;
  repoId: string;
  repoName: string;
  targetFiles: string[];
  dependsOn: number[];
  estimatedTokens: number;
  complexity: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface DependencyEdge {
  from: number;
  to: number;
}

export interface FileConflict {
  file: string;
  repoName: string;
  solutions: number[];
  suggestedResolution: string;
}

export interface ExecutionPlan {
  solutions: SolutionSpec[];
  dependencyEdges: DependencyEdge[];
  conflicts: FileConflict[];
  totalEstimatedTokens: number;
  totalEstimatedCostUsd: number;
}

export interface NewRepoSpec {
  name: string;
  description?: string;
  techHint: string;
  githubOwner: string;
  visibility: 'private' | 'public';
}

export interface Scaffold {
  key: string;
  label: string;
  language: string;
}

export interface SolutionRun {
  id: string;
  solutionId: string;
  pipelineRunId: string;
  attemptNumber: number;
  terminalStatus: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  costUsd: number | null;
  startedAt: string;
  completedAt: string | null;
}

export interface PrRollupEntry {
  prUrl: string;
  prNumber: number | null;
  repoFullName: string | null;
  status: string;
  /** Run / launch id when {@link kind} = 'RUN'. Null for system PRs. */
  launchId: string | null;
  createdAt: string | null;
  /**
   * V169 (2026-05-02) — origin classifier. `RUN` = per-run code push.
   * `BOOTSTRAP` / `ARCHITECTURE` / `CONTRACT` / `CHAT_TOOL` = system PRs
   * opened by the corresponding ExecuteSpec subsystem. UI groups by this
   * to keep the dashboard rollup readable.
   */
  kind?: 'RUN' | 'BOOTSTRAP' | 'ARCHITECTURE' | 'CONTRACT' | 'CHAT_TOOL' | string;
  /**
   * V186 (2026-05-11) — CI rollup from the latest PrRefreshService poll.
   * UNKNOWN both pre-poll and when GitHub returns zero check-runs (= no CI
   * configured). UI hides the chip on UNKNOWN + NEUTRAL.
   */
  ciStatus?: 'UNKNOWN' | 'PENDING' | 'SUCCESS' | 'FAILURE' | 'NEUTRAL';
  ciFailingCount?: number;
  ciCheckedAt?: string | null;
}

export interface ProjectOverview {
  activeLaunches: number;
  completedLaunches: number;
  failedLaunches: number;
  totalActualTokens: number;
  totalCostUsd: number;
  prCount: number;
  recentPrOpen: number;
  recentPrMerged: number;
}

export interface ValidateOwnerResult {
  canCreate: boolean;
  reason: string;
}

export interface CreateExecutionRequest {
  requirement: string;
  repoIds: string[];
  newRepos?: NewRepoSpec[];
  jiraEnabled?: boolean;
  confluenceUrl?: string;
  figmaUrl?: string;
  aiProvider?: string;
  /** Optional branch override. Null/empty = clone each repo on its default branch. */
  branch?: string;
  /** v2.5.7 — continue-on-PR: ID of the execution that created the original PR. */
  sourceExecutionId?: string;
  /** v2.5.7 — continue-on-PR: repo full name (e.g. "owner/repo"). */
  continuePrRepoFullName?: string;
  /** v2.5.7 — continue-on-PR: PR number to address. */
  continuePrNumber?: number;
}

/** v2.5.7 — pre-fill config for the "Address review" flow. */
export interface ContinueOnPrConfig {
  sourceExecutionId: string;
  prNumber: number;
  repoFullName: string;
  headBranch: string;
  reviewComments: string;
}

export interface ArchApprovalRequest {
  approved: boolean;
  annotations?: string;
}

export interface DecisionRequest {
  action: DecisionAction;
}

export interface RepoAnalysisStatus {
  repoId: string;
  repoName: string;
  codemapStatus: 'PENDING' | 'RUNNING' | 'DONE';
  archDocStatus: 'PENDING' | 'RUNNING' | 'DONE';
}
