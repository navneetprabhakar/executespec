import { useCallback, useEffect, useState } from 'react';
import { fetchBillingStatus, type BillingStatus } from '@/lib/billing-api';

/** Dispatch this event anywhere to force UsageBadge to refresh. */
export const refreshUsageBadge = () =>
  window.dispatchEvent(new CustomEvent('usage-badge:refresh'));

export function UsageBadge() {
  const [status, setStatus] = useState<BillingStatus | null>(null);

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
            pct > 90 ? 'bg-red-500' : pct > 70 ? 'bg-amber-500' : 'bg-accent-blue'
          }`}
          style={{ width: `${Math.min(pct, 100)}%` }}
        />
      </div>
      <span>{runsUsage.current}/{runsUsage.max} runs</span>
    </div>
  );
}
