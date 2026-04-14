import { ExternalLink, Github } from 'lucide-react';
import { type RunDetail } from '@/lib/api';
import ValidationReportPanel from '@/components/runs/ValidationReportPanel';
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
export default function InlineRunDetail({ run }: { run: RunDetail }) {
  const tasks = run.tasks ?? [];
  const completed = tasks.filter((t) => t.status === 'COMPLETE').length;
  const totalTokens = (run.totalInputTokens ?? 0) + (run.totalOutputTokens ?? 0);

  return (
    <div className="space-y-3 text-[11px] max-h-[480px] overflow-y-auto pr-1">
      {/* Request + stats */}
      <div className="space-y-2">
        <div>
          <div className="text-[10px] uppercase tracking-wide text-muted">Request</div>
          <p className="text-[12px] text-primary whitespace-pre-wrap">{run.userRequest}</p>
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <Stat label="Status" value={run.status} valueClass={STATUS_COLOR[run.status] ?? 'text-muted'} />
          <Stat label="Tasks" value={`${completed}/${tasks.length}`} />
          {totalTokens > 0 && <Stat label="Tokens" value={totalTokens.toLocaleString()} />}
          {run.actualCostUsd != null && <Stat label="Cost" value={`$${run.actualCostUsd.toFixed(4)}`} />}
          {run.aiProvider && <Stat label="Provider" value={run.aiProvider} />}
        </div>
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

      {/* Validation report — reuses the full panel used in the Runs tab */}
      <ValidationReportPanel runId={run.runId} status={run.status} />
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
