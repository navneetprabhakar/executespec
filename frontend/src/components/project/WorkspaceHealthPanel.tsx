import {useEffect, useState} from 'react';
import {errMsg} from '@/lib/utils';
import {getWorkspaceHealth, analyseProject, type WorkspaceHealthReport} from '@/lib/api';

/**
 * v2.5.2 — per-repo health summary for the Project Overview tab.
 *
 * Uses the workspace-health rollup endpoint (cached 10 min in Redis per repo
 * for origin-SHA lookups). Shows drift, webhook, open PR count, and last
 * analysed timestamp in a single row per repo; a header-level "Analyse All"
 * button re-triggers the existing analyse pipeline.
 */
export default function WorkspaceHealthPanel({projectId}: {projectId: string}) {
  const [report, setReport] = useState<WorkspaceHealthReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysing, setAnalysing] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await getWorkspaceHealth(projectId));
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load(); /* eslint-disable-next-line */
  }, [projectId]);

  const doAnalyseAll = async () => {
    if (analysing) return;
    setAnalysing(true);
    try {
      await analyseProject(projectId);
      await load();
    } catch (e) {
      setError(e instanceof Error ? errMsg(e) : String(e));
    } finally {
      setAnalysing(false);
    }
  };

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
      <div className='flex items-center justify-between mb-2'>
        <h3 className='text-sm font-medium text-primary'>Workspace health</h3>
        <div className='flex items-center gap-2'>
          <button
            onClick={load}
            disabled={loading}
            className='text-[11px] px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50'
          >
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
          <button
            onClick={doAnalyseAll}
            disabled={analysing}
            title='Regenerate codemaps + architecture from current origin HEAD for every linked repo'
            className='text-[11px] px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50'
          >
            {analysing ? 'Analysing…' : 'Analyse all'}
          </button>
        </div>
      </div>

      {error && <div className='text-xs text-accent-red'>{error}</div>}

      {!error && report && report.repos.length === 0 && (
        <div className='text-xs text-muted'>No repos linked yet.</div>
      )}

      {!error && report && report.repos.length > 0 && (
        <div className='space-y-1'>
          {report.repos.map((r) => {
            const sha7 = (s: string | null) => (s ? s.slice(0, 7) : '—');
            // v2.5.7 UX-fix: distinguish "never analysed" (setup state, amber)
            // from "stale" (analysed before but drifted, red). Previously both
            // showed "stale codemap" in red which alarmed users on brand-new repos.
            const freshness =
              r.lastCodeMapSha == null
                ? {label: 'not yet analysed', cls: 'bg-accent-amber/10 text-accent-amber'}
                : r.isStale
                  ? {label: 'stale codemap', cls: 'bg-accent-red/10 text-accent-red'}
                  : {label: 'up to date', cls: 'bg-status-success/10 text-status-success'};
            const freshnessDetail =
              r.lastCodeMapSha == null
                ? `current: ${sha7(r.currentOriginSha)}`
                : `${sha7(r.lastCodeMapSha)} → ${sha7(r.currentOriginSha)}`;
            return (
              <div
                key={r.repoId}
                className='flex items-center justify-between gap-2 text-xs py-1 border-b border-[var(--border-default)] last:border-b-0'
              >
                <div className='flex items-center gap-2 min-w-0'>
                  <span className='font-mono truncate text-primary'>{r.repoName}</span>
                  <span className='text-[11px] text-muted'>{r.defaultBranch}</span>
                </div>
                <div className='flex items-center gap-1.5 shrink-0'>
                  <span
                    className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${freshness.cls}`}
                  >
                    {freshness.label} · {freshnessDetail}
                  </span>
                  <span
                    className={`inline-flex items-center rounded-sm px-1.5 py-0.5 text-[9px] ${
                      r.webhookInstalled
                        ? 'bg-status-success/10 text-status-success'
                        : 'bg-[var(--gray-400)]/50 text-muted'
                    }`}
                  >
                    {r.webhookInstalled ? 'webhook ✓' : 'set up webhook'}
                  </span>
                  {r.openPrCount > 0 && (
                    <span className='inline-flex items-center rounded-sm bg-accent-blue/10 text-accent-blue px-1.5 py-0.5 text-[9px]'>
                      {r.openPrCount} open PR{r.openPrCount === 1 ? '' : 's'}
                    </span>
                  )}
                </div>
              </div>
            );
          })}
          <div className='text-[9px] text-muted pt-1'>
            Last refreshed: {new Date(report.lastRefreshedAt).toLocaleString()}
          </div>
        </div>
      )}
    </div>
  );
}
