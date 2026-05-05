/**
 * PrReviewButton — small inline button that runs the PR Review Agent against a single PR.
 *
 * Renders one of three states:
 *   1. Idle — clickable Eye icon "Run AI review"
 *   2. Running — spinner + "QUEUED" / "RUNNING"
 *   3. Done — small badge showing the verdict (APPROVE/COMMENT/REQUEST_CHANGES) + click to expand
 *
 * On expand the verdict + summary are rendered in a popover-like floating box. Clicking the box
 * link routes to the existing `/projects/{id}/dashboard?prReview={id}` deep-link (frontend-only,
 * uses window.history so we don't add a new route — the dashboard already reads the query).
 *
 * PAID-tier only on the backend; we surface 403/429 via toast-equivalent inline text.
 */
import {useEffect, useRef, useState} from 'react';
import {Eye, Loader2, ThumbsUp, AlertTriangle, MessageSquare} from 'lucide-react';

import {
  type PrReviewDto,
  type PrReviewWithComments,
  getPrReview,
  listPrReviewsForPr,
  submitPrReview,
} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {cn, errMsg} from '@/lib/utils';

const PAID_TIERS = new Set(['PRO', 'TEAM', 'MAX', 'ENTERPRISE']);

interface PrReviewButtonProps {
  projectId: string;
  repoFullName: string;
  prNumber: number;
}

export default function PrReviewButton({projectId, repoFullName, prNumber}: PrReviewButtonProps) {
  const tier = useAuthStore((s) => s.user?.tier ?? 'FREE');
  const isPaid = PAID_TIERS.has(tier);

  const [latest, setLatest] = useState<PrReviewDto | null>(null);
  const [detail, setDetail] = useState<PrReviewWithComments | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [polling, setPolling] = useState(false);
  const [showPopover, setShowPopover] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollTimer = useRef<number | null>(null);

  // Initial load — fetch any existing review for this PR.
  useEffect(() => {
    let cancelled = false;
    listPrReviewsForPr(repoFullName, prNumber)
      .then((rows) => {
        if (cancelled) return;
        if (rows.length > 0) setLatest(rows[0]);
      })
      .catch(() => {
        // Auth errors etc — silently ignore for the inline state.
      });
    return () => {
      cancelled = true;
    };
  }, [repoFullName, prNumber]);

  // Poll while the review is in flight.
  useEffect(() => {
    if (!latest) return undefined;
    const inFlight = latest.status === 'QUEUED' || latest.status === 'RUNNING';
    if (!inFlight) return undefined;
    setPolling(true);
    const tick = async () => {
      try {
        const d = await getPrReview(latest.id);
        setLatest(d.review);
        setDetail(d);
        if (d.review.status === 'COMPLETE' || d.review.status === 'FAILED') {
          setPolling(false);
        }
      } catch (e) {
        setError(errMsg(e));
      }
    };
    pollTimer.current = window.setInterval(tick, 2500);
    return () => {
      if (pollTimer.current) window.clearInterval(pollTimer.current);
      setPolling(false);
    };
  }, [latest]);

  const handleClick = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!isPaid) {
      setError('PR review is a paid feature — upgrade to Pro or above.');
      return;
    }
    if (latest && (latest.status === 'COMPLETE' || latest.status === 'FAILED')) {
      // Toggle popover with details
      if (!detail) {
        try {
          const d = await getPrReview(latest.id);
          setDetail(d);
        } catch (err) {
          setError(errMsg(err));
        }
      }
      setShowPopover((v) => !v);
      return;
    }
    if (latest && (latest.status === 'QUEUED' || latest.status === 'RUNNING')) {
      // Already in flight — just open popover with progress
      setShowPopover((v) => !v);
      return;
    }
    setSubmitting(true);
    setError(null);
    setShowPopover(true); // open immediately so the user sees QUEUED → status, or the error
    try {
      const r = await submitPrReview({projectId, repoFullName, prNumber});
      setLatest(r);
    } catch (err) {
      setError(errMsg(err));
    } finally {
      setSubmitting(false);
    }
  };

  // Keep tooltip + visual aligned to the badge state.
  const inFlight =
    submitting ||
    polling ||
    (latest && (latest.status === 'QUEUED' || latest.status === 'RUNNING'));

  const verdict = latest?.verdict;
  let buttonContent: React.ReactNode;
  let title: string;
  if (inFlight) {
    buttonContent = <Loader2 className='h-3 w-3 animate-spin' />;
    title =
      'AI review in progress — ' +
      (latest?.status?.toLowerCase() ?? 'queueing') +
      '. Click to view progress.';
  } else if (verdict === 'APPROVE') {
    buttonContent = <ThumbsUp className='h-3 w-3' />;
    title = 'AI review: APPROVE — click to view details';
  } else if (verdict === 'REQUEST_CHANGES') {
    buttonContent = <AlertTriangle className='h-3 w-3' />;
    title = 'AI review: REQUEST_CHANGES — click to view details';
  } else if (verdict === 'COMMENT') {
    buttonContent = <MessageSquare className='h-3 w-3' />;
    title = 'AI review: COMMENT — click to view details';
  } else {
    buttonContent = <Eye className='h-3 w-3' />;
    title = isPaid ? 'Run AI PR review' : 'PR review (paid feature)';
  }

  return (
    <span className='relative inline-flex'>
      <button
        onClick={handleClick}
        title={title}
        disabled={submitting}
        className={cn(
          'inline-flex items-center gap-1 text-[11px]',
          verdict === 'APPROVE'
            ? 'text-status-complete hover:text-accent-blue'
            : verdict === 'REQUEST_CHANGES'
              ? 'text-status-error hover:text-accent-blue'
              : verdict === 'COMMENT'
                ? 'text-status-partial hover:text-accent-blue'
                : 'text-muted hover:text-accent-blue',
        )}
      >
        {buttonContent}
      </button>
      {showPopover && (
        <div
          className='absolute right-0 top-5 z-20 w-80 max-h-96 overflow-y-auto bg-bg-elevated border border-[var(--border-default)] rounded shadow-lg p-3 text-[11px] space-y-2'
          onClick={(e) => e.stopPropagation()}
        >
          <div className='flex items-baseline justify-between gap-2'>
            <strong className='text-secondary'>AI PR review</strong>
            <button
              onClick={() => setShowPopover(false)}
              className='text-muted hover:text-secondary'
            >
              ✕
            </button>
          </div>
          {error && <div className='text-status-error'>{error}</div>}
          {latest && (
            <div className='text-muted'>
              {latest.status}
              {latest.verdict ? ` · ${latest.verdict}` : ''}
              {latest.durationMs ? ` · ${Math.round(latest.durationMs / 1000)}s` : ''}
            </div>
          )}
          {detail?.review.summary && (
            <div className='text-secondary whitespace-pre-wrap'>{detail.review.summary}</div>
          )}
          {detail && detail.comments.length > 0 && (
            <div className='space-y-1.5 mt-1.5 pt-1.5 border-t border-[var(--border-default)]'>
              <div className='text-muted text-[10px]'>{detail.comments.length} comments</div>
              {detail.comments.slice(0, 5).map((c) => (
                <div key={c.id} className='border border-[var(--border-default)] rounded p-1.5'>
                  <div className='font-mono text-[10px] text-muted'>
                    {c.filePath}:{c.lineNumber}
                    {c.severity ? ` · ${c.severity}` : ''}
                  </div>
                  <div className='text-secondary'>{c.body}</div>
                </div>
              ))}
              {detail.comments.length > 5 && (
                <div className='text-muted text-[10px]'>
                  +{detail.comments.length - 5} more comments — view full review on GitHub
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </span>
  );
}
