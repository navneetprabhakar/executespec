import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {errMsg} from '@/lib/utils';
import {BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell} from 'recharts';
import {getTechDebt, dispatchTechDebtFix, type TechDebtReport} from '@/lib/api';

/**
 * v2.6.0 UI polish — token-aligned. All colours resolve to CSS custom
 * properties declared in tokens.css so the palette changes with theme
 * (dark / light) and stays in lock-step with the rest of the app.
 *
 * Only recharts (canvas-rendered) needs concrete hex — we resolve the
 * CSS variables at render time via getComputedStyle so the chart respects
 * both themes without duplicating the palette.
 */
const SEVERITY_VARS: Record<string, string> = {
  BLOCKER: '--status-error',
  MAJOR: '--status-waiting',
  MINOR: '--accent-blue',
  INFO: '--status-queued',
};
const CATEGORY_VARS: Record<string, string> = {
  LOGIC: '--accent-blue',
  COMPLETENESS: '--accent-harness',
  SECURITY: '--status-error',
  PERFORMANCE: '--status-waiting',
  STYLE: '--status-success',
  ARCHITECTURE: '--status-blocked',
  TEST: '--accent-planner',
  WIRING: '--status-success',
};
function resolveVar(name: string | undefined): string {
  if (!name) return 'var(--status-queued)';
  if (typeof window === 'undefined') return 'var(--status-queued)';
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || 'var(--status-queued)';
}
const catColor = (k: string) => resolveVar(CATEGORY_VARS[k] ?? '--status-queued');
const sevColor = (k: string) => resolveVar(SEVERITY_VARS[k] ?? '--status-queued');

/**
 * v2.5.2 — Tech Debt roll-up. Entirely DB-derived (no LLM); safe to render
 * alongside other Overview widgets. Defaults to all-history; UI toggles a
 * 30-day lookback filter.
 */
export default function TechDebtPanel({projectId}: {projectId: string}) {
  const [report, setReport] = useState<TechDebtReport | null>(null);
  const [since, setSince] = useState<number | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  // V183 Phase 2 — per-category fix dispatch. Tracks {category → {runId, outcome}} so
  // the user clicks "Fix N security issues" and gets a "Watch run →" link inline. State
  // is per-mount; nothing persisted client-side because the run id is the durable handle.
  const [fixState, setFixState] = useState<
    Record<
      string,
      {
        state: 'idle' | 'dispatching' | 'done' | 'error';
        runId?: string;
        error?: string;
        outcome?: 'DISPATCHED' | 'REUSED_ACTIVE';
      }
    >
  >({});

  async function handleFix(category: string) {
    setFixState((prev) => ({...prev, [category]: {state: 'dispatching'}}));
    try {
      const r = await dispatchTechDebtFix(projectId, category);
      setFixState((prev) => ({
        ...prev,
        [category]: {state: 'done', runId: r.runId, outcome: r.outcome},
      }));
    } catch (e) {
      setFixState((prev) => ({...prev, [category]: {state: 'error', error: errMsg(e)}}));
    }
  }

  useEffect(() => {
    let alive = true;
    getTechDebt(projectId, since)
      .then((r) => {
        if (alive) setReport(r);
      })
      .catch((e) => {
        if (alive) setError(e instanceof Error ? errMsg(e) : String(e));
      });
    return () => {
      alive = false;
    };
  }, [projectId, since]);

  if (error) {
    return <div className='text-xs text-status-error'>{error}</div>;
  }
  if (!report) return null;
  if (report.totalRunsAnalyzed === 0) {
    return (
      <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 text-xs text-muted'>
        No validation reports yet — tech-debt signals will appear once runs start completing.
      </div>
    );
  }

  const totalIssues = Object.values(report.issuesByCategory).reduce((a, b) => a + b, 0);

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-3'>
      <div className='flex items-center justify-between'>
        <h3 className='text-sm font-medium text-primary'>Tech debt</h3>
        <div className='flex items-center gap-1'>
          <button
            onClick={() => setSince(undefined)}
            className={`text-[11px] px-2 py-0.5 rounded-sm border ${
              since === undefined
                ? 'bg-accent-blue/10 border-accent-blue/40 text-accent-blue'
                : 'border-[var(--border-default)] text-muted hover:text-primary'
            }`}
          >
            All time
          </button>
          <button
            onClick={() => setSince(30)}
            className={`text-[11px] px-2 py-0.5 rounded-sm border ${
              since === 30
                ? 'bg-accent-blue/10 border-accent-blue/40 text-accent-blue'
                : 'border-[var(--border-default)] text-muted hover:text-primary'
            }`}
          >
            30 days
          </button>
        </div>
      </div>

      <div className='space-y-3 text-xs'>
        <div>
          <div className='text-[11px] uppercase tracking-wide text-muted mb-1'>
            By category · {totalIssues} total
          </div>
          {(() => {
            const data = Object.entries(report.issuesByCategory)
              .sort((a, b) => b[1] - a[1])
              .slice(0, 8)
              .map(([name, count]) => ({name, count}));
            if (data.length === 0) return <div className='text-muted'>None</div>;
            return (
              <ResponsiveContainer width='100%' height={Math.max(96, data.length * 28)}>
                <BarChart
                  data={data}
                  layout='vertical'
                  margin={{top: 4, right: 12, bottom: 4, left: 4}}
                >
                  <XAxis type='number' hide />
                  <YAxis
                    type='category'
                    dataKey='name'
                    width={104}
                    tick={{fontSize: 12, fill: resolveVar('--text-muted')}}
                    axisLine={false}
                    tickLine={false}
                  />
                  <Tooltip
                    cursor={{fill: 'rgba(255,255,255,0.04)'}}
                    contentStyle={{
                      fontSize: 11,
                      background: resolveVar('--gray-200'),
                      border: `1px solid ${resolveVar('--border-default')}`,
                      color: resolveVar('--text-primary'),
                    }}
                  />
                  <Bar dataKey='count' radius={[2, 2, 2, 2]}>
                    {data.map((d) => (
                      <Cell key={d.name} fill={catColor(d.name)} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            );
          })()}
        </div>
        <div>
          <div className='text-[11px] uppercase tracking-wide text-muted mb-1'>By severity</div>
          <div className='flex flex-wrap gap-1.5'>
            {Object.entries(report.issuesBySeverity)
              .sort((a, b) => b[1] - a[1])
              .map(([sev, count]) => (
                <span
                  key={sev}
                  className='inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 text-[11px]'
                  style={{backgroundColor: `${sevColor(sev)}22`, color: sevColor(sev)}}
                >
                  <span className='font-mono'>{sev}</span>
                  <span className='text-secondary'>{count}</span>
                </span>
              ))}
            {Object.keys(report.issuesBySeverity).length === 0 && (
              <span className='text-muted'>None</span>
            )}
          </div>
        </div>
      </div>

      {/* V183 Phase 2 — per-category fix-by-category buttons. One PR per category
          keeps diffs coherent for review; the link surfaces the dispatched run id so
          the user can watch progress without leaving the dashboard. */}
      {Object.keys(report.issuesByCategory).length > 0 && (
        <div>
          <div className='text-[11px] uppercase tracking-wide text-muted mb-1'>Fix by category</div>
          <div className='space-y-1'>
            {Object.entries(report.issuesByCategory)
              .sort((a, b) => b[1] - a[1])
              .map(([cat, count]) => {
                const s = fixState[cat] ?? {state: 'idle'};
                return (
                  <div key={cat} className='flex items-center justify-between gap-2 text-xs'>
                    <span className='font-mono' style={{color: catColor(cat)}}>
                      {cat}
                    </span>
                    <span className='flex-1 text-right text-muted text-[10px]'>{count}</span>
                    {s.state === 'idle' && (
                      <button
                        type='button'
                        onClick={() => handleFix(cat)}
                        className='px-2 py-0.5 text-[10px] font-medium rounded bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 transition-colors'
                        title={`Dispatch a patch-mode run that fixes all ${count} ${cat} issue${count === 1 ? '' : 's'}`}
                      >
                        Fix
                      </button>
                    )}
                    {s.state === 'dispatching' && (
                      <span className='text-[10px] text-muted'>Dispatching…</span>
                    )}
                    {s.state === 'done' && s.runId && (
                      <Link
                        to={`/runs/${s.runId}`}
                        className='px-2 py-0.5 text-[10px] font-medium rounded bg-status-success/10 text-status-success hover:bg-status-success/20'
                        title={
                          s.outcome === 'REUSED_ACTIVE'
                            ? 'Already in flight — click to watch existing run'
                            : 'Watch dispatched run'
                        }
                      >
                        {s.outcome === 'REUSED_ACTIVE' ? 'In flight →' : 'Watch run →'}
                      </Link>
                    )}
                    {s.state === 'error' && s.error && (
                      <span className='text-[10px] text-status-error truncate max-w-[120px]'>
                        {s.error}
                      </span>
                    )}
                  </div>
                );
              })}
          </div>
        </div>
      )}

      {report.topFiles.length > 0 && (
        <div>
          <div className='text-[11px] uppercase tracking-wide text-muted mb-1'>
            Top file hotspots
          </div>
          <div className='space-y-0.5 text-xs'>
            {report.topFiles.map((f) => (
              <div key={f.filePath} className='flex items-center justify-between text-muted'>
                <span className='font-mono truncate'>{f.filePath}</span>
                <span className='shrink-0 ml-2'>{f.issueCount}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className='text-[11px] text-muted pt-1 border-t border-[var(--border-default)]'>
        {report.totalRunsAnalyzed} run{report.totalRunsAnalyzed === 1 ? '' : 's'} analysed
        {report.averageAccuracyScore > 0 && (
          <> · avg accuracy {report.averageAccuracyScore.toFixed(1)}</>
        )}
      </div>
    </div>
  );
}
