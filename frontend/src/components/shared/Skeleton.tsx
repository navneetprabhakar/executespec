/**
 * 2026-05-14 (mobile-first redesign) — Skeleton primitive.
 *
 * Before: every async surface in ExecuteSpec showed "Loading…" text on a
 * blank background while data was in flight. Best-in-class apps (Linear,
 * Stripe, Vercel) ship layout-matched skeletons so the user sees the
 * shape of the page immediately and perceived performance feels instant.
 *
 * Usage:
 *   <Skeleton variant="row" count={5} />     // 5 list-row skeletons
 *   <Skeleton variant="card" />              // a single card skeleton
 *   <Skeleton variant="text" w="60%" />      // a single text-line
 *   <Skeleton variant="chart" />             // a chart-sized rectangle
 *
 * The shimmer animation is defined in `frontend/src/index.css`
 * (`@keyframes shimmer`) and is suppressed automatically under
 * `prefers-reduced-motion`.
 */
import {cn} from '@/lib/utils';

type Variant = 'text' | 'row' | 'card' | 'chart' | 'circle';

interface SkeletonProps {
  variant?: Variant;
  /** How many copies to render (only for `row` + `card`). Default 1. */
  count?: number;
  /** Optional width override (CSS length). */
  w?: string;
  /** Optional height override (CSS length). */
  h?: string;
  className?: string;
  /** Aria-label announced to assistive tech. Default "Loading content". */
  label?: string;
}

const VARIANT_CLASS: Record<Variant, string> = {
  text: 'h-3 rounded-sm',
  row: 'h-12 rounded-md',
  card: 'h-24 rounded-lg',
  chart: 'h-44 rounded-lg',
  circle: 'rounded-full',
};

export default function Skeleton({
  variant = 'text',
  count = 1,
  w,
  h,
  className,
  label = 'Loading content',
}: SkeletonProps) {
  const items = Array.from({length: Math.max(1, count)});
  return (
    <div role='status' aria-busy='true' aria-label={label} className={cn('space-y-2', className)}>
      {items.map((_, i) => (
        <div
          key={i}
          className={cn(
            'shimmer block',
            VARIANT_CLASS[variant],
            variant === 'circle' && !h && 'h-12 w-12',
          )}
          style={{
            width: w ?? (variant === 'text' ? '100%' : undefined),
            height: h,
          }}
          aria-hidden='true'
        />
      ))}
    </div>
  );
}
