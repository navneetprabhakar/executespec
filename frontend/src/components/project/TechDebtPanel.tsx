import { useEffect, useState } from 'react';
import { getTechDebt, type TechDebtReport } from '@/lib/api';

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
    return <div className="text-[11px] text-accent-red">{error}</div>;
  }
  if (!report) return null;
  if (report.totalRunsAnalyzed === 0) {
    return (
      <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 text-[11px] text-muted">
        No validation reports yet — tech-debt signals will appear once runs start completing.
      </div>
    );
  }

  const totalIssues = Object.values(report.issuesByCategory).reduce((a, b) => a + b, 0);

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-[12px] font-medium text-primary">Tech debt</h3>
        <div className="flex items-center gap-1">
          <button
            onClick={() => setSince(undefined)}
            className={`text-[10px] px-2 py-0.5 rounded-sm border ${
              since === undefined
                ? 'bg-accent-blue/10 border-accent-blue/40 text-accent-blue'
                : 'border-[var(--border-default)] text-muted hover:text-primary'
            }`}
          >
            All time
          </button>
          <button
            onClick={() => setSince(30)}
            className={`text-[10px] px-2 py-0.5 rounded-sm border ${
              since === 30
                ? 'bg-accent-blue/10 border-accent-blue/40 text-accent-blue'
                : 'border-[var(--border-default)] text-muted hover:text-primary'
            }`}
          >
            30 days
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 text-[11px]">
        <div>
          <div className="text-[10px] uppercase tracking-wide text-muted mb-1">
            By category · {totalIssues} total
          </div>
          <div className="space-y-0.5">
            {Object.entries(report.issuesByCategory)
              .sort((a, b) => b[1] - a[1])
              .slice(0, 6)
              .map(([cat, count]) => (
                <div key={cat} className="flex items-center justify-between text-muted">
                  <span className="font-mono">{cat}</span>
                  <span>{count}</span>
                </div>
              ))}
          </div>
        </div>
        <div>
          <div className="text-[10px] uppercase tracking-wide text-muted mb-1">By severity</div>
          <div className="space-y-0.5">
            {Object.entries(report.issuesBySeverity)
              .sort((a, b) => b[1] - a[1])
              .map(([sev, count]) => (
                <div key={sev} className="flex items-center justify-between text-muted">
                  <span className="font-mono">{sev}</span>
                  <span>{count}</span>
                </div>
              ))}
          </div>
        </div>
      </div>

      {report.topFiles.length > 0 && (
        <div>
          <div className="text-[10px] uppercase tracking-wide text-muted mb-1">Top file hotspots</div>
          <div className="space-y-0.5 text-[11px]">
            {report.topFiles.map((f) => (
              <div key={f.filePath} className="flex items-center justify-between text-muted">
                <span className="font-mono truncate">{f.filePath}</span>
                <span className="shrink-0 ml-2">{f.issueCount}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="text-[10px] text-muted pt-1 border-t border-[var(--border-default)]">
        {report.totalRunsAnalyzed} run{report.totalRunsAnalyzed === 1 ? '' : 's'} analysed
        {report.averageAccuracyScore > 0 && (
          <> · avg accuracy {report.averageAccuracyScore.toFixed(1)}</>
        )}
      </div>
    </div>
  );
}
