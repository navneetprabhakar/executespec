import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Filter, RefreshCw } from 'lucide-react';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import * as api from '@/lib/api';
import { cn } from '@/lib/utils';

// ── Human-readable labels ────────────────────────────────────────────────

const ENTITY_TYPES = ['ALL', 'USER', 'ORG', 'ORG_MEMBER', 'INVITATION', 'PROJECT'] as const;

const EVENT_TYPE_LABELS: Record<string, string> = {
  USER_REGISTERED: 'User Registered',
  EMAIL_VERIFIED: 'Email Verified',
  PASSWORD_CHANGED: 'Password Changed',
  PASSWORD_RESET: 'Password Reset',
  USER_STATUS_CHANGED: 'User Status Changed',
  USER_TIER_CHANGED: 'User Tier Changed',
  USER_ROLE_CHANGED: 'User Role Changed',
  USER_GITHUB_SETTINGS_CHANGED: 'GitHub Settings Changed',
  USER_AI_KEYS_CHANGED: 'AI Keys Changed',
  USER_JIRA_SETTINGS_CHANGED: 'JIRA Settings Changed',
  USER_CONFLUENCE_SETTINGS_CHANGED: 'Confluence Settings Changed',
  ORG_CREATED: 'Org Created',
  ORG_UPDATED: 'Org Updated',
  ORG_STATUS_CHANGED: 'Org Status Changed',
  ORG_TIER_CHANGED: 'Org Tier Changed',
  ORG_RUN_LIMIT_CHANGED: 'Run Limit Changed',
  ORG_API_KEYS_FLAG_CHANGED: 'API Keys Flag Changed',
  ORG_AI_KEYS_CHANGED: 'Org AI Keys Changed',
  ORG_KEY_SOURCE_CHANGED: 'Key Source Changed',
  ORG_JIRA_SETTINGS_CHANGED: 'Org JIRA Settings Changed',
  ORG_CONFLUENCE_SETTINGS_CHANGED: 'Org Confluence Settings Changed',
  ORG_MEMBER_ADDED: 'Member Added',
  ORG_MEMBER_REMOVED: 'Member Removed',
  ORG_MEMBER_ROLE_CHANGED: 'Member Role Changed',
  ORG_MEMBER_SUSPENDED: 'Member Suspended',
  ORG_MEMBER_RESTORED: 'Member Restored',
  INVITATION_CREATED: 'Invitation Created',
  INVITATION_ESCALATED: 'Invitation Escalated',
  INVITATION_APPROVED: 'Invitation Approved',
  INVITATION_REJECTED: 'Invitation Rejected',
  INVITATION_ACCEPTED: 'Invitation Accepted',
  INVITATION_CANCELLED: 'Invitation Cancelled',
  ADMIN_INVITATION_CREATED: 'Admin Invitation Created',
  PROJECT_CREATED: 'Project Created',
  PROJECT_ARCHIVED: 'Project Archived',
  PROJECT_MEMBER_ADDED: 'Project Member Added',
  PROJECT_MEMBER_REMOVED: 'Project Member Removed',
};

function humanLabel(eventType: string): string {
  return EVENT_TYPE_LABELS[eventType] ?? eventType.replace(/_/g, ' ');
}

function entityBadgeColor(entityType: string): string {
  switch (entityType) {
    case 'USER': return 'bg-accent-violet/20 text-accent-violet';
    case 'ORG': return 'bg-accent-cyan/20 text-accent-cyan';
    case 'ORG_MEMBER': return 'bg-accent-amber/20 text-accent-amber';
    case 'INVITATION': return 'bg-blue-500/20 text-blue-400';
    case 'PROJECT': return 'bg-accent-emerald/20 text-accent-emerald';
    default: return 'bg-zinc-500/20 text-zinc-400';
  }
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

/** Try to render a JSON string as compact key=value pairs. */
function renderJsonValue(raw: string | null): string {
  if (!raw) return '-';
  try {
    const obj = JSON.parse(raw);
    return Object.entries(obj)
      .map(([k, v]) => `${k}: ${v}`)
      .join(', ');
  } catch {
    return raw;
  }
}

// ── Date helpers ─────────────────────────────────────────────────────────

function daysAgoISO(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  d.setHours(0, 0, 0, 0);
  return d.toISOString();
}

function toDatetimeLocal(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromDatetimeLocal(val: string): string {
  return new Date(val).toISOString();
}

// ── Component ────────────────────────────────────────────────────────────

export default function AuditPage() {
  const permissions = useTierPermissions();
  const isSuperAdmin = useAuthStore((s) => s.user?.globalRole === 'SUPER_ADMIN');
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);

  // Redirect users without audit permission
  if (permissions && !permissions.canViewAudit) {
    return <Navigate to="/" replace />;
  }

  // Filter state
  const [entityFilter, setEntityFilter] = useState('ALL');
  const [eventFilter, setEventFilter] = useState('');
  const [fromDate, setFromDate] = useState(daysAgoISO(7));
  const [toDate, setToDate] = useState(new Date().toISOString());
  const [page, setPage] = useState(0);

  // Data state
  const [data, setData] = useState<api.AuditLogPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.getAuditLogs({
        orgScoped: !isSuperAdmin,
        entityType: entityFilter !== 'ALL' ? entityFilter : undefined,
        eventType: eventFilter || undefined,
        from: fromDate,
        to: toDate,
        page,
        size: 50,
      });
      setData(result);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [isSuperAdmin, entityFilter, eventFilter, fromDate, toDate, page]);

  useEffect(() => { fetchLogs(); }, [fetchLogs]);

  const entries = data?.content ?? [];

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-zinc-100">Audit Trail</h2>
        <button
          onClick={fetchLogs}
          disabled={loading}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white/[0.06] text-zinc-300 text-xs font-medium hover:bg-white/[0.1] transition-colors disabled:opacity-40"
        >
          <RefreshCw className={cn('h-3.5 w-3.5', loading && 'animate-spin')} />
          Refresh
        </button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3 rounded-lg bg-surface-1 border border-white/[0.06] p-3">
        <Filter className="h-4 w-4 text-zinc-500 mt-5" />

        {/* Date range */}
        <div className="space-y-1">
          <label className="text-[10px] font-medium uppercase tracking-wider text-zinc-500">From</label>
          <input
            type="datetime-local"
            value={toDatetimeLocal(fromDate)}
            onChange={(e) => { setFromDate(fromDatetimeLocal(e.target.value)); setPage(0); }}
            className="block w-48 px-2 py-1.5 rounded-md bg-white/[0.04] border border-white/[0.08] text-sm text-zinc-200 focus:outline-none focus:ring-1 focus:ring-accent-violet"
          />
        </div>
        <div className="space-y-1">
          <label className="text-[10px] font-medium uppercase tracking-wider text-zinc-500">To</label>
          <input
            type="datetime-local"
            value={toDatetimeLocal(toDate)}
            onChange={(e) => { setToDate(fromDatetimeLocal(e.target.value)); setPage(0); }}
            className="block w-48 px-2 py-1.5 rounded-md bg-white/[0.04] border border-white/[0.08] text-sm text-zinc-200 focus:outline-none focus:ring-1 focus:ring-accent-violet"
          />
        </div>

        {/* Entity type chips */}
        <div className="space-y-1">
          <label className="text-[10px] font-medium uppercase tracking-wider text-zinc-500">Entity</label>
          <div className="flex gap-1">
            {ENTITY_TYPES.map((t) => (
              <button
                key={t}
                onClick={() => { setEntityFilter(t); setPage(0); }}
                className={cn(
                  'px-2.5 py-1 rounded-full text-xs font-medium whitespace-nowrap transition-colors',
                  entityFilter === t
                    ? 'bg-accent-violet text-white'
                    : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.04] hover:bg-white/[0.06]'
                )}
              >
                {t === 'ALL' ? 'All' : t.replace(/_/g, ' ')}
              </button>
            ))}
          </div>
        </div>

        {/* Event type search */}
        <div className="space-y-1">
          <label className="text-[10px] font-medium uppercase tracking-wider text-zinc-500">Event Type</label>
          <select
            value={eventFilter}
            onChange={(e) => { setEventFilter(e.target.value); setPage(0); }}
            className="block w-56 px-2 py-1.5 rounded-md bg-white/[0.04] border border-white/[0.08] text-sm text-zinc-200 focus:outline-none focus:ring-1 focus:ring-accent-violet"
          >
            <option value="">All Events</option>
            {Object.entries(EVENT_TYPE_LABELS).map(([key, label]) => (
              <option key={key} value={key}>{label}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="rounded-lg bg-accent-rose/10 border border-accent-rose/20 px-4 py-2 text-sm text-accent-rose">
          {error}
        </div>
      )}

      {/* Results table */}
      <div className="rounded-lg bg-surface-1 border border-white/[0.06] overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-white/[0.06] text-left">
                <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wider text-zinc-500">Time</th>
                <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wider text-zinc-500">Event</th>
                <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wider text-zinc-500">Entity</th>
                <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wider text-zinc-500">Actor</th>
                <th className="px-4 py-2.5 text-xs font-medium uppercase tracking-wider text-zinc-500">Changes</th>
              </tr>
            </thead>
            <tbody>
              {loading && entries.length === 0 ? (
                <tr><td colSpan={5} className="px-4 py-12 text-center text-zinc-500">Loading...</td></tr>
              ) : entries.length === 0 ? (
                <tr><td colSpan={5} className="px-4 py-12 text-center text-zinc-500">No audit events found</td></tr>
              ) : (
                entries.map((entry) => (
                  <tr key={entry.id} className="border-b border-white/[0.03] hover:bg-white/[0.02] transition-colors">
                    <td className="px-4 py-2.5 text-zinc-400 whitespace-nowrap text-xs">
                      {formatTime(entry.createdAt)}
                    </td>
                    <td className="px-4 py-2.5">
                      <span className="text-zinc-200 text-xs font-medium">{humanLabel(entry.eventType)}</span>
                    </td>
                    <td className="px-4 py-2.5">
                      <span className={cn(
                        'inline-block px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase',
                        entityBadgeColor(entry.entityType)
                      )}>
                        {entry.entityType}
                      </span>
                      <span className="ml-1.5 text-xs text-zinc-500 font-mono" title={entry.entityId}>
                        {entry.entityId.length > 12 ? entry.entityId.slice(0, 8) + '...' : entry.entityId}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-xs text-zinc-300 whitespace-nowrap">
                      {entry.actorEmail ?? (entry.actorId ? entry.actorId.slice(0, 8) + '...' : 'SYSTEM')}
                    </td>
                    <td className="px-4 py-2.5 text-xs max-w-xs">
                      {entry.oldValue && entry.newValue ? (
                        <div className="space-y-0.5">
                          <div className="text-zinc-500 line-through">{renderJsonValue(entry.oldValue)}</div>
                          <div className="text-accent-emerald">{renderJsonValue(entry.newValue)}</div>
                        </div>
                      ) : entry.newValue ? (
                        <span className="text-accent-emerald">{renderJsonValue(entry.newValue)}</span>
                      ) : entry.oldValue ? (
                        <span className="text-accent-rose line-through">{renderJsonValue(entry.oldValue)}</span>
                      ) : (
                        <span className="text-zinc-600">-</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-2.5 border-t border-white/[0.06]">
            <span className="text-xs text-zinc-500">
              Page {data.number + 1} of {data.totalPages} ({data.totalElements} total)
            </span>
            <div className="flex gap-1">
              <button
                onClick={() => setPage(Math.max(0, page - 1))}
                disabled={page === 0}
                className="p-1.5 rounded bg-white/[0.04] hover:bg-white/[0.08] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
              >
                <ChevronLeft className="h-4 w-4 text-zinc-400" />
              </button>
              <button
                onClick={() => setPage(Math.min(data.totalPages - 1, page + 1))}
                disabled={page >= data.totalPages - 1}
                className="p-1.5 rounded bg-white/[0.04] hover:bg-white/[0.08] disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
              >
                <ChevronRight className="h-4 w-4 text-zinc-400" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
