/**
 * Live cost progress bar — green 0–80%, amber 80–100%, red ≥100% of the hard cap.
 * Mirrors v3's CostProgressBar. Aria-progressbar + sr-only label for a11y.
 */
export function CostProgressBar({
  spent,
  cap,
  label = 'Spend vs hard cap',
}: {
  spent: number;
  cap: number;
  label?: string;
}) {
  const rawPct = cap > 0 ? Math.max(0, (spent / cap) * 100) : 0;
  const fillPct = Math.min(100, rawPct);
  const overage = cap > 0 ? Math.max(0, spent - cap) : 0;
  const tone = rawPct >= 100 ? 'fail' : rawPct >= 80 ? 'warn' : 'good';
  const fillColor =
    tone === 'fail'
      ? 'var(--signal-coral)'
      : tone === 'warn'
        ? 'var(--signal-y)'
        : 'var(--signal-mint)';
  const labelColor =
    tone === 'fail'
      ? 'var(--signal-coral-text)'
      : tone === 'warn'
        ? 'var(--signal-y-text)'
        : 'var(--signal-mint-text-on-light)';
  return (
    <div
      role='progressbar'
      aria-valuenow={Math.round(fillPct)}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuetext={
        overage > 0
          ? `${Math.round(rawPct)}%, exceeded by $${overage.toFixed(4)}`
          : `${Math.round(rawPct)}%`
      }
      aria-label={label}
      style={{width: '100%'}}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          fontSize: 11,
          fontFamily: "'JetBrains Mono', monospace",
          color: labelColor,
          marginBottom: 4,
        }}
      >
        <span>{label}</span>
        <span>
          ${spent.toFixed(4)} / ${cap.toFixed(4)} · {Math.round(rawPct)}%
        </span>
      </div>
      {overage > 0 && (
        <div
          role='status'
          style={{color: labelColor, fontSize: 11, marginBottom: 4}}
        >{`Exceeded by $${overage.toFixed(4)}`}</div>
      )}
      <div
        style={{
          height: 6,
          background: 'var(--signal-card-elev)',
          borderRadius: 3,
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${fillPct}%`,
            height: '100%',
            background: fillColor,
            transition: 'width 0.3s ease-out',
          }}
        />
      </div>
    </div>
  );
}
