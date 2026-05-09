import type {ReactNode} from 'react';
import {Check, AlertTriangle, X, Info, Sparkles} from 'lucide-react';

/**
 * R-106 — single status primitive.
 *
 * Replaces every chip variant in v3 (the strict review caught 5
 * different visual languages for status on the runs list alone).
 *
 * Tone is the only knob that varies; label is the only content.
 * Optional `dot` prefixes a coloured dot for in-progress/pulse states.
 * Optional `icon` slot for a leading glyph (✓ / ✗ / ⚠ etc).
 *
 * F-21 fix (2026-05-09) — colour-only status was an a11y violation
 * (red/green colour-blind users couldn't distinguish ok / err). Each
 * non-neutral tone now auto-renders a default lucide glyph that
 * encodes the same semantics (Check / AlertTriangle / X / Info /
 * Sparkles), and a visually-hidden tone label gives screen readers
 * the same signal. Callers can still pass `icon=` to override the
 * default glyph for one-off variants.
 */
export type PillTone = 'ok' | 'warn' | 'err' | 'neutral' | 'info' | 'brand';

interface PillProps {
  tone?: PillTone;
  dot?: boolean;
  icon?: ReactNode;
  children: ReactNode;
  /** Add a native HTML `title` tooltip — useful for status_reason hints. */
  title?: string;
  /** Visual size; defaults to `md`. `sm` for inline rows. */
  size?: 'sm' | 'md';
  className?: string;
  /** Set true to suppress the auto-supplied tone icon (for chips where
   * the children already carry an explicit glyph or icon). */
  noAutoIcon?: boolean;
}

const toneToVar: Record<PillTone, {bg: string; fg: string; border: string}> = {
  ok: {bg: 'rgba(16,185,129,0.12)', fg: 'var(--v3-success)', border: 'rgba(16,185,129,0.32)'},
  warn: {bg: 'rgba(245,158,11,0.12)', fg: 'var(--v3-warning)', border: 'rgba(245,158,11,0.32)'},
  err: {bg: 'rgba(239,68,68,0.12)', fg: 'var(--v3-error)', border: 'rgba(239,68,68,0.36)'},
  neutral: {
    bg: 'rgba(148,163,184,0.10)',
    fg: 'var(--v3-text-muted)',
    border: 'rgba(148,163,184,0.26)',
  },
  info: {bg: 'rgba(91,141,239,0.14)', fg: 'var(--v3-brand)', border: 'rgba(91,141,239,0.32)'},
  brand: {bg: 'rgba(91,141,239,0.16)', fg: 'var(--v3-brand-2)', border: 'rgba(91,141,239,0.36)'},
};

const TONE_ICON: Record<PillTone, {Cmp: typeof Check | null; srLabel: string}> = {
  ok: {Cmp: Check, srLabel: 'Success'},
  warn: {Cmp: AlertTriangle, srLabel: 'Warning'},
  err: {Cmp: X, srLabel: 'Error'},
  neutral: {Cmp: null, srLabel: ''},
  info: {Cmp: Info, srLabel: 'Info'},
  brand: {Cmp: Sparkles, srLabel: ''},
};

export default function Pill({
  tone = 'neutral',
  dot = false,
  icon,
  children,
  title,
  size = 'md',
  className,
  noAutoIcon = false,
}: PillProps) {
  const palette = toneToVar[tone];
  const isSmall = size === 'sm';
  const toneInfo = TONE_ICON[tone];
  // Auto-supply the tone glyph when caller didn't pass one + tone has
  // a meaningful icon + the chip isn't already prefixed by a dot.
  const AutoIconCmp = !icon && !dot && !noAutoIcon ? toneInfo.Cmp : null;
  const iconSize = isSmall ? 10 : 12;
  return (
    <span
      className={`v3-pill ${className ?? ''}`}
      title={title}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: isSmall ? 4 : 6,
        padding: isSmall ? '1px 6px' : '2px 8px',
        fontSize: isSmall ? 10.5 : 11.5,
        fontWeight: 600,
        lineHeight: 1.4,
        letterSpacing: 0.1,
        background: palette.bg,
        color: palette.fg,
        border: `1px solid ${palette.border}`,
        borderRadius: 4,
        whiteSpace: 'nowrap',
      }}
    >
      {dot && (
        <span
          aria-hidden='true'
          style={{
            display: 'inline-block',
            width: isSmall ? 5 : 6,
            height: isSmall ? 5 : 6,
            borderRadius: '50%',
            background: palette.fg,
          }}
        />
      )}
      {icon && (
        <span aria-hidden='true' style={{display: 'inline-flex', alignItems: 'center'}}>
          {icon}
        </span>
      )}
      {AutoIconCmp && (
        <AutoIconCmp size={iconSize} strokeWidth={2.5} aria-hidden='true' />
      )}
      {/* Visually-hidden tone label so SR users get the same signal that
          colour-blind users now get from the leading glyph. */}
      {toneInfo.srLabel && (
        <span
          style={{
            position: 'absolute',
            width: 1,
            height: 1,
            margin: -1,
            padding: 0,
            overflow: 'hidden',
            clip: 'rect(0,0,0,0)',
            whiteSpace: 'nowrap',
            border: 0,
          }}
        >
          {toneInfo.srLabel}:
        </span>
      )}
      {children}
    </span>
  );
}
