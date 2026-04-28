import { useCallback, useEffect, useState } from 'react';
import { fetchBillingStatus, type BillingStatus } from '@/lib/billing-api';
import { useAuthStore } from '@/stores/useAuthStore';

/** Dispatch this event anywhere to force UsageBadge to refresh. */
export const refreshUsageBadge = () =>
  window.dispatchEvent(new CustomEvent('usage-badge:refresh'));

export function UsageBadge() {
  const [status, setStatus] = useState<BillingStatus | null>(null);
  const user = useAuthStore((s) => s.user);

  const refresh = useCallback(() => {
    fetchBillingStatus().then(setStatus).catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    // Refresh every 2 minutes
    const interval = setInterval(refresh, 2 * 60 * 1000);
    // Listen for explicit refresh events (e.g. after credits charged)
    window.addEventListener('usage-badge:refresh', refresh);
    return () => {
      clearInterval(interval);
      window.removeEventListener('usage-badge:refresh', refresh);
    };
  }, [refresh]);

  // v2.5.13 — SUPER_ADMIN doesn't operate within billing quotas; hide the badge
  // entirely so they don't see misleading "283/10 runs" red bars when their
  // backing billing plan is PRO but they're actually unlimited via role.
  if (user?.globalRole === 'SUPER_ADMIN') return null;
  // Tier-coherence: TEAM + ENTERPRISE are BYOK + unlimited by spec.
  if (user?.tier === 'ENTERPRISE' || user?.tier === 'TEAM') return null;

  if (!status) return null;

  const runsUsage = status.usage.find(u => u.metric === 'RUNS');
  if (!runsUsage) return null;

  // Hide entirely for unlimited tiers (ENTERPRISE: max = -1)
  if (runsUsage.max === -1) return null;

  const pct = runsUsage.pctUsed;
  const color = pct > 90 ? 'text-accent-red' : pct > 70 ? 'text-accent-amber' : 'text-muted';

  return (
    <div className={`flex items-center gap-1.5 text-xs ${color}`}>
      <div className="w-16 h-1.5 bg-[var(--gray-400)] rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${
            pct > 90 ? 'bg-status-error' : pct > 70 ? 'bg-status-waiting' : 'bg-accent-blue'
          }`}
          style={{ width: `${Math.min(pct, 100)}%` }}
        />
      </div>
      <span>{runsUsage.current}/{runsUsage.max} runs</span>
    </div>
  );
}
