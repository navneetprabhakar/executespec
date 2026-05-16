import {ReactNode} from 'react';

/**
 * Tiny loading primitives — Spinner (animated ring), SkeletonRow (placeholder
 * for list items), and LoadingState (card with spinner + label). Used across
 * Signal pages to replace bare "Loading…" text so the user has a clear
 * signal that something is happening, not stuck.
 */

export function Spinner({size = 14, color}: {size?: number; color?: string}) {
  return (
    <span
      aria-label='Loading'
      role='status'
      style={{
        display: 'inline-block',
        width: size,
        height: size,
        borderRadius: '50%',
        border: `${Math.max(2, Math.round(size / 7))}px solid var(--signal-hairline)`,
        borderTopColor: color ?? 'var(--signal-coral)',
        animation: 'signal-spin 0.8s linear infinite',
        verticalAlign: 'middle',
      }}
    />
  );
}

/**
 * Card-shaped loading state. Drop in where you'd otherwise write
 * `<Card>Loading…</Card>` so every empty-loading state in the app looks the
 * same.
 */
export function LoadingState({label = 'Loading…', children}: {label?: string; children?: ReactNode}) {
  return (
    <div
      style={{
        background: 'var(--signal-card-bg)',
        border: '1px solid var(--signal-hairline)',
        borderRadius: 16,
        padding: '20px 18px',
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        marginBottom: 8,
      }}
    >
      <Spinner size={16} />
      <div style={{fontSize: 13.5, color: 'var(--signal-text-dim)'}}>
        {label}
        {children && <div style={{marginTop: 4}}>{children}</div>}
      </div>
    </div>
  );
}

/** Shimmer placeholder for list rows — gives the page rhythm while data lands. */
export function SkeletonRow({lines = 2}: {lines?: number}) {
  return (
    <div
      style={{
        background: 'var(--signal-card-bg)',
        border: '1px solid var(--signal-hairline)',
        borderRadius: 16,
        padding: '14px 16px',
        marginBottom: 8,
        display: 'grid',
        gridTemplateColumns: '36px 1fr auto',
        gap: 12,
        alignItems: 'center',
      }}
    >
      <div style={{width: 10, height: 10, borderRadius: '50%', background: 'var(--signal-hairline)', margin: '0 auto', animation: 'signal-shimmer 1.6s ease-in-out infinite'}} />
      <div style={{minWidth: 0}}>
        {Array.from({length: lines}).map((_, i) => (
          <div
            key={i}
            style={{
              height: i === 0 ? 12 : 8,
              width: i === 0 ? '70%' : '45%',
              background: 'var(--signal-hairline)',
              borderRadius: 4,
              marginBottom: 6,
              animation: 'signal-shimmer 1.6s ease-in-out infinite',
              animationDelay: `${i * 0.1}s`,
            }}
          />
        ))}
      </div>
      <div style={{width: 40, height: 14, background: 'var(--signal-hairline)', borderRadius: 4, animation: 'signal-shimmer 1.6s ease-in-out infinite'}} />
    </div>
  );
}

/** Inline message: "<spinner> Doing X…" — useful inside cards. */
export function InlineLoader({label = 'Loading…'}: {label?: string}) {
  return (
    <span style={{display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--signal-text-dim)'}}>
      <Spinner size={12} />
      {label}
    </span>
  );
}
