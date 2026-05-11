// Pilot v3.0 — Project workspace types.

export type RepoScope = 'ORG' | 'USER';

export interface StackInfo {
  language: string;
  framework: string;
  version: string;
}

export interface ProjectRepo {
  id: string;
  scope: RepoScope;
  ownerOrgId: string | null;
  ownerUserId: string | null;
  githubRepoFullName: string;
  githubOwner: string;
  defaultBranch: string;
  lastSyncedSha: string | null;
  /** Raw JSON string of {@link StackInfo}; null when not yet detected. */
  detectedStack: string | null;
  /** v2.5.2 — PR webhook installation state. */
  webhookInstalled: boolean;
  /** R-322 — declared monorepo project root (null = repo root). Detected
   *  from {@code .executespec/root.yml} on the default branch at attach
   *  + sync time. */
  subdir: string | null;
  /** R-325 — GitHub-reported repo size at attach time, in KB. NULL when
   *  the row predates V156 or GH didn't return a size. */
  sizeKb: number | null;
  /** R-325 — TRUE when the attach bypassed the size-gate via the
   *  "Attach anyway" opt-in. Drives a per-run credit surcharge. */
  attachedAnyway: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectRepoLink {
  id: string;
  projectId: string;
  repoId: string;
  repo: ProjectRepo;
  relationships: string[];
  linkedAt: string;
  linkedBy: string;
}

export interface PrSummary {
  repoFullName: string;
  prNumber: number;
  prUrl: string;
  status: 'OPEN' | 'MERGED' | 'CLOSED';
  headBranch: string | null;
  baseBranch: string | null;
  createdAt: string;
  mergedAt: string | null;
  /**
   * 2026-05-11 (V186) — CI rollup status from the most recent
   * /projects/{id}/prs/refresh poll. Mirrors PullRequestRecord.CiCheckStatus.
   * UNKNOWN both pre-poll and when GitHub returns zero check-runs.
   */
  ciStatus?: 'UNKNOWN' | 'PENDING' | 'SUCCESS' | 'FAILURE' | 'NEUTRAL';
  /** Number of failing checks at the last poll. 0 unless ciStatus = FAILURE. */
  ciFailingCount?: number;
  /** ISO-8601 timestamp of the last successful check-runs poll; null pre-poll. */
  ciCheckedAt?: string | null;
}

export interface TaskSummary {
  taskId: string;
  runId: string;
  name: string;
  status: string;
  score: number | null;
  createdAt: string;
}

export interface ScoreEntry {
  runId: string;
  overallScore: number;
  codeQualityScore: number;
  testCoverageScore: number;
  architectureScore: number;
  securityScore: number;
  performanceScore: number;
  designComplianceScore: number | null;
  createdAt: string;
}

/**
 * v2.6.4 — per-surface cost breakdown so the project dashboard can show "where the spend went"
 * without an extra API call. Sum of these equals {@link ProjectDashboardDTO.totalCostUsd}.
 */
export interface ProjectCostBreakdown {
  runs: number; // pipeline_run.actual_cost_usd
  launches: number; // execution_run.total_cost_usd
  codeReviews: number; // code_review_scan.cost_usd
  prReviews: number; // pr_review.cost_usd
  architecture: number; // project_architecture_doc + repo_architecture_doc
  generatedDocs: number; // project_generated_doc
}

export interface ProjectDashboardDTO {
  activeRuns: number;
  completedRuns: number;
  failedRuns: number;
  avgScore: number;
  totalTokens: number;
  totalCostUsd: number;
  /** v2.6.4 — populated by ProjectDashboardController; older responses may omit it. */
  costBreakdown?: ProjectCostBreakdown;
  recentTasks: TaskSummary[];
  recentPrs: PrSummary[];
  architectureDocStatus: 'NONE' | 'GENERATED' | 'STALE';
  architectureDocVersion: number | null;
}

export interface ArchitectureDoc {
  id: string;
  version: number;
  content: string;
  userAnnotations: string | null;
  generatedAt: string;
}

export type Complexity = 'S' | 'M' | 'L';

export interface AssessmentResult {
  summary: string;
  complexity: Complexity;
  filesAffected: string[];
  newFiles: string[];
  risks: string[];
  crossRepoDeps: string[];
  /** Repos considered during assessment (added in pilot v3.0). */
  repoFullNames: string[];
}

export interface LinkRepoRequest {
  scope: RepoScope;
  githubRepoFullName: string;
  defaultBranch?: string;
  /** R-325 — when true, bypass the {@code executespec.repo.max-size-kb}
   *  hard reject. Set by RepoAttachPopover only after the user clicks
   *  "Attach anyway" on the size-gate prompt. Runs touching this repo
   *  count more credits. */
  attachAnyway?: boolean;
}

export interface ProjectSettings {
  branchNamingPattern: string;
  targetBranch: string;
  defaultReviewers: string[];
}
