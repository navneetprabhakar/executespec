import type {
  ProjectDashboardDTO,
  ProjectRepo,
  ProjectRepoLink,
  ArchitectureDoc,
  ScoreEntry,
  LinkRepoRequest,
  RepoScope,
} from '@/types/project';
import {
  ActivityPage,
  AdrDetail,
  AdrSummary,
  ApiError,
  ArchDiffReport,
  BASE,
  CodeReviewScanDto,
  CodeReviewScanWithIssues,
  ComplianceReport,
  ContractCoverageResponse,
  DriftCheckResult,
  GeneratedDocRow,
  GeneratedDocType,
  GitHubRemoteRepo,
  InvariantSummary,
  InvariantViolation,
  InvitePreview,
  PrReviewDto,
  ProjectChatInfo,
  ProjectChatMessageDto,
  ProjectDocumentRow,
  ProjectDto,
  ProjectInviteDto,
  ProjectInviteRole,
  ProjectMemoryResponse,
  ProjectSlackChannelDto,
  RepoFile,
  RepoFileList,
  RunLedgerEntry,
  RunSummary,
  SharedReportData,
  SpecTemplate,
  StalenessReport,
  TechDebtFixResponse,
  TechDebtReport,
  TestReportData,
  ValidationReportData,
  WorkspaceHealthReport,
  authHeader,
  parseErrorMessage,
  req,
} from './core';

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

/**
 * V191 (PR-B-part-2, 2026-05-13) — reverse of {@link archiveProject}. Idempotent on the backend:
 * unarchiving an already-active project returns 200 OK with no audit row.
 */
export const unarchiveProject = (id: string) =>
  req<ProjectDto>(`/projects/${id}/unarchive`, {method: 'PUT'});

/**
 * V191 (PR-B-part-2) — soft-delete with 30-day undo window. The project disappears from the
 * listing immediately; call {@link restoreProject} within 30 days to bring it back. After that
 * the cleanup sweeper (PR-H) hard-deletes it.
 */
export const deleteProject = (id: string) => req<void>(`/projects/${id}`, {method: 'DELETE'});

/** V191 (PR-B-part-2) — undo a soft-delete within the 30-day window. */
export const restoreProject = (id: string) =>
  req<ProjectDto>(`/projects/${id}/restore`, {method: 'POST'});

export const adminTriggerUsageReport = (period: 'WEEKLY' | 'MONTHLY') =>
  req<{emailsSent: number; period: string}>('/admin/usage-reports/trigger', {
    method: 'POST',
    body: JSON.stringify({period}),
  });

/** Update or remove the project association of an existing run. Pass null to un-assign. */
export const updateRunProject = (runId: string, projectId: string | null) =>
  req<RunSummary>(`/runs/${runId}/project`, {
    method: 'PATCH',
    body: JSON.stringify({projectId: projectId ?? ''}),
  });

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

export const getTestReport = async (runId: string): Promise<TestReportData | null> => {
  try {
    return await req<TestReportData>(`/runs/${runId}/test-report`);
  } catch {
    return null;
  }
};

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

function filenameFromDisposition(header: string | null, fallback: string): string {
  if (!header) return fallback;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf8?.[1]) return decodeURIComponent(utf8[1].replace(/["']/g, ''));
  const ascii = /filename="?([^";]+)"?/i.exec(header);
  return ascii?.[1] ? ascii[1] : fallback;
}

export const downloadRunCode = async (
  runId: string,
  format: 'repo' | 'zip' = 'repo',
): Promise<void> => {
  const res = await fetch(`${BASE}/runs/${runId}/download?format=${format}`, {
    headers: {...authHeader()},
    credentials: 'include',
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  const blob = await res.blob();
  const fallback =
    format === 'repo'
      ? `${runId.substring(0, 8)}-repo.zip`
      : `workspace-${runId.substring(0, 8)}.zip`;
  const filename = filenameFromDisposition(res.headers.get('content-disposition'), fallback);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};

// Pilot v3.0: Project workspace, repos, architecture, scoring

// Project dashboard
export const getProjectDashboard = (projectId: string) =>
  req<ProjectDashboardDTO>(`/projects/${projectId}/dashboard`);

export const getProjectScores = (projectId: string) =>
  req<ScoreEntry[]>(`/projects/${projectId}/scores`);

// Repos — lifecycle (scoped ORG | USER)
export const listRepos = (scope: RepoScope | 'ALL' = 'ALL') =>
  req<ProjectRepo[]>(`/repos?scope=${scope}`);

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

// Per-repo architecture regen (Task #82) — explicit, heavy (codemap + per-repo arch LLM calls).
// Split out of syncRepo, which is now a fast metadata-only refresh.
export const regenerateRepoArchitecture = (projectId: string, repoId: string) =>
  req<{id: string; version: number; content: string; generatedAt: string}>(
    `/projects/${projectId}/repos/${repoId}/regenerate-architecture`,
    {method: 'POST'},
  );

// Project workspace analyse (regenerates codemaps + architecture.md + summary.md on disk)
export const analyseProject = (projectId: string, branch?: string) =>
  req<ArchitectureDoc>(
    `/projects/${projectId}/analyse${branch ? `?branch=${encodeURIComponent(branch)}` : ''}`,
    {method: 'POST'},
  );
export const createProjectSpecTemplate = (
  projectId: string,
  body: {name: string; description?: string; requirementTemplate: string},
) =>
  req<SpecTemplate>(`/projects/${projectId}/templates`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
export const getArchitectureDiff = (projectId: string, from: number, to: number) =>
  req<ArchDiffReport>(`/projects/${projectId}/architecture/diff?from=${from}&to=${to}`);
export const getTechDebt = (projectId: string, sinceDays?: number) =>
  req<TechDebtReport>(`/projects/${projectId}/tech-debt${sinceDays ? `?since=${sinceDays}` : ''}`);

/**
 * Phase 2 (V183) — dispatch a patch-mode run that fixes every issue of the named
 * category across the project's linked repos. One PR per category. Idempotent
 * server-side: an in-flight run for the same (project, category) returns the
 * existing run with `outcome=REUSED_ACTIVE`.
 */
export const dispatchTechDebtFix = (projectId: string, category: string) =>
  req<TechDebtFixResponse>(`/projects/${projectId}/tech-debt/fix`, {
    method: 'POST',
    body: JSON.stringify({category}),
  });
export const getWorkspaceHealth = (projectId: string) =>
  req<WorkspaceHealthReport>(`/projects/${projectId}/workspace-health`);
export const getContractCoverage = (projectId: string, repoId: string) =>
  req<ContractCoverageResponse>(`/projects/${projectId}/repos/${repoId}/contract-coverage`);
export const rescanContractCoverage = (projectId: string, repoId: string) =>
  req<ContractCoverageResponse>(`/projects/${projectId}/repos/${repoId}/contract-coverage/rescan`, {
    method: 'POST',
  });
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
export const getStandardsReport = (projectId: string, orgId: string, sinceDays = 30) =>
  req<ComplianceReport>(
    `/projects/${projectId}/standards/report?orgId=${encodeURIComponent(orgId)}&sinceDays=${sinceDays}`,
  );
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

export const listProjectAdrs = (projectId: string, acceptedOnly = false) =>
  req<AdrSummary[]>(`/projects/${projectId}/adrs${acceptedOnly ? '?acceptedOnly=true' : ''}`);

export const getProjectAdr = (projectId: string, id: string) =>
  req<AdrDetail>(`/projects/${projectId}/adrs/${id}`);

export const listProjectInvariants = (projectId: string) =>
  req<InvariantSummary[]>(`/projects/${projectId}/invariants`);

export const listProjectInvariantViolations = (projectId: string) =>
  req<InvariantViolation[]>(`/projects/${projectId}/invariants/violations`);

// v2.5.2 — install a PR webhook on a repo the user linked (rather than created).
export const installRepoWebhook = (repoId: string) =>
  req<{installed: boolean; reason?: string}>(`/repos/${repoId}/webhook`, {method: 'POST'});

export const getProjectSummary = (projectId: string) =>
  req<{content: string}>(`/projects/${projectId}/summary`);

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

export const listProjectDocuments = (projectId: string) =>
  req<ProjectDocumentRow[]>(`/projects/${projectId}/documents`);

export const uploadProjectDocument = async (
  projectId: string,
  file: File,
): Promise<ProjectDocumentRow> => {
  const form = new FormData();
  form.append('file', file);
  // BUG-FIX (RL-2026-05-038): the auth token is stored under 'access_token' (useAuthStore /
  // AuthCallbackPage); reading 'token' always returned null → every RAG upload 401'd.
  const token = localStorage.getItem('access_token');
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

// PR refresh — polls GitHub for the latest state of every project PR + the
// CI check-runs rollup per non-terminal PR (V186, 2026-05-11). `ciFailing` is
// the count of PRs that came back FAILURE on this sweep — surfaced in the
// toast copy so a webhook-less customer sees the red CI signal on every
// Refresh click.
export const refreshProjectPrs = (projectId: string) =>
  req<{checked: number; updated: number; newlyMerged: number; ciFailing: number}>(
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
export const listRepoFiles = (projectId: string, repoId: string) =>
  req<RepoFileList>(`/projects/${projectId}/repos/${repoId}/files`);
export const readRepoFile = (projectId: string, repoId: string, path: string) =>
  req<RepoFile>(`/projects/${projectId}/repos/${repoId}/file?path=${encodeURIComponent(path)}`);
export const getArchitectureStaleness = (projectId: string) =>
  req<StalenessReport>(`/projects/${projectId}/architecture/staleness`);
export const getProjectMemory = (projectId: string) =>
  req<ProjectMemoryResponse>(`/projects/${projectId}/memory`);
export const getProjectLedger = (projectId: string) =>
  req<RunLedgerEntry[]>(`/projects/${projectId}/memory/ledger`);

export const compactProjectMemory = (projectId: string) =>
  req<{snapshotVersion: number; compacted: boolean}>(`/projects/${projectId}/memory/compact`, {
    method: 'POST',
  });

export const compileProjectChatToSpec = (projectId: string) =>
  req<{spec: string}>(`/projects/${projectId}/chat/compile`, {method: 'POST'});

export const clearProjectChat = (projectId: string) =>
  req<{deleted: number}>(`/projects/${projectId}/chat`, {method: 'DELETE'});

export const compactProjectChat = (projectId: string) =>
  req<{compactedFrom: number; kept: number; totalAfter: number}>(
    `/projects/${projectId}/chat/compact`,
    {method: 'POST'},
  );

export const getProjectChatInfo = (projectId: string) =>
  req<ProjectChatInfo>(`/projects/${projectId}/chat/info`);

export const getProjectChatHistory = (projectId: string) =>
  req<ProjectChatMessageDto[]>(`/projects/${projectId}/chat`);

export const listPrReviewsForProject = (projectId: string) =>
  req<PrReviewDto[]>(`/projects/${projectId}/pr-reviews`);

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
