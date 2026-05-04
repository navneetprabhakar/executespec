import {useEffect, useMemo, useState} from 'react';
import {getActivityFeed, type ActivityEntry, type ActivityPage} from '@/lib/api';
import {cn} from '@/lib/utils';

/**
 * E4.2 — top-level Activity tab content for the Project dashboard.
 * UX-preserved (per the eval doc § "UX-preservation contract"):
 * reuses the same card / row / chip primitives + 4-band semantic
 * tokens — no new colors, fonts, or component primitives.
 *
 * Filter bar: time window dropdown (7/30/90 days) + event-type chips
 * + actorId text input + admin-events toggle. X-03 (UX audit, 2026-05-03)
 * promoted the comma-separated eventTypes textbox into multi-select chips
 * (the audit found procurement reviewers couldn't tell what the valid
 * filter values were — discoverable chips beat free-text). Filters still
 * live in local component state; saved-filter persistence + bookmarkable
 * URLs deferred to a follow-up slice.
 */

// X-03 — curated set of frequently-filtered event types. Picked from the
// eventTypes already emitted by ActivityEventEmitter (E1.8/E1.9) — the most
// common procurement / SOC2-aligned filters land here. Anything not in the
// chip set still works via the legacy text input below for power users
// (we kept the text input but moved it under a "More filters" disclosure).
const COMMON_EVENT_TYPES = [
  'PR_OPENED',
  'PR_MERGED',
  'PR_CLOSED',
  'RUN_STARTED',
  'RUN_COMPLETED',
  'RUN_FAILED',
  'PROJECT_CREATED',
  'REPO_LINKED',
  'STANDARDS_VIOLATION',
  'CONTRACT_DRIFT_DETECTED',
] as const;

export default function ActivityTab({projectId}: {projectId: string}) {
  const [page, setPage] = useState<ActivityPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [sinceDays, setSinceDays] = useState(30);
  // X-03 — chip-driven set of selected event types. Free-text input still
  // exists for power users (see `customEventTypes` below) so they can hit
  // any custom event the registry exposes.
  const [chipSelected, setChipSelected] = useState<Set<string>>(new Set());
  const [customEventTypes, setCustomEventTypes] = useState<string>('');
  const [moreFiltersOpen, setMoreFiltersOpen] = useState(false);
  const [actorFilter, setActorFilter] = useState<string>('');
  const [includeAdmin, setIncludeAdmin] = useState(false);
  const [selected, setSelected] = useState<ActivityEntry | null>(null);

  // X-03 — combine chips + custom text into a single deduped list for
  // the request. Chip-only or text-only or both is fine.
  const eventTypes = useMemo(() => {
    const out = new Set(chipSelected);
    customEventTypes
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean)
      .forEach((t) => out.add(t));
    return Array.from(out);
  }, [chipSelected, customEventTypes]);

  const toggleChip = (eventType: string) => {
    setChipSelected((prev) => {
      const next = new Set(prev);
      if (next.has(eventType)) next.delete(eventType);
      else next.add(eventType);
      return next;
    });
  };

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(
        await getActivityFeed(projectId, {
          sinceDays,
          eventTypes: eventTypes.length > 0 ? eventTypes : undefined,
          actorId: actorFilter || undefined,
          includeAdminEvents: includeAdmin,
          limit: 200,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // X-03 — refetch on chip changes too. eventTypes is derived from
    // chipSelected + customEventTypes; depending on its identity is correct
    // because Array.from on a Set produces a fresh array per dependency tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, sinceDays, includeAdmin, chipSelected]);

  return (
    <div className='space-y-3 max-w-3xl'>
      <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
        <div className='flex items-center justify-between mb-2'>
          <h3 className='text-sm font-medium text-primary'>Activity</h3>
          <button
            onClick={load}
            disabled={loading}
            className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50'
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>

        {/* Filter bar — X-03 chip-first redesign. Time window + admin
            toggle stay inline; event-type chips are below them in their
            own row so they wrap cleanly when many are selected. */}
        <div className='space-y-2 mb-3'>
          <div className='flex flex-wrap items-center gap-2'>
            <select
              value={sinceDays}
              onChange={(e) => setSinceDays(Number(e.target.value))}
              className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-primary'
            >
              <option value={1}>last 24 hours</option>
              <option value={7}>last 7 days</option>
              <option value={30}>last 30 days</option>
              <option value={90}>last 90 days</option>
            </select>
            <label className='flex items-center gap-1 text-[11px] text-muted'>
              <input
                type='checkbox'
                checked={includeAdmin}
                onChange={(e) => setIncludeAdmin(e.target.checked)}
              />
              include admin events
            </label>
            {chipSelected.size > 0 && (
              <button
                onClick={() => setChipSelected(new Set())}
                className='text-[11px] px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary'
                title='Clear all event-type chips'
              >
                Clear chips
              </button>
            )}
            <button
              onClick={() => setMoreFiltersOpen((v) => !v)}
              className='text-[11px] px-1.5 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary ml-auto'
              aria-expanded={moreFiltersOpen}
            >
              {moreFiltersOpen ? 'Hide more filters' : 'More filters'}
            </button>
          </div>

          {/* Chip row — multi-select. Click to add / remove. */}
          <div className='flex flex-wrap items-center gap-1.5'>
            <span className='text-[10px] uppercase tracking-wide text-muted font-mono pr-0.5'>
              Filter by event:
            </span>
            {COMMON_EVENT_TYPES.map((t) => {
              const active = chipSelected.has(t);
              return (
                <button
                  key={t}
                  type='button'
                  onClick={() => toggleChip(t)}
                  aria-pressed={active}
                  title={active ? `Remove ${t} from filter` : `Filter to ${t}`}
                  className={cn(
                    'text-[10.5px] font-mono px-1.5 py-0.5 rounded-sm border transition-colors',
                    active
                      ? 'border-accent-blue/50 bg-accent-blue/10 text-accent-blue'
                      : 'border-[var(--border-default)] text-muted hover:text-secondary hover:border-accent-blue/30',
                  )}
                >
                  {t}
                </button>
              );
            })}
          </div>

          {/* Power-user disclosure — actor id + raw comma-sep event types. */}
          {moreFiltersOpen && (
            <div className='flex flex-wrap items-center gap-2'>
              <input
                type='text'
                placeholder='Custom event types (comma-sep, e.g. ADR_PROPOSED,STANDARD_OVERRIDDEN)'
                value={customEventTypes}
                onChange={(e) => setCustomEventTypes(e.target.value)}
                onBlur={load}
                className='flex-1 min-w-[260px] text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-primary'
              />
              <input
                type='text'
                placeholder='Actor user id'
                value={actorFilter}
                onChange={(e) => setActorFilter(e.target.value)}
                onBlur={load}
                className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-primary w-[180px]'
              />
            </div>
          )}
        </div>

        {error && <div className='text-xs text-accent-red mb-2'>{error}</div>}

        {/* Feed list */}
        {!error && page && (
          <>
            {page.entries.length === 0 ? (
              <div className='text-[11px] text-muted'>No events in this window.</div>
            ) : (
              <div className='space-y-1'>
                {page.entries.map((e) => (
                  <ActivityRow key={e.id} entry={e} onClick={() => setSelected(e)} />
                ))}
                {page.hasMore && (
                  <div className='text-[10px] text-muted pt-1'>
                    Page limit reached — refine filters or shorten the window to see more.
                  </div>
                )}
              </div>
            )}
            <div className='text-[9px] text-muted pt-1'>
              Window: {new Date(page.from).toLocaleString()} → {new Date(page.to).toLocaleString()}
            </div>
          </>
        )}
      </div>

      {selected && <ActivityDetailModal entry={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}

function ActivityRow({entry, onClick}: {entry: ActivityEntry; onClick: () => void}) {
  const tierCls =
    entry.tier === 'HIGH_SIGNAL'
      ? 'bg-accent-blue/10 text-accent-blue'
      : 'bg-accent-amber/10 text-accent-amber';
  return (
    <button
      onClick={onClick}
      className='w-full text-left flex items-start justify-between gap-2 text-[11px] py-1 border-b border-[var(--border-default)] last:border-b-0 hover:bg-[var(--gray-200)]/40'
    >
      <div className='flex items-start gap-2 min-w-0'>
        <span
          className={`shrink-0 inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${tierCls}`}
        >
          {entry.eventType}
        </span>
        <span className='text-primary truncate'>{entry.summary}</span>
      </div>
      <span className='shrink-0 text-[10px] text-muted'>
        {new Date(entry.occurredAt).toLocaleString()}
      </span>
    </button>
  );
}

function ActivityDetailModal({entry, onClose}: {entry: ActivityEntry; onClose: () => void}) {
  // Inline modal — keeps the slice tight (full ResponsiveModal not
  // strictly needed since the detail view is read-only).
  return (
    <div
      onClick={onClose}
      className='fixed inset-0 z-40 bg-black/40 flex items-center justify-center p-4'
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 max-w-2xl w-full max-h-[80vh] overflow-y-auto'
      >
        <div className='flex items-center justify-between mb-2'>
          <h4 className='text-sm font-medium text-primary'>{entry.eventType}</h4>
          <button
            onClick={onClose}
            className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary'
          >
            Close
          </button>
        </div>
        <dl className='text-[11px] grid grid-cols-[110px_1fr] gap-x-3 gap-y-1'>
          <dt className='text-muted'>Tier</dt>
          <dd className='text-primary'>{entry.tier}</dd>
          <dt className='text-muted'>When</dt>
          <dd className='text-primary'>{new Date(entry.occurredAt).toISOString()}</dd>
          <dt className='text-muted'>Actor</dt>
          <dd className='text-primary'>
            {entry.actorType} {entry.actorUserId || ''}
          </dd>
          <dt className='text-muted'>Target</dt>
          <dd className='text-primary'>
            {entry.targetType || '—'} {entry.targetId || ''}
          </dd>
          <dt className='text-muted'>Summary</dt>
          <dd className='text-primary'>{entry.summary}</dd>
        </dl>
        {entry.payload && (
          <pre className='mt-2 text-[10px] font-mono text-muted whitespace-pre-wrap'>
            {entry.payload}
          </pre>
        )}
      </div>
    </div>
  );
}
