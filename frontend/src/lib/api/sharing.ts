import {
  BASE,
  AdminInviteRequest,
  ShareLinkDto,
  ShareLinkListResponse,
  ShareLinkSurface,
  parseErrorMessage,
  req,
} from './core';

export const acceptInvite = (token: string) =>
  req<{message: string}>(`/org/accept-invite?token=${token}`, {method: 'POST'});
export const adminApproveInvite = (id: string) =>
  req<{message: string}>(`/admin/invitations/${id}/approve`, {method: 'POST'});
export const adminRejectInvite = (id: string) =>
  req<{message: string}>(`/admin/invitations/${id}/reject`, {method: 'POST'});
export const adminInviteUser = (body: AdminInviteRequest) =>
  req<{message: string}>('/admin/invite', {method: 'POST', body: JSON.stringify(body)});

export const createShareLink = (
  projectId: string,
  body: {surface: ShareLinkSurface; scopeId?: string; label?: string; expiresInDays?: number},
) =>
  req<ShareLinkDto>(`/projects/${projectId}/share-links`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const listShareLinks = (projectId: string) =>
  req<ShareLinkListResponse>(`/projects/${projectId}/share-links`);

export const revokeShareLink = (projectId: string, linkId: string) =>
  req<void>(`/projects/${projectId}/share-links/${linkId}`, {method: 'DELETE'});

/**
 * Public share-link URL for the dashboard owner to copy. Frontend lives at
 * `/shared/:token` (added in v2.6.4) which calls these GETs to render the surface.
 */
export const shareLinkPublicUrl = (token: string) =>
  `${window.location.origin}/shared/${encodeURIComponent(token)}`;

async function publicSharedJson<T>(token: string, suffix: string): Promise<T> {
  const res = await fetch(`${BASE}/shared/${encodeURIComponent(token)}${suffix}`, {
    credentials: 'include',
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new Error(parseErrorMessage(res.status, raw));
  }
  return res.json() as Promise<T>;
}

export interface SharedLinkMeta {
  surface: ShareLinkSurface;
  scopeId: string | null;
  label: string | null;
  projectId: string;
  createdAt: string;
  expiresAt: string | null;
}

export interface SharedRun {
  runId: string;
  title: string | null;
  status: string;
  statusReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  costUsd: number | null;
  label: string | null;
}

export interface SharedProjectOverview {
  projectId: string;
  name: string;
  description: string | null;
  status: string | null;
  aiProvider: string | null;
  githubOwner: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  label: string | null;
}

export interface SharedValidationReportLink {
  runId: string;
  accuracyScore: number | null;
  verifiedScore: number | null;
  reviewerVerdict: string | null;
  finalBuildPassed: boolean | null;
  testsPassed: number | null;
  testsTotal: number | null;
  recommendation: string | null;
  createdAt: string | null;
}

export interface SharedCodeReview {
  id: string;
  repoFullName: string | null;
  status: string | null;
  reportMarkdown: string | null;
  totalIssues: number;
  criticalCount: number;
  majorCount: number;
  label: string | null;
}

export interface SharedPrReview {
  id: string;
  repoFullName: string | null;
  prNumber: number | null;
  verdict: string | null;
  summary: string | null;
  commentCount: number;
  label: string | null;
}

export interface SharedArchitecture {
  projectId: string;
  version: number | null;
  content: string | null;
  generatedAt: string | null;
  label: string | null;
}

export type SharedProjectSurface =
  | {kind: 'PROJECT_OVERVIEW'; meta: SharedLinkMeta; project: SharedProjectOverview}
  | {kind: 'RUN_DETAIL'; meta: SharedLinkMeta; run: SharedRun}
  | {kind: 'VALIDATION_REPORT'; meta: SharedLinkMeta; report: SharedValidationReportLink}
  | {kind: 'CODE_REVIEW'; meta: SharedLinkMeta; review: SharedCodeReview}
  | {kind: 'PR_REVIEW'; meta: SharedLinkMeta; review: SharedPrReview}
  | {kind: 'ARCHITECTURE_DOC'; meta: SharedLinkMeta; architecture: SharedArchitecture};

export async function getSharedProjectSurface(token: string): Promise<SharedProjectSurface> {
  const meta = await publicSharedJson<SharedLinkMeta>(token, '/meta');
  switch (meta.surface) {
    case 'PROJECT_OVERVIEW':
      return {
        kind: 'PROJECT_OVERVIEW',
        meta,
        project: await publicSharedJson<SharedProjectOverview>(token, '/project'),
      };
    case 'RUN_DETAIL':
      return {kind: 'RUN_DETAIL', meta, run: await publicSharedJson<SharedRun>(token, '/run')};
    case 'VALIDATION_REPORT':
      return {
        kind: 'VALIDATION_REPORT',
        meta,
        report: await publicSharedJson<SharedValidationReportLink>(token, '/validation-report'),
      };
    case 'CODE_REVIEW':
      return {
        kind: 'CODE_REVIEW',
        meta,
        review: await publicSharedJson<SharedCodeReview>(token, '/code-review'),
      };
    case 'PR_REVIEW':
      return {
        kind: 'PR_REVIEW',
        meta,
        review: await publicSharedJson<SharedPrReview>(token, '/pr-review'),
      };
    case 'ARCHITECTURE_DOC':
      return {
        kind: 'ARCHITECTURE_DOC',
        meta,
        architecture: await publicSharedJson<SharedArchitecture>(token, '/architecture'),
      };
    default:
      throw new Error('Unsupported share link surface.');
  }
}
