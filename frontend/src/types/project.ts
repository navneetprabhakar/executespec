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
  sourceReadinessStatus:
    | 'UNKNOWN'
    | 'LINKED'
    | 'AUTH_VALIDATED'
    | 'SOURCE_READABLE'
    | 'INVENTORIED'
    | 'INDEXING'
    | 'READY'
    | 'BLOCKED';
  sourceReadinessReason: string | null;
  sourceFileCount: number | null;
  sourceReadinessCheckedAt: string | null;
  executespecFolderStatus:
    | 'NOT_BOOTSTRAPPED'
    | 'BOOTSTRAP_PR_OPEN'
    | 'BOOTSTRAPPED'
    | 'BOOTSTRAP_FAILED';
  executespecBootstrapPrUrl: string | null;
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
  /** V278 — neutral source identity. Legacy GitHub fields above stay populated for SCM rows;
   *  LOCAL rows carry opaque client-minted identities and a lease-derived connection status. */
  sourceType?: 'SCM' | 'LOCAL' | null;
  scmProvider?: string | null;
  scmRepositoryId?: string | null;
  scmCloneUrl?: string | null;
  sourceKey?: string | null;
  sourceDisplayName?: string | null;
  localFolderId?: string | null;
  localInstallationId?: string | null;
  sourceRevision?: string | null;
  sourceCapabilities?: string[] | null;
  sourceMetadata?: Record<string, string> | null;
  sourceLastSeenAt?: string | null;
  sourceLeaseExpiresAt?: string | null;
  /** ONLINE while the connector lease is unexpired; OFFLINE otherwise. Null for SCM rows. */
  localConnectionStatus?: 'ONLINE' | 'OFFLINE' | null;
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

/** V286 — source-bound semantic chunk coverage. This is not a symbol/call relationship graph. */
export interface ProjectCodeIndexReadiness {
  projectId: string;
  repoId: string;
  currentSourceSha: string;
  indexedSourceSha: string | null;
  status: 'UNINDEXED' | 'INDEXING' | 'READY' | 'PARTIAL' | 'STALE' | 'FAILED';
  reason: string | null;
  discoveredFiles: number;
  eligibleFiles: number;
  indexedFiles: number;
  refreshedFiles: number;
  unchangedFiles: number;
  skippedFiles: number;
  deletedFiles: number;
  failedFiles: number;
  indexedChunks: number;
  coveragePercent: number;
  inventoryTruncated: boolean;
  semanticSearchAvailable: boolean;
  lexicalFallbackAvailable: boolean;
  relationshipGraphAvailable: boolean;
  retrievalMode: 'SEMANTIC' | 'LEXICAL';
  embeddingProvider: string | null;
  embeddingModel: string | null;
}

export interface ProjectCodeIndexStatus {
  repoName: string;
  readiness: ProjectCodeIndexReadiness;
}

/**
 * Result of an explicit source-index refresh request.
 *
 * The server may reject semantic work for the selected project route while still returning 202:
 * callers must inspect `status` and `reason` instead of treating every accepted HTTP response as
 * proof that provider-backed indexing was queued.
 */
export interface ProjectCodeIndexRefreshAccepted {
  repoId: string;
  status: 'QUEUED' | 'UNAVAILABLE' | 'PARTIAL' | 'READY';
  fallback: 'RIPGREP';
  relationshipGraphAvailable: false;
  reason: string | null;
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
  /**
   * Server-explained cause for an UNKNOWN ciStatus (e.g. `WEBHOOK_NOT_INSTALLED`). Absent on
   * older servers; the UI must render unchanged when missing.
   */
  ciStatusReason?: string | null;
  /** Linked repository id for webhook installation. Absent on older servers. */
  repoId?: string | null;
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
  /** Reports rejected by deterministic validity checks and omitted from avgScore. */
  excludedScoreReports?: number;
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
  // null in the /versions LIST payload (content is omitted there to keep the list flat as docs
  // grow); always present on the latest/current doc endpoint.
  content: string | null;
  userAnnotations: string | null;
  generatedAt: string;
}

export type ArchitectureJobStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'NEEDS_CONFIRMATION';

export interface ArchitectureJob {
  id: string;
  projectId: string;
  status: ArchitectureJobStatus;
  phase: 'PREPARING' | 'EXECUTING' | 'PUBLISHING';
  terminal: boolean;
  architectureVersion: number | null;
  attemptCount: number;
  deadlineAt: string;
  errorCode: string | null;
  errorMessage: string | null;
  qualityStatus?: 'QUALIFIED' | 'WARNING' | 'FAILED' | null;
  /** Canonical JSON evidence emitted by the server for operator diagnosis. */
  qualityEvidence?: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
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
  scmProvider: 'GITHUB' | 'BITBUCKET';
  repoFullName: string;
  scmRepositoryId?: string;
  /** Compatibility alias accepted by old servers; new callers should not send it. */
  githubRepoFullName?: string;
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
