import {
  MemberUsage,
  ModelMatrixView,
  OrgDto,
  OrgInvitation,
  OrgMemberDto,
  OrgStandardDto,
  OrgStandardUpsert,
  ProjectMemberDto,
  SeatsAdjustResponse,
  SpecTemplate,
  ValidationResult,
  req,
} from './core';

export const getMyOrg = () => req<OrgDto>('/org/me');

export const createOrg = (body: {name: string; domains: string[]; adminEmail: string}) =>
  req<OrgDto>('/org', {method: 'POST', body: JSON.stringify(body)});

export const updateOrg = (body: {name?: string; domains?: string[]}) =>
  req<OrgDto>('/org/me', {method: 'PUT', body: JSON.stringify(body)});

/**
 * Adjust seat count on the caller's TEAM-tier org (ORG_ADMIN / SUPER_ADMIN).
 * Pushes new quantity to Stripe (prorated) and syncs org.seat_count locally.
 * Rejects reducing below current active member count (400).
 * Range: 2-20 (self-serve).
 */
export const adjustOrgSeats = (newSeats: number) =>
  req<SeatsAdjustResponse>('/org/me/seats/adjust', {
    method: 'POST',
    body: JSON.stringify({newSeats}),
  });

/** Updates org key source mode and provider restrictions (ORG_ADMIN only). */
export const updateOrgKeySourceSettings = (
  keySourceMode: string,
  enabledProviders: string[] | null,
) =>
  req<OrgDto>('/org/me/settings/key-source', {
    method: 'PUT',
    body: JSON.stringify({keySourceMode, enabledProviders}),
  });

/** Reads the org's effective model matrix (defaults + overrides). Any org admin. */
export const getOrgModelMatrix = () => req<ModelMatrixView>('/org/me/model-matrix');

/**
 * Replaces the org's full set of model-matrix overrides (sparse — only the cells to
 * override). Empty map clears all. ENTERPRISE-only (PUT 403s otherwise).
 */
export const updateOrgModelMatrix = (overrides: Record<string, string>) =>
  req<ModelMatrixView>('/org/me/model-matrix', {
    method: 'PUT',
    body: JSON.stringify({overrides}),
  });

/** Saves org-level JIRA credentials (ORG_ADMIN only). Pass empty strings to clear. */
export const updateOrgJiraSettings = (baseUrl: string, email: string, apiToken: string) =>
  req<OrgDto>('/org/me/settings/jira', {
    method: 'PUT',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Tests JIRA credentials at org level. */
export const validateOrgJira = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/org/me/settings/jira/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Saves org-level Confluence credentials (ORG_ADMIN only). Pass empty strings to clear. */
export const updateOrgConfluenceSettings = (baseUrl: string, email: string, apiToken: string) =>
  req<OrgDto>('/org/me/settings/confluence', {
    method: 'PUT',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

/** Tests Confluence credentials at org level. */
export const validateOrgConfluence = (baseUrl: string, email: string, apiToken: string) =>
  req<ValidationResult>('/org/me/settings/confluence/validate', {
    method: 'POST',
    body: JSON.stringify({baseUrl, email, apiToken}),
  });

export const getOrgMembers = () => req<OrgMemberDto[]>('/org/me/members');

export const removeMember = (userId: string) =>
  req<void>(`/org/me/members/${userId}`, {method: 'DELETE'});

export const changeMemberRole = (userId: string, role: string) =>
  req<void>(`/org/me/members/${userId}/role`, {method: 'PUT', body: JSON.stringify({role})});

export const inviteMember = (body: {email: string; orgRole?: string; projectIds?: string[]}) =>
  req<{message: string; status: string}>('/org/invite', {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const getOrgInvitations = () => req<OrgInvitation[]>('/org/me/invitations');

export const getProjectMembers = (projectId: string) =>
  req<ProjectMemberDto[]>(`/projects/${projectId}/members`);

export const addProjectMember = (projectId: string, userId: string, role: string) =>
  req<void>(`/projects/${projectId}/members`, {
    method: 'POST',
    body: JSON.stringify({userId, role}),
  });

export const removeProjectMember = (projectId: string, userId: string) =>
  req<void>(`/projects/${projectId}/members/${userId}`, {method: 'DELETE'});

export const adminListOrgs = () => req<OrgDto[]>('/admin/orgs');
export const adminUpdateOrgTier = (id: string, tier: string) =>
  req<OrgDto>(`/admin/orgs/${id}/tier`, {method: 'PUT', body: JSON.stringify({tier})});
export const adminUpdateOrgStatus = (id: string, status: string) =>
  req<OrgDto>(`/admin/orgs/${id}/status`, {method: 'PUT', body: JSON.stringify({status})});

// Per-org overrides
export const adminUpdateOrgRunLimit = (id: string, limit: number | null) =>
  req<OrgDto>(`/admin/orgs/${id}/run-limit`, {method: 'PUT', body: JSON.stringify({limit})});

// Org member suspend
export const orgSuspendMember = (userId: string, suspended: boolean) =>
  req<void>(`/org/me/members/${userId}/suspend`, {
    method: 'PUT',
    body: JSON.stringify({suspended}),
  });

export const adminGrantOrgRuns = (
  orgId: string,
  quantity: number,
  expiryDays?: number | null,
  reason?: string,
) =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  req<any>(`/admin/orgs/${orgId}/grant-runs`, {
    method: 'POST',
    body: JSON.stringify({quantity, expiryDays, reason}),
  });

export const getAnalyticsMembers = (orgId: string, period = 30) =>
  req<MemberUsage[]>(`/admin/analytics/members?orgId=${orgId}&period=${period}`);
export const createOrgSpecTemplate = (
  orgId: string,
  body: {name: string; description?: string; requirementTemplate: string},
) =>
  req<SpecTemplate>(`/orgs/${orgId}/templates`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const listOrgStandards = (orgId: string) =>
  req<OrgStandardDto[]>(`/orgs/${orgId}/standards`);

export const createOrgStandard = (orgId: string, body: OrgStandardUpsert) =>
  req<OrgStandardDto>(`/orgs/${orgId}/standards`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

export const updateOrgStandard = (orgId: string, standardId: string, body: OrgStandardUpsert) =>
  req<OrgStandardDto>(`/orgs/${orgId}/standards/${encodeURIComponent(standardId)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const deleteOrgStandard = (orgId: string, standardId: string) =>
  req<void>(`/orgs/${orgId}/standards/${encodeURIComponent(standardId)}`, {
    method: 'DELETE',
  });
