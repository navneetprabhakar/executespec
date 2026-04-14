export type ExecutionRunStatus =
  | 'SETUP'
  | 'ANALYSING'
  | 'ARCH_REVIEW'
  | 'PLANNING'
  | 'EXECUTING'
  | 'PAUSED'
  | 'COMPLETE'
  | 'ABORTED';

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
  launchId: string;
  createdAt: string | null;
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
