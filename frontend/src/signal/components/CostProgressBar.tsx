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
  const pct = cap > 0 ? Math.min(100, (spent / cap) * 100) : 0;
  const tone = pct >= 100 ? 'fail' : pct >= 80 ? 'warn' : 'good';
  const fillColor =
    tone === 'fail' ? 'var(--signal-coral)' :
    tone === 'warn' ? 'var(--signal-y)' :
    'var(--signal-mint)';
  const labelColor =
    tone === 'fail' ? 'var(--signal-coral-text)' :
    tone === 'warn' ? 'var(--signal-y-text)' :
    'var(--signal-mint-text-on-light)';
  return (
    <div
      role='progressbar'
      aria-valuenow={Math.round(pct)}
      aria-valuemin={0}
      aria-valuemax={100}
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
          ${spent.toFixed(2)} / ${cap.toFixed(2)} · {Math.round(pct)}%
        </span>
      </div>
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
            width: `${pct}%`,
            height: '100%',
            background: fillColor,
            transition: 'width 0.3s ease-out',
          }}
        />
      </div>
    </div>
  );
}
