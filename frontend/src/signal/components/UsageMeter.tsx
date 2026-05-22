import {Link} from 'react-router-dom';
import {useAuthStore} from '@/stores/useAuthStore';

/**
 * Compact runs-used / limit meter for the monthly quota. FREE is a genuine
 * MONTHLY quota now (15 runs/month), so the copy says "this month".
 *
 * Reads `permissions.currentRunCount` / `permissions.monthlyRunLimit` from the
 * auth store. `monthlyRunLimit === -1` means unlimited — we show a simple
 * count with no bar. The fill colour ramps green → amber → red as the user
 * approaches the cap, and an "Upgrade" link appears once they're ≥80% through.
 */
export function UsageMeter({className, style}: {className?: string; style?: React.CSSProperties}) {
  const permissions = useAuthStore((s) => s.permissions);
  if (!permissions) return null;

  const {currentRunCount, monthlyRunLimit} = permissions;
  const unlimited = monthlyRunLimit === -1;
  // Clamp the ratio to [0,1] for the bar; counts can exceed the limit (top-ups).
  const ratio = unlimited || monthlyRunLimit <= 0 ? 0 : currentRunCount / monthlyRunLimit;
  const pct = Math.max(0, Math.min(1, ratio));
  const nearLimit = !unlimited && ratio >= 0.8;
  const atLimit = !unlimited && currentRunCount >= monthlyRunLimit;

  // Green under 80%, amber 80–100%, red at/over the cap.
  const fill = atLimit
    ? 'var(--signal-coral)'
    : nearLimit
      ? 'var(--signal-y)'
      : 'var(--signal-mint)';

  const label = unlimited
    ? `${currentRunCount} runs this month`
    : `${currentRunCount} / ${monthlyRunLimit} runs this month`;

  return (
    <div
      className={className}
      style={{
        background: 'var(--signal-card-bg)',
        border: '1px solid var(--signal-hairline)',
        borderRadius: 12,
        padding: '10px 14px',
        ...style,
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
          marginBottom: unlimited ? 0 : 8,
          gap: 12,
        }}
      >
        <span style={{fontSize: 12.5, color: 'var(--signal-text-dim)', fontWeight: 500}}>
          {label}
        </span>
        {nearLimit && (
          <Link
            to='/plans'
            style={{
              fontSize: 12,
              fontWeight: 600,
              color: 'var(--signal-coral)',
              whiteSpace: 'nowrap',
            }}
          >
            {atLimit ? 'Out of runs — upgrade →' : 'Upgrade →'}
          </Link>
        )}
      </div>
      {!unlimited && (
        <div
          role='progressbar'
          aria-valuemin={0}
          aria-valuemax={monthlyRunLimit}
          aria-valuenow={Math.min(currentRunCount, monthlyRunLimit)}
          aria-label='Monthly runs used'
          style={{
            height: 6,
            borderRadius: 999,
            background: 'var(--signal-card-elev)',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              width: `${pct * 100}%`,
              height: '100%',
              background: fill,
              borderRadius: 999,
              transition: 'width 0.3s ease, background 0.3s ease',
            }}
          />
        </div>
      )}
    </div>
  );
}
