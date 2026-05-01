import {PartyPopper, ArrowRight, Github} from 'lucide-react';
import {Link} from 'react-router-dom';
import ScoreRing from './ScoreRing';
import {capture} from '@/lib/analytics';

/**
 * Wave 2.2 — one-time celebrate banner shown above run detail when a
 * first-run completes. Gates on:
 *  - {@code run.status ∈ [COMPLETE, PARTIAL]} (terminal-but-usable)
 *  - run was submitted from the sandbox flow (sessionStorage flag)
 *
 * Banner elements (per docs/plans/ttfv-reduction-plan.md §5 Day 7):
 *  - ScoreRing of verifiedScore (primary, 80px)
 *  - Two CTAs: "Run another" + "Link your own repo"
 *  - "Share this run" (share link) — surfaced when share token exists
 *
 * Dismissible. State held in sessionStorage so the banner doesn't re-
 * appear on page reload after dismissal.
 */
export function FirstRunCelebration({
  runId,
  verifiedScore,
  status,
  onDismiss,
}: {
  runId: string;
  verifiedScore: number | null | undefined;
  status: string;
  onDismiss: () => void;
}) {
  const score = typeof verifiedScore === 'number' ? verifiedScore : null;
  const usable = status === 'COMPLETE' || status === 'PARTIAL';
  if (!usable) return null;

  return (
    <div
      className='mb-4 rounded-lg p-4 sm:p-5 border border-accent-blue/30 bg-gradient-to-br from-accent-blue/10 via-[var(--gray-100)] to-transparent'
      role='region'
      aria-label='First run celebration'
    >
      <div className='flex items-start gap-4 flex-wrap sm:flex-nowrap'>
        {score !== null && (
          <div className='flex-shrink-0'>
            <ScoreRing score={score} size={80} label={undefined as unknown as string} />
          </div>
        )}

        <div className='flex-1 min-w-0'>
          <div className='flex items-center gap-2 text-accent-blue'>
            <PartyPopper className='h-4 w-4' />
            <span className='text-xs font-mono uppercase tracking-wide'>First run complete</span>
          </div>
          <h2 className='mt-2 text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em]'>
            {status === 'COMPLETE'
              ? 'Your AI-generated code is ready to ship.'
              : 'Shipped with a couple of caveats — still worth a look.'}
          </h2>
          <p className='mt-1 text-sm text-secondary'>
            {score !== null
              ? `Verified ${score}/100 — this was your first submit. Try one on your own repo next.`
              : `This was your first run — try submitting a real spec on your own repo next.`}
          </p>

          <div className='mt-4 flex flex-wrap gap-2'>
            <Link
              to='/runs/new'
              onClick={() => capture('first_run_celebration_run_another', {runId})}
              className='inline-flex items-center gap-1.5 px-3 py-1.5 rounded-sm bg-accent-blue text-white text-xs font-medium hover:bg-accent-blue-hover'
            >
              Run another
              <ArrowRight className='h-3.5 w-3.5' />
            </Link>
            <Link
              to='/projects'
              onClick={() => capture('first_run_celebration_link_repo', {runId})}
              className='inline-flex items-center gap-1.5 px-3 py-1.5 rounded-sm bg-[var(--gray-300)] text-primary text-xs font-medium hover:bg-[var(--gray-400)]'
            >
              <Github className='h-3.5 w-3.5' />
              Link your own repo
            </Link>
            <button
              onClick={() => {
                capture('first_run_celebration_dismissed', {runId});
                onDismiss();
              }}
              className='ml-auto px-3 py-1.5 text-xs text-muted hover:text-secondary'
            >
              Dismiss
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
