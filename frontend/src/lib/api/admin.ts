import {
  ActivationFunnelReport,
  AuditChainVerifyReport,
  AuditLogPage,
  AuthUser,
  FeatureFlagDto,
  HaltAllRunsResult,
  OrgInvitation,
  PagedResponse,
  TierConfig,
  TierLifecycleDto,
  TierLifecycleUpdateRequest,
  WebhookEventEntry,
  req,
} from './core';

export const fetchTiersLifecycle = () => req<TierLifecycleDto[]>('/admin/tiers');

export const updateTierLifecycle = (tier: string, body: TierLifecycleUpdateRequest) =>
  req<unknown>(`/admin/tiers/${tier}/lifecycle`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });

export const adminListUsers = () => req<AuthUser[]>('/admin/users');
export const adminGetUser = (id: string) => req<AuthUser>(`/admin/users/${id}`);
export const adminUpdateUserStatus = (id: string, status: string) =>
  req<AuthUser>(`/admin/users/${id}/status`, {method: 'PUT', body: JSON.stringify({status})});
export const adminUpdateUserTier = (id: string, tier: string) =>
  req<AuthUser>(`/admin/users/${id}/tier`, {method: 'PUT', body: JSON.stringify({tier})});
export const adminUpdateUserRole = (id: string, role: string) =>
  req<AuthUser>(`/admin/users/${id}/role`, {method: 'PUT', body: JSON.stringify({role})});
export const haltAllActiveRuns = (reason?: string) =>
  req<HaltAllRunsResult>('/admin/runs/halt-all', {
    method: 'POST',
    body: JSON.stringify(reason ? {reason} : {}),
  });
export const verifyAuditChain = () => req<AuditChainVerifyReport>('/admin/audit-chain/verify');
export const getActivationFunnel = (sinceDays = 30) =>
  req<ActivationFunnelReport>(`/admin/activation-funnel?sinceDays=${sinceDays}`);

export const adminListEscalated = () => req<OrgInvitation[]>('/admin/invitations/escalated');
export const adminGetTierConfig = () => req<TierConfig[]>('/admin/tier-config');
export const adminUpdateTierConfig = (tier: string, monthlyRunLimit: number) =>
  req<TierConfig>(`/admin/tier-config/${tier}`, {
    method: 'PUT',
    body: JSON.stringify({monthlyRunLimit}),
  });

// Admin Grant Runs & Top-Up Packages

export const adminGrantUserRuns = (
  userId: string,
  quantity: number,
  expiryDays?: number | null,
  reason?: string,
) =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  req<any>(`/admin/users/${userId}/grant-runs`, {
    method: 'POST',
    body: JSON.stringify({quantity, expiryDays, reason}),
  });

export const adminListFeatureFlags = () => req<FeatureFlagDto[]>('/admin/feature-flags');

/** v2.5.8 — public feature-flag map for UI gating. Keys whitelisted to
 *  integrations.*, billing.*, pipeline.*, project.*, execution.view.*. */
export const getPublicFeatureFlags = () =>
  req<Record<string, boolean>>('/admin/feature-flags/public');
export const adminUpdateFeatureFlag = (key: string, value: string) =>
  req<FeatureFlagDto>(`/admin/feature-flags/${encodeURIComponent(key)}`, {
    method: 'PUT',
    body: JSON.stringify({value}),
  });

export const getAuditLogs = (params: {
  orgScoped?: boolean;
  entityType?: string;
  eventType?: string;
  actorId?: string;
  entityId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}) => {
  const qs = new URLSearchParams();
  if (params.entityType) qs.set('entityType', params.entityType);
  if (params.eventType) qs.set('eventType', params.eventType);
  if (params.actorId) qs.set('actorId', params.actorId);
  if (params.entityId) qs.set('entityId', params.entityId);
  if (params.from) qs.set('from', params.from);
  if (params.to) qs.set('to', params.to);
  qs.set('page', String(params.page ?? 0));
  qs.set('size', String(params.size ?? 50));
  const base = params.orgScoped ? '/org/me/audit-logs' : '/admin/audit-logs';
  return req<AuditLogPage>(`${base}?${qs.toString()}`);
};

export const adminListWebhookEvents = (page = 0, size = 50, gateway?: string) => {
  const params = new URLSearchParams({page: String(page), size: String(size)});
  if (gateway) params.set('gateway', gateway);
  return req<PagedResponse<WebhookEventEntry>>(`/admin/webhook-events?${params}`);
};
