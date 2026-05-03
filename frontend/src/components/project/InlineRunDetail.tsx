import {useEffect, useMemo, useState} from 'react';
import {
  ChevronDown,
  ChevronRight,
  ExternalLink,
  Github,
  RefreshCw,
  Upload,
  Loader2,
  GitBranch,
} from 'lucide-react';
import {
  getFiles,
  pushToGitHub,
  pushToScm,
  retryTask,
  syncRun,
  syncRunToOriginal,
  type RunDetail,
} from '@/lib/api';
import ValidationReportPanel from '@/components/runs/ValidationReportPanel';
import {CodeTab} from '@/components/runs/RunDetailTabs';
import VerdictPill, {deriveVerdict} from '@/components/runs/VerdictPill';
import {AddGitHubPatDrawer} from '@/components/runs/InlineCredentialDrawers';
import {useAuthStore} from '@/stores/useAuthStore';
import {useUiStore} from '@/stores/useUiStore';
import {cn} from '@/lib/utils';

const STATUS_COLOR: Record<string, string> = {
  COMPLETE: 'text-status-success',
  PARTIAL: 'text-status-partial',
  FAILED: 'text-status-error',
  CANCELLED: 'text-status-error',
  RUNNING: 'text-status-executing',
  PLANNING: 'text-status-thinking',
  WORKING: 'text-status-executing',
  REVIEWING: 'text-status-thinking',
  AWAITING_APPROVAL: 'text-status-waiting',
};

/**
 * Read-only inline run viewer. Shows the full run experience — request, stats,
 * task list with failure reasons, PR link, and the complete ValidationReportPanel
 * (score card + issues + markdown report) — without coupling to the run store
 * (so the project view and the runs tab stay independent).
 */
export default function InlineRunDetail({
  run,
  onRefresh,
}: {
  run: RunDetail;
  onRefresh?: () => void | Promise<void>;
}) {
  const tasks = run.tasks ?? [];
  const completed = tasks.filter((t) => t.status === 'COMPLETE').length;
  const totalTokens = (run.totalInputTokens ?? 0) + (run.totalOutputTokens ?? 0);
  const [refreshing, setRefreshing] = useState(false);
  const [pushing, setPushing] = useState(false);
  const [pushStatus, setPushStatus] = useState<string | null>(null);
  const [patDrawerOpen, setPatDrawerOpen] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncBranch, setSyncBranch] = useState('');
  const [syncStatus, setSyncStatus] = useState<string | null>(null);
  const [resettingOriginal, setResettingOriginal] = useState(false);
  const [retryingFailed, setRetryingFailed] = useState(false);
  const addToast = useUiStore((s) => s.addToast);

  // R-10 (UX audit, 2026-05-03) — bring inline run detail to parity with
  // RunDetail's push helper. Pre-fix the inline button only knew GitHub +
  // failed silently when the user had no PAT (Bitbucket users + brand-new
  // accounts both hit this). Now: Bitbucket users → SCM-agnostic push;
  // GitHub-without-PAT → inline drawer that captures the PAT in-line so the
  // user doesn't bounce to /settings and lose their place.
  const user = useAuthStore((s) => s.user);
  const loadMe = useAuthStore((s) => s.loadMe);
  const hasBitbucket = !!user?.hasBitbucketAppPassword;
  const hasGitHub = !!user?.hasGithubPat;
  const scmLabel = hasBitbucket && !hasGitHub ? 'Bitbucket' : 'GitHub';

  const doRefresh = async () => {
    if (!onRefresh || refreshing) return;
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  };

  const terminal = ['COMPLETE', 'PARTIAL'].includes(run.status);
  const alreadyPushed = !!(run.scmPrUrl || run.githubPrUrl);

  const doSync = async () => {
    if (syncing || !terminal) return;
    setSyncing(true);
    setSyncStatus(null);
    try {
      const r = await syncRun(run.runId, syncBranch.trim() || undefined);
      setSyncStatus(
        `Synced ${r.reposSynced} repo${r.reposSynced === 1 ? '' : 's'} — ${r.details.join(', ')}`,
      );
      if (onRefresh) await onRefresh();
    } catch (e) {
      setSyncStatus(`Sync failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSyncing(false);
    }
  };

  const doResetToOriginal = async () => {
    if (resettingOriginal || !terminal) return;
    setResettingOriginal(true);
    setSyncStatus(null);
    try {
      const r = await syncRunToOriginal(run.runId);
      setSyncStatus(`Reset to run state: ${r.details.join(', ')}`);
      if (onRefresh) await onRefresh();
    } catch (e) {
      setSyncStatus(`Reset failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setResettingOriginal(false);
    }
  };

  const doPush = async () => {
    if (pushing || alreadyPushed) return;
    // R-10 (UX audit) — Bitbucket fallback (matches RunDetail.handleScmPush).
    if (hasBitbucket && !hasGitHub) {
      setPushing(true);
      setPushStatus(null);
      try {
        await pushToScm(run.runId);
        setPushStatus('Pushed — PR opening on Bitbucket');
        if (onRefresh) await onRefresh();
      } catch (e) {
        setPushStatus(`Push failed: ${e instanceof Error ? e.message : String(e)}`);
      } finally {
        setPushing(false);
      }
      return;
    }
    // R-10 — GitHub path. If user has no PAT, surface the inline drawer
    // instead of letting the request fail with a server-side credential
    // error. Drawer onClose triggers loadMe() so the user state has the new
    // PAT before they retry.
    if (!hasGitHub) {
      setPatDrawerOpen(true);
      return;
    }
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

  const closePatDrawer = async () => {
    setPatDrawerOpen(false);
    // After the drawer saves, refresh user state so hasGithubPat flips before
    // the user clicks Push again. AddGitHubPatDrawer already called setUser()
    // synchronously, but loadMe() is the canonical refresh path.
    await loadMe();
  };

  // R-11 (UX audit, 2026-05-03) — derive the retryable failed-task list
  // (mirrors RunDetail.failedTasks). Includes parents whose subtasks failed
  // even when the parent's own status was incorrectly persisted as COMPLETE.
  const failedTasks = useMemo(() => {
    if (!run.tasks) return [] as any[];
    const all = run.tasks as any[];
    const parentIdsWithFailedSubtasks = new Set(
      all
        .filter((t) => t.parentTaskId && ['FAILED', 'PARTIAL', 'BLOCKED'].includes(t.status))
        .map((t) => t.parentTaskId),
    );
    return all.filter(
      (t) =>
        !t.parentTaskId &&
        (['FAILED', 'PARTIAL', 'BLOCKED'].includes(t.status) ||
          parentIdsWithFailedSubtasks.has(t.taskId)),
    );
  }, [run.tasks]);

  const handleRetryFailed = async () => {
    if (failedTasks.length === 0) return;
    setRetryingFailed(true);
    try {
      await Promise.all(failedTasks.map((t: any) => retryTask(run.runId, t.taskId)));
      addToast({type: 'success', message: `Retrying ${failedTasks.length} failed task(s)`});
      if (onRefresh) await onRefresh();
    } catch (e: any) {
      addToast({type: 'error', message: e?.message ?? 'Retry failed'});
    } finally {
      setRetryingFailed(false);
    }
  };

  return (
    <div className='space-y-3 text-xs'>
      {/* Request + stats */}
      <div className='space-y-2'>
        <div className='flex items-start justify-between gap-3'>
          <div className='flex-1 min-w-0'>
            <div className='text-[11px] uppercase tracking-wide text-muted'>Request</div>
            <p className='text-xs text-primary whitespace-pre-wrap'>{run.userRequest}</p>
          </div>
          <div className='flex items-center gap-1.5 flex-shrink-0'>
            {terminal && !alreadyPushed && (
              <button
                onClick={doPush}
                disabled={pushing}
                title={`Push this run's workspace to ${scmLabel} and open a PR`}
                className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50'
              >
                {pushing ? (
                  <Loader2 className='h-3 w-3 animate-spin' />
                ) : (
                  <Upload className='h-3 w-3' />
                )}
                {pushing ? 'Pushing…' : `Push & open PR`}
              </button>
            )}
            {terminal && (
              <div className='inline-flex items-center gap-1 text-[11px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-1 py-0.5'>
                <GitBranch className='h-3 w-3 text-muted' />
                <input
                  type='text'
                  placeholder='branch'
                  value={syncBranch}
                  onChange={(e) => setSyncBranch(e.target.value)}
                  className='w-20 bg-transparent text-[11px] font-mono text-primary placeholder-muted focus:outline-none'
                  title="Branch to sync this run's workspace from. Empty = each repo's default."
                />
                <button
                  onClick={doSync}
                  disabled={syncing}
                  title='Hard-reset workspace from origin/<branch> — re-review against post-merge code'
                  className='inline-flex items-center gap-1 text-accent-blue hover:text-accent-blue/80 disabled:opacity-50'
                >
                  {syncing ? <Loader2 className='h-3 w-3 animate-spin' /> : 'Sync'}
                </button>
              </div>
            )}
            {terminal && (
              <button
                onClick={doResetToOriginal}
                disabled={resettingOriginal}
                title='Reset workspace to the exact commit SHAs this run cloned at start (v2.5.2+)'
                className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-muted hover:text-primary disabled:opacity-50'
              >
                {resettingOriginal ? (
                  <Loader2 className='h-3 w-3 animate-spin' />
                ) : (
                  'Reset to run state'
                )}
              </button>
            )}
            {onRefresh && (
              <button
                onClick={doRefresh}
                disabled={refreshing}
                title='Refresh run detail'
                className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary disabled:opacity-50'
              >
                <RefreshCw className={cn('h-3 w-3', refreshing && 'animate-spin')} />
                Refresh
              </button>
            )}
          </div>
        </div>
        <div className='flex flex-wrap items-center gap-x-4 gap-y-1'>
          <Stat
            label='Status'
            value={run.status}
            valueClass={STATUS_COLOR[run.status] ?? 'text-muted'}
          />
          {/* M-021 (Backlog) — VerdictPill backfill on the inline run
              detail header. Mirrors the RunCard treatment from M-043
              and the ValidationReportPanel + ScoreCard from earlier
              Plan-1 batches. Uses the same statusReason-only signal
              set as RunCard (RunDetail carries the full signal set
              but InlineRunDetail's header is a compact summary, and
              consistency with the list view matters more than the
              extra fidelity here). Renders nothing pre-v2.5.10 runs. */}
          {(() => {
            const v = deriveVerdict({statusReason: run.statusReason ?? null});
            return v ? <VerdictPill size='sm' statusReason={run.statusReason ?? null} /> : null;
          })()}
          <Stat label='Tasks' value={`${completed}/${tasks.length}`} />
          {totalTokens > 0 && <Stat label='Tokens' value={totalTokens.toLocaleString()} />}
          {run.actualCostUsd != null && (
            <Stat label='Cost' value={`$${run.actualCostUsd.toFixed(4)}`} />
          )}
          {run.aiProvider && <Stat label='Provider' value={run.aiProvider} />}
        </div>
        {syncStatus && <div className='text-[11px] text-muted'>{syncStatus}</div>}
        {pushStatus && (
          <div
            className={cn(
              'text-[11px]',
              pushStatus.startsWith('Push failed') ? 'text-status-error' : 'text-status-success',
            )}
          >
            {pushStatus}
          </div>
        )}
        {(run.scmPrUrl || run.githubPrUrl) && (
          <a
            href={run.scmPrUrl ?? run.githubPrUrl!}
            target='_blank'
            rel='noreferrer'
            className='inline-flex items-center gap-1.5 text-accent-blue hover:underline'
          >
            <Github className='h-3 w-3' />
            View pull request
            <ExternalLink className='h-2.5 w-2.5' />
          </a>
        )}
      </div>

      {/* Tasks */}
      {tasks.length > 0 && (
        <div>
          <div className='text-[11px] uppercase tracking-wide text-muted mb-1'>Tasks</div>
          <div className='space-y-1'>
            {tasks.map((t) => (
              <div
                key={t.taskId}
                className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1.5'
              >
                <div className='flex items-center gap-2'>
                  <span
                    className={cn('font-mono text-[11px]', STATUS_COLOR[t.status] ?? 'text-muted')}
                  >
                    {t.status}
                  </span>
                  <span className='truncate text-secondary'>{t.title}</span>
                  {t.durationMs != null && (
                    <span className='ml-auto text-[11px] text-muted font-mono'>
                      {Math.round(t.durationMs / 1000)}s
                    </span>
                  )}
                </div>
                {t.failureReason && (
                  <div className='text-[11px] text-accent-red mt-0.5 line-clamp-2'>
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

      {/* Validation report — reuses the full panel used in the Runs tab.
          v2.6.4 — pass stampMeta so the expanded report renders the
          shareable RunStamp (1-line summary + share-link + embed). */}
      <ValidationReportPanel
        runId={run.runId}
        status={run.status}
        statusReason={run.statusReason ?? null}
        stampMeta={{
          runStartedAt: run.startedAt,
          runCompletedAt: run.completedAt,
          tasksTotal: run.tasks?.length ?? null,
          tasksComplete: run.tasks?.filter((t: any) => t.status === 'COMPLETE').length ?? null,
          provider: (run as any).aiProvider ?? null,
        }}
        retryFailed={
          ['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(run.status)
            ? handleRetryFailed
            : undefined
        }
        retryFailedCount={failedTasks.length}
        retryFailedBusy={retryingFailed}
      />

      {/* R-10 — inline PAT capture for GitHub-without-token users. Same
          drawer used by AddRepoModal (P-03) so the user gets identical UX
          across the surfaces that need a PAT. */}
      <AddGitHubPatDrawer open={patDrawerOpen} onClose={closePatDrawer} />
    </div>
  );
}

function CodePanel({run}: {run: RunDetail}) {
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
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]'>
      <button
        onClick={() => setOpen((v) => !v)}
        className='w-full flex items-center justify-between px-2.5 py-1.5 text-xs text-secondary hover:text-primary'
      >
        <span className='flex items-center gap-1.5'>
          {open ? <ChevronDown className='h-3 w-3' /> : <ChevronRight className='h-3 w-3' />}
          Code
          {files != null && <span className='text-muted'>({files.length} files)</span>}
        </span>
        {!terminal && <span className='text-[11px] text-muted'>run still in progress</span>}
      </button>
      {open && (
        <div className='border-t border-[var(--border-default)] h-[500px] overflow-hidden'>
          {error && <div className='p-3 text-xs text-accent-red'>{error}</div>}
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

function Stat({label, value, valueClass}: {label: string; value: string; valueClass?: string}) {
  return (
    <span className='text-xs'>
      <span className='text-muted'>{label}:</span>{' '}
      <span className={cn('font-mono tabular-nums', valueClass ?? 'text-primary')}>{value}</span>
    </span>
  );
}
