import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, ExternalLink, Github, RefreshCw, Upload, Loader2 } from 'lucide-react';
import { getFiles, pushToGitHub, type RunDetail } from '@/lib/api';
import ValidationReportPanel from '@/components/runs/ValidationReportPanel';
import { CodeTab } from '@/components/runs/RunDetailTabs';
import { cn } from '@/lib/utils';

const STATUS_COLOR: Record<string, string> = {
  COMPLETE:   'text-emerald-400',
  PARTIAL:    'text-accent-amber',
  FAILED:     'text-accent-red',
  CANCELLED:  'text-accent-red',
  RUNNING:    'text-accent-blue',
  PLANNING:   'text-accent-blue',
  WORKING:    'text-accent-blue',
  REVIEWING:  'text-accent-blue',
  AWAITING_APPROVAL: 'text-accent-amber',
};

/**
 * Read-only inline run viewer. Shows the full run experience — request, stats,
 * task list with failure reasons, PR link, and the complete ValidationReportPanel
 * (score card + issues + markdown report) — without coupling to the run store
 * (so the project view and the runs tab stay independent).
 */
export default function InlineRunDetail({ run, onRefresh }: { run: RunDetail; onRefresh?: () => void | Promise<void> }) {
  const tasks = run.tasks ?? [];
  const completed = tasks.filter((t) => t.status === 'COMPLETE').length;
  const totalTokens = (run.totalInputTokens ?? 0) + (run.totalOutputTokens ?? 0);
  const [refreshing, setRefreshing] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [pushStatus, setPushStatus] = useState<string | null>(null);

  const doRefresh = async () => {
    if (!onRefresh || refreshing) return;
    setRefreshing(true);
    try { await onRefresh(); } finally { setRefreshing(false); }
  };

  const terminal = ['COMPLETE', 'PARTIAL'].includes(run.status);
  const alreadyPushed = !!(run.scmPrUrl || run.githubPrUrl);

  const doPush = async () => {
    if (pushing || alreadyPushed) return;
    setPushing(true);
    setPushStatus(null);
    try {
      const repoMode = run.repoUrl ? 'EXISTING' : 'NEW';
      await pushToGitHub(run.runId, repoMode);
      setPushStatus('Pushed — PR opening on GitHub');
      if (onRefresh) await onRefresh();
    } catch (e) {
      setPushStatus(`Push failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setPushing(false);
    }
  };

  return (
    <div className="space-y-3 text-[11px]">
      {/* Request + stats */}
      <div className="space-y-2">
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="text-[10px] uppercase tracking-wide text-muted">Request</div>
            <p className="text-[12px] text-primary whitespace-pre-wrap">{run.userRequest}</p>
          </div>
          <div className="flex items-center gap-1.5 flex-shrink-0">
            {terminal && !alreadyPushed && (
              <button
                onClick={doPush}
                disabled={pushing}
                title="Push this run's workspace to GitHub and open a PR"
                className="inline-flex items-center gap-1 text-[10px] px-2 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50"
              >
                {pushing ? <Loader2 className="h-3 w-3 animate-spin" /> : <Upload className="h-3 w-3" />}
                {pushing ? 'Pushing…' : 'Push & open PR'}
              </button>
            )}
            {onRefresh && (
              <button
                onClick={doRefresh}
                disabled={refreshing}
                title="Refresh run detail"
                className="inline-flex items-center gap-1 text-[10px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50"
              >
                <RefreshCw className={cn('h-3 w-3', refreshing && 'animate-spin')} />
                Refresh
              </button>
            )}
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <Stat label="Status" value={run.status} valueClass={STATUS_COLOR[run.status] ?? 'text-muted'} />
          <Stat label="Tasks" value={`${completed}/${tasks.length}`} />
          {totalTokens > 0 && <Stat label="Tokens" value={totalTokens.toLocaleString()} />}
          {run.actualCostUsd != null && <Stat label="Cost" value={`$${run.actualCostUsd.toFixed(4)}`} />}
          {run.aiProvider && <Stat label="Provider" value={run.aiProvider} />}
        </div>
        {pushStatus && (
          <div className={cn(
            'text-[10px]',
            pushStatus.startsWith('Push failed') ? 'text-accent-red' : 'text-emerald-400',
          )}>
            {pushStatus}
          </div>
        )}
        {(run.scmPrUrl || run.githubPrUrl) && (
          <a
            href={run.scmPrUrl ?? run.githubPrUrl!}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 text-accent-blue hover:underline"
          >
            <Github className="h-3 w-3" />
            View pull request
            <ExternalLink className="h-2.5 w-2.5" />
          </a>
        )}
      </div>

      {/* Tasks */}
      {tasks.length > 0 && (
        <div>
          <div className="text-[10px] uppercase tracking-wide text-muted mb-1">Tasks</div>
          <div className="space-y-1">
            {tasks.map((t) => (
              <div
                key={t.taskId}
                className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1.5"
              >
                <div className="flex items-center gap-2">
                  <span className={cn('font-mono text-[10px]', STATUS_COLOR[t.status] ?? 'text-muted')}>
                    {t.status}
                  </span>
                  <span className="truncate text-secondary">{t.title}</span>
                  {t.durationMs != null && (
                    <span className="ml-auto text-[10px] text-muted font-mono">
                      {Math.round(t.durationMs / 1000)}s
                    </span>
                  )}
                </div>
                {t.failureReason && (
                  <div className="text-[10px] text-accent-red mt-0.5 line-clamp-2">
                    {t.failureReason}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Code — workspace files, lazy-loaded on expand */}
      <CodePanel run={run} />

      {/* Validation report — reuses the full panel used in the Runs tab */}
      <ValidationReportPanel runId={run.runId} status={run.status} />
    </div>
  );
}

function CodePanel({ run }: { run: RunDetail }) {
  const [open, setOpen] = useState(false);
  const [files, setFiles] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const terminal = ['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(run.status);

  useEffect(() => {
    if (!open || files !== null) return;
    setLoading(true);
    setError(null);
    getFiles(run.runId)
      .then(setFiles)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to list files'))
      .finally(() => setLoading(false));
  }, [open, run.runId, files]);

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center justify-between px-2.5 py-1.5 text-[11px] text-secondary hover:text-primary"
      >
        <span className="flex items-center gap-1.5">
          {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
          Code
          {files != null && <span className="text-muted">({files.length} files)</span>}
        </span>
        {!terminal && <span className="text-[10px] text-muted">run still in progress</span>}
      </button>
      {open && (
        <div className="border-t border-[var(--border-default)] h-[500px] overflow-hidden">
          {error && <div className="p-3 text-[11px] text-accent-red">{error}</div>}
          {!error && (
            <CodeTab
              runId={run.runId}
              files={files ?? []}
              filesLoading={loading}
              repoUrl={run.repoUrl ?? null}
              status={run.status}
              scmProvider={run.scmProvider ?? null}
            />
          )}
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, valueClass }: { label: string; value: string; valueClass?: string }) {
  return (
    <span className="text-[11px]">
      <span className="text-muted">{label}:</span>{' '}
      <span className={cn('font-mono tabular-nums', valueClass ?? 'text-primary')}>{value}</span>
    </span>
  );
}
