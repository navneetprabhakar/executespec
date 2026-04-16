import { useEffect, useState } from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { getTechDebt, type TechDebtReport } from '@/lib/api';

const SEVERITY_COLOR: Record<string, string> = {
  BLOCKER: '#ef4444',
  MAJOR: '#f59e0b',
  MINOR: '#3b82f6',
  INFO: '#6b7280',
};
const CATEGORY_COLOR: Record<string, string> = {
  LOGIC: '#3b82f6',
  COMPLETENESS: '#8b5cf6',
  SECURITY: '#ef4444',
  PERFORMANCE: '#f59e0b',
  STYLE: '#10b981',
  ARCHITECTURE: '#ec4899',
  TEST: '#06b6d4',
  WIRING: '#84cc16',
};
const catColor = (k: string) => CATEGORY_COLOR[k] ?? '#6b7280';
const sevColor = (k: string) => SEVERITY_COLOR[k] ?? '#6b7280';

/**
 * v2.5.2 — Tech Debt roll-up. Entirely DB-derived (no LLM); safe to render
 * alongside other Overview widgets. Defaults to all-history; UI toggles a
 * 30-day lookback filter.
 */
export default function TechDebtPanel({ projectId }: { projectId: string }) {
  const [report, setReport] = useState<TechDebtReport | null>(null);
  const [since, setSince] = useState<number | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getTechDebt(projectId, since)
      .then((r) => { if (alive) setReport(r); })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)); });
    return () => { alive = false; };
  }, [projectId, since]);

  if (error) {
    return <div className="text-xs text-accent-red">{error}</div>;
  }
  if (!report) return null;
  if (report.totalRunsAnalyzed === 0) {
    return (
      <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 text-xs text-muted">
        No validation reports yet — tech-debt signals will appear once runs start completing.
      </div>
    );
  }

  const totalIssues = Object.values(report.issuesByCategory).reduce((a, b) => a + b, 0);

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-primary">Tech debt</h3>
        <div className="flex items-center gap-1">
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

      <div className="space-y-3 text-xs">
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted mb-1">
            By category · {totalIssues} total
          </div>
          {(() => {
            const data = Object.entries(report.issuesByCategory)
              .sort((a, b) => b[1] - a[1])
              .slice(0, 8)
              .map(([name, count]) => ({ name, count }));
            if (data.length === 0) return <div className="text-muted">None</div>;
            return (
              <ResponsiveContainer width="100%" height={Math.max(80, data.length * 22)}>
                <BarChart data={data} layout="vertical" margin={{ top: 4, right: 12, bottom: 4, left: 4 }}>
                  <XAxis type="number" hide />
                  <YAxis
                    type="category"
                    dataKey="name"
                    width={96}
                    tick={{ fontSize: 10, fill: '#9ca3af' }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <Tooltip cursor={{ fill: 'rgba(255,255,255,0.04)' }}
                           contentStyle={{ fontSize: 11, background: '#111', border: '1px solid #333' }} />
                  <Bar dataKey="count" radius={[2, 2, 2, 2]}>
                    {data.map((d) => <Cell key={d.name} fill={catColor(d.name)} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            );
          })()}
        </div>
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted mb-1">By severity</div>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(report.issuesBySeverity)
              .sort((a, b) => b[1] - a[1])
              .map(([sev, count]) => (
                <span key={sev}
                      className="inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 text-[11px]"
                      style={{ backgroundColor: `${sevColor(sev)}22`, color: sevColor(sev) }}>
                  <span className="font-mono">{sev}</span>
                  <span className="text-secondary">{count}</span>
                </span>
              ))}
            {Object.keys(report.issuesBySeverity).length === 0 && (
              <span className="text-muted">None</span>
            )}
          </div>
        </div>
      </div>

      {report.topFiles.length > 0 && (
        <div>
          <div className="text-[11px] uppercase tracking-wide text-muted mb-1">Top file hotspots</div>
          <div className="space-y-0.5 text-xs">
            {report.topFiles.map((f) => (
              <div key={f.filePath} className="flex items-center justify-between text-muted">
                <span className="font-mono truncate">{f.filePath}</span>
                <span className="shrink-0 ml-2">{f.issueCount}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="text-[11px] text-muted pt-1 border-t border-[var(--border-default)]">
        {report.totalRunsAnalyzed} run{report.totalRunsAnalyzed === 1 ? '' : 's'} analysed
        {report.averageAccuracyScore > 0 && (
          <> · avg accuracy {report.averageAccuracyScore.toFixed(1)}</>
        )}
      </div>
    </div>
  );
}
