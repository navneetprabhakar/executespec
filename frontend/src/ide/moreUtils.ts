import type {TierPermissions} from '@/hooks/useTierPermissions';
import type * as api from '@/lib/api';

export const PAGE_SIZE = 30;

export type RunFilter = 'all' | 'live' | 'shipped' | 'needs' | 'done' | 'failed';

export interface RunCounts {
  all: number;
  live: number;
  shipped: number;
  needs: number;
  done: number;
  failed: number;
}

export interface LabelRow {
  k: string;
  v: string;
}

export interface LinkRow {
  to: string;
  label: string;
  sub: string;
}

export const LIVE_STATUSES = new Set([
  'CLASSIFYING',
  'PLANNING',
  'EXECUTING',
  'AWAITING_APPROVAL',
  'RUNNING',
  'PROVIDER_SWITCHING',
  'REVIEWING',
  'PAUSED',
]);

export function isLiveStatus(status?: string | null): boolean {
  return LIVE_STATUSES.has((status || '').toUpperCase());
}

export function scoreOf(r: api.RunSummary): number | null | undefined {
  return r.verifiedScore;
}

export function costOf(r: api.RunSummary): number | null | undefined {
  return (
    r.actualCostUsd ??
    (r as unknown as {actualCost?: number}).actualCost ??
    (r as unknown as {totalCost?: number}).totalCost
  );
}

export function runBucket(r: api.RunSummary): Exclude<RunFilter, 'all'> {
  const s = (r.status || '').toUpperCase();
  if (isLiveStatus(s)) return 'live';
  const score = scoreOf(r);
  const success = s === 'COMPLETED' || s === 'COMPLETE';
  if (success && score != null && score >= 85) return 'shipped';
  if (s === 'PARTIAL' || (success && score != null && score < 85)) return 'needs';
  if (success) return 'done';
  return 'failed';
}

export function scoreColor(score?: number | null): string {
  if (score == null) return 'var(--ide-fg-muted)';
  if (score >= 85) return 'var(--ide-green)';
  if (score >= 70) return 'var(--ide-yellow)';
  return 'var(--ide-red)';
}

export function searchRuns(runs: api.RunSummary[] | null, q: string): api.RunSummary[] {
  const list = runs ?? [];
  const needle = q.trim().toLowerCase();
  if (!needle) return list;
  const idNeedle = needle.startsWith('r-') ? needle.slice(2) : needle;
  return list.filter(
    (r) =>
      (r.runTitle || '').toLowerCase().includes(needle) ||
      (r.userRequest || '').toLowerCase().includes(needle) ||
      r.runId.toLowerCase().includes(idNeedle),
  );
}

export function runCounts(runs: api.RunSummary[]): RunCounts {
  const counts: RunCounts = {all: runs.length, live: 0, shipped: 0, needs: 0, done: 0, failed: 0};
  for (const r of runs) counts[runBucket(r)]++;
  return counts;
}

export function filterRuns(runs: api.RunSummary[], filter: RunFilter): api.RunSummary[] {
  return filter === 'all' ? runs : runs.filter((r) => runBucket(r) === filter);
}

export function runFilterTabs(
  counts: RunCounts,
): Array<{key: RunFilter; label: string; n: number}> {
  return [
    {key: 'all', label: 'All', n: counts.all},
    {key: 'live', label: 'Live', n: counts.live},
    {key: 'shipped', label: 'Shipped', n: counts.shipped},
    {key: 'needs', label: 'Needs review', n: counts.needs},
    {key: 'done', label: 'Done', n: counts.done},
    {key: 'failed', label: 'Failed', n: counts.failed},
  ];
}

export function runDetailPath(r: api.RunSummary): string {
  return isLiveStatus(r.status) ? `/live/${r.runId}` : `/result/${r.runId}`;
}

export function accountDisplayName(user: api.AuthUser | null | undefined): string {
  const email = user?.email || '-';
  return [user?.firstName, user?.lastName].filter(Boolean).join(' ').trim() || email.split('@')[0];
}

export function accountEmail(user: api.AuthUser | null | undefined): string {
  return user?.email || '-';
}

export function usageLabel(permissions: TierPermissions | null): string {
  if (!permissions) return '- / -';
  return `${permissions.currentRunCount} / ${
    permissions.monthlyRunLimit === -1 ? '∞' : permissions.monthlyRunLimit
  }`;
}

export function limitRows(permissions: TierPermissions | null): LabelRow[] {
  return [
    {
      k: 'Monthly work cap',
      v: permissions
        ? permissions.monthlyRunLimit === -1
          ? 'Unlimited'
          : String(permissions.monthlyRunLimit)
        : '-',
    },
    {k: 'Work used', v: permissions ? String(permissions.currentRunCount) : '-'},
    {
      k: 'Max org seats',
      v: permissions
        ? permissions.maxOrgMembers === -1
          ? 'Unlimited'
          : String(permissions.maxOrgMembers)
        : '-',
    },
    {k: 'Live log access', v: permissions?.canViewLiveLog ? 'Enabled' : 'PRO+'},
    {k: 'Provider keys', v: permissions?.canManageAiKeys ? 'Enabled' : 'PRO+'},
    {k: 'Audit log', v: permissions?.canViewAudit ? 'Enabled' : 'TEAM+'},
    {
      k: 'Deploy engine',
      v: permissions?.canDeploy
        ? `Up to ${permissions.maxDeployments === -1 ? '∞' : permissions.maxDeployments}`
        : 'PRO+',
    },
  ];
}

export function billingRows(tier: string): LinkRow[] {
  return [
    {to: '/billing', label: 'Usage & billing', sub: `${tier} tier`},
    {to: '/plans', label: 'Compare plans', sub: ''},
  ];
}

export function organizationRows(user: api.AuthUser | null | undefined): LinkRow[] {
  return [
    {to: '/org-members', label: 'Team access', sub: 'Invite teammates and assign roles'},
    ...(user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN'
      ? [{to: '/org/settings', label: 'Org settings', sub: 'SSO · keys · model matrix'}]
      : []),
  ];
}

export function developerRows(): LinkRow[] {
  return [
    {to: '/integrations', label: 'Repository access', sub: 'GitHub token / app'},
    {to: '/api-keys', label: 'Model provider keys', sub: 'BYOK credentials'},
    {to: '/deployments', label: 'Deployments', sub: 'Cloud Run services'},
  ];
}

export function notificationPreferenceLabel(eventType: string): string {
  return eventType.replace(/_/g, ' ').toLowerCase();
}

export function notificationChannelLabel(channel: string): string {
  return `${channel.toLowerCase()} channel`;
}
