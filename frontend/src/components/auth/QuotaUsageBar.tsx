import { Link } from 'react-router-dom';

interface Props {
  used: number;
  limit: number;   // -1 = unlimited
  tier: string;
}

export default function QuotaUsageBar({ used, limit, tier }: Props) {
  if (limit === -1) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted">
        <span>Runs this month:</span>
        <span className="font-semibold text-primary">{used}</span>
        <span className="text-status-success">∞ unlimited</span>
      </div>
    );
  }

  const pct = Math.min((used / limit) * 100, 100);
  // v2.5.12 — token-aligned. Was raw `bg-red-500` / `bg-amber-500`; now
  // uses status tokens that match StatusBadge + ValidationReport.
  const barColor =
    pct >= 90 ? 'bg-status-error' :
    pct >= 70 ? 'bg-status-waiting' :
    'bg-accent-blue';
  // v2.5.12 — surface upgrade CTA when the user is approaching their cap.
  const showUpgrade = pct >= 70 && tier !== 'ENTERPRISE' && tier !== 'MAX';

  return (
    <div className="flex items-center gap-3 text-xs">
      <span className="text-muted">Runs this month:</span>
      <div className="flex items-center gap-2">
        <div className="h-1.5 w-24 rounded-full bg-[var(--gray-400)]">
          <div className={`h-full rounded-full transition-all ${barColor}`} style={{ width: `${pct}%` }} />
        </div>
        <span className="text-secondary">
          {used} / {limit}
          {pct >= 90 && <span className="ml-1 text-status-error">Quota nearly full</span>}
        </span>
        {showUpgrade && (
          <Link
            to="/plans"
            className="text-accent-blue hover:underline font-medium"
          >
            Upgrade
          </Link>
        )}
      </div>
    </div>
  );
}
