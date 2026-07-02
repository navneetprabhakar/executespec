import type * as api from '@/lib/api';
import {runTitleFor} from '@/signal/lib/runUtils';

export type NotifFilter = 'all' | 'run' | 'team' | 'billing' | 'system';
export type AuditFilter = 'all' | 'auth' | 'billing' | 'members' | 'admin';
export type MoreRow = {to: string; label: string; sub: string};

export interface SearchHit {
  id: string;
  kind: 'run' | 'project';
  title: string;
  subtitle: string;
  to: string;
}

export const LIVE_SEARCH_STATUSES = new Set([
  'CLASSIFYING',
  'PLANNING',
  'EXECUTING',
  'AWAITING_APPROVAL',
  'RUNNING',
]);

export const SEARCH_QUICK_ACTIONS: MoreRow[] = [
  {to: '/compose', label: 'Start work', sub: 'Draft a request'},
  {to: '/projects', label: 'Browse projects', sub: 'All your active projects'},
  {to: '/runs', label: 'Work', sub: 'Filter and browse'},
];

export function notificationType(n: api.NotificationItem): string {
  return (
    (n as unknown as {category?: string; type?: string}).category ||
    (n as unknown as {type?: string}).type ||
    ''
  ).toLowerCase();
}

export function filterNotifications(
  items: api.NotificationItem[] | null,
  filter: NotifFilter,
): api.NotificationItem[] {
  if (!items) return [];
  if (filter === 'all') return items;
  return items.filter((n) => {
    const t = notificationType(n);
    if (filter === 'run') return t.includes('run') || t.includes('plan');
    if (filter === 'team') return t.includes('member') || t.includes('invite');
    if (filter === 'billing')
      return t.includes('billing') || t.includes('quota') || t.includes('cap');
    if (filter === 'system')
      return t.includes('system') || t.includes('flag') || t.includes('release');
    return true;
  });
}

export function isNotificationUnread(n: api.NotificationItem): boolean {
  return (
    !(n as unknown as {read?: boolean; readAt?: string}).read &&
    !(n as unknown as {readAt?: string}).readAt
  );
}

export function unreadNotificationCount(items: api.NotificationItem[] | null): number {
  return (items || []).filter(isNotificationUnread).length;
}

export function notificationDotColor(n: api.NotificationItem): string {
  const severity = (n as unknown as {severity?: string}).severity;
  if (severity === 'error') return 'var(--ide-red)';
  if (severity === 'warn') return 'var(--ide-yellow)';
  return 'var(--ide-green)';
}

export function notificationTarget(n: api.NotificationItem): string {
  const nAny = n as unknown as {link?: string | null; type?: string; category?: string};
  if (nAny.link) return nAny.link;
  const t = (nAny.type || nAny.category || '').toUpperCase();
  if (t.includes('PLAN_READY') || t.includes('RUN')) return '/runs';
  if (t.includes('PR')) return '/runs';
  if (t.includes('BILLING') || t.includes('QUOTA') || t.includes('CAP')) return '/billing';
  if (t.includes('MEMBER') || t.includes('INVITE')) return '/org-members';
  return '/compose';
}

export function markNotificationReadLocal(
  items: api.NotificationItem[] | null,
  id: string,
): api.NotificationItem[] | null {
  return (
    items?.map((n) => ((n as unknown as {id?: string}).id === id ? {...n, read: true} : n)) || items
  );
}

export function markAllNotificationsReadLocal(
  items: api.NotificationItem[] | null,
): api.NotificationItem[] | null {
  return items?.map((n) => ({...n, read: true})) || items;
}

export function buildSearchHits(
  q: string,
  runs: api.RunSummary[],
  projects: api.ProjectDto[],
  limit = 30,
): SearchHit[] {
  const needle = q.trim().toLowerCase();
  if (!needle) return [];
  const out: SearchHit[] = [];
  for (const r of runs) {
    const txt =
      `${(r as unknown as {userRequest?: string}).userRequest ?? ''} ${r.runId}`.toLowerCase();
    if (txt.includes(needle)) {
      const s = (r.status || '').toUpperCase();
      const isLive = LIVE_SEARCH_STATUSES.has(s);
      out.push({
        id: r.runId,
        kind: 'run',
        title: runTitleFor(r),
        subtitle: `R-${r.runId.slice(0, 6)} · ${s.toLowerCase()}`,
        to: isLive ? `/live/${r.runId}` : `/result/${r.runId}`,
      });
    }
  }
  for (const p of projects) {
    const txt = `${p.name} ${p.id}`.toLowerCase();
    if (txt.includes(needle)) {
      out.push({
        id: p.id,
        kind: 'project',
        title: p.name,
        subtitle: `${(p as unknown as {repoCount?: number}).repoCount ?? 0} repos · ${
          (p as unknown as {totalRuns?: number}).totalRuns ?? 0
        } work items`,
        to: `/projects/${p.id}`,
      });
    }
  }
  return out.slice(0, limit);
}

export function filterAuditLogs(
  logs: api.AuditLogEntry[] | null,
  filter: AuditFilter,
): api.AuditLogEntry[] {
  return (logs || []).filter((l) => {
    if (filter === 'all') return true;
    const t = (
      (l as unknown as {action?: string; eventType?: string}).action ||
      (l as unknown as {eventType?: string}).eventType ||
      ''
    ).toLowerCase();
    if (filter === 'auth')
      return (
        t.includes('login') || t.includes('oauth') || t.includes('logout') || t.includes('password')
      );
    if (filter === 'billing')
      return (
        t.includes('billing') ||
        t.includes('subscription') ||
        t.includes('payment') ||
        t.includes('invoice')
      );
    if (filter === 'members')
      return t.includes('member') || t.includes('invite') || t.includes('seat');
    if (filter === 'admin') return t.includes('admin') || t.includes('tier') || t.includes('flag');
    return true;
  });
}

export function auditAccessErrorMessage(msg: string, tier: string, isOrgAdmin: boolean): string {
  if (!/403|permission|forbidden/i.test(msg)) return msg;
  if (tier === 'FREE' || tier === 'PRO') {
    return 'Audit log is a TEAM+ feature. Upgrade your plan to enable it.';
  }
  if (!isOrgAdmin) {
    return 'Audit log is restricted to org admins. Ask your org admin to grant you access.';
  }
  return "Audit log isn't enabled for your org yet. Open Org Settings -> Compliance to turn it on, or contact support if the toggle isn't visible.";
}

export function auditAction(l: api.AuditLogEntry): string {
  return (
    (l as unknown as {action?: string; eventType?: string}).action ||
    (l as unknown as {eventType?: string}).eventType ||
    'event'
  );
}

export function auditActor(l: api.AuditLogEntry): string | null | undefined {
  return (l as unknown as {actorEmail?: string; actorUserId?: string}).actorEmail || l.actorId;
}

export function auditTimestamp(l: api.AuditLogEntry): string | null | undefined {
  return (
    (l as unknown as {createdAt?: string; occurredAt?: string}).createdAt ||
    (l as unknown as {occurredAt?: string}).occurredAt
  );
}

export function auditTarget(l: api.AuditLogEntry): string {
  return (
    (l as unknown as {targetId?: string}).targetId ||
    (l as unknown as {entityId?: string}).entityId ||
    ''
  );
}

function csvCell(value: string | null | undefined): string {
  const s = value || '';
  return /[",\n\r]/.test(s) ? `"${s.split('"').join('""')}"` : s;
}

export function auditCsvText(logs: api.AuditLogEntry[]): string {
  const rows = ['action,actor,target,createdAt'];
  for (const l of logs) {
    rows.push(
      [auditAction(l), auditActor(l), auditTarget(l), auditTimestamp(l)]
        .map((cell) => csvCell(cell || ''))
        .join(','),
    );
  }
  return rows.join('\n');
}

export function activeProjects(projects: api.ProjectDto[]): api.ProjectDto[] {
  return projects.filter((x) => !x.archivedAt);
}

export function setupEssentialRows(tier: string): MoreRow[] {
  return [
    {
      to: '/org-members',
      label: 'Invite teammates',
      sub: 'Add people and assign roles',
    },
    {
      to: '/integrations',
      label: 'Connect repositories',
      sub: 'GitHub, Bitbucket, and issue trackers',
    },
    {to: '/provider-keys', label: 'Model provider', sub: 'Default provider and BYOK keys'},
    {to: '/billing', label: 'Billing', sub: `${tier} tier`},
  ];
}

export function setupWorkspaceRows(
  projects: api.ProjectDto[],
  runCount: number | null,
  unread: number | null,
): MoreRow[] {
  return [
    {
      to: '/projects',
      label: 'Projects',
      sub: `${projects.length} active project${projects.length === 1 ? '' : 's'}`,
    },
    {
      to: '/runs',
      label: 'Work',
      sub: runCount != null ? `${runCount} items` : 'Loading...',
    },
    {
      to: '/notifications',
      label: 'Notifications',
      sub: unread != null ? `${unread} unread` : '-',
    },
  ];
}

export function setupAccountRows(email?: string | null): MoreRow[] {
  return [
    {to: '/account', label: 'Account', sub: email || '-'},
    {to: '/plans', label: 'Plans and pricing', sub: 'Compare tiers'},
    {
      to: '/org/settings',
      label: 'Org settings',
      sub: 'Domains, seats, SSO, model matrix',
    },
  ];
}

export function setupOperationRows(
  tier: string,
  isSuperAdmin: boolean,
  isOrgAdmin: boolean,
): MoreRow[] {
  return [
    {to: '/search', label: 'Search', sub: 'Work, projects, files'},
    {to: '/insights', label: 'Insights', sub: 'Usage and outcomes'},
    ...(isOrgAdmin || isSuperAdmin
      ? [{to: '/audit', label: 'Audit log', sub: 'Compliance trail'}]
      : []),
    {to: '/deployments', label: 'Deployments', sub: 'Cloud Run / Docker'},
    {to: '/trust', label: 'Trust and security', sub: 'Compliance, SOC2'},
  ];
}
