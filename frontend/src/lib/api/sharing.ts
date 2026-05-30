import {
  AdminInviteRequest,
  ShareLinkDto,
  ShareLinkListResponse,
  ShareLinkSurface,
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
