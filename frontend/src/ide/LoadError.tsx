/**
 * D4 — the honest counterpart to an empty state.
 *
 * Rendered wherever a load failed, in place of the "No X yet." copy that would
 * otherwise assert something the app never verified. Kept deliberately small:
 * one short sentence saying we could not load, the server's own reason, and a
 * Retry only where retrying could plausibly help.
 *
 * Version B: the failure reads as a 3px leading edge plus a tint (`.ide-note`),
 * not as a centred block that repaints its whole container. A policy fact
 * (forbidden / not on this plan) is `info` — it is not a fault; a transport
 * failure is `warn`. The wording is unchanged: every clause here exists to stop
 * the reader inferring emptiness from a failure.
 */
import type {LoadFailure} from '@/lib/loadState';
import {isRetryable} from '@/lib/loadState';

const HEADLINE: Record<LoadFailure['kind'], string> = {
  forbidden: 'You do not have access to this.',
  missing: 'This is not available on your plan or in this environment.',
  offline: 'Could not reach the server.',
  generic: 'Could not load this.',
};

const TONE: Record<LoadFailure['kind'], string> = {
  forbidden: 'info',
  missing: 'info',
  offline: 'warn',
  generic: 'warn',
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
  const showRetry = !!onRetry && isRetryable(failure);
  return (
    <div className={`ide-note ${TONE[failure.kind]}`} role='status'>
      <span className='gf-note-title'>{headline}</span>
      {detail && <div>{detail}</div>}
      <div style={{marginTop: 'var(--ide-s1)'}}>
        {what
          ? `This is not a claim that you have no ${what} — we could not find out.`
          : 'This is not a claim that there is nothing here — we could not find out.'}
      </div>
      {showRetry && (
        <button
          type='button'
          className='ide-btn ghost'
          style={{marginTop: 'var(--ide-s3)'}}
          onClick={onRetry}
        >
          Retry
        </button>
      )}
    </div>
  );
}
