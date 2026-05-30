import {
  ApiDocsProbeResult,
  AuthUser,
  ConversationMessage,
  FileDiffPayload,
  FileWithStatus,
  LoginResponse,
  MessageResponse,
  MetricsResponse,
  ModelComparisonData,
  OrgDto,
  PickSampleResponse,
  PostMergeBar,
  PrCommentInboxDto,
  PullRequestSnapshot,
  SampleSpecDto,
  SandboxStatus,
  SsoConfig,
  SsoProvider,
  TestGraphData,
  TimelineData,
  UsageMetrics,
  req,
  reqText,
} from './core';

export const getMetrics = (days = 0) =>
  req<MetricsResponse>(`/metrics${days > 0 ? `?days=${days}` : ''}`);

export const getModelComparison = (days = 30) =>
  req<ModelComparisonData>(`/model-comparison?days=${days}`);

export const engineRollback = (id: string) =>
  req<void>(`/deployments/${id}/engine-rollback`, {method: 'POST'});

export const engineDestroy = (id: string) =>
  req<void>(`/deployments/${id}/engine-destroy`, {method: 'DELETE'});

export const getTimeline = (runId: string) => req<TimelineData>(`/runs/${runId}/timeline`);

export const getFiles = (runId: string) => req<string[]>(`/runs/${runId}/files`);

export const getFilesWithStatus = (runId: string) =>
  req<FileWithStatus[]>(`/runs/${runId}/files/status`);

/** File content is served as text/plain — must NOT use res.json(). */
export const getFile = (runId: string, filePath: string) =>
  reqText(`/runs/${runId}/file?path=${encodeURIComponent(filePath)}`);

export const getFileDiff = (runId: string, filePath: string) =>
  req<FileDiffPayload>(`/runs/${runId}/file/diff?path=${encodeURIComponent(filePath)}`);

export const getMessages = (runId: string) => req<MessageResponse[]>(`/runs/${runId}/messages`);

/** Fetch the generated OpenAPI YAML for a run (served as text/plain). */
export const getOpenApiSpec = (runId: string) => reqText(`/runs/${runId}/openapi.yaml`);
export const probeApiDocs = (runId: string): Promise<ApiDocsProbeResult> =>
  req<ApiDocsProbeResult>(`/runs/${runId}/preview/probe-api-docs`);

export const conversationChat = (messages: ConversationMessage[]) =>
  req<{reply: string}>('/conversation', {
    method: 'POST',
    body: JSON.stringify({messages}),
  });

/**
 * R-190 (post-cutover) — coerce legacy `view_mode='vibe'` rows to 'pro'.
 * Call this on every AuthUser DTO that crosses the API boundary so the
 * narrowed `viewMode?: 'pro' | 'project'` type matches what the rest of
 * the frontend ever sees.
 */
export function normalizeAuthUser<T extends {viewMode?: unknown}>(u: T): T {
  if (u && (u as {viewMode?: unknown}).viewMode === 'vibe') {
    return {...u, viewMode: 'pro' as const};
  }
  return u;
}

export const listOnboardingSamples = () => req<SampleSpecDto[]>('/onboarding/sample-specs');

export const pickOnboardingSample = (sampleId: string) =>
  req<PickSampleResponse>('/onboarding/pick-sample', {
    method: 'POST',
    body: JSON.stringify({sampleId}),
  });

export const getSandboxStatus = () => req<SandboxStatus>('/onboarding/sandbox-status');

export const authRefresh = () => req<LoginResponse>('/auth/refresh', {method: 'POST'});

export const authMe = () => req<AuthUser>('/auth/me');

export const authResendVerification = (email: string) =>
  req<{message: string}>('/auth/resend-verification', {
    method: 'POST',
    body: JSON.stringify({email}),
  });

/** Updates the user's UI view mode preference. R-190 (post-cutover) —
 *  the legacy 'vibe' option is gone; only 'pro' and 'project' are valid
 *  client-side. Backend still accepts 'vibe' for back-compat. */
export const updateViewMode = (viewMode: 'pro' | 'project') =>
  req<AuthUser>('/auth/settings/view-mode', {
    method: 'PUT',
    body: JSON.stringify({viewMode}),
  });

/**
 * V198 — toggle per-org external project sharing. When true, ORG_ADMIN or
 * SUPER_ADMIN may invite any email domain (gmail, outlook, etc.) as a
 * cross-org collaborator on a project. Default is false; existing tenants
 * are opt-in.
 */
export const updateExternalSharing = (enabled: boolean) =>
  req<OrgDto>('/org/me/settings/external-sharing', {
    method: 'PUT',
    body: JSON.stringify({enabled}),
  });

export const resendInvitation = (id: string) =>
  req<{message: string}>(`/org/me/invitations/${id}/resend`, {method: 'POST'});

export const getTestGraph = async (runId: string): Promise<TestGraphData | null> => {
  try {
    return await req<TestGraphData>(`/runs/${runId}/test-graph`);
  } catch {
    return null;
  }
};

export const markTestInvalid = (testResultId: number) =>
  req<void>(`/test-results/${testResultId}/mark-invalid`, {method: 'PUT'});

export const applyTestFixes = (runId: string, signature?: string) =>
  req<void>(
    `/runs/${runId}/apply-test-fixes${signature ? `?signature=${encodeURIComponent(signature)}` : ''}`,
    {method: 'POST'},
  );

export const getPrComments = (runId: string) =>
  req<PrCommentInboxDto[]>(`/runs/${runId}/pr-comments`);

export const getPrCommentPendingCount = (runId: string) =>
  req<{count: number}>(`/runs/${runId}/pr-comments/pending-count`);

export const dismissPrComment = (runId: string, commentId: number) =>
  req<PrCommentInboxDto>(`/runs/${runId}/pr-comments/${commentId}/dismiss`, {method: 'POST'});

/**
 * Phase P2 — apply a PR review comment via ApplyService. Pushes the resulting fix to the same
 * branch the PR is from, so the PR auto-updates. Returns the apply-run id; UI follows it via
 * the existing apply-run polling loop.
 */
export const applyPrComment = (runId: string, commentId: number) =>
  req<{applyRunId: string}>(`/runs/${runId}/pr-comments/${commentId}/apply`, {method: 'POST'});

export const getAnalyticsUsage = (entityType: string, entityId: string, period = 30) =>
  req<UsageMetrics>(
    `/admin/analytics/usage?entityType=${entityType}&entityId=${entityId}&period=${period}`,
  );

export const getSsoConfig = () => req<SsoConfig>('/auth/sso/config');

export const updateSsoConfig = (
  body: Partial<{
    provider: SsoProvider | null;
    enforced: boolean;
    metadataUrl: string | null;
    domain: string | null;
  }>,
) =>
  req<SsoConfig>('/auth/sso/config', {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const clearSsoConfig = () => req<void>('/auth/sso/config', {method: 'DELETE'});
export const getPostMergeBar = (projectId: string) =>
  req<PostMergeBar[]>(`/projects/${projectId}/post-merge-bar`);
export const dismissPostMergeBar = (projectId: string) =>
  req<void>(`/projects/${projectId}/post-merge-bar`, {method: 'DELETE'});

/**
 * 2026-05-04 — bumped default maxChars 4_000 → 200_000. The backend concatenates
 * every file's patch into one diff blob and truncates at the byte limit BEFORE
 * the frontend's `splitDiffByFile` parser sees it. With the previous 4 KB cap,
 * a typical multi-file PR (e.g. one with a 2-3 KB README change near the top of
 * the diff) had all subsequent file patches silently dropped — symptom on dev
 * 2026-05-04 was "every PR only shows README.md". 200 KB covers virtually every
 * realistic PR from V2 runs (the per-task scope is bounded), keeps responses
 * gzip'd under 50 KB on the wire, and falls back to a "truncated, N chars
 * omitted" footer when even that's exceeded so the user can click through to
 * GitHub for the full diff. Caller can still pass a smaller value if they need
 * a tight summary (no current call site does).
 */
export const getPullRequestSnapshot = (
  repoFullName: string,
  prNumber: number,
  maxChars = 200_000,
) =>
  req<PullRequestSnapshot>(
    `/prs/snapshot?repo=${encodeURIComponent(repoFullName)}&number=${prNumber}&maxChars=${maxChars}`,
  );
