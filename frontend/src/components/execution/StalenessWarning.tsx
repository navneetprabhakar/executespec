import { useEffect, useState } from 'react';
import { getWorkspaceHealth, analyseProject, type RepoHealth } from '@/lib/api';

/**
 * v2.5.2 (UX-2.1) — pre-Launch codemap freshness warning.
 *
 * Calls the workspace-health endpoint on mount and whenever the user changes
 * repo selection. If any selected repo is stale, renders a non-blocking
 * warning block with two CTAs:
 *  - Analyse before planning: invokes analyseProject, then refetches health
 *  - Proceed anyway: dismisses the warning (system will still refresh silently)
 *
 * The warning is informational only — it does NOT gate the Launch button.
 * Kept out of the submit path so users can override when they've just
 * analysed from another tab and the health cache is still warming.
 */
export default function StalenessWarning({
  projectId,
  selectedRepoIds,
}: {
  projectId: string;
  selectedRepoIds: string[];
}) {
  const [health, setHealth] = useState<RepoHealth[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [just, setJust] = useState(false);

  useEffect(() => {
    let alive = true;
    getWorkspaceHealth(projectId)
      .then((r) => { if (alive) setHealth(r.repos); })
      .catch(() => {});
    return () => { alive = false; };
  }, [projectId]);

  if (dismissed) return null;
  if (!health || selectedRepoIds.length === 0) return null;

  const staleSelected = health.filter(
    (h) => selectedRepoIds.includes(h.repoId) && h.isStale,
  );
  if (staleSelected.length === 0) {
    if (just) {
      return (
        <div className="rounded-sm border border-emerald-500/40 bg-emerald-500/10 px-3 py-1.5 text-xs text-emerald-400">
          ✓ Codemaps refreshed — ready to plan.
        </div>
      );
    }
    return null;
  }

  const doAnalyse = async () => {
    setBusy(true);
    try {
      await analyseProject(projectId);
      const r = await getWorkspaceHealth(projectId);
      setHealth(r.repos);
      setJust(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-sm border border-accent-amber/40 bg-accent-amber/10 p-3 space-y-2">
      <div className="flex items-start gap-2">
        <span className="text-accent-amber text-sm leading-none">⚠</span>
        <div className="flex-1 min-w-0">
          <div className="text-xs font-medium text-accent-amber">
            Codemaps may be outdated
          </div>
          <ul className="mt-1 space-y-0.5">
            {staleSelected.map((h) => {
              const old7 = h.lastCodeMapSha ? h.lastCodeMapSha.slice(0, 7) : 'never analysed';
              const cur7 = h.currentOriginSha ? h.currentOriginSha.slice(0, 7) : '?';
              return (
                <li key={h.repoId} className="text-[11px] text-muted font-mono">
                  {h.repoName} — {old7} → {cur7}
                </li>
              );
            })}
          </ul>
          <div className="text-[11px] text-muted mt-1">
            Planning against stale codemaps may plan tasks against code that no
            longer exists. The system will auto-refresh on your first task, but
            you can front-load the wait here.
          </div>
        </div>
      </div>
      <div className="flex items-center gap-2 pl-5">
        <button
          onClick={doAnalyse}
          disabled={busy}
          className="text-[11px] font-medium px-2 py-0.5 rounded-sm bg-accent-amber/20 text-accent-amber hover:bg-accent-amber/30 disabled:opacity-50"
        >
          {busy ? 'Analysing…' : 'Analyse before planning'}
        </button>
        <button
          onClick={() => setDismissed(true)}
          className="text-[11px] text-muted hover:text-primary"
        >
          Proceed anyway
        </button>
      </div>
    </div>
  );
}
