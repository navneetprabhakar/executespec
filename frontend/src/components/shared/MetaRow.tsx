/**
 * 2026-05-14 (mobile-first redesign, P1) — Metadata-strip primitive.
 *
 * Dot-separated metadata strips ("ACTIVE · ANTHROPIC · OWNER pplai-app ·
 * REPOS 2 · DONE 16 · spend $3.72") read fine on desktop but break on
 * 375px phones: the chips wrap to 3+ lines, a trailing `·` lands at the
 * end of a clipped line and looks like a typo, and the user can't tell
 * which value belongs to which label.
 *
 * Best-in-class apps (Linear, Stripe, Vercel) collapse these to a
 * label-above-value grid on phone and keep the dot-separated rhythm on
 * desktop. This primitive does the same in one place so every meta strip
 * across the app shares the same shape.
 *
 * Usage:
 *   <MetaRow
 *     items={[
 *       {label: 'Status', value: 'ACTIVE', tone: 'success'},
 *       {label: 'Provider', value: 'ANTHROPIC'},
 *       {label: 'Owner', value: 'pplai-app'},
 *       {label: 'Repos', value: '2'},
 *       {label: 'Done', value: '16'},
 *       {label: 'Spend', value: '$3.72', mono: true},
 *     ]}
 *   />
 *
 * Falsy values are filtered out (so callers can spread conditional
 * segments without surfacing empty chips). Tones map to small colour
 * accents — keep optional.
 */
import type {ReactNode} from 'react';
import {cn} from '@/lib/utils';

export type MetaTone = 'neutral' | 'success' | 'warn' | 'danger' | 'brand' | 'muted';

export interface MetaItem {
  label: string;
  value: ReactNode;
  /** When true, render value in mono font (good for IDs, money, timestamps). */
  mono?: boolean;
  /** Optional colour accent for the value. */
  tone?: MetaTone;
  /** Optional click handler — turns the chip into a tappable button. */
  onClick?: () => void;
  /** Optional tooltip text. */
  title?: string;
}

interface MetaRowProps {
  items: (MetaItem | null | undefined | false)[];
  /** Extra className for the outer wrapper. */
  className?: string;
  /** Force the desktop inline layout (default: auto-switch at >=sm). */
  inlineOnly?: boolean;
}

const VALUE_TONE_CLASS: Record<MetaTone, string> = {
  neutral: 'text-secondary',
  success: 'text-status-success',
  warn: 'text-status-waiting',
  danger: 'text-status-error',
  brand: 'text-accent-blue',
  muted: 'text-muted',
};

export default function MetaRow({items, className, inlineOnly = false}: MetaRowProps) {
  const real = items.filter((i): i is MetaItem => !!i);
  if (real.length === 0) return null;

  return (
    <>
      {/* ── Phone: stacked grid (label-above-value), 2-up ─────────────── */}
      {!inlineOnly && (
        <dl className={cn('sm:hidden grid grid-cols-2 gap-x-3 gap-y-2 text-xs', className)}>
          {real.map((it, i) => (
            <div key={`m-${i}`} className='min-w-0'>
              <dt className='text-[10px] uppercase tracking-wider text-muted font-medium leading-tight'>
                {it.label}
              </dt>
              <dd
                className={cn(
                  'mt-0.5 truncate text-sm',
                  VALUE_TONE_CLASS[it.tone ?? 'neutral'],
                  it.mono && 'font-mono',
                )}
                title={it.title}
              >
                {it.onClick ? (
                  <button
                    type='button'
                    onClick={it.onClick}
                    className='block w-full text-left truncate hover:text-primary transition-colors'
                  >
                    {it.value}
                  </button>
                ) : (
                  it.value
                )}
              </dd>
            </div>
          ))}
        </dl>
      )}

      {/* ── Desktop: dot-separated inline strip ──────────────────────────
          BUG fix 2026-05-14 — the previous classlist was
          `hidden sm:flex flex flex-wrap …`, where the later static `flex`
          overrode `hidden` on phone, rendering BOTH the phone grid AND the
          desktop strip stacked on the same screen. Splitting the layout
          mode into the conditional half so `hidden sm:flex` actually
          hides the strip under sm:. */}
      <div
        className={cn(
          inlineOnly ? 'flex' : 'hidden sm:flex',
          'flex-wrap items-baseline gap-x-2 gap-y-1 text-xs',
          className,
        )}
      >
        {real.map((it, i) => (
          <span key={`d-${i}`} className='inline-flex items-baseline gap-1.5'>
            {i > 0 && (
              <span className='text-subtle' aria-hidden='true'>
                ·
              </span>
            )}
            <span className='text-muted uppercase tracking-wider text-[10.5px] font-medium'>
              {it.label}
            </span>
            {it.onClick ? (
              <button
                type='button'
                onClick={it.onClick}
                title={it.title}
                className={cn(
                  'hover:text-primary transition-colors',
                  VALUE_TONE_CLASS[it.tone ?? 'neutral'],
                  it.mono && 'font-mono',
                )}
              >
                {it.value}
              </button>
            ) : (
              <span
                title={it.title}
                className={cn(VALUE_TONE_CLASS[it.tone ?? 'neutral'], it.mono && 'font-mono')}
              >
                {it.value}
              </span>
            )}
          </span>
        ))}
      </div>
    </>
  );
}
