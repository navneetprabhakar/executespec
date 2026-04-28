import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Users, X } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import { getRuns } from '@/lib/api';

/**
 * R-312 (Wave 7) — Pro → Team upgrade nudge banner.
 *
 * **Why this exists.** F2 (Senior Priya, Pro→Team candidate) called out
 * that the upgrade path is pull-only — Pro users can run 10+ specs solo
 * without ever seeing an in-app prompt to invite their team. The
 * persona report ranked it as P3 (3-day fix, single-component
 * commitment device that converts solo Pro into Team).
 *
 * **What this fix is.** A single dismissible banner mounted in the v3
 * shell that renders only when ALL of these are true:
 *   - User is authenticated
 *   - Tier is exactly 'PRO' (not FREE — wrong stage; not TEAM — already
 *     converted; not ENTERPRISE — wrong upgrade path)
 *   - Lifetime completed run count ≥ 5 (the F2-defined milestone — runs
 *     are sticky achievements, the user has *proven* the workflow works
 *     for them solo, so the nudge feels earned not pushy)
 *   - User hasn't dismissed it (localStorage `v3.upgrade-nudge.dismissed`
 *     persists across sessions — once dismissed, never again on this
 *     browser; cheap to wipe by clearing site data)
 *
 * The "5 runs" count is read once on mount via getRuns; the request is
 * cheap (page=0,size=1 → only `totalElements` matters). Failures
 * silently noop — banner just doesn't render. No retry, no toast.
 *
 * Acceptance per docs/plans/wave-7-issue-list.md R-312:
 *   (a) Renders only for PRO with ≥ 5 completed runs
 *   (b) Dismiss persists across sessions
 *   (c) Doesn't render for non-PRO tiers
 *   (d) CTA → /v3/team upgrade flow
 */

const DISMISS_KEY = 'v3.upgrade-nudge.dismissed';
const MILESTONE = 5;

export default function UpgradeNudge() {
  const user = useAuthStore((s) => s.user);
  const [runCount, setRunCount] = useState<number | null>(null);
  const [dismissed, setDismissed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(DISMISS_KEY) === '1';
    } catch {
      return false;
    }
  });

  // Only PRO users get the count fetch — saves an API call for everyone else.
  const isProTier = user?.tier === 'PRO';

  useEffect(() => {
    if (!isProTier || dismissed) return;
    let cancelled = false;
    getRuns({ status: 'COMPLETE', page: 0, size: 1 })
      .then((p) => {
        if (cancelled) return;
        setRunCount(p?.totalElements ?? 0);
      })
      .catch(() => {
        // Silent fail — banner just doesn't render. The user is mid-task,
        // a "couldn't load run count" toast would be worse than no banner.
      });
    return () => {
      cancelled = true;
    };
  }, [isProTier, dismissed]);

  function dismiss() {
    try {
      localStorage.setItem(DISMISS_KEY, '1');
    } catch {
      /* localStorage unavailable (private mode etc.) — still hide for this session */
    }
    setDismissed(true);
  }

  // Render-eligibility gate: render nothing unless every condition is met.
  if (!isProTier) return null;
  if (dismissed) return null;
  if (runCount === null) return null; // still loading
  if (runCount < MILESTONE) return null;

  return (
    <div
      role="region"
      aria-label="Upgrade suggestion"
      style={{
        margin: '0 auto 12px',
        maxWidth: 1140,
        padding: '10px 14px',
        background:
          'linear-gradient(90deg, rgba(249,115,22,0.10) 0%, rgba(251,191,36,0.10) 100%)',
        border: '1px solid rgba(249,115,22,0.32)',
        borderRadius: 8,
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        fontSize: 13,
        color: 'var(--v3-text)',
      }}
    >
      <Users
        className="h-4 w-4"
        style={{ color: 'var(--c-pop-orange, #F97316)', flexShrink: 0 }}
        aria-hidden="true"
      />
      <span style={{ flex: 1, lineHeight: 1.5 }}>
        You've shipped <strong>{runCount} PRs</strong> solo on Pro. Want to invite your team
        to share credits and review?
      </span>
      <Link
        to="/v3/team"
        style={{
          padding: '6px 12px',
          background: 'var(--v3-brand)',
          color: 'var(--v3-text-on-brand)',
          borderRadius: 5,
          fontSize: 12,
          fontWeight: 600,
          textDecoration: 'none',
          whiteSpace: 'nowrap',
        }}
      >
        Invite team →
      </Link>
      <button
        type="button"
        onClick={dismiss}
        aria-label="Dismiss upgrade suggestion"
        title="Dismiss"
        style={{
          background: 'transparent',
          border: 'none',
          color: 'var(--v3-text-subtle)',
          cursor: 'pointer',
          padding: 4,
          display: 'inline-flex',
          alignItems: 'center',
        }}
      >
        <X className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
}
