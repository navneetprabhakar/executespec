import {useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {getPostMergeBar, dismissPostMergeBar, type PostMergeBar as Bar} from '@/lib/api';

/**
 * v2.5.3 (UX-1.3 polish) — stacked "Continue building" banner(s).
 *
 * Backend writes every merge to a capped Redis list (newest first, max 3).
 * Single merge → one banner row. Multiple merges → show top 2 individually
 * and collapse the rest into a "+ N more merged" chip with a tooltip listing
 * the extras. Dismissing clears every banner for the project.
 */
export default function PostMergeBar({projectId}: {projectId: string}) {
  const [bars, setBars] = useState<Bar[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    let alive = true;
    getPostMergeBar(projectId)
      .then((r) => {
        if (alive) setBars(Array.isArray(r) ? r : []);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [projectId]);

  if (bars.length === 0) return null;

  const close = async () => {
    setBars([]);
    try {
      await dismissPostMergeBar(projectId);
    } catch {
      /* ignore */
    }
  };

  const startNext = async () => {
    await close();
    navigate(`/projects/${projectId}/dashboard?tab=launches&new=1`);
  };

  // Show at most 2 full rows; collapse the rest into a single chip.
  const visible = bars.slice(0, 2);
  const extras = bars.slice(2);

  return (
    <div className='space-y-1'>
      {visible.map((bar, i) => (
        <div
          key={`${bar.repoFullName}-${bar.prNumber}`}
          /* 2026-05-14 (P1/P3) — stack on phone so the "Start next →"
             CTA + dismiss don't collapse out of view, and the long
             repo/owner#NN identifier wraps cleanly instead of clipping
             with a trailing dash. Uses `owner/repo#NN` semantic
             ordering rather than dumping the raw repoFullName. */
          className='flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1.5 sm:gap-3 rounded-sm border border-status-success/40 bg-status-success/10 px-3 py-2 sm:py-1.5 text-xs text-status-success'
        >
          <div className='min-w-0 break-words sm:truncate'>
            ✓{' '}
            <span className='font-mono'>
              {bar.repoFullName}#{bar.prNumber}
            </span>{' '}
            merged — workspace refreshed.
          </div>
          <div className='flex items-center gap-2 sm:shrink-0'>
            {i === 0 && (
              <button
                onClick={startNext}
                className='text-[11px] font-medium px-3 py-1 min-h-[36px] rounded-sm bg-status-success/20 hover:bg-status-success/30'
              >
                Start next requirement →
              </button>
            )}
            {i === 0 && (
              <button
                onClick={close}
                title='Dismiss all'
                aria-label='Dismiss merge banner'
                className='text-muted hover:text-primary min-w-[36px] min-h-[36px] inline-flex items-center justify-center'
              >
                ×
              </button>
            )}
          </div>
        </div>
      ))}
      {extras.length > 0 && (
        <div
          className='inline-flex items-center rounded-sm bg-status-success/10 text-status-success px-2 py-0.5 text-[11px]'
          title={extras.map((e) => `${e.repoFullName}#${e.prNumber}`).join('\n')}
        >
          + {extras.length} more merged
        </div>
      )}
    </div>
  );
}
