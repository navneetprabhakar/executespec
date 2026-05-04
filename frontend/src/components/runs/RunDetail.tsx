import {useState, useMemo, useRef, useEffect} from 'react';
import {useNavigate} from 'react-router-dom';
import {motion, AnimatePresence} from 'framer-motion';
import {
  ArrowLeft,
  X,
  Trash2,
  AlertTriangle,
  Download,
  RotateCcw,
  Pause,
  Play,
  Loader2,
  ChevronDown,
  ChevronUp,
  Github,
  Archive,
  RefreshCw,
} from 'lucide-react';
import {useIsMobile} from '@/hooks/useIsMobile';
import GlowDot from '@/components/shared/GlowDot';
import StatusBadge from '@/components/shared/StatusBadge';
import RunDetailTabs from './RunDetailTabs';
import ValidationReportPanel from './ValidationReportPanel';
import AssessmentPanel from './AssessmentPanel';
import TestProgressTracker from './TestProgressTracker';
import TestReportPanel from './TestReportPanel';
import {RunTimeline} from '@/components/run/RunTimeline';
import {useRunStore} from '@/stores/useRunStore';
import {useUiStore} from '@/stores/useUiStore';
import {useSseStore} from '@/stores/useSseStore';
import {
  downloadWorkspace,
  editRunPlan,
  pushToGitHub,
  pushToScm,
  updateGithubSettings,
  archiveRun,
  retryTask,
  getValidationReport,
  type ReviewReport,
  type ValidationReportData,
  type TaskSummary,
} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {cn, describeRunStatus} from '@/lib/utils';
import {V2_LEGACY_UI} from '@/lib/constants';
// R-190 (post-cutover) — VibeProgressNarrative deleted with the Vibe surface.
// useViewMode no longer exposes isVibe; Pro layout is the only path now.

export default function RunDetail() {
  const {
    activeRun,
    activeRunId,
    setActiveRunId,
    approveRun,
    cancelRun,
    createRun,
    pauseRun,
    resumeRun,
  } = useRunStore();
  const {addToast, setActiveTab} = useUiStore();
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const [confirming, setConfirming] = useState<string | null>(null);
  const [reRunning, setReRunning] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [downloadOpen, setDownloadOpen] = useState(false);
  const [editingPlan, setEditingPlan] = useState(false);
  const [planFeedback, setPlanFeedback] = useState('');
  const [submittingPlanEdit, setSubmittingPlanEdit] = useState(false);
  const [showGitHubModal, setShowGitHubModal] = useState(false);
  const [ghPat, setGhPat] = useState('');
  const [ghOwner, setGhOwner] = useState('');
  const [ghPushing, setGhPushing] = useState(false);
  const [ghError, setGhError] = useState('');
  const [showArchiveConfirm, setShowArchiveConfirm] = useState(false);
  const [archiving, setArchiving] = useState(false);
  const [retryingFailed, setRetryingFailed] = useState(false);
  const downloadRef = useRef<HTMLDivElement>(null);
  const user = useAuthStore((s) => s.user);
  const loadMe = useAuthStore((s) => s.loadMe);
  const sseEntries = useSseStore((s) => s.entries);

  // Show toast for budget events; suppress compaction toasts (too noisy with subtask decomposition)
  const budgetShownRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    for (const entry of sseEntries) {
      // COMPACTION_TRIGGERED: skip toasts — they appear in the live log and are too frequent
      // when subtask decomposition creates many parallel sub-tasks each hitting context limits.
      if (entry.type === 'BUDGET_EXCEEDED' && !budgetShownRef.current.has(entry.id)) {
        budgetShownRef.current.add(entry.id);
        const parts = ((entry.data?.payload as string) || '').split('|');
        const message =
          parts[2] || 'Run stopped — cost budget exceeded. Upgrade for higher limits.';
        addToast({type: 'error', message});
      }
      if (entry.type === 'BUDGET_WARNING' && !budgetShownRef.current.has(entry.id)) {
        budgetShownRef.current.add(entry.id);
        const parts = ((entry.data?.payload as string) || '').split('|');
        const percent = parts[2] || '?';
        const message = parts[4] || `Cost warning: ${percent}% of budget used.`;
        addToast({type: parseInt(percent) >= 90 ? 'error' : 'info', message});
      }
    }
  }, [sseEntries]);

  // Close download dropdown on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (downloadRef.current && !downloadRef.current.contains(e.target as Node)) {
        setDownloadOpen(false);
      }
    };
    if (downloadOpen) document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [downloadOpen]);

  // Parse the reviewJson once whenever the run refreshes
  const review = useMemo<ReviewReport | null>(() => {
    if (!activeRun?.reviewJson) return null;
    try {
      return JSON.parse(activeRun.reviewJson) as ReviewReport;
    } catch {
      return null;
    }
  }, [activeRun?.reviewJson]);

  // Validation report — used to surface accuracy score in the review summary banner
  const [reviewReport, setReviewReport] = useState<ValidationReportData | null>(null);
  const reportReadyEvt = useSseStore((s) => s.entries).some(
    (e) => e.type === 'VALIDATION_REPORT_READY',
  );
  useEffect(() => {
    if (!activeRunId || !['COMPLETE', 'PARTIAL', 'FAILED'].includes(activeRun?.status ?? ''))
      return;
    getValidationReport(activeRunId).then((r) => {
      if (r) setReviewReport(r);
    });
  }, [activeRunId, activeRun?.status, reportReadyEvt]);

  const handle = async (action: () => Promise<void>, successMsg: string) => {
    try {
      await action();
      addToast({type: 'success', message: successMsg});
      setConfirming(null);
    } catch (e: any) {
      // 409 = gate was lost after a server restart; the run has been auto-cancelled
      if (e.message?.startsWith('409')) {
        addToast({
          type: 'error',
          message: 'Gate expired (server was restarted). Run has been cancelled — please resubmit.',
        });
        setConfirming(null);
        // Refresh so the UI shows the run as FAILED
        if (activeRunId) await useRunStore.getState().fetchRun(activeRunId);
        await useRunStore.getState().fetchRuns();
      } else {
        addToast({type: 'error', message: e.message});
      }
    }
  };

  const hasBitbucket = !!user?.hasBitbucketAppPassword;
  const hasGitHub = !!user?.hasGithubPat;
  const scmLabel = hasBitbucket && !hasGitHub ? 'Bitbucket' : 'GitHub';

  const handleScmPush = async () => {
    if (!activeRun) return;
    // If user has Bitbucket but not GitHub, use SCM-agnostic endpoint
    if (hasBitbucket && !hasGitHub) {
      setGhPushing(true);
      try {
        await pushToScm(activeRun.runId);
        addToast({type: 'success', message: 'Publishing to Bitbucket...'});
      } catch (e: any) {
        addToast({type: 'error', message: e.message || 'Bitbucket push failed'});
      } finally {
        setGhPushing(false);
      }
      return;
    }
    // GitHub path
    if (!hasGitHub) {
      setGhOwner(user?.githubOwner ?? '');
      setGhError('');
      setShowGitHubModal(true);
      return;
    }
    setGhPushing(true);
    try {
      await pushToGitHub(activeRun.runId);
      addToast({type: 'success', message: 'Publishing to GitHub...'});
    } catch (e: any) {
      addToast({type: 'error', message: e.message || 'GitHub push failed'});
    } finally {
      setGhPushing(false);
    }
  };

  const handleGitHubSaveAndPush = async () => {
    if (!activeRun || !ghPat.trim() || !ghOwner.trim()) return;
    setGhPushing(true);
    setGhError('');
    try {
      await updateGithubSettings(ghPat.trim(), ghOwner.trim());
      await loadMe();
      await pushToGitHub(activeRun.runId);
      setShowGitHubModal(false);
      setGhPat('');
      addToast({type: 'success', message: 'Publishing to GitHub…'});
    } catch (e: any) {
      setGhError(e.message || 'Failed to save credentials or push');
    } finally {
      setGhPushing(false);
    }
  };

  const handleReRun = async () => {
    if (!activeRun) return;

    // R-07 (UX audit, 2026-05-03): pre-fix this set pendingNewRunConfig and
    // showed a toast "Re-run configuration loaded. Adjust and launch when
    // ready." — but the user was still on RunDetail, where the modal doesn't
    // mount. They had to manually navigate to /runs and open the modal,
    // making the button appear broken. Now we set the config AND navigate
    // to /runs so RunList's useEffect immediately opens NewRunModal with
    // the prefilled config. Net: one click does what the label promises.
    const config = {
      goal: activeRun.userRequest ?? '',
      executionMode: (activeRun.executionMode as 'PROTOTYPE' | 'PRODUCTION') ?? 'PROTOTYPE',
      model: activeRun.workerModel,
      provider: activeRun.workerModel ? 'openai' : undefined, // Fallback; ProviderModelSelector handles persistence
      projectId: activeRun.projectId ?? undefined,
      repoMode: (activeRun.repoMode as 'NONE' | 'NEW' | 'EXISTING') ?? 'NONE',
      repoUrl: activeRun.repoUrl ?? undefined,
    };

    useUiStore.getState().setPendingNewRunConfig(config);
    // Navigate to /runs (where RunList is) with the pendingConfig already
    // in the store. RunList's useEffect picks it up and opens NewRunModal
    // automatically. If the user came from a project, prefer the project's
    // own launch flow because that path threads the project's repo + arch
    // selection through the launch wizard.
    if (activeRun.projectId) {
      navigate(`/projects/${activeRun.projectId}/dashboard?tab=runs`);
    } else {
      navigate('/runs');
    }
  };

  const handlePause = async () => {
    if (!activeRun) return;
    setPausing(true);
    try {
      await pauseRun(activeRun.runId);
      addToast({type: 'success', message: 'Run paused'});
    } catch (e: any) {
      addToast({type: 'error', message: e.message});
    } finally {
      setPausing(false);
    }
  };

  // Retry all FAILED/PARTIAL tasks within this run
  const failedTasks = useMemo(() => {
    if (!activeRun?.tasks) return [];
    const tasks = activeRun.tasks as any[];
    // Parent IDs that have at least one FAILED/PARTIAL/BLOCKED subtask —
    // catches the case where a subtask returned PARTIAL but the parent was
    // incorrectly persisted as COMPLETE (subtask decomposition edge case).
    const parentIdsWithFailedSubtasks = new Set(
      tasks
        .filter((t) => t.parentTaskId && ['FAILED', 'PARTIAL', 'BLOCKED'].includes(t.status))
        .map((t) => t.parentTaskId),
    );
    return tasks.filter(
      (t) =>
        !t.parentTaskId &&
        (['FAILED', 'PARTIAL', 'BLOCKED'].includes(t.status) ||
          parentIdsWithFailedSubtasks.has(t.taskId)),
    );
  }, [activeRun?.tasks]);

  const handleRetryFailed = async () => {
    if (!activeRun || failedTasks.length === 0) return;
    setRetryingFailed(true);
    try {
      await Promise.all(failedTasks.map((t: any) => retryTask(activeRun.runId, t.taskId)));
      addToast({type: 'success', message: `Retrying ${failedTasks.length} failed task(s)`});
    } catch (e: any) {
      addToast({type: 'error', message: e.message || 'Retry failed'});
    } finally {
      setRetryingFailed(false);
    }
  };

  // v2.5.12 — edit-and-restart was V1-only; removed along with the store
  // action. The same outcome is achievable via "New Run" with an updated
  // spec, so this is pure cleanup.

  const handleResume = async () => {
    if (!activeRun) return;
    setResuming(true);
    try {
      await resumeRun(activeRun.runId);
      addToast({type: 'success', message: 'Run resumed — re-running incomplete tasks'});
    } catch (e: any) {
      addToast({type: 'error', message: e.message});
    } finally {
      setResuming(false);
    }
  };

  return (
    <>
      <AnimatePresence>
        {activeRun && (
          <motion.div
            initial={{x: '100%', opacity: 0}}
            animate={{x: 0, opacity: 1}}
            exit={{x: '100%', opacity: 0}}
            transition={{type: 'spring', stiffness: 300, damping: 30}}
            className='flex flex-col h-full overflow-y-auto bg-[var(--gray-100)] border-l border-[var(--border-default)]'
          >
            {/* R-190 (post-cutover) — Pro layout is the only path. The legacy
              Vibe-mode branch (minimal header + VibeProgressNarrative + Code/
              Preview tabs only) was deleted with the Vibe surface. */}
            <>
              {/* Mobile back button */}
              <button
                onClick={() => setActiveRunId(null)}
                className='lg:hidden flex items-center gap-1.5 px-4 py-2.5 text-sm text-secondary hover:text-primary border-b border-[var(--border-default)] transition-colors'
              >
                <ArrowLeft className='h-4 w-4' />
                Back to runs
              </button>

              {/* Header */}
              <div className='flex items-start justify-between px-4 py-3 border-b border-[var(--border-default)] flex-shrink-0'>
                <div className='flex items-start gap-2.5 min-w-0'>
                  <GlowDot status={activeRun.status} className='mt-1 flex-shrink-0' />
                  <div className='min-w-0'>
                    <div className='flex items-center gap-2 flex-wrap'>
                      <p className='font-mono text-xs text-secondary'>
                        {activeRun.runId.slice(0, 16)}
                      </p>
                      {/* v2.5.8 — when the reviewer's final build passes on a PARTIAL run,
                      show one unified "Complete · with warnings" chip (amber) instead of
                      the previous orange-PARTIAL + green-BUILD chip combo that visually
                      contradicted itself. The tooltip still explains the smoke-test nuance. */}
                      {finalBuildPassed(activeRun) && activeRun.status === 'PARTIAL' ? (
                        <span
                          className='inline-flex items-center gap-1.5 text-xs font-medium tracking-wide uppercase text-accent-amber'
                          title='Final build passed — intermediate smoke-test warnings did not block delivery'
                        >
                          <GlowDot status='COMPLETE' size='sm' />
                          Complete · with warnings
                        </span>
                      ) : (
                        <StatusBadge
                          status={activeRun.status}
                          statusReason={activeRun.statusReason}
                        />
                      )}
                    </div>
                    {/* v2.5.13 mobile fix — run title was missing from the Pro-view header
                    (only the short ID was shown), making it impossible to know which
                    spec you were looking at on a small screen. */}
                    {(activeRun.runTitle || activeRun.userRequest) && (
                      <p
                        className='text-sm text-primary mt-1 line-clamp-2'
                        title={activeRun.runTitle ?? activeRun.userRequest ?? undefined}
                      >
                        {activeRun.runTitle ?? activeRun.userRequest}
                      </p>
                    )}
                    <p className='text-xs text-muted mt-0.5 line-clamp-1'>
                      {describeRunStatus(
                        activeRun.status,
                        activeRun.tasks,
                        activeRun.failureReason,
                        activeRun.executionMode,
                      )}
                    </p>
                  </div>
                </div>
                <button
                  onClick={() => setActiveRunId(null)}
                  className='text-muted hover:text-secondary flex-shrink-0 ml-2 p-1.5 -m-1'
                >
                  <X className='h-4 w-4' />
                </button>
              </div>

              {/* Retry banner */}
              {activeRun.isRetry && (
                <div className='flex items-center gap-2 px-4 py-2 bg-accent-amber/10 border-b border-accent-amber/20 text-xs text-accent-amber flex-shrink-0'>
                  <RefreshCw className='h-3 w-3 flex-shrink-0' />
                  <span>
                    This run is auto-retry #{activeRun.runRetryCount ?? 1} of{' '}
                    <button
                      onClick={() => activeRun.sourceRunId && setActiveRunId(activeRun.sourceRunId)}
                      className='underline hover:text-primary transition-colors'
                    >
                      {activeRun.sourceRunId?.slice(0, 12)}
                    </button>
                  </span>
                </div>
              )}

              {/* Pilot R4 — feature assessment, shown above the plan-approval gate. */}
              {activeRun.status === 'AWAITING_APPROVAL' && activeRun.assessmentResult && (
                <div className='flex-shrink-0 px-4 pt-3'>
                  <AssessmentPanel raw={activeRun.assessmentResult} />
                </div>
              )}

              {/* Codemap stats — paid existing-repo runs only */}
              {activeRun.codemapFileCount != null && activeRun.codemapFileCount > 0 && (
                <div className='flex-shrink-0 px-4 pt-2 text-xs text-muted'>
                  Codemap pulled — {activeRun.codemapFileCount} files,{' '}
                  {activeRun.codemapRouteCount ?? 0} routes
                  {activeRun.codemapInputTokens != null &&
                    activeRun.codemapOutputTokens != null && (
                      <>
                        {' '}
                        (
                        {(
                          activeRun.codemapInputTokens + activeRun.codemapOutputTokens
                        ).toLocaleString()}{' '}
                        tokens)
                      </>
                    )}
                </div>
              )}

              {/* Gate banner */}
              {activeRun.status === 'AWAITING_APPROVAL' && (
                <div className='flex-shrink-0'>
                  {/* P-15 / R-02 (UX audit, 2026-05-03) — surface the plan
                      INLINE above Approve/Reject so the user reads what
                      they're approving. Pre-fix, the banner just said
                      "Review the plan and approve to continue" with no
                      plan in sight — users had to switch to the Tasks tab
                      to see what they'd be charged for. The 5-task preview
                      below is bounded so it never crowds the action bar.
                      Click "Show all N tasks" to expand inline (no tab
                      switch needed). */}
                  {Array.isArray(activeRun.tasks) && activeRun.tasks.length > 0 && (
                    <PlanGatePreview
                      tasks={activeRun.tasks.filter(
                        (t) => !t.parentTaskId && !t.taskId?.includes('_gap_'),
                      )}
                    />
                  )}

                  {/* [F-12] (audit Sprint 1 PR-4) — Batch dual-option cards
                  hidden. V2 backend has not implemented batch dispatch
                  (V1 batch was deleted 2026-04-13; the V2 re-impl is
                  scoped in docs/plans/pending-v2-batch-execution-plan.md
                  but not shipped). Showing the "Batch · Save N · Results
                  in ~4 hours" card promised a feature the user couldn't
                  actually use — every batch-approved run failed at submit.
                  Standard REALTIME gate banner is the only path until V2
                  batch lands. The `batchAvailable` condition is force-false
                  here so the standard banner always wins; backend-side
                  `batchAvailable` flag still lives on RunDetail DTO for
                  the eventual re-enable. */}
                  {/* eslint-disable-next-line @typescript-eslint/no-unused-expressions */}
                  {false && activeRun?.batchAvailable && <div />}
                  {/* Standard gate banner — only path under V2 today */}
                  {true && (
                    <GateBanner
                      message={
                        activeRun.creditsCharged && activeRun.creditsCharged > 1
                          ? `This spec will use ${activeRun.creditsCharged} credits (~$${activeRun.costEstimateHigh?.toFixed(2) ?? '?'})`
                          : 'Review the plan and approve to continue'
                      }
                      icon={<AlertTriangle className='h-4 w-4 text-accent-amber' />}
                      onApprove={() =>
                        handle(
                          () =>
                            approveRun(
                              activeRun.runId,
                              true,
                              activeRun.creditsCharged ?? undefined,
                              'REALTIME',
                            ),
                          'Run approved',
                        )
                      }
                      onReject={() =>
                        handle(() => approveRun(activeRun.runId, false), 'Run rejected')
                      }
                      confirming={confirming}
                      setConfirming={setConfirming}
                      approveLabel={
                        activeRun.creditsCharged && activeRun.creditsCharged > 1
                          ? `Approve (${activeRun.creditsCharged} credits)`
                          : 'Approve Plan'
                      }
                      rejectLabel='Reject'
                    />
                  )}
                  {/* Edit Plan feedback area — hidden while V2 edit-plan endpoint returns 410 Gone */}
                  {V2_LEGACY_UI.editRunPlan && (
                    <div className='px-4 pb-3'>
                      {!editingPlan ? (
                        <button
                          onClick={() => setEditingPlan(true)}
                          className='text-xs text-secondary hover:text-primary underline underline-offset-2'
                        >
                          Edit Plan
                        </button>
                      ) : (
                        <div className='space-y-2 mt-1'>
                          <textarea
                            value={planFeedback}
                            onChange={(e) => setPlanFeedback(e.target.value)}
                            placeholder='Describe what to change in the plan...'
                            rows={3}
                            className='w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2 text-sm text-primary placeholder-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-accent-blue/50 resize-none'
                          />
                          <div className='flex gap-2'>
                            <button
                              disabled={submittingPlanEdit || !planFeedback.trim()}
                              onClick={async () => {
                                setSubmittingPlanEdit(true);
                                try {
                                  await editRunPlan(activeRun.runId, planFeedback.trim());
                                  setPlanFeedback('');
                                  setEditingPlan(false);
                                  addToast({
                                    type: 'success',
                                    message: 'Plan updated — review the new tasks',
                                  });
                                  if (activeRunId)
                                    await useRunStore.getState().fetchRun(activeRunId);
                                } catch (e: any) {
                                  addToast({type: 'error', message: e.message});
                                } finally {
                                  setSubmittingPlanEdit(false);
                                }
                              }}
                              className='px-3 py-1.5 text-xs font-medium rounded-md bg-accent-blue/20 text-accent-blue hover:bg-accent-blue/30 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5'
                            >
                              {submittingPlanEdit && <Loader2 className='h-3 w-3 animate-spin' />}
                              Submit Feedback
                            </button>
                            <button
                              onClick={() => {
                                setEditingPlan(false);
                                setPlanFeedback('');
                              }}
                              className='px-3 py-1.5 text-xs font-medium rounded-md text-secondary hover:text-primary'
                            >
                              Cancel
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
              {/* v2.5.12 — AWAITING_CLARIFICATION + clarifyRun were V1-only.
              V2 never emits this status. ClarificationBanner usage removed;
              the component itself stays for history but is dead code. */}
              {/* Batch progress indicator */}
              {(activeRun.status === 'IN_PROGRESS_BATCH' ||
                activeRun.status === 'QUEUED_BATCH') && (
                <div className='mx-4 mt-3 p-3 rounded-sm border border-accent-blue/20 bg-accent-blue/5 flex-shrink-0'>
                  <div className='flex items-center justify-between mb-1.5'>
                    <span className='text-xs font-semibold text-primary'>
                      {activeRun.status === 'QUEUED_BATCH' ? 'Batch queued' : 'Batch in progress'}
                    </span>
                    {activeRun.batchProgressTotal != null && activeRun.batchProgressTotal > 0 && (
                      <span className='text-[10px] text-muted'>
                        {activeRun.batchProgressCompleted ?? 0} / {activeRun.batchProgressTotal}{' '}
                        tasks
                      </span>
                    )}
                  </div>
                  <div className='h-1.5 rounded-full bg-[var(--gray-300)] overflow-hidden'>
                    <div
                      className='h-full rounded-full bg-accent-blue transition-all duration-500'
                      style={{
                        width: activeRun.batchProgressTotal
                          ? `${Math.round(((activeRun.batchProgressCompleted ?? 0) / activeRun.batchProgressTotal) * 100)}%`
                          : '0%',
                      }}
                    />
                  </div>
                  <div className='text-[10px] text-muted mt-1.5'>
                    Batch runs typically complete in 1-4 hours. You'll be notified when done.
                  </div>
                </div>
              )}

              {/* Review summary shown on COMPLETE runs.
                  P-19 / R-03 (UX audit, 2026-05-03): pre-fix this banner
                  showed both a score chip ("92/100 verified · Est. 88") AND
                  the review summary text. The score chip duplicated what
                  VerdictPill + ScoreCard already render below, contributing
                  to the "6 competing scoring narratives" the audit flagged.
                  Dropped the score chip; kept the textual summary because
                  it's the LLM-written review prose nobody else surfaces.
                  VerdictPill below this banner is now the single primary
                  score signal. */}
              {activeRun.status === 'COMPLETE' && review && review.summary && (
                <div className='mx-4 mt-3 p-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-xs flex-shrink-0'>
                  <p className='text-secondary leading-relaxed'>{review.summary}</p>
                </div>
              )}
              {/* Failure reason banner */}
              {activeRun.status === 'FAILED' && activeRun.failureReason && (
                <div className='mx-4 mt-3 p-3 rounded-sm border border-accent-red/30 bg-accent-red/5 flex-shrink-0'>
                  <div className='flex items-center gap-2 mb-1'>
                    <AlertTriangle className='h-4 w-4 text-accent-red flex-shrink-0' />
                    <p className='text-xs font-medium text-accent-red'>Run failed</p>
                  </div>
                  <p className='text-xs text-secondary leading-relaxed'>
                    {activeRun.failureReason}
                  </p>
                </div>
              )}
              {/* Paused banner — edit goal and restart */}
              {activeRun.status === 'PAUSED' && (
                <div className='mx-4 mt-3 p-3 rounded-sm border border-accent-amber/30 bg-accent-amber/5 space-y-2 flex-shrink-0'>
                  <div className='flex items-center gap-2'>
                    <Pause className='h-4 w-4 text-accent-amber flex-shrink-0' />
                    <p className='text-xs font-medium text-accent-amber'>Run paused</p>
                  </div>
                  <p className='text-xs text-secondary leading-relaxed'>
                    <span className='text-muted'>Current goal:</span> {activeRun.userRequest}
                  </p>
                  {/* v2.5.12 — "Edit Goal & Restart" removed with editAndRestartRun.
                  Equivalent outcome: start a New Run with the updated spec. */}
                  <div className='flex items-center gap-2'>
                    <button
                      onClick={handleResume}
                      disabled={resuming}
                      className='flex items-center gap-1.5 rounded-md bg-accent-blue/20 hover:bg-accent-blue/30 border border-accent-blue/30 px-3 py-1.5 text-xs font-medium text-accent-blue transition-colors disabled:opacity-50'
                    >
                      <Play className='h-3 w-3' />
                      {resuming ? 'Resuming…' : 'Resume'}
                    </button>
                  </div>
                </div>
              )}

              {/* Run Timeline */}
              <div className='px-4 border-b border-[var(--border-default)] flex-shrink-0'>
                <RunTimeline
                  events={sseEntries.map((e) => ({
                    type: e.type,
                    taskId: (e.data?.taskId as string) || undefined,
                    payload: (e.data?.payload as string) || undefined,
                    timestamp: new Date(e.timestamp).toISOString(),
                  }))}
                  status={activeRun.status}
                  taskCount={
                    activeRun.tasks?.filter((t) => !t.parentTaskId && !t.taskId?.includes('_gap_'))
                      .length || 0
                  }
                  completedTaskCount={
                    activeRun.tasks?.filter(
                      (t) =>
                        !t.parentTaskId && !t.taskId?.includes('_gap_') && t.status === 'COMPLETE',
                    ).length || 0
                  }
                />
              </div>

              {/* Action bar */}
              <div className='flex items-center gap-1 lg:gap-2 px-3 lg:px-4 py-2 flex-shrink-0 overflow-x-auto scrollbar-hide border-b border-[var(--border-default)]'>
                {!['COMPLETE', 'FAILED', 'CANCELLED', 'PAUSED'].includes(activeRun.status) && (
                  <>
                    <button
                      onClick={handlePause}
                      disabled={pausing}
                      className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-amber transition-colors disabled:opacity-50 whitespace-nowrap'
                    >
                      <Pause className='h-3.5 w-3.5' />
                      {pausing ? 'Pausing…' : 'Pause'}
                    </button>
                    <button
                      onClick={() => handle(() => cancelRun(activeRun.runId), 'Run cancelled')}
                      className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-red transition-colors whitespace-nowrap'
                    >
                      <Trash2 className='h-3.5 w-3.5' />
                      Cancel
                    </button>
                  </>
                )}
                {['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL', 'PAUSED'].includes(
                  activeRun.status,
                ) && (
                  <>
                    <button
                      onClick={handleReRun}
                      disabled={reRunning}
                      className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-blue transition-colors disabled:opacity-50 whitespace-nowrap'
                    >
                      <RotateCcw className={cn('h-3.5 w-3.5', reRunning && 'animate-spin')} />
                      {reRunning ? 'Launching…' : 'Re-run'}
                    </button>
                    {failedTasks.length > 0 && (
                      <button
                        onClick={handleRetryFailed}
                        disabled={retryingFailed}
                        className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-amber transition-colors disabled:opacity-50 whitespace-nowrap'
                        title={`Retry ${failedTasks.length} failed task(s)`}
                      >
                        <Play className={cn('h-3.5 w-3.5', retryingFailed && 'animate-pulse')} />
                        {retryingFailed ? 'Retrying…' : `Retry Failed (${failedTasks.length})`}
                      </button>
                    )}
                  </>
                )}
                <div className='flex items-center gap-2 ml-auto'>
                  {['COMPLETE', 'PARTIAL'].includes(activeRun.status) && (
                    <button
                      onClick={handleScmPush}
                      disabled={ghPushing}
                      className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-blue transition-colors disabled:opacity-50 whitespace-nowrap'
                      title={`Publish to ${scmLabel}`}
                    >
                      <Github className='h-3.5 w-3.5' />
                      {ghPushing ? 'Publishing...' : scmLabel}
                    </button>
                  )}
                  <button
                    onClick={() =>
                      handle(() => downloadWorkspace(activeRun.runId), 'Download started')
                    }
                    className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-green transition-colors whitespace-nowrap'
                    title='Download workspace as ZIP'
                  >
                    <Download className='h-3.5 w-3.5' />
                    Download
                  </button>
                  {['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(activeRun.status) &&
                    !activeRun.archivedAt && (
                      <button
                        onClick={() => setShowArchiveConfirm(true)}
                        className='flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-red transition-colors whitespace-nowrap'
                        title='Archive this run'
                      >
                        <Archive className='h-3.5 w-3.5' />
                        Archive
                      </button>
                    )}
                  {activeRun.archivedAt && (
                    <span
                      className='flex items-center gap-1.5 px-2.5 py-2 text-xs text-muted whitespace-nowrap'
                      title={`Archived on ${new Date(activeRun.archivedAt).toLocaleDateString()}`}
                    >
                      <Archive className='h-3.5 w-3.5' />
                      Archived
                    </span>
                  )}
                </div>
              </div>

              {/* Reports + Tabs — scroll is handled by the outer container so the
              title bar + status icons scroll together as one surface. */}
              <div>
                <MobileCollapsibleReports
                  runId={activeRun.runId}
                  status={activeRun.status}
                  statusReason={activeRun.statusReason ?? null}
                  stampMeta={{
                    runStartedAt: activeRun.startedAt,
                    runCompletedAt: activeRun.completedAt,
                    tasksTotal: activeRun.tasks?.length ?? null,
                    tasksComplete:
                      activeRun.tasks?.filter((t: any) => t.status === 'COMPLETE').length ?? null,
                    provider: (activeRun as any).aiProvider ?? null,
                  }}
                />
                <RunDetailTabs />
              </div>
            </>
          </motion.div>
        )}
      </AnimatePresence>

      {/* GitHub PAT Modal */}
      {showGitHubModal && (
        <div
          className='fixed inset-0 z-50 flex items-center justify-center bg-black/50'
          onClick={() => setShowGitHubModal(false)}
        >
          <div
            className='bg-[var(--gray-100)] border border-[var(--border-default)] rounded-lg p-5 w-full max-w-sm shadow-xl'
            onClick={(e) => e.stopPropagation()}
          >
            <div className='flex items-center gap-2 mb-4'>
              <Github className='h-5 w-5 text-primary' />
              <h3 className='text-sm font-semibold text-primary'>Connect GitHub</h3>
            </div>
            <p className='text-xs text-muted mb-3'>
              Enter your GitHub Personal Access Token and username to publish this workspace.
            </p>
            <label className='block text-xs text-muted mb-1'>Personal Access Token</label>
            <input
              type='password'
              value={ghPat}
              onChange={(e) => setGhPat(e.target.value)}
              placeholder='ghp_...'
              className='w-full px-3 py-2 mb-3 text-xs rounded border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue'
            />
            <label className='block text-xs text-muted mb-1'>GitHub Owner / Username</label>
            <input
              type='text'
              value={ghOwner}
              onChange={(e) => setGhOwner(e.target.value)}
              placeholder='your-username'
              className='w-full px-3 py-2 mb-3 text-xs rounded border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue'
            />
            {ghError && <p className='text-xs text-accent-red mb-3'>{ghError}</p>}
            <div className='flex justify-end gap-2'>
              <button
                onClick={() => {
                  setShowGitHubModal(false);
                  setGhPat('');
                  setGhError('');
                }}
                className='px-3 py-1.5 text-xs text-muted hover:text-primary transition-colors'
              >
                Cancel
              </button>
              <button
                onClick={handleGitHubSaveAndPush}
                disabled={ghPushing || !ghPat.trim() || !ghOwner.trim()}
                className='px-3 py-1.5 text-xs bg-accent-blue text-white rounded hover:opacity-90 transition-opacity disabled:opacity-50'
              >
                {ghPushing ? 'Publishing…' : 'Save & Publish'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Archive Confirmation Modal */}
      {showArchiveConfirm && (
        <div
          className='fixed inset-0 z-50 flex items-center justify-center bg-black/50'
          onClick={() => setShowArchiveConfirm(false)}
        >
          <div
            className='bg-[var(--gray-100)] border border-[var(--border-default)] rounded-lg p-5 w-full max-w-sm shadow-xl'
            onClick={(e) => e.stopPropagation()}
          >
            {/* R-13 (UX audit, 2026-05-03): pre-fix copy was "permanent and
                cannot be undone" — read like a destructive deletion when
                archive is really "remove from active list." Softened to
                explain what archive actually does + the eventual purge. */}
            <div className='flex items-center gap-2 mb-3'>
              <AlertTriangle className='h-5 w-5 text-accent-amber' />
              <h3 className='text-sm font-semibold text-primary'>Archive this run?</h3>
            </div>
            <p className='text-xs text-secondary mb-1'>
              Archive removes this run from your active runs list. The run becomes read-only — you
              can still open it by URL or share its report.
            </p>
            <p className='text-xs text-muted mb-4'>
              Archived runs auto-purge after <strong className='text-secondary'>90 days</strong>.
            </p>
            <div className='flex justify-end gap-2'>
              <button
                onClick={() => setShowArchiveConfirm(false)}
                className='px-3 py-1.5 text-xs text-muted hover:text-primary transition-colors'
              >
                Cancel
              </button>
              <button
                onClick={async () => {
                  setArchiving(true);
                  try {
                    await archiveRun(activeRun!.runId);
                    addToast({type: 'success', message: 'Run archived'});
                    setShowArchiveConfirm(false);
                    setActiveRunId(null);
                  } catch (e: any) {
                    addToast({type: 'error', message: e?.message || 'Failed to archive'});
                  } finally {
                    setArchiving(false);
                  }
                }}
                disabled={archiving}
                className='px-3 py-1.5 text-xs bg-accent-amber/90 text-white rounded hover:opacity-90 transition-opacity disabled:opacity-50'
              >
                {archiving ? 'Archiving…' : 'Archive'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

// ── Mobile-friendly collapsible reports section ──────────────────────────

/**
 * Returns true when the reviewer's final-build passed. Used to show a green
 * "final build passed" chip on PARTIAL runs — reviewJson carries the truth
 * even when the per-task smoke-test aggregation reported PARTIAL.
 */
function finalBuildPassed(run: {reviewJson?: string | null}): boolean {
  if (!run.reviewJson) return false;
  try {
    const r = JSON.parse(run.reviewJson);
    return r?.finalBuildPassed === true || r?.finalBuildExitCode === 0;
  } catch {
    return false;
  }
}

interface MobileCollapsibleReportsProps {
  runId: string;
  status: string;
  statusReason?: string | null;
  stampMeta?: {
    runStartedAt?: string | null;
    runCompletedAt?: string | null;
    tasksTotal?: number | null;
    tasksComplete?: number | null;
    provider?: string | null;
  };
}

function MobileCollapsibleReports({
  runId,
  status,
  statusReason,
  stampMeta,
}: MobileCollapsibleReportsProps) {
  const isMobile = useIsMobile();
  const [collapsed, setCollapsed] = useState(true);

  // On desktop, always show reports inline (no collapsing)
  if (!isMobile) {
    return (
      <>
        <TestProgressTracker runId={runId} status={status} />
        <ValidationReportPanel
          runId={runId}
          status={status}
          statusReason={statusReason}
          stampMeta={stampMeta}
        />
        <TestReportPanel runId={runId} status={status} />
      </>
    );
  }

  return (
    <div className='flex-shrink-0 border-b border-[var(--border-default)]'>
      <button
        onClick={() => setCollapsed(!collapsed)}
        className='flex items-center gap-1.5 w-full px-4 py-1.5 text-xs text-muted hover:text-secondary transition-colors'
      >
        {collapsed ? <ChevronDown className='h-3 w-3' /> : <ChevronUp className='h-3 w-3' />}
        <span>Reports & Progress</span>
      </button>
      {!collapsed && (
        <div className='max-h-[30vh] overflow-y-auto'>
          <TestProgressTracker runId={runId} status={status} />
          <ValidationReportPanel
            runId={runId}
            status={status}
            statusReason={statusReason}
            stampMeta={stampMeta}
          />
          <TestReportPanel runId={runId} status={status} />
        </div>
      )}
    </div>
  );
}

interface GateBannerProps {
  message: string;
  icon: React.ReactNode;
  onApprove: () => void;
  onReject: () => void;
  approveLabel: string;
  rejectLabel: string;
  confirming: string | null;
  setConfirming: (v: string | null) => void;
}

// v2.5.12 — ClarificationBanner removed with the AWAITING_CLARIFICATION
// status. V2 never emits that status; the V1 clarify path returned 410
// Gone. Clean removal.

// ── Gate Banner ───────────────────────────────────────────────────────────

function GateBanner({
  message,
  icon,
  onApprove,
  onReject,
  approveLabel,
  rejectLabel,
  confirming,
  setConfirming,
}: GateBannerProps) {
  return (
    <div className='mx-4 mt-3 p-3 rounded-sm border border-accent-amber/30 bg-accent-amber/5 flex items-center justify-between gap-3'>
      <div className='flex items-center gap-2 min-w-0'>
        {icon}
        <p className='text-xs text-accent-amber truncate'>{message}</p>
      </div>
      <div className='flex items-center gap-2 flex-shrink-0'>
        {confirming === 'reject' ? (
          <>
            <span className='text-xs text-accent-red'>{rejectLabel}?</span>
            <button onClick={onReject} className='text-xs text-accent-red font-medium'>
              Yes
            </button>
            <button onClick={() => setConfirming(null)} className='text-xs text-muted'>
              No
            </button>
          </>
        ) : confirming === 'approve' ? (
          <>
            <span className='text-xs text-accent-green'>{approveLabel}?</span>
            <button onClick={onApprove} className='text-xs text-accent-green font-medium'>
              Yes
            </button>
            <button onClick={() => setConfirming(null)} className='text-xs text-muted'>
              No
            </button>
          </>
        ) : (
          <>
            <button
              onClick={() => setConfirming('reject')}
              className='px-2.5 py-1 rounded text-xs bg-accent-red/15 text-accent-red hover:bg-accent-red/25'
            >
              {rejectLabel}
            </button>
            <button
              onClick={() => setConfirming('approve')}
              className='px-2.5 py-1 rounded text-xs bg-accent-green/15 text-accent-green hover:bg-accent-green/25'
            >
              {approveLabel}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * P-15 / R-02 (UX audit, 2026-05-03) — collapsible plan preview rendered above
 * the AWAITING_APPROVAL gate banner. Five tasks shown by default; the rest
 * collapse behind "Show all N tasks" so the action bar never gets crowded.
 *
 * Rendering rule: top-level tasks only (excludes subtasks via parentTaskId
 * filter and gap tasks via taskId._gap_ check — same filter the run-detail
 * Tasks tab uses). Each row shows the task title only — no model / complexity
 * because the user's job here is "is this the work I want to pay for?", not
 * "what tier did the planner pick?".
 */
function PlanGatePreview({tasks}: {tasks: TaskSummary[]}) {
  const [expanded, setExpanded] = useState(false);
  if (!tasks.length) return null;
  const PREVIEW = 5;
  const shown = expanded ? tasks : tasks.slice(0, PREVIEW);
  const hasMore = tasks.length > PREVIEW;
  return (
    <div className='mx-4 mt-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] text-xs'>
      <div className='px-3 py-2 border-b border-[var(--border-default)] flex items-center justify-between'>
        <span className='font-medium text-secondary'>
          Plan — {tasks.length} task{tasks.length !== 1 ? 's' : ''}
        </span>
        {hasMore && (
          <button
            onClick={() => setExpanded(!expanded)}
            className='text-[11px] text-accent-blue hover:underline'
            aria-expanded={expanded}
          >
            {expanded ? 'Show less' : `Show all ${tasks.length}`}
          </button>
        )}
      </div>
      <ol className='divide-y divide-[var(--border-default)]'>
        {shown.map((t, i) => (
          <li key={t.taskId} className='px-3 py-1.5 flex items-start gap-2'>
            <span
              className='font-mono text-[10px] text-muted tabular-nums w-5 flex-shrink-0 pt-px'
              aria-hidden='true'
            >
              {i + 1}.
            </span>
            <span className='text-primary leading-tight'>{t.title}</span>
          </li>
        ))}
      </ol>
      {!expanded && hasMore && (
        <div className='px-3 py-1.5 text-[11px] text-muted italic border-t border-[var(--border-default)]'>
          + {tasks.length - PREVIEW} more task{tasks.length - PREVIEW !== 1 ? 's' : ''}
        </div>
      )}
    </div>
  );
}
