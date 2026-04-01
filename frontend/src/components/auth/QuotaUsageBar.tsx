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
        <span className="text-[var(--status-success)]">∞ unlimited</span>
      </div>
    );
  }

  const pct = Math.min((used / limit) * 100, 100);
  const barColor = pct >= 90 ? 'bg-red-500' : pct >= 70 ? 'bg-amber-500' : 'bg-accent-blue';

  return (
    <div className="flex items-center gap-3 text-xs">
      <span className="text-muted">Runs this month:</span>
      <div className="flex items-center gap-2">
        <div className="h-1.5 w-24 rounded-full bg-[var(--gray-400)]">
          <div className={`h-full rounded-full transition-all ${barColor}`} style={{ width: `${pct}%` }} />
        </div>
        <span className="text-secondary">
          {used} / {limit}
          {pct >= 90 && <span className="ml-1 text-accent-red">⚠ Quota nearly full</span>}
        </span>
      </div>
    </div>
  );
}
