import {useCallback, useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {Button, Card, StatusPill} from '@/signal/components/Primitives';
import {IdeBadge} from '@/ide/IdeKit';
import {Markdown} from '@/signal/components/Markdown';
import * as api from '@/lib/api';

/**
 * Dedicated PR Review agent submission surface, shared by both chromes.
 *
 * Surfaces the dark `submitPrReview` / `getPrReview` / `listPrReviewsForPr` /
 * `dispatchPrReviewAutofix` endpoints (V172 + Phase-1 autofix V183) that no
 * screen reached. Behaviour:
 *
 *  - On mount, loads the latest review for this PR (`listPrReviewsForPr`). If a
 *    review exists and is still in-flight (QUEUED/RUNNING) we resume polling it.
 *  - "Review this PR with AI" → `submitPrReview` (202 + QUEUED) → poll
 *    `getPrReview` every ~2.5s until COMPLETE | FAILED, showing progress.
 *  - COMPLETE → verdict badge (APPROVE=green / COMMENT=blue / REQUEST_CHANGES=red)
 *    + summary (Markdown) + the line-anchored comments listed
 *    (`filePath:lineNumber · severity` + Markdown body).
 *  - verdict != APPROVE → "Autofix N comments" → `dispatchPrReviewAutofix` →
 *    navigate to `/result/{runId}`.
 *  - 403 (tier) / 429 (quota) / 502 (GitHub fetch) errors surfaced verbatim with
 *    a disabled submit button on the gating cases.
 *
 * `variant` switches the styling between the two chromes so each stays native:
 * IDE uses `ide-card` / `ide-btn` / `IdeBadge` / `var(--ide-*)`; Signal uses the
 * `Card` / `Button` / `StatusPill` primitives + `var(--signal-*)`.
 */
const POLL_MS = 2500;

type Props = {
  projectId: string;
  repoFullName: string;
  prNumber: number;
  variant: 'signal' | 'ide';
};

function isTerminal(s?: string | null): boolean {
  return s === 'COMPLETE' || s === 'FAILED';
}

// Heuristic: tier / quota gates are unrecoverable from this surface, so we both
// surface the message and disable the submit button rather than inviting retry.
function isGate(message: string): boolean {
  const m = message.toLowerCase();
  return (
    m.includes('403') ||
    m.includes('429') ||
    m.includes('forbidden') ||
    m.includes('quota') ||
    m.includes('upgrade') ||
    m.includes('tier') ||
    m.includes('not allowed')
  );
}

export function PrReviewPanel({projectId, repoFullName, prNumber, variant}: Props) {
  const navigate = useNavigate();
  const [review, setReview] = useState<api.PrReviewDto | null>(null);
  const [comments, setComments] = useState<api.PrReviewCommentDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [autofixing, setAutofixing] = useState(false);
  const [err, setErr] = useState('');
  const [gated, setGated] = useState(false);
  const pollRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reviewIdRef = useRef<string | null>(null);

  const clearPoll = useCallback(() => {
    if (pollRef.current) {
      clearTimeout(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  // Poll a single review id until it reaches a terminal state.
  const poll = useCallback(
    (id: string) => {
      clearPoll();
      const tick = async () => {
        try {
          const res = await api.getPrReview(id);
          // Guard against a stale poll if a newer review was started meanwhile.
          if (reviewIdRef.current !== id) return;
          setReview(res.review);
          setComments(res.comments ?? []);
          if (!isTerminal(res.review.status)) {
            pollRef.current = setTimeout(tick, POLL_MS);
          }
        } catch (e: unknown) {
          if (reviewIdRef.current !== id) return;
          setErr(e instanceof Error ? e.message : 'Failed to load review status.');
        }
      };
      pollRef.current = setTimeout(tick, POLL_MS);
    },
    [clearPoll],
  );

  // On mount / when the PR changes, load the latest review for this PR.
  useEffect(() => {
    let off = false;
    setLoading(true);
    setErr('');
    setGated(false);
    setReview(null);
    setComments([]);
    reviewIdRef.current = null;
    clearPoll();
    api
      .listPrReviewsForPr(repoFullName, prNumber)
      .then(async (list) => {
        if (off) return;
        const latest = (list ?? [])[0] ?? null;
        if (!latest) {
          setLoading(false);
          return;
        }
        reviewIdRef.current = latest.id;
        // Fetch full detail (comments) for the latest review.
        try {
          const full = await api.getPrReview(latest.id);
          if (off) return;
          setReview(full.review);
          setComments(full.comments ?? []);
          if (!isTerminal(full.review.status)) poll(latest.id);
        } catch {
          if (off) return;
          setReview(latest);
        }
        setLoading(false);
      })
      .catch(() => {
        // Empty / not-yet-reviewed PRs commonly 404 on the by-PR query — treat as
        // "no review yet" rather than a hard error so the submit button shows.
        if (!off) setLoading(false);
      });
    return () => {
      off = true;
      clearPoll();
    };
  }, [repoFullName, prNumber, poll, clearPoll]);

  async function submit() {
    setSubmitting(true);
    setErr('');
    setGated(false);
    try {
      const dto = await api.submitPrReview({projectId, repoFullName, prNumber});
      reviewIdRef.current = dto.id;
      setReview(dto);
      setComments([]);
      if (!isTerminal(dto.status)) poll(dto.id);
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Failed to submit PR review.';
      setErr(message);
      setGated(isGate(message));
    } finally {
      setSubmitting(false);
    }
  }

  async function autofix() {
    if (!review) return;
    setAutofixing(true);
    setErr('');
    try {
      const res = await api.dispatchPrReviewAutofix(review.id);
      navigate(`/result/${res.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to dispatch autofix.');
      setAutofixing(false);
    }
  }

  const status = review?.status;
  const inFlight = status === 'QUEUED' || status === 'RUNNING';
  const complete = status === 'COMPLETE';
  const failed = status === 'FAILED';
  const verdict = review?.verdict ?? null;
  const canAutofix = complete && verdict != null && verdict !== 'APPROVE';
  const fixableCount = comments.length;

  const verdictLabel =
    verdict === 'APPROVE'
      ? 'Approve'
      : verdict === 'REQUEST_CHANGES'
        ? 'Request changes'
        : verdict === 'COMMENT'
          ? 'Comment'
          : '—';

  if (variant === 'ide') {
    const verdictKind: 'ok' | 'warn' | 'bad' =
      verdict === 'APPROVE' ? 'ok' : verdict === 'REQUEST_CHANGES' ? 'bad' : 'warn';
    return (
      <div className='ide-card' style={{marginTop: 10}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6}}>
          <span style={{fontWeight: 600, color: 'var(--ide-fg-bright)'}}>AI PR review</span>
          {complete && <IdeBadge kind={verdictKind}>{verdictLabel}</IdeBadge>}
          {inFlight && (
            <IdeBadge kind='warn'>{status === 'QUEUED' ? 'Queued' : 'Reviewing…'}</IdeBadge>
          )}
          {failed && <IdeBadge kind='bad'>Failed</IdeBadge>}
        </div>

        {err && (
          <div
            className='ide-card'
            style={{margin: '0 0 8px', color: 'var(--ide-red)', fontSize: 12.5}}
          >
            {err}
          </div>
        )}

        {loading ? (
          <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)'}}>Loading review…</div>
        ) : !review ? (
          <>
            <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginBottom: 8}}>
              Run the AI reviewer over this PR for a verdict + line-anchored comments.
            </div>
            <button className='ide-btn' disabled={submitting || gated} onClick={submit}>
              {submitting ? 'Submitting…' : 'Review this PR with AI'}
            </button>
          </>
        ) : inFlight ? (
          <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
            <span className='ide-spinner' aria-hidden='true' />
            <span style={{fontSize: 12.5, color: 'var(--ide-fg-muted)'}}>
              {status === 'QUEUED' ? 'Queued — waiting for a worker…' : 'Reviewing the diff…'}
            </span>
          </div>
        ) : failed ? (
          <>
            <div style={{fontSize: 12.5, color: 'var(--ide-red)', marginBottom: 8}}>
              {review.failureReason || 'The review failed.'}
            </div>
            <button className='ide-btn ghost' disabled={submitting} onClick={submit}>
              {submitting ? 'Submitting…' : 'Retry review'}
            </button>
          </>
        ) : (
          <>
            {review.summary && (
              <div style={{fontSize: 13, color: 'var(--ide-fg)', marginBottom: 8}}>
                <Markdown>{review.summary}</Markdown>
              </div>
            )}
            {comments.length > 0 ? (
              <div style={{display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 8}}>
                {comments.map((c) => {
                  const sev = c.severity;
                  const sevKind: 'ok' | 'warn' | 'bad' =
                    sev === 'CRITICAL' || sev === 'MAJOR' ? 'bad' : sev === 'MINOR' ? 'warn' : 'ok';
                  return (
                    <div
                      key={c.id}
                      style={{borderTop: '1px solid var(--ide-border)', paddingTop: 6}}
                    >
                      <div style={{display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2}}>
                        <span
                          className='ide-mono'
                          style={{fontSize: 11.5, color: 'var(--ide-fg-muted)'}}
                        >
                          {c.filePath}
                          {c.lineNumber ? `:${c.lineNumber}` : ''}
                        </span>
                        {sev && <IdeBadge kind={sevKind}>{sev}</IdeBadge>}
                        {c.category && (
                          <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                            {c.category}
                          </span>
                        )}
                      </div>
                      <div style={{fontSize: 12.5, color: 'var(--ide-fg)'}}>
                        <Markdown>{c.body}</Markdown>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div style={{fontSize: 12.5, color: 'var(--ide-fg-muted)', marginBottom: 8}}>
                No line-anchored comments.
              </div>
            )}
            <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
              {canAutofix && (
                <button className='ide-btn' disabled={autofixing} onClick={autofix}>
                  {autofixing
                    ? 'Dispatching…'
                    : `Autofix ${fixableCount} comment${fixableCount === 1 ? '' : 's'}`}
                </button>
              )}
              <button className='ide-btn ghost' disabled={submitting} onClick={submit}>
                {submitting ? 'Submitting…' : 'Re-review'}
              </button>
            </div>
          </>
        )}
      </div>
    );
  }

  const verdictPill: 'shipped' | 'needs' | 'failed' =
    verdict === 'APPROVE' ? 'shipped' : verdict === 'REQUEST_CHANGES' ? 'failed' : 'needs';
  const sevColor = (sev: api.PrReviewCommentDto['severity']): string =>
    sev === 'CRITICAL' || sev === 'MAJOR'
      ? 'var(--signal-coral)'
      : sev === 'MINOR'
        ? 'var(--signal-y-text)'
        : 'var(--signal-text-dim)';

  return (
    <Card style={{marginTop: 10}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8}}>
        <span style={{fontSize: 13, fontWeight: 700, color: 'var(--signal-ink)'}}>
          AI PR review
        </span>
        {complete && <StatusPill kind={verdictPill}>{verdictLabel}</StatusPill>}
        {inFlight && (
          <StatusPill kind='live'>{status === 'QUEUED' ? 'Queued' : 'Reviewing…'}</StatusPill>
        )}
        {failed && <StatusPill kind='failed'>Failed</StatusPill>}
      </div>

      {err && (
        <div
          style={{
            background: 'var(--signal-coral-soft)',
            color: 'var(--signal-coral-text)',
            border: '1px solid var(--signal-coral)',
            borderRadius: 10,
            padding: '8px 12px',
            marginBottom: 10,
            fontSize: 12.5,
          }}
        >
          {err}
        </div>
      )}

      {loading ? (
        <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0}}>Loading review…</p>
      ) : !review ? (
        <>
          <p
            style={{
              fontSize: 13,
              color: 'var(--signal-text-dim)',
              margin: '0 0 10px',
              lineHeight: 1.5,
            }}
          >
            Run the AI reviewer over this PR for a verdict + line-anchored comments.
          </p>
          <Button kind='primary' sm disabled={submitting || gated} onClick={submit}>
            {submitting ? 'Submitting…' : 'Review this PR with AI'}
          </Button>
        </>
      ) : inFlight ? (
        <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0}}>
          {status === 'QUEUED' ? 'Queued — waiting for a worker…' : 'Reviewing the diff…'}
        </p>
      ) : failed ? (
        <>
          <p style={{fontSize: 13, color: 'var(--signal-coral-text)', margin: '0 0 10px'}}>
            {review.failureReason || 'The review failed.'}
          </p>
          <Button kind='secondary' sm disabled={submitting} onClick={submit}>
            {submitting ? 'Submitting…' : 'Retry review'}
          </Button>
        </>
      ) : (
        <>
          {review.summary && (
            <div style={{fontSize: 13.5, color: 'var(--signal-ink)', marginBottom: 10}}>
              <Markdown>{review.summary}</Markdown>
            </div>
          )}
          {comments.length > 0 ? (
            <div style={{marginBottom: 10}}>
              {comments.map((c) => (
                <div
                  key={c.id}
                  style={{borderTop: '1px solid var(--signal-hairline)', padding: '8px 0'}}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      marginBottom: 2,
                      flexWrap: 'wrap',
                    }}
                  >
                    <span
                      style={{
                        fontFamily: "'JetBrains Mono', monospace",
                        fontSize: 11.5,
                        color: 'var(--signal-text-dim)',
                      }}
                    >
                      {c.filePath}
                      {c.lineNumber ? `:${c.lineNumber}` : ''}
                    </span>
                    {c.severity && (
                      <span style={{fontSize: 11, fontWeight: 700, color: sevColor(c.severity)}}>
                        {c.severity}
                      </span>
                    )}
                    {c.category && (
                      <span style={{fontSize: 11, color: 'var(--signal-text-mute)'}}>
                        {c.category}
                      </span>
                    )}
                  </div>
                  <div style={{fontSize: 13, color: 'var(--signal-ink)'}}>
                    <Markdown>{c.body}</Markdown>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: '0 0 10px'}}>
              No line-anchored comments.
            </p>
          )}
          <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
            {canAutofix && (
              <Button kind='primary' sm disabled={autofixing} onClick={autofix}>
                {autofixing
                  ? 'Dispatching…'
                  : `Autofix ${fixableCount} comment${fixableCount === 1 ? '' : 's'}`}
              </Button>
            )}
            <Button kind='secondary' sm disabled={submitting} onClick={submit}>
              {submitting ? 'Submitting…' : 'Re-review'}
            </Button>
          </div>
        </>
      )}
    </Card>
  );
}
