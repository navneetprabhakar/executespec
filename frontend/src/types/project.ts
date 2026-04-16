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

export interface ProjectDashboardDTO {
  activeRuns: number;
  completedRuns: number;
  failedRuns: number;
  avgScore: number;
  totalTokens: number;
  totalCostUsd: number;
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
}

export interface ProjectSettings {
  branchNamingPattern: string;
  targetBranch: string;
  defaultReviewers: string[];
}
