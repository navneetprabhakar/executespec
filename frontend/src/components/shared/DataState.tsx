import type {ReactNode} from 'react';
import {AlertTriangle, RefreshCw, Loader2} from 'lucide-react';

/**
 * F-12 fix (2026-05-09) — single state-machine renderer for fetch
 * results. Pair with `useFetch<T>` from `@/hooks/useFetch`.
 *
 *   <DataState
 *     loading={loading}
 *     error={error}
 *     onRetry={retry}
 *     data={data}
 *     emptyMessage="No insights yet"
 *   >
 *     {(d) => <InsightsBody data={d} />}
 *   </DataState>
 *
 * Renders the right branch:
 *   - loading + no data         → centered spinner + label
 *   - error                     → error block with retry button
 *   - data is null/empty array  → empty state (overridable)
 *   - data present              → calls children(data)
 *
 * Refetching with stale data still rendered (loading=true while data
 * is non-null) keeps the previous UI visible — no flash of empty.
 *
 * The audit found 7/10 v3 pages had no error state and one (Insights)
 * could leave a spinner spinning forever on fetch failure. Migrating
 * to this primitive closes both classes of bug.
 */
export interface DataStateProps<T> {
  loading: boolean;
  error: Error | null;
  onRetry?: () => void;
  data: T | null;
  /** Override the empty-state guard. Defaults: null OR (Array.isArray && length===0). */
  isEmpty?: (d: T) => boolean;
  /** Custom empty-state node. Falls back to the `emptyMessage` text. */
  emptyState?: ReactNode;
  emptyMessage?: string;
  /** Called with non-null, non-empty data. */
  children: (data: T) => ReactNode;
  className?: string;
}

function defaultIsEmpty<T>(d: T): boolean {
  if (d == null) return true;
  if (Array.isArray(d)) return d.length === 0;
  return false;
}

export default function DataState<T>({
  loading,
  error,
  onRetry,
  data,
  isEmpty = defaultIsEmpty,
  emptyState,
  emptyMessage = 'No data yet.',
  children,
  className,
}: DataStateProps<T>) {
  // Error trumps loading — a failed retry should expose the error
  // immediately, not flash a spinner first.
  if (error) {
    return (
      <div
        role='alert'
        className={className}
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 24,
          background: 'var(--v3-surface, var(--gray-100))',
          border: '1px solid var(--v3-border, var(--border-default))',
          borderRadius: 8,
          color: 'var(--v3-text-muted)',
          gap: 10,
          minHeight: 160,
        }}
      >
        <AlertTriangle
          size={20}
          color='var(--v3-error, var(--accent-red))'
          aria-hidden='true'
        />
        <div style={{fontSize: 13, fontWeight: 600, color: 'var(--v3-text)'}}>
          Couldn't load this view
        </div>
        <div
          style={{
            fontSize: 12,
            maxWidth: 380,
            textAlign: 'center',
            lineHeight: 1.5,
          }}
        >
          {error.message || 'Something went wrong fetching data.'}
        </div>
        {onRetry && (
          <button
            type='button'
            onClick={onRetry}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              marginTop: 4,
              padding: '6px 12px',
              fontSize: 12,
              fontWeight: 500,
              color: 'var(--v3-text)',
              background: 'var(--v3-surface-elevated, var(--gray-200))',
              border: '1px solid var(--v3-border, var(--border-default))',
              borderRadius: 6,
              cursor: 'pointer',
            }}
          >
            <RefreshCw size={12} aria-hidden='true' />
            Retry
          </button>
        )}
      </div>
    );
  }

  // Initial load — no data yet AND fetching.
  if (loading && (data == null || isEmpty(data))) {
    return (
      <div
        className={className}
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 24,
          color: 'var(--v3-text-muted)',
          gap: 8,
          minHeight: 160,
        }}
        aria-live='polite'
        aria-busy='true'
      >
        <Loader2
          size={18}
          aria-hidden='true'
          className='animate-spin'
          style={{color: 'var(--v3-brand)'}}
        />
        <span style={{fontSize: 12}}>Loading…</span>
      </div>
    );
  }

  if (data == null || isEmpty(data)) {
    return (
      <div
        className={className}
        style={{
          padding: 24,
          color: 'var(--v3-text-muted)',
          fontSize: 13,
          textAlign: 'center',
          background: 'var(--v3-surface, var(--gray-100))',
          border: '1px dashed var(--v3-border, var(--border-default))',
          borderRadius: 8,
          minHeight: 120,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {emptyState ?? emptyMessage}
      </div>
    );
  }

  return <>{children(data)}</>;
}
