import { useEffect, useState } from 'react';
import { Loader2, RefreshCw, Sparkles } from 'lucide-react';
import { compactProjectMemory, getProjectMemory, type ProjectMemoryResponse } from '@/lib/api';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { cn } from '@/lib/utils';

interface Props {
  projectId: string;
}

export default function ProjectMemoryPanel({ projectId }: Props) {
  const [memory, setMemory] = useState<ProjectMemoryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [compacting, setCompacting] = useState(false);
  const [compactStatus, setCompactStatus] = useState<string | null>(null);

  const load = async () => {
    try {
      const res = await getProjectMemory(projectId);
      setMemory(res);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load memory');
    }
  };

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getProjectMemory(projectId)
      .then(res => { if (!cancelled) setMemory(res); })
      .catch(e => { if (!cancelled) setError(e?.message ?? 'Failed to load memory'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [projectId]);

  const handleCompact = async () => {
    if (compacting) return;
    setCompacting(true);
    setCompactStatus(null);
    try {
      const res = await compactProjectMemory(projectId);
      setCompactStatus(res.compacted
        ? `Compacted → snapshot v${res.snapshotVersion}`
        : 'Nothing to compact');
      await load();
    } catch (e) {
      setCompactStatus(`Failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setCompacting(false);
    }
  };

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
    <div className="space-y-5 text-xs">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-2">
        <div className="text-xs text-muted">
          {compactStatus && (
            <span className={cn(
              compactStatus.startsWith('Failed') ? 'text-accent-red' : 'text-emerald-400',
            )}>
              {compactStatus}
            </span>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={load}
            title="Refresh memory"
            className="inline-flex items-center gap-1 text-xs px-2 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
          >
            <RefreshCw className="h-3 w-3" />
            Refresh
          </button>
          <button
            onClick={handleCompact}
            disabled={compacting || memory.stats.recentEntryCount === 0}
            title="Force-compact all uncompacted ledger entries into a new snapshot"
            className={cn(
              'inline-flex items-center gap-1 text-xs px-2 py-1 rounded-sm border',
              compacting || memory.stats.recentEntryCount === 0
                ? 'border-[var(--border-default)] text-muted opacity-50 cursor-not-allowed'
                : 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20',
            )}
          >
            {compacting ? <Loader2 className="h-3 w-3 animate-spin" /> : <Sparkles className="h-3 w-3" />}
            Compact
          </button>
        </div>
      </div>

      {/* Budget bar */}
      <div>
        <div className="flex items-center justify-between text-xs text-secondary mb-1">
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
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-xs">
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[11px] uppercase tracking-wide text-muted">Total runs</div>
          <div className="text-sm font-mono tabular-nums">{memory.stats.totalRuns}</div>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[11px] uppercase tracking-wide text-muted">Snapshot</div>
          <div className="text-sm font-mono tabular-nums">
            {memory.stats.snapshotVersion > 0 ? `v${memory.stats.snapshotVersion}` : '—'}
          </div>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[11px] uppercase tracking-wide text-muted">Compacted through</div>
          <div className="text-sm font-mono tabular-nums">
            {memory.stats.snapshotCoversUpTo > 0 ? `R${memory.stats.snapshotCoversUpTo}` : '—'}
          </div>
        </div>
        <div className="rounded-sm border border-[var(--border-default)] px-2.5 py-1.5">
          <div className="text-[11px] uppercase tracking-wide text-muted">Recent entries</div>
          <div className="text-sm font-mono tabular-nums">{memory.stats.recentEntryCount}</div>
        </div>
      </div>

      {/* Compacted history */}
      {memory.stats.snapshotVersion > 0 && snapshotBlock.trim() && (
        <details open className="rounded-md border border-[var(--border-default)] p-3">
          <summary className="cursor-pointer text-sm font-medium text-primary">
            Compacted history (R1–R{memory.stats.snapshotCoversUpTo})
          </summary>
          <div className="mt-2 text-xs font-mono text-secondary whitespace-pre-wrap">
            <MarkdownContent content={snapshotBlock} />
          </div>
        </details>
      )}

      {/* Recent entries */}
      <div>
        <h3 className="text-xs font-semibold uppercase tracking-wide text-secondary mb-2">
          Recent runs
        </h3>
        <div className="space-y-2">
          {memory.recentEntries.map(entry => (
            <div key={entry.id} className="rounded-md border border-[var(--border-default)] p-3">
              <pre className="text-xs font-mono text-secondary whitespace-pre-wrap">
                {entry.entry}
              </pre>
              <div className="mt-2 text-[11px] text-muted">
                R{entry.runNumber} · {new Date(entry.createdAt).toLocaleDateString()} · ~{entry.tokenCount ?? '?'} tokens
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
