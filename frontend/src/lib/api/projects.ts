import type {
  ArchitectureJob,
  ProjectDashboardDTO,
  ProjectCodeIndexStatus,
  ProjectCodeIndexRefreshAccepted,
  ProjectRepo,
  ProjectRepoLink,
  ArchitectureDoc,
  ScoreEntry,
  LinkRepoRequest,
  RepoScope,
} from '@/types/project';
import {
  ActivityPage,
  AdrCapability,
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
  ScmRemoteRepository,
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

export interface GeneratedDocJob {
  id: string;
  projectId: string;
  docType: GeneratedDocType;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'NEEDS_CONFIRMATION';
  terminal: boolean;
  provider: string;
  model: string;
  resultDocId: string | null;
  resultDocVersion: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  attemptCount: number;
  deadlineAt: string;
  providerStartedAt: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

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

export interface ProjectExportJob {
  id: string;
  status:
    | 'QUEUED'
    | 'RUNNING'
    | 'CANCEL_REQUESTED'
    | 'CANCELLED'
    | 'SUCCEEDED'
    | 'FAILED'
    | 'EXPIRING'
    | 'EXPIRED';
  fileName: string;
  sizeBytes: number | null;
  sha256: string | null;
  attemptCount: number;
  errorCode: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  expiresAt: string | null;
  cancelRequestedAt: string | null;
  lastDownloadedAt: string | null;
  downloadCount: number;
  cancellable: boolean;
  downloadable: boolean;
}

export interface ProjectExportAuditEvent {
  id: string;
  eventType: string;
  outcome: string;
  actorUserId: string | null;
  details: string;
  occurredAt: string;
}

export const createProjectExport = (projectId: string, idempotencyKey: string) =>
  req<ProjectExportJob>(`/projects/${projectId}/export`, {
    method: 'POST',
    headers: {'Idempotency-Key': idempotencyKey},
  });

export const listProjectExports = (projectId: string) =>
  req<ProjectExportJob[]>(`/projects/${projectId}/export`);

export const getProjectExport = (projectId: string, jobId: string) =>
  req<ProjectExportJob>(`/projects/${projectId}/export/${jobId}`);

export const cancelProjectExport = (projectId: string, jobId: string) =>
  req<ProjectExportJob>(`/projects/${projectId}/export/${jobId}/cancel`, {method: 'POST'});

export const getProjectExportAudit = (projectId: string, jobId: string) =>
  req<ProjectExportAuditEvent[]>(`/projects/${projectId}/export/${jobId}/audit`);

/**
 * Stream directly into the File System Access API where available. Older browsers retain the
 * existing Blob fallback, bounded by the server's export byte ceiling.
 */
export async function downloadProjectExport(
  projectId: string,
  job: Pick<ProjectExportJob, 'id' | 'fileName'>,
): Promise<void> {
  const res = await fetch(`${BASE}/projects/${projectId}/export/${job.id}/download`, {
    headers: {...authHeader()},
    credentials: 'include',
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }

  type SaveHandle = {createWritable: () => Promise<WritableStream<Uint8Array>>};
  const picker = (
    window as unknown as {
      showSaveFilePicker?: (options: {
        suggestedName: string;
        types: Array<{description: string; accept: Record<string, string[]>}>;
      }) => Promise<SaveHandle>;
    }
  ).showSaveFilePicker;
  if (picker && res.body) {
    const handle = await picker({
      suggestedName: job.fileName,
      types: [{description: 'Gzip archive', accept: {'application/gzip': ['.tar.gz']}}],
    });
    await res.body.pipeTo(await handle.createWritable());
    return;
  }

  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = job.fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export const createProject = (body: {
  name: string;
  description?: string;
  aiProvider?: string;
  githubOwner?: string;
}) => req<ProjectDto>('/projects', {method: 'POST', body: JSON.stringify(body)});

export const updateProjectSettings = (
  id: string,
  body: {
    name?: string;
    description?: string;
    targetBranch?: string;
    branchNamingPattern?: string;
    aiProvider?: string;
    githubOwner?: string;
  },
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

/**
 * RR-947 — a report read must distinguish genuine ABSENCE (the run has no report yet) from a
 * FAILURE (401/403/429/5xx/network/parse). Swallowing every error as `null` made the UI render an
 * authorization/outage/parse failure as "no evidence", hid retry, and let a transient share-service
 * outage masquerade as an expired/revoked link. Only a typed 404/410 is treated as absence; every
 * other error propagates so the caller's `.catch(toLoadFailure)` can show a real, retryable state.
 */
function isTypedAbsence(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 404 || e.status === 410);
}

export const getValidationReport = async (runId: string): Promise<ValidationReportData | null> => {
  try {
    return await req<ValidationReportData>(`/runs/${runId}/report`);
  } catch (e) {
    if (isTypedAbsence(e)) return null;
    throw e;
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
  // RR-947 — return null only for the typed 404/410 absence contract; every other status/network
  // failure throws so `SharedPage` shows the real error instead of a false "expired or revoked".
  const res = await fetch(`${BASE}/reports/shared/${shareToken}`).catch((e) => {
    throw new ApiError(0, e instanceof Error ? e.message : "Can't reach the server.");
  });
  if (res.status === 404 || res.status === 410) return null;
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  return res.json();
};

export const getTestReport = async (runId: string): Promise<TestReportData | null> => {
  try {
    return await req<TestReportData>(`/runs/${runId}/test-report`);
  } catch (e) {
    if (isTypedAbsence(e)) return null;
    throw e;
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

export const listScmRepos = (provider: 'GITHUB' | 'BITBUCKET', owner?: string) =>
  req<ScmRemoteRepository[]>(
    `/repos/scm?provider=${provider}${owner ? `&owner=${encodeURIComponent(owner)}` : ''}`,
  );

export const createRepo = (data: LinkRepoRequest) =>
  req<ProjectRepo>('/repos', {method: 'POST', body: JSON.stringify(data)});

export const deleteRepo = (repoId: string) => req<void>(`/repos/${repoId}`, {method: 'DELETE'});

export const syncRepo = (repoId: string) =>
  req<ProjectRepo>(`/repos/${repoId}/sync`, {method: 'POST'});

// Repos — project link
export const getProjectRepos = (projectId: string) =>
  req<ProjectRepoLink[]>(`/projects/${projectId}/repos`);

export const getProjectCodeIndex = (projectId: string) =>
  req<ProjectCodeIndexStatus[]>(`/projects/${projectId}/code-index`);

export const refreshProjectCodeIndex = (projectId: string, repoId: string) =>
  req<ProjectCodeIndexRefreshAccepted>(`/projects/${projectId}/code-index/${repoId}/refresh`, {
    method: 'POST',
  });

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
export const startArchitectureRefresh = (projectId: string) =>
  req<ArchitectureJob>(`/projects/${projectId}/architecture/generate-async`, {
    method: 'POST',
  });

export const getArchitectureRefreshJob = (projectId: string, jobId: string) =>
  req<ArchitectureJob>(`/projects/${projectId}/architecture/jobs/${jobId}`);

export const getActiveArchitectureRefreshJob = async (projectId: string) =>
  (await req<ArchitectureJob | undefined>(`/projects/${projectId}/architecture/jobs/active`)) ??
  null;

/** Latest durable state, including terminal NEEDS_CONFIRMATION after a browser reload. */
export const getLatestArchitectureRefreshJob = async (projectId: string) =>
  (await req<ArchitectureJob | undefined>(`/projects/${projectId}/architecture/jobs/latest`)) ??
  null;

export const cancelArchitectureRefreshJob = (projectId: string, jobId: string) =>
  req<void>(`/projects/${projectId}/architecture/jobs/${jobId}`, {method: 'DELETE'});

/**
 * Explicitly confirms a retry of the same durable job after the server reports an uncertain
 * provider outcome. Starting a fresh job is intentionally not a substitute for this route.
 */
export const confirmArchitectureRefreshRetry = (projectId: string, jobId: string) =>
  req<ArchitectureJob>(`/projects/${projectId}/architecture/jobs/${jobId}/confirm-retry`, {
    method: 'POST',
  });

/** Compatibility for older clients; the web console uses startArchitectureRefresh. */
export const generateArchitecture = (projectId: string) =>
  req<ArchitectureJob>(`/projects/${projectId}/architecture/generate`, {
    method: 'POST',
  });

// Per-repo architecture regen (Task #82) — explicit, heavy (codemap + per-repo arch LLM calls).
// The intent is admitted through the same durable project-wide refresh boundary so canonical
// project and repository map versions cannot be published independently.
export const regenerateRepoArchitecture = (projectId: string, repoId: string) =>
  req<ArchitectureJob>(`/projects/${projectId}/repos/${repoId}/regenerate-architecture`, {
    method: 'POST',
  });

// Project workspace analyse (regenerates codemaps + architecture.md + summary.md on disk)
export const analyseProject = (projectId: string, branch?: string) =>
  req<ArchitectureJob>(
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

export const getProjectAdrCapability = (projectId: string) =>
  req<AdrCapability>(`/projects/${projectId}/adrs/capability`);

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
  req<GeneratedDocJob>(`/projects/${projectId}/docs/${docType}/generate`, {method: 'POST'});

export const getProjectDocJob = (projectId: string, docType: GeneratedDocType, jobId: string) =>
  req<GeneratedDocJob>(`/projects/${projectId}/docs/${docType}/jobs/${jobId}`);

export const getLatestProjectDocJob = async (
  projectId: string,
  docType: GeneratedDocType,
): Promise<GeneratedDocJob | null> =>
  (await req<GeneratedDocJob | undefined>(`/projects/${projectId}/docs/${docType}/jobs/latest`)) ??
  null;

/** Explicitly confirms replay after provider execution had an uncertain durable outcome. */
export const confirmProjectDocRetry = (
  projectId: string,
  docType: GeneratedDocType,
  jobId: string,
) =>
  req<GeneratedDocJob>(`/projects/${projectId}/docs/${docType}/jobs/${jobId}/confirm-retry`, {
    method: 'POST',
  });

/**
 * Returns null (204) when the doc hasn't been generated yet. Callers should
 * render a "Generate" CTA in that case. req<T>() returns undefined on 204,
 * which we normalise to null at the call site.
 */
export const getProjectDoc = async (
  projectId: string,
  docType: GeneratedDocType,
): Promise<GeneratedDocRow | null> => {
  // RR-1313 — only a typed 404/410 means "not generated". Any other status
  // (401/429/500/network) must propagate so the Docs surface can render a
  // retryable error instead of a false "no docs" empty state.
  try {
    const row = await req<GeneratedDocRow | undefined>(`/projects/${projectId}/docs/${docType}`);
    return row ?? null;
  } catch (e) {
    if (isTypedAbsence(e)) return null;
    throw e;
  }
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

export const getArchitectureDoc = async (projectId: string): Promise<ArchitectureDoc | null> => {
  // RR-1313 — only a typed 404/410 means "no map generated yet". Any other status
  // (401/429/500/network) must propagate so the surface renders a retryable error
  // instead of a false "No system map yet" empty state.
  try {
    const doc = await req<ArchitectureDoc | undefined>(`/projects/${projectId}/architecture`);
    return doc ?? null;
  } catch (e) {
    if (isTypedAbsence(e)) return null;
    throw e;
  }
};

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

export type ProjectChatConfirmedAction =
  | 'REGENERATE_ARCHITECTURE'
  | 'GENERATE_PROJECT_DOC'
  | 'RUN_CODE_REVIEW';

export interface ProjectChatActionScope {
  action: ProjectChatConfirmedAction;
  provider: string;
  model: string;
  target: Record<string, string>;
}

export interface ProjectChatConfirmationReference {
  confirmationId: string;
  projectId: string;
  action: ProjectChatConfirmedAction;
  capabilities: string[];
  provider: string;
  model: string;
  targetHash: string;
  expiresAt: string;
  consumed: boolean;
  budget: {
    maxTotalTokens: number;
    maxCostUsd: number;
    maxProviderCalls: number;
  };
}

export interface IssuedProjectChatConfirmation {
  confirmation: ProjectChatConfirmationReference;
  /** One-time bearer. Callers must retain it only in memory and clear it after any outcome. */
  confirmationToken: string;
}

export interface ProjectChatConfirmationExecutionResult {
  confirmation: ProjectChatConfirmationReference;
  result: Record<string, unknown>;
}

export const issueProjectChatConfirmation = (projectId: string, scope: ProjectChatActionScope) =>
  req<IssuedProjectChatConfirmation>(`/projects/${projectId}/chat/confirmations`, {
    method: 'POST',
    body: JSON.stringify(scope),
  });

export const consumeProjectChatConfirmation = (
  projectId: string,
  confirmationId: string,
  confirmationToken: string,
  scope: ProjectChatActionScope,
) =>
  req<ProjectChatConfirmationExecutionResult>(
    `/projects/${projectId}/chat/confirmations/${confirmationId}/consume`,
    {
      method: 'POST',
      body: JSON.stringify({confirmationToken, ...scope}),
    },
  );

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
