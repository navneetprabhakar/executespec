import { useEffect, useState } from 'react';
import { getWorkspaceHealth, analyseProject, type RepoHealth } from '@/lib/api';

interface Props {
  projectId: string;
  onClose: () => void;
  /** Refreshes the parent's repo list after the reset completes. */
  onDone?: () => void | Promise<void>;
}

/**
 * v2.5.3 (UX-4.1 polish) — confirmation sheet for "Reset workspace".
 *
 * Pulls current SHA state via `getWorkspaceHealth` so the user can see which
 * repos are up-to-date, which will move forward, and roughly how many commits
 * they'll inherit. Clicking Confirm triggers `analyseProject` (re-pulls origin
 * HEAD + regenerates codemaps + architecture) and surfaces per-repo progress.
 *
 * Non-destructive: no local workspace edits are wiped by this flow — the
 * backend's analyse path operates on a fresh pull, so "reset" = "refresh".
 */
export default function ResetWorkspaceSheet({ projectId, onClose, onDone }: Props) {
  const [repos, setRepos] = useState<RepoHealth[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getWorkspaceHealth(projectId)
      .then((r) => { if (alive) setRepos(r.repos); })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); });
    return () => { alive = false; };
  }, [projectId]);

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await analyseProject(projectId);
      // Refetch health after analyse so the sheet reflects the new state
      // before handing control back to the parent.
      const r = await getWorkspaceHealth(projectId);
      setRepos(r.repos);
      setDone(true);
      if (onDone) await onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center">
      <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md p-4 w-full max-w-xl space-y-3">
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-[13px] font-medium text-primary">Reset workspace</h3>
          <button onClick={onClose} className="text-muted hover:text-primary text-[14px] leading-none">×</button>
        </div>

        <p className="text-xs text-muted">
          This will pull the latest origin HEAD for every linked repo and
          regenerate codemaps + architecture. Next Launch will plan against
          that fresh state.
        </p>

        {error && <div className="text-xs text-accent-red">{error}</div>}

        <div className="border border-[var(--border-default)] rounded-sm">
          <div className="text-[11px] uppercase tracking-wide text-muted px-2 py-1 border-b border-[var(--border-default)]">
            Repos ({repos?.length ?? 0})
          </div>
          {!repos ? (
            <div className="text-xs text-muted p-2">Loading…</div>
          ) : repos.length === 0 ? (
            <div className="text-xs text-muted p-2">No repos linked.</div>
          ) : (
            <div className="divide-y divide-[var(--border-default)]">
              {repos.map((r) => {
                const old7 = r.lastCodeMapSha ? r.lastCodeMapSha.slice(0, 7) : '—';
                const cur7 = r.currentOriginSha ? r.currentOriginSha.slice(0, 7) : '?';
                const upToDate = !r.isStale;
                return (
                  <div key={r.repoId} className="flex items-center gap-2 text-xs px-2 py-1.5">
                    <span className="font-mono truncate flex-1">{r.repoName}</span>
                    <span className="text-[11px] text-muted">{r.defaultBranch}</span>
                    <span className={`text-[11px] font-mono ${upToDate ? 'text-muted' : 'text-accent-amber'}`}>
                      {old7} → {cur7}
                    </span>
                    <span className={`text-[9px] ${upToDate ? 'text-muted' : 'text-accent-amber'}`}>
                      {upToDate ? '(already up to date)' : '(will refresh)'}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {done && (
          <div className="rounded-sm border border-status-success/40 bg-status-success/10 px-2 py-1.5 text-xs text-status-success">
            ✓ Workspace refreshed — codemaps & architecture regenerated.
          </div>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            disabled={busy}
            className="text-xs text-muted px-2 py-1 hover:text-primary disabled:opacity-50"
          >
            {done ? 'Close' : 'Cancel'}
          </button>
          {!done && (
            <button
              onClick={confirm}
              disabled={busy}
              className="text-xs rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue px-3 py-1 disabled:opacity-50 hover:bg-accent-blue/20"
            >
              {busy ? 'Refreshing…' : 'Reset workspace'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
