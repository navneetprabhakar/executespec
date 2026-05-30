import {
  CodeReviewAutofixResponse,
  CodeReviewScanDto,
  CodeReviewScanWithIssues,
  ExecResult,
  LocalPreviewStatus,
  PrReviewAutofixResponse,
  PrReviewDto,
  PrReviewWithComments,
  PreviewAnalysis,
  PreviewLogs,
  req,
} from './core';

export const analyzePreview = (runId: string) =>
  req<PreviewAnalysis>(`/runs/${runId}/preview/analyze`);

export const getPreviewStatus = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/status`);

export const startPreview = (runId: string, envVars?: Record<string, string>) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/start`, {
    method: 'POST',
    body: envVars && Object.keys(envVars).length > 0 ? JSON.stringify({envVars}) : undefined,
  });

export const stopPreview = (runId: string) =>
  req<void>(`/runs/${runId}/preview/stop`, {method: 'DELETE'});

export const rebuildPreview = (runId: string) =>
  req<LocalPreviewStatus>(`/runs/${runId}/preview/rebuild`, {method: 'POST'});

export const getPreviewLogs = (
  runId: string,
  lines = 0,
  filter: 'all' | 'backend' | 'frontend' | 'db' = 'all',
) => req<PreviewLogs>(`/runs/${runId}/preview/logs?lines=${lines}&filter=${filter}`);

export const execPreviewCommand = (runId: string, cmd: string, args: string[] = []) =>
  req<ExecResult>(`/runs/${runId}/preview/exec`, {
    method: 'POST',
    body: JSON.stringify({cmd, args}),
  });

/** v2.5.7 — Fetch PR review comments (inline + top-level reviews) for the Address Review flow. */
export const fetchPrReviewComments = (projectId: string, prNumber: number, repoFullName: string) =>
  req<{comments: string}>(
    `/projects/${projectId}/prs/${prNumber}/reviews?repo=${encodeURIComponent(repoFullName)}`,
  );

export const submitPrReview = (body: {projectId: string; repoFullName: string; prNumber: number}) =>
  req<PrReviewDto>('/pr-reviews', {method: 'POST', body: JSON.stringify(body)});

export const getPrReview = (id: string) => req<PrReviewWithComments>(`/pr-reviews/${id}`);

export const listPrReviewsForPr = (repoFullName: string, prNumber: number) =>
  req<PrReviewDto[]>(`/pr-reviews?repo=${encodeURIComponent(repoFullName)}&number=${prNumber}`);

/**
 * Phase 1 (V183) — dispatch a patch-mode run that addresses every comment on a PR
 * review. Returns the run id so the UI can navigate to `/runs/{id}` and watch
 * progress. Idempotent server-side: a fresh dispatch when there's already an active
 * run for this review returns the existing run with `outcome=REUSED_ACTIVE`.
 */
export const dispatchPrReviewAutofix = (reviewId: string) =>
  req<PrReviewAutofixResponse>(`/pr-reviews/${reviewId}/autofix`, {method: 'POST'});

export const submitCodeReview = (body: {
  projectId: string;
  repoId?: string;
  repoFullName: string;
}) => req<CodeReviewScanDto>('/code-reviews', {method: 'POST', body: JSON.stringify(body)});

export const getCodeReview = (id: string) => req<CodeReviewScanWithIssues>(`/code-reviews/${id}`);

/**
 * Phase 4 (V183) — dispatch a patch-mode run that addresses every OPEN issue of the
 * named severity bucket from a code review scan. One PR per severity. Idempotent
 * server-side: an in-flight run for the same (scan, severity) returns the existing
 * run with `outcome=REUSED_ACTIVE`.
 */
export const dispatchCodeReviewAutofix = (
  scanId: string,
  severity: 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO',
) =>
  req<CodeReviewAutofixResponse>(`/code-reviews/${scanId}/autofix`, {
    method: 'POST',
    body: JSON.stringify({severity}),
  });
