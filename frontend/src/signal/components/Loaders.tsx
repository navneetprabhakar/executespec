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
export function LoadingState({
  label = 'Loading…',
  children,
}: {
  label?: string;
  children?: ReactNode;
}) {
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
