import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getPostMergeBar, dismissPostMergeBar, type PostMergeBar as Bar } from '@/lib/api';

/**
 * v2.5.2 — "Continue building" prompt after a PR merges.
 *
 * Backend writes a transient Redis marker in {@code PostMergeCascadeService}
 * on merge. The dashboard polls once on mount; marker TTLs out after 10 min.
 * Dismissed either by clicking the CTA (which routes to Launch) or the X.
 */
export default function PostMergeBar({ projectId }: { projectId: string }) {
  const [bar, setBar] = useState<Bar | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    let alive = true;
    getPostMergeBar(projectId)
      .then((r) => { if (alive) setBar(r); })
      .catch(() => {});
    return () => { alive = false; };
  }, [projectId]);

  if (!bar) return null;

  const close = async () => {
    setBar(null);
    try { await dismissPostMergeBar(projectId); } catch { /* ignore */ }
  };

  const startNext = async () => {
    await close();
    // Existing Launch affordance lives in the Launches tab; route there with
    // a hash so the tab can deep-link open the modal when wiring is added.
    navigate(`/projects/${projectId}/dashboard?tab=launches&new=1`);
  };

  return (
    <div className="flex items-center justify-between gap-3 rounded-sm border border-emerald-500/40 bg-emerald-500/10 px-3 py-1.5 text-[11px] text-emerald-400">
      <div className="min-w-0 truncate">
        ✓ PR #{bar.prNumber} merged on{' '}
        <span className="font-mono">{bar.repoFullName}</span>
        {' '}— workspace refreshed. Ready for the next requirement.
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <button
          onClick={startNext}
          className="text-[10px] font-medium px-2 py-0.5 rounded-sm bg-emerald-500/20 hover:bg-emerald-500/30"
        >
          Start next requirement →
        </button>
        <button
          onClick={close}
          title="Dismiss"
          className="text-muted hover:text-primary"
        >
          ×
        </button>
      </div>
    </div>
  );
}
