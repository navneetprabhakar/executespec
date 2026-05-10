import {useEffect, useMemo, useState} from 'react';
import {Link} from 'react-router-dom';
import {
  ChevronDown,
  ChevronRight,
  ChevronUp,
  ExternalLink,
  Github,
  RefreshCw,
  Upload,
  Loader2,
  GitBranch,
  Pause,
  XCircle,
  RotateCcw,
  ArrowUpRight,
} from 'lucide-react';
import {
  cancelRun as cancelRunApi,
  getFiles,
  pauseRun as pauseRunApi,
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
import {
  runStatusTextClass,
  isNonTerminalRunStatus,
  isTerminalRunStatus,
} from '@/components/runs/runStatusColors';
import {useAuthStore} from '@/stores/useAuthStore';
import {useRunStore} from '@/stores/useRunStore';
import {useUiStore} from '@/stores/useUiStore';
import {cn, errMsg} from '@/lib/utils';

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
  // R-01 (UX audit, 2026-05-03) — Pause/Cancel/Re-run state for parity
  // with the standalone RunDetail surface. Pre-fix the inline view had
  // no way to pause a non-terminal run or re-launch a finished one — the
  // user had to navigate to /runs/{id} for those affordances.
  const [pausing, setPausing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  // 2026-05-10 (UX feedback) — long specs (1000+ chars on Debt-Resolution
  // style runs) flooded the run-detail panel with a 30-line text wall and
  // pushed the action cluster + tasks + report below the fold. Collapse to
  // ~6 lines by default; user can expand on demand.
  const [requestExpanded, setRequestExpanded] = useState(false);
  const addToast = useUiStore((s) => s.addToast);
  const setPendingNewRunConfig = useUiStore((s) => s.setPendingNewRunConfig);
  const setRunStoreFilter = useRunStore((s) => s.setProjectFilter);

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

  // R-01 — Pause / Cancel / Re-run handlers. Mirror RunDetail's behaviour
  // so users get the same outcome regardless of which surface they used
  // to open the run.
  const doPause = async () => {
    if (!isLive || pausing) return;
    setPausing(true);
    try {
      await pauseRunApi(run.runId);
      addToast({type: 'success', message: 'Run paused'});
      if (onRefresh) await onRefresh();
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) ?? 'Pause failed'});
    } finally {
      setPausing(false);
    }
  };

  const doCancel = async () => {
    if (!isLive || cancelling) return;
    if (
      !window.confirm(
        'Cancel this run? Any in-progress task will be interrupted and the workspace state at the cancel point is preserved for inspection.',
      )
    )
      return;
    setCancelling(true);
    try {
      await cancelRunApi(run.runId);
      addToast({type: 'success', message: 'Cancellation requested'});
      if (onRefresh) await onRefresh();
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) ?? 'Cancel failed'});
    } finally {
      setCancelling(false);
    }
  };

  const doReRun = () => {
    // Mirror RunDetail.handleReRun. Stuffs a NewRunConfig into the UI
    // store so /runs auto-opens NewRunModal with the spec pre-filled.
    setPendingNewRunConfig({
      goal: run.userRequest ?? '',
      executionMode: (run.executionMode as 'PROTOTYPE' | 'PRODUCTION') ?? 'PROTOTYPE',
      model: run.workerModel,
      provider: run.workerModel ? 'openai' : undefined,
      projectId: run.projectId ?? undefined,
      repoMode: (run.repoMode as 'NONE' | 'NEW' | 'EXISTING') ?? 'NONE',
      repoUrl: run.repoUrl ?? undefined,
    });
    if (run.projectId) {
      // Preserve the project filter so the new run lands on the right
      // project's run list rather than the global one.
      setRunStoreFilter(run.projectId);
      window.location.assign(`/projects/${run.projectId}/dashboard?tab=runs`);
    } else {
      window.location.assign('/runs');
    }
  };

  // R-01 (UX audit, 2026-05-03) — terminal here matches the push-eligible
  // states (the workspace must be in a deterministic state to push). The
  // wider terminal set used by the Code panel below picks up FAILED/
  // CANCELLED via isTerminalRunStatus.
  const terminal = ['COMPLETE', 'PARTIAL'].includes(run.status);
  const isFullyTerminal = isTerminalRunStatus(run.status);
  const isLive = isNonTerminalRunStatus(run.status);
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
      setSyncStatus(`Sync failed: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
      setSyncStatus(`Reset failed: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
        setPushStatus(`Push failed: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
      setPushStatus(`Push failed: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    if (!run.tasks) return [] as any[];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
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
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      await Promise.all(failedTasks.map((t: any) => retryTask(run.runId, t.taskId)));
      addToast({type: 'success', message: `Retrying ${failedTasks.length} failed task(s)`});
      if (onRefresh) await onRefresh();
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) ?? 'Retry failed'});
    } finally {
      setRetryingFailed(false);
    }
  };

  // 2026-05-10 (UX feedback) — Request collapse threshold. Anything under
  // 320 chars renders fully (single short paragraph); over 320 collapses
  // to ~6 lines with a Show more / Show less toggle. The cutoff is the
  // observable point where a textarea spec becomes a wall.
  const REQUEST_COLLAPSE_CHARS = 320;
  const requestCollapsible = (run.userRequest || '').length > REQUEST_COLLAPSE_CHARS;

  return (
    <div className='space-y-3 text-xs'>
      {/* 2026-05-10 (UX feedback on /projects/.../runs/.../detail) — restructured
          header. Pre-fix the layout was `flex justify-between` between the
          REQUEST text and a 9-button action cluster. With long specs (1000+
          chars on Debt-Resolution runs) the request flowed down 30 lines
          while the action cluster sat compact at the top-right, leaving a
          wide vertical bar of empty space and pushing the actual run signals
          (status, validation, tasks) far below the fold. New shape:
            row 1: action cluster — full width, flex-wrap, kept on top so
                   primary affordances (Re-run, Push & PR, Open full view)
                   are always visible without scrolling
            row 2: stats line (Status / Tasks / Cost / Provider / VerdictPill)
            row 3: REQUEST card — collapsed to ~6 lines by default with
                   Show more / Show less toggle when over 320 chars */}
      <div className='space-y-2'>
        {/* row 1 — action cluster */}
        <div className='flex items-center gap-1.5 flex-wrap'>
          {/* R-01 — non-terminal runs gain Pause + Cancel inline so the
                inline view stops being a feature-gated subset of the
                standalone run page. Mirrors RunDetail's button positions. */}
          {isLive && (
            <>
              <button
                onClick={doPause}
                disabled={pausing}
                title='Pause this run between tasks. The run can be resumed manually from the standalone run page.'
                className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary disabled:opacity-50'
              >
                {pausing ? (
                  <Loader2 className='h-3 w-3 animate-spin' />
                ) : (
                  <Pause className='h-3 w-3' />
                )}
                {pausing ? 'Pausing…' : 'Pause'}
              </button>
              <button
                onClick={doCancel}
                disabled={cancelling}
                title='Cancel this run. Workspace state at cancel point is preserved for inspection.'
                className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-status-error/40 bg-status-error/10 text-status-error hover:bg-status-error/20 disabled:opacity-50'
              >
                {cancelling ? (
                  <Loader2 className='h-3 w-3 animate-spin' />
                ) : (
                  <XCircle className='h-3 w-3' />
                )}
                {cancelling ? 'Cancelling…' : 'Cancel'}
              </button>
            </>
          )}
          {/* R-01 — Re-run on terminal runs. Stuffs a NewRunConfig into
                the UI store so /runs (or the project's run list) auto-
                opens NewRunModal with the spec pre-filled, matching
                RunDetail's behaviour. */}
          {isFullyTerminal && (
            <button
              onClick={doReRun}
              title='Re-launch this run with the same spec, project, and provider'
              className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary'
            >
              <RotateCcw className='h-3 w-3' />
              Re-run
            </button>
          )}
          {/* R-01 — escape hatch to the full standalone run page (live
                log tab, plan tab, full task graph). Many users still want
                to see the full chrome — this lets them get there in one
                click instead of remembering the URL pattern. */}
          <Link
            to={`/runs/${run.runId}`}
            title='Open the full standalone run page (live log, plan, task graph)'
            className='inline-flex items-center gap-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary'
          >
            <ArrowUpRight className='h-3 w-3' />
            Open full view
          </Link>
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
        <div className='flex flex-wrap items-center gap-x-4 gap-y-1'>
          <Stat label='Status' value={run.status} valueClass={runStatusTextClass(run.status)} />
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
        {/* row 3 — REQUEST card. Collapsed to ~6 lines by default for long
            specs; toggle expands. Bordered + subtle bg so it reads as a
            distinct surface, not a paragraph in the page flow. */}
        {run.userRequest && (
          <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2'>
            <div className='flex items-center justify-between gap-2 mb-1'>
              <div className='text-[11px] uppercase tracking-wide font-mono text-muted'>
                Request
              </div>
              {requestCollapsible && (
                <button
                  onClick={() => setRequestExpanded((v) => !v)}
                  className='inline-flex items-center gap-0.5 text-[11px] text-accent-blue hover:underline'
                  aria-expanded={requestExpanded}
                >
                  {requestExpanded ? (
                    <>
                      <ChevronUp className='h-3 w-3' /> Show less
                    </>
                  ) : (
                    <>
                      <ChevronDown className='h-3 w-3' />
                      Show all ({run.userRequest.length.toLocaleString()} chars)
                    </>
                  )}
                </button>
              )}
            </div>
            <p
              className={cn(
                'text-xs text-primary whitespace-pre-wrap leading-relaxed',
                requestCollapsible && !requestExpanded && 'line-clamp-6',
              )}
            >
              {run.userRequest}
            </p>
          </div>
        )}
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
            // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
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
                  <span className={cn('font-mono text-[11px]', runStatusTextClass(t.status))}>
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
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          tasksComplete: run.tasks?.filter((t: any) => t.status === 'COMPLETE').length ?? null,
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
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
      .catch((e) => setError(e instanceof Error ? errMsg(e) : 'Failed to list files'))
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
