import {errMsg} from '@/lib/utils';
import {Fragment, useEffect, useMemo, useState} from 'react';
import GlassPanel from '@/components/shared/GlassPanel';
import {
  getNotificationPreferences,
  updateNotificationPreference,
  type NotificationPrefRow,
} from '@/lib/api';

/**
 * M-003 (Wave 2) — minimal toggle matrix for notification preferences.
 * Per parked decision #7 the UX shape is "Email/InApp × event-type" —
 * a flat checkbox grid. Backend (NotificationPrefController) returns
 * one row per (event-type, channel) pair carrying current effective
 * state, default state, and override flag.
 *
 * The grid groups events by tier (HIGH_SIGNAL first, ADMIN_TOGGLE
 * second) so users can ignore the long admin list if they don't run
 * an org. AUDIT_ONLY events are filtered server-side — they're not
 * user-routable.
 */
export default function NotificationPreferences() {
  const [rows, setRows] = useState<NotificationPrefRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getNotificationPreferences()
      .then((r) => {
        if (alive) setRows(r);
      })
      .catch((e) => {
        if (alive) setError(errMsg(e) ?? 'Failed to load preferences');
      });
    return () => {
      alive = false;
    };
  }, []);

  // Group rows: { eventType: { IN_APP: row, EMAIL: row } }
  const grouped = useMemo(() => {
    if (!rows) return null;
    const out: Record<
      string,
      {tier: string; IN_APP?: NotificationPrefRow; EMAIL?: NotificationPrefRow}
    > = {};
    for (const r of rows) {
      if (!out[r.eventType]) out[r.eventType] = {tier: r.tier};
      if (r.channel === 'IN_APP') out[r.eventType].IN_APP = r;
      else if (r.channel === 'EMAIL') out[r.eventType].EMAIL = r;
    }
    return out;
  }, [rows]);

  const events = useMemo(() => {
    if (!grouped) return [];
    return Object.keys(grouped).sort((a, b) => {
      const ta = grouped[a].tier,
        tb = grouped[b].tier;
      // HIGH_SIGNAL first, then ADMIN_TOGGLE
      if (ta !== tb) return ta === 'HIGH_SIGNAL' ? -1 : 1;
      return a.localeCompare(b);
    });
  }, [grouped]);

  /**
   * Audit r5 (P2-6, 2026-05-06) — categorise events. The flat list of
   * 30+ event types overwhelmed first-run users; the tier sort split
   * HIGH_SIGNAL vs ADMIN_TOGGLE but didn't help users find the
   * specific category they cared about ("just runs, please"). This
   * resolver groups events into 5 plain-English buckets driven by
   * the event name prefix. New event types fall into "Other" so we
   * never lose them in a category miss.
   */
  function eventCategory(eventType: string): string {
    if (/^RUN_/i.test(eventType)) return 'Runs';
    if (/^PR_|^PR$|^PULL_REQUEST_/i.test(eventType)) return 'Pull requests';
    if (/^STANDARDS_|^ARCH_|^INVARIANT_/i.test(eventType)) return 'Standards & architecture';
    if (/^CODEMAP_|^ARCHITECTURE_DOC_|^MEMORY_|^AUTO_DETECTION_/i.test(eventType))
      return 'Codebase intelligence';
    if (/^QUOTA_|^BILLING_|^INVOICE_|^PAYMENT_|^TIER_/i.test(eventType)) return 'Billing & quota';
    if (/^USER_|^ORG_|^INVITE_|^MEMBER_/i.test(eventType)) return 'Org & members';
    return 'Other';
  }
  /**
   * Sort categories so the most-relevant block always renders first.
   * Runs is the daily-driver signal; Pull requests + Standards next;
   * Codebase intelligence + Org & members further down; Other last
   * (unknown event types stay reachable but never lead the page).
   */
  const CATEGORY_ORDER = [
    'Runs',
    'Pull requests',
    'Standards & architecture',
    'Billing & quota',
    'Codebase intelligence',
    'Org & members',
    'Other',
  ];
  const eventsByCategory = useMemo(() => {
    if (!grouped) return [] as Array<{category: string; events: string[]}>;
    const map: Record<string, string[]> = {};
    for (const ev of events) {
      const c = eventCategory(ev);
      if (!map[c]) map[c] = [];
      map[c].push(ev);
    }
    return CATEGORY_ORDER.filter((c) => (map[c]?.length ?? 0) > 0).map((c) => ({
      category: c,
      events: map[c],
    }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [grouped, events]);

  const handleToggle = async (eventType: string, channel: 'IN_APP' | 'EMAIL', next: boolean) => {
    const key = `${eventType}|${channel}`;
    setBusyKey(key);
    setError(null);
    try {
      const updated = await updateNotificationPreference(eventType, channel, next);
      setRows(
        (prev) =>
          prev?.map((r) =>
            r.eventType === updated.eventType && r.channel === updated.channel ? updated : r,
          ) ?? null,
      );
    } catch (e) {
      setError(errMsg(e) ?? 'Failed to save preference');
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <GlassPanel className='px-5 py-4'>
      <div className='flex items-baseline justify-between mb-3 gap-3 flex-wrap'>
        <div>
          <h3 className='text-sm font-medium text-primary'>Notification preferences</h3>
          <p className='text-xs text-muted mt-0.5'>
            Choose which events ping you. In-app notifications appear in the bell; email goes to
            your account address.
          </p>
        </div>
      </div>

      {error && (
        <div className='mb-3 p-2 bg-status-error/10 border border-status-error/30 text-status-error rounded-sm text-xs'>
          {error}
        </div>
      )}

      {!grouped && !error && <p className='text-xs text-muted'>Loading…</p>}

      {grouped && events.length === 0 && (
        <p className='text-xs text-muted'>No notification events available.</p>
      )}

      {grouped && events.length > 0 && (
        <div className='overflow-x-auto -mx-1'>
          <table className='w-full text-xs'>
            <thead>
              <tr className='text-left text-muted'>
                <th className='px-2 py-2 font-medium'>Event</th>
                <th className='px-2 py-2 font-medium text-center w-20'>In-app</th>
                <th className='px-2 py-2 font-medium text-center w-20'>Email</th>
              </tr>
            </thead>
            <tbody>
              {/* Audit r5 (P2-6, 2026-05-06) — 5-bucket grouping with
                  section headers. Pre-fix every event type sat in one
                  flat alphabetical list (sorted only by HIGH_SIGNAL
                  vs ADMIN_TOGGLE). Now Runs lead, then PRs, then
                  Standards, etc., with a header row per category so
                  scanning is one row per section instead of 30+. */}
              {eventsByCategory.map(({category, events: catEvents}) => (
                <Fragment key={category}>
                  <tr className='border-t border-[var(--border-default)]'>
                    <td
                      colSpan={3}
                      className='px-2 pt-3 pb-1 text-[10px] uppercase tracking-wider text-muted font-semibold'
                    >
                      {category}
                    </td>
                  </tr>
                  {catEvents.map((eventType) => {
                    const g = grouped[eventType];
                    const isAdminTier = g.tier === 'ADMIN_TOGGLE';
                    return (
                      <tr key={eventType} className='border-t border-[var(--border-default)]'>
                        <td className='px-2 py-2'>
                          <span className='text-secondary font-mono'>{eventType}</span>
                          {isAdminTier && (
                            <span className='ml-2 text-[10px] uppercase tracking-wider text-muted'>
                              admin
                            </span>
                          )}
                        </td>
                        {(['IN_APP', 'EMAIL'] as const).map((channel) => {
                          const row = g[channel];
                          const key = `${eventType}|${channel}`;
                          if (!row)
                            return (
                              <td key={channel} className='px-2 py-2 text-center text-muted'>
                                —
                              </td>
                            );
                          return (
                            <td key={channel} className='px-2 py-2 text-center'>
                              <input
                                type='checkbox'
                                checked={row.enabled}
                                disabled={busyKey === key}
                                onChange={(e) => handleToggle(eventType, channel, e.target.checked)}
                                aria-label={`${eventType} via ${channel === 'IN_APP' ? 'in-app' : 'email'}`}
                                className='accent-accent-blue cursor-pointer'
                              />
                              {row.override && (
                                <span
                                  title="You've overridden the default"
                                  className='ml-1 text-[10px] text-muted'
                                >
                                  ·
                                </span>
                              )}
                            </td>
                          );
                        })}
                      </tr>
                    );
                  })}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </GlassPanel>
  );
}
