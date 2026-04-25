import { useEffect, useState } from 'react';
import { getActivityFeed, type ActivityEntry, type ActivityPage } from '@/lib/api';

/**
 * E4.2 — top-level Activity tab content for the Project dashboard.
 * UX-preserved (per the eval doc § "UX-preservation contract"):
 * reuses the same card / row / chip primitives + 4-band semantic
 * tokens — no new colors, fonts, or component primitives.
 *
 * Filter bar: time window dropdown (7/30/90 days) + event-type chips
 * + actorId text input + admin-events toggle. Filters live in local
 * component state for v1; saved-filter persistence + bookmarkable
 * URLs deferred to a follow-up slice.
 */
export default function ActivityTab({ projectId }: { projectId: string }) {
  const [page, setPage] = useState<ActivityPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [sinceDays, setSinceDays] = useState(30);
  const [eventTypeFilter, setEventTypeFilter] = useState<string>('');
  const [actorFilter, setActorFilter] = useState<string>('');
  const [includeAdmin, setIncludeAdmin] = useState(false);
  const [selected, setSelected] = useState<ActivityEntry | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await getActivityFeed(projectId, {
        sinceDays,
        eventTypes: eventTypeFilter
          ? eventTypeFilter.split(',').map((t) => t.trim()).filter(Boolean)
          : undefined,
        actorId: actorFilter || undefined,
        includeAdminEvents: includeAdmin,
        limit: 200,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, sinceDays, includeAdmin]);

  return (
    <div className="space-y-3 max-w-3xl">
      <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-medium text-primary">Activity</h3>
          <button
            onClick={load}
            disabled={loading}
            className="text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50"
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>

        {/* Filter bar */}
        <div className="flex flex-wrap items-center gap-2 mb-3">
          <select
            value={sinceDays}
            onChange={(e) => setSinceDays(Number(e.target.value))}
            className="text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-primary"
          >
            <option value={1}>last 24 hours</option>
            <option value={7}>last 7 days</option>
            <option value={30}>last 30 days</option>
            <option value={90}>last 90 days</option>
          </select>
          <input
            type="text"
            placeholder="Event types (comma-sep, e.g. PR_OPENED,PR_MERGED)"
            value={eventTypeFilter}
            onChange={(e) => setEventTypeFilter(e.target.value)}
            onBlur={load}
            className="flex-1 min-w-[200px] text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-primary"
          />
          <input
            type="text"
            placeholder="Actor user id"
            value={actorFilter}
            onChange={(e) => setActorFilter(e.target.value)}
            onBlur={load}
            className="text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-primary w-[180px]"
          />
          <label className="flex items-center gap-1 text-[11px] text-muted">
            <input
              type="checkbox"
              checked={includeAdmin}
              onChange={(e) => setIncludeAdmin(e.target.checked)}
            />
            include admin events
          </label>
        </div>

        {error && <div className="text-xs text-accent-red mb-2">{error}</div>}

        {/* Feed list */}
        {!error && page && (
          <>
            {page.entries.length === 0 ? (
              <div className="text-[11px] text-muted">
                No events in this window.
              </div>
            ) : (
              <div className="space-y-1">
                {page.entries.map((e) => (
                  <ActivityRow key={e.id} entry={e} onClick={() => setSelected(e)} />
                ))}
                {page.hasMore && (
                  <div className="text-[10px] text-muted pt-1">
                    Page limit reached — refine filters or shorten the window to see more.
                  </div>
                )}
              </div>
            )}
            <div className="text-[9px] text-muted pt-1">
              Window: {new Date(page.from).toLocaleString()} →{' '}
              {new Date(page.to).toLocaleString()}
            </div>
          </>
        )}
      </div>

      {selected && (
        <ActivityDetailModal entry={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  );
}

function ActivityRow({ entry, onClick }: { entry: ActivityEntry; onClick: () => void }) {
  const tierCls =
    entry.tier === 'HIGH_SIGNAL'
      ? 'bg-accent-blue/10 text-accent-blue'
      : 'bg-accent-amber/10 text-accent-amber';
  return (
    <button
      onClick={onClick}
      className="w-full text-left flex items-start justify-between gap-2 text-[11px] py-1 border-b border-[var(--border-default)] last:border-b-0 hover:bg-[var(--gray-200)]/40"
    >
      <div className="flex items-start gap-2 min-w-0">
        <span className={`shrink-0 inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${tierCls}`}>
          {entry.eventType}
        </span>
        <span className="text-primary truncate">{entry.summary}</span>
      </div>
      <span className="shrink-0 text-[10px] text-muted">
        {new Date(entry.occurredAt).toLocaleString()}
      </span>
    </button>
  );
}

function ActivityDetailModal({
  entry,
  onClose,
}: {
  entry: ActivityEntry;
  onClose: () => void;
}) {
  // Inline modal — keeps the slice tight (full ResponsiveModal not
  // strictly needed since the detail view is read-only).
  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-40 bg-black/40 flex items-center justify-center p-4"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 max-w-2xl w-full max-h-[80vh] overflow-y-auto"
      >
        <div className="flex items-center justify-between mb-2">
          <h4 className="text-sm font-medium text-primary">{entry.eventType}</h4>
          <button
            onClick={onClose}
            className="text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
          >
            Close
          </button>
        </div>
        <dl className="text-[11px] grid grid-cols-[110px_1fr] gap-x-3 gap-y-1">
          <dt className="text-muted">Tier</dt><dd className="text-primary">{entry.tier}</dd>
          <dt className="text-muted">When</dt><dd className="text-primary">{new Date(entry.occurredAt).toISOString()}</dd>
          <dt className="text-muted">Actor</dt><dd className="text-primary">{entry.actorType} {entry.actorUserId || ''}</dd>
          <dt className="text-muted">Target</dt><dd className="text-primary">{entry.targetType || '—'} {entry.targetId || ''}</dd>
          <dt className="text-muted">Summary</dt><dd className="text-primary">{entry.summary}</dd>
        </dl>
        {entry.payload && (
          <pre className="mt-2 text-[10px] font-mono text-muted whitespace-pre-wrap">
            {entry.payload}
          </pre>
        )}
      </div>
    </div>
  );
}
