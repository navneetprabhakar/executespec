/**
 * GA-readiness P3.2 (2026-05-06) — visible cost-cap progress bar.
 *
 * Renders a horizontal bar showing how much of the run's approved hard cap
 * has been spent. Three colour thresholds:
 *   - 0–80%   : green (calm)
 *   - 80–100% : amber (about to land at the cap)
 *   - ≥100%   : red   (cap exceeded — backend will terminate at the next
 *                budget check; this is a transient state)
 *
 * Audit Risk #6 mitigation: customers will dispute a $20 charge if they
 * can't see it coming. This bar makes the spend trajectory visible mid-run
 * so the gate-approval estimate is no longer the only number they see.
 *
 * Falls through silently when {@code cap} is null, zero, or unknown — the
 * bar simply doesn't render. Legacy runs predating the V2 hardCapUsd
 * column are unaffected.
 */
import {useMemo} from 'react';

interface CostProgressBarProps {
  /** Current spend in USD (live snapshot from /api/runs/{id}/cost). */
  current: number | null | undefined;
  /** Approved hard cap in USD ({@code pipeline_run.hard_cap_usd}). */
  cap: number | null | undefined;
  /** Compact mode — for inline use beside the credits chip. */
  compact?: boolean;
}

export function CostProgressBar({current, cap, compact = false}: CostProgressBarProps) {
  const ratio = useMemo(() => {
    if (current == null || cap == null || cap <= 0) return null;
    return Math.max(0, Math.min(1.05, current / cap)); // cap visual at 105%
  }, [current, cap]);

  if (ratio == null) return null;

  const pct = Math.min(100, Math.round(ratio * 100));
  const overCap = current != null && cap != null && current >= cap;

  // Threshold colour. CSS vars come from the design tokens; fallback hex
  // to keep this component working if the test renderer doesn't load CSS.
  const barColour = overCap
    ? 'bg-[var(--status-error)]'
    : pct >= 80
      ? 'bg-[var(--status-warning)]'
      : 'bg-[var(--status-success)]';

  const trackHeight = compact ? 'h-1' : 'h-1.5';
  const labelClass = compact ? 'text-[10px]' : 'text-xs';

  return (
    <div className='flex flex-col gap-1' aria-label='Cost cap progress'>
      <div className={`flex items-baseline justify-between ${labelClass}`}>
        <span className='text-muted font-mono'>
          ${current?.toFixed(2)} / ${cap?.toFixed(2)}
        </span>
        <span
          className={
            overCap
              ? 'text-[var(--status-error)] font-medium font-mono'
              : pct >= 80
                ? 'text-[var(--status-warning)] font-mono'
                : 'text-muted font-mono'
          }
        >
          {pct}%
        </span>
      </div>
      <div className={`w-full ${trackHeight} bg-[var(--gray-200)] rounded-full overflow-hidden`}>
        <div
          className={`${trackHeight} ${barColour} transition-[width] duration-500`}
          style={{width: `${pct}%`}}
          role='progressbar'
          aria-valuenow={pct}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuetext={`${pct} percent of the $${cap?.toFixed(2)} cap consumed`}
        />
      </div>
      {overCap && (
        <p className='text-[10px] text-[var(--status-error)]' role='alert'>
          Hard cap reached — run will terminate at the next budget check.
        </p>
      )}
    </div>
  );
}

export default CostProgressBar;
