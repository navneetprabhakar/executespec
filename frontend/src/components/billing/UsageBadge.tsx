import { useEffect, useState } from 'react';
import { fetchBillingStatus, type BillingStatus } from '@/lib/billing-api';

export function UsageBadge() {
  const [status, setStatus] = useState<BillingStatus | null>(null);

  useEffect(() => {
    fetchBillingStatus().then(setStatus).catch(() => {});
    // Refresh every 5 minutes
    const interval = setInterval(() => {
      fetchBillingStatus().then(setStatus).catch(() => {});
    }, 5 * 60 * 1000);
    return () => clearInterval(interval);
  }, []);

  if (!status) return null;

  const runsUsage = status.usage.find(u => u.metric === 'RUNS');
  if (!runsUsage) return null;

  const pct = runsUsage.pctUsed;
  const color = pct > 90 ? 'text-red-400' : pct > 70 ? 'text-amber-400' : 'text-zinc-500';

  return (
    <div className={`flex items-center gap-1.5 text-xs ${color}`}>
      <div className="w-16 h-1.5 bg-zinc-700 rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${
            pct > 90 ? 'bg-red-500' : pct > 70 ? 'bg-amber-500' : 'bg-indigo-500'
          }`}
          style={{ width: `${Math.min(pct, 100)}%` }}
        />
      </div>
      <span>{runsUsage.current}/{runsUsage.max} runs</span>
    </div>
  );
}
