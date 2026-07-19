/**
 * D4 — the honest counterpart to an empty state.
 *
 * Rendered wherever a load failed, in place of the "No X yet." copy that would
 * otherwise assert something the app never verified. Kept deliberately small:
 * one short sentence saying we could not load, the server's own reason, and a
 * Retry only where retrying could plausibly help.
 */
import type {LoadFailure} from '@/lib/loadState';
import {isRetryable} from '@/lib/loadState';

const HEADLINE: Record<LoadFailure['kind'], string> = {
  forbidden: 'You do not have access to this.',
  missing: 'This is not available on your plan or in this environment.',
  offline: 'Could not reach the server.',
  generic: 'Could not load this.',
};

export function LoadError({
  failure,
  what,
  onRetry,
}: {
  failure: LoadFailure;
  /** Plural noun for the data, e.g. `members`. Used only in the sub-line. */
  what?: string;
  onRetry?: () => void;
}) {
  const headline = HEADLINE[failure.kind];
  // The server message is usually the more specific of the two (e.g. the tier
  // that a feature needs). Show it when it adds something over the headline.
  const detail = failure.message && failure.message !== headline ? failure.message : '';
  return (
    <div className='ide-empty' role='status' style={{textAlign: 'center'}}>
      <div style={{color: 'var(--ide-fg)', marginBottom: 4}}>{headline}</div>
      {detail && <div style={{marginBottom: 4}}>{detail}</div>}
      <div style={{marginBottom: onRetry ? 10 : 0}}>
        {what
          ? `This is not a claim that you have no ${what} — we could not find out.`
          : 'This is not a claim that there is nothing here — we could not find out.'}
      </div>
      {onRetry && isRetryable(failure) && (
        <button type='button' className='ide-btn ghost' onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
