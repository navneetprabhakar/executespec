import { useEffect, useState } from 'react';
import { getProjectMemory, type ProjectMemoryResponse } from '@/lib/api';
import { MarkdownContent } from '@/components/shared/MarkdownContent';

interface Props {
  projectId: string;
}

export default function ProjectMemoryPanel({ projectId }: Props) {
  const [memory, setMemory] = useState<ProjectMemoryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getProjectMemory(projectId)
      .then(res => { if (!cancelled) setMemory(res); })
      .catch(e => { if (!cancelled) setError(e?.message ?? 'Failed to load memory'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId]);

  if (loading) {
    return <div className="animate-pulse h-48 bg-[var(--gray-100)] rounded-md" />;
  }
  if (error) {
    return <div className="text-sm text-accent-red">{error}</div>;
  }
  if (!memory || memory.stats.totalRuns === 0) {
    return (
      <div className="text-center text-sm text-muted py-12">
        No project memory yet. Memory is generated automatically after each completed run in this project.
      </div>
    );
  }

  const pct = Math.min(100, Math.round((memory.stats.currentTokens / memory.stats.tokenBudget) * 100));
  const barColor = pct > 80 ? 'bg-accent-amber' : 'bg-accent-blue';
  const snapshotBlock = memory.fullContext.split('## RECENT RUNS')[0] ?? '';

  return (
    <div className="space-y-5 text-[12px]">
      {/* Budget bar */}
      <div>
        <div className="flex items-center justify-between text-[11px] text-secondary mb-1">
          <span>Token budget</span>
          <span className="font-mono tabular-nums">
            {(memory.stats.currentTokens / 1000).toFixed(1)}K / {(memory.stats.tokenBudget / 1000).toFixed(0)}K
          </span>
        </div>
        <div className="w-full bg-[var(--gray-200)] rounded-full h-1.5">
          <div className={`${barColor} h-1.5 rounded-full`} style={{ width: `${pct}%` }} />
        </div>
      </div>

      {/* Stats tiles */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[11px]">
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[10px] uppercase tracking-wide text-muted">Total runs</div>
          <div className="text-sm font-mono tabular-nums">{memory.stats.totalRuns}</div>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[10px] uppercase tracking-wide text-muted">Snapshot</div>
          <div className="text-sm font-mono tabular-nums">
            {memory.stats.snapshotVersion > 0 ? `v${memory.stats.snapshotVersion}` : '—'}
          </div>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[10px] uppercase tracking-wide text-muted">Compacted through</div>
          <div className="text-sm font-mono tabular-nums">
            {memory.stats.snapshotCoversUpTo > 0 ? `R${memory.stats.snapshotCoversUpTo}` : '—'}
          </div>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[10px] uppercase tracking-wide text-muted">Recent entries</div>
          <div className="text-sm font-mono tabular-nums">{memory.stats.recentEntryCount}</div>
        </div>
      </div>

      {/* Compacted history */}
      {memory.stats.snapshotVersion > 0 && snapshotBlock.trim() && (
        <details open className="rounded-md border border-[var(--border-default)] p-3">
          <summary className="cursor-pointer text-[12px] font-medium text-primary">
            Compacted history (R1–R{memory.stats.snapshotCoversUpTo})
          </summary>
          <div className="mt-2 text-[11px] font-mono text-secondary whitespace-pre-wrap">
            <MarkdownContent content={snapshotBlock} />
          </div>
        </details>
      )}

      {/* Recent entries */}
      <div>
        <h3 className="text-[11px] font-semibold uppercase tracking-wide text-secondary mb-2">
          Recent runs
        </h3>
        <div className="space-y-2">
          {memory.recentEntries.map(entry => (
            <div key={entry.id} className="rounded-md border border-[var(--border-default)] p-3">
              <pre className="text-[11px] font-mono text-secondary whitespace-pre-wrap">
                {entry.entry}
              </pre>
              <div className="mt-2 text-[10px] text-muted">
                R{entry.runNumber} · {new Date(entry.createdAt).toLocaleDateString()} · ~{entry.tokenCount ?? '?'} tokens
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
