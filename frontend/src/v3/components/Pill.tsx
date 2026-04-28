import type { ReactNode } from 'react';

/**
 * R-106 — single status primitive.
 *
 * Replaces every chip variant in v3 (the strict review caught 5
 * different visual languages for status on the runs list alone).
 *
 * Tone is the only knob that varies; label is the only content.
 * Optional `dot` prefixes a coloured dot for in-progress/pulse states.
 * Optional `icon` slot for a leading glyph (✓ / ✗ / ⚠ etc).
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
}

const toneToVar: Record<PillTone, { bg: string; fg: string; border: string }> = {
  ok:      { bg: 'rgba(16,185,129,0.12)',  fg: 'var(--v3-success)', border: 'rgba(16,185,129,0.32)'  },
  warn:    { bg: 'rgba(245,158,11,0.12)',  fg: 'var(--v3-warning)', border: 'rgba(245,158,11,0.32)'  },
  err:     { bg: 'rgba(239,68,68,0.12)',   fg: 'var(--v3-error)', border: 'rgba(239,68,68,0.36)'   },
  neutral: { bg: 'rgba(148,163,184,0.10)', fg: 'var(--v3-text-muted)', border: 'rgba(148,163,184,0.26)' },
  info:    { bg: 'rgba(91,141,239,0.14)',  fg: 'var(--v3-brand)', border: 'rgba(91,141,239,0.32)'  },
  brand:   { bg: 'rgba(91,141,239,0.16)',  fg: 'var(--v3-brand-2)', border: 'rgba(91,141,239,0.36)'  },
};

export default function Pill({
  tone = 'neutral',
  dot = false,
  icon,
  children,
  title,
  size = 'md',
  className,
}: PillProps) {
  const palette = toneToVar[tone];
  const isSmall = size === 'sm';
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
          aria-hidden="true"
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
        <span aria-hidden="true" style={{ display: 'inline-flex', alignItems: 'center' }}>
          {icon}
        </span>
      )}
      {children}
    </span>
  );
}
