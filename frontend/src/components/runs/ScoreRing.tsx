/**
 * ScoreRing — conic-gradient score visualisation borrowed from the marketing
 * mocks on executespec.ai/#tour. Used as the hero element for the expanded
 * validation report + wherever a big 0-100 number deserves visual weight.
 *
 * Inline conic-gradient (no SVG/canvas) keeps the component zero-dependency
 * and matches the landing-site tour pixel-for-pixel.
 *
 * v2.6.0 · UI polish v1 — part of the marketing-to-app alignment pass.
 */
import { cn } from '@/lib/utils';

interface ScoreRingProps {
  /** 0-100 score — caller is responsible for filtering null. */
  score: number;
  /** Short label under the number (e.g. "verified / 100"). */
  label?: string;
  /** Pixel size of the full ring. Default 140. */
  size?: number;
  /** Ring thickness in pixels. Default 10. */
  thickness?: number;
  /** Override stroke color; defaults to graduated by score (≥85 green, ≥70 blue, ≥50 amber, else red). */
  color?: string;
  className?: string;
}

function defaultColor(score: number): string {
  if (score >= 85) return 'var(--status-success)';
  if (score >= 70) return 'var(--accent-blue)';
  if (score >= 50) return 'var(--status-waiting)';
  return 'var(--status-error)';
}

export default function ScoreRing({
  score,
  label = 'verified / 100',
  size = 140,
  thickness = 10,
  color,
  className,
}: ScoreRingProps) {
  const clamped = Math.max(0, Math.min(100, Math.round(score)));
  const stroke = color ?? defaultColor(clamped);
  const innerInset = thickness;

  return (
    <div
      className={cn('relative flex items-center justify-center flex-shrink-0', className)}
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        background: `conic-gradient(${stroke} 0% ${clamped}%, var(--border-default) ${clamped}% 100%)`,
      }}
      role="img"
      aria-label={`${clamped} out of 100 — ${label}`}
    >
      <div
        className="absolute rounded-full"
        style={{
          inset: innerInset,
          background: 'var(--gray-100)',
        }}
      />
      <div className="relative flex flex-col items-center leading-none">
        <div
          className="font-mono font-semibold tabular-nums text-primary"
          style={{ fontSize: size * 0.34 }}
        >
          {clamped}
        </div>
        {label && (
          <div
            className="font-mono tracking-wider uppercase text-muted mt-1"
            style={{ fontSize: Math.max(9, size * 0.065) }}
          >
            {label}
          </div>
        )}
      </div>
    </div>
  );
}
