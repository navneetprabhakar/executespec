import { useState, useMemo, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, X, Trash2, AlertTriangle, Download, RotateCcw, Pause, Play, HelpCircle, Send, Loader2, ChevronDown, ChevronUp, Github, Archive } from 'lucide-react';
import { useIsMobile } from '@/hooks/useIsMobile';
import GlowDot from '@/components/shared/GlowDot';
import StatusBadge from '@/components/shared/StatusBadge';
import RunDetailTabs from './RunDetailTabs';
import ValidationReportPanel from './ValidationReportPanel';
import TestProgressTracker from './TestProgressTracker';
import TestReportPanel from './TestReportPanel';
import { RunTimeline } from '@/components/run/RunTimeline';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { useSseStore } from '@/stores/useSseStore';
import { useViewMode } from '@/hooks/useViewMode';
import { downloadWorkspace, editRunPlan, pushToGitHub, pushToScm, updateGithubSettings, archiveRun, retryTask, type ReviewReport } from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';
import { cn, describeRunStatus } from '@/lib/utils';
import VibeProgressNarrative from './VibeProgressNarrative';

export default function RunDetail() {
  const { activeRun, activeRunId, setActiveRunId, approveRun, clarifyRun, cancelRun, createRun, pauseRun, editAndRestartRun, resumeRun } = useRunStore();
  const { addToast, setActiveTab } = useUiStore();
  const { isVibe } = useViewMode();
  const isMobile = useIsMobile();
  const [confirming, setConfirming] = useState<string | null>(null);
  const [reRunning, setReRunning] = useState(false);
  const [pausing, setPausing] = useState(false);
  const [editingGoal, setEditingGoal] = useState(false);
  const [newGoal, setNewGoal] = useState('');
  const [restarting, setRestarting] = useState(false);
  const [resuming, setResuming] = useState(false);
  const [clarificationInput, setClarificationInput] = useState('');
  const [clarifying, setClarifying] = useState(false);
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

  // Show toast when context compaction is triggered
  const compactionShownRef = useRef<Set<string>>(new Set());
  const budgetShownRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    for (const entry of sseEntries) {
      if (entry.type === 'COMPACTION_TRIGGERED' && !compactionShownRef.current.has(entry.id)) {
        compactionShownRef.current.add(entry.id);
        const taskId = (entry.data?.taskId as string) || 'unknown';
        addToast({ type: 'info', message: `Context compacted for task ${taskId}` });
      }
      if (entry.type === 'BUDGET_EXCEEDED' && !budgetShownRef.current.has(entry.id)) {
        budgetShownRef.current.add(entry.id);
        const parts = ((entry.data?.payload as string) || '').split('|');
        const message = parts[2] || 'Run stopped — cost budget exceeded. Upgrade for higher limits.';
        addToast({ type: 'error', message });
      }
      if (entry.type === 'BUDGET_WARNING' && !budgetShownRef.current.has(entry.id)) {
        budgetShownRef.current.add(entry.id);
        const parts = ((entry.data?.payload as string) || '').split('|');
        const percent = parts[2] || '?';
        const message = parts[4] || `Cost warning: ${percent}% of budget used.`;
        addToast({ type: parseInt(percent) >= 90 ? 'error' : 'info', message });
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
    try { return JSON.parse(activeRun.reviewJson) as ReviewReport; }
    catch { return null; }
  }, [activeRun?.reviewJson]);

  const handle = async (action: () => Promise<void>, successMsg: string) => {
    try {
      await action();
      addToast({ type: 'success', message: successMsg });
      setConfirming(null);
    } catch (e: any) {
      // 409 = gate was lost after a server restart; the run has been auto-cancelled
      if (e.message?.startsWith('409')) {
        addToast({ type: 'error', message: 'Gate expired (server was restarted). Run has been cancelled — please resubmit.' });
        setConfirming(null);
        // Refresh so the UI shows the run as FAILED
        if (activeRunId) await useRunStore.getState().fetchRun(activeRunId);
        await useRunStore.getState().fetchRuns();
      } else {
        addToast({ type: 'error', message: e.message });
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
        addToast({ type: 'success', message: 'Publishing to Bitbucket...' });
      } catch (e: any) {
        addToast({ type: 'error', message: e.message || 'Bitbucket push failed' });
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
      addToast({ type: 'success', message: 'Publishing to GitHub...' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'GitHub push failed' });
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
      addToast({ type: 'success', message: 'Publishing to GitHub…' });
    } catch (e: any) {
      setGhError(e.message || 'Failed to save credentials or push');
    } finally {
      setGhPushing(false);
    }
  };

  const handleReRun = async () => {
    if (!activeRun) return;
    
    // Instead of launching directly, we open the NewRunModal with pre-filled config.
    // This allows the user to toggle Prototype Mode or change models before launching.
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
    addToast({ type: 'info', message: 'Re-run configuration loaded. Adjust and launch when ready.' });
  };

  const handlePause = async () => {
    if (!activeRun) return;
    setPausing(true);
    try {
      await pauseRun(activeRun.runId);
      addToast({ type: 'success', message: 'Run paused' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    } finally {
      setPausing(false);
    }
  };

  // Retry all FAILED/PARTIAL tasks within this run
  const failedTasks = useMemo(() => {
    if (!activeRun?.tasks) return [];
    return activeRun.tasks.filter((t: any) =>
      ['FAILED', 'PARTIAL', 'BLOCKED'].includes(t.status) && !t.parentTaskId
    );
  }, [activeRun?.tasks]);

  const handleRetryFailed = async () => {
    if (!activeRun || failedTasks.length === 0) return;
    setRetryingFailed(true);
    try {
      await Promise.all(
        failedTasks.map((t: any) => retryTask(activeRun.runId, t.taskId))
      );
      addToast({ type: 'success', message: `Retrying ${failedTasks.length} failed task(s)` });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'Retry failed' });
    } finally {
      setRetryingFailed(false);
    }
  };

  const handleEditAndRestart = async () => {
    if (!activeRun || !newGoal.trim()) return;
    setRestarting(true);
    try {
      const newRunId = await editAndRestartRun(activeRun.runId, newGoal.trim());
      addToast({ type: 'success', message: `Restarted with new goal: ${newRunId.slice(0, 8)}` });
      setEditingGoal(false);
      setNewGoal('');
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    } finally {
      setRestarting(false);
    }
  };

  const handleResume = async () => {
    if (!activeRun) return;
    setResuming(true);
    try {
      await resumeRun(activeRun.runId);
      addToast({ type: 'success', message: 'Run resumed — re-running incomplete tasks' });
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
    } finally {
      setResuming(false);
    }
  };

  return (
    <>
    <AnimatePresence>
      {activeRun && (
        <motion.div
          initial={{ x: '100%', opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          exit={{ x: '100%', opacity: 0 }}
          transition={{ type: 'spring', stiffness: 300, damping: 30 }}
          className="flex flex-col h-full bg-[var(--gray-100)] border-l border-[var(--border-default)]"
        >
          {/* ── Vibe Mode: simplified view ──────────────────────────── */}
          {isVibe ? (
            <>
              {/* Minimal header */}
              <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border-default)] flex-shrink-0">
                <div className="flex items-center gap-2.5 min-w-0">
                  <GlowDot status={activeRun.status} className="flex-shrink-0" />
                  <div className="min-w-0">
                    <p className="text-xs font-mono font-medium text-[var(--text-primary)] line-clamp-1">
                      {activeRun.runTitle ?? activeRun.userRequest?.slice(0, 60) ?? 'Run'}
                    </p>
                    <StatusBadge status={activeRun.status} />
                  </div>
                </div>
                <button onClick={() => setActiveRunId(null)} className="text-muted hover:text-secondary flex-shrink-0 ml-2 p-1.5 -m-1">
                  <X className="h-4 w-4" />
                </button>
              </div>

              {/* Narrative + summary */}
              <div className="max-h-48 overflow-y-auto flex-shrink-0 border-b border-[var(--border-default)]">
                <VibeProgressNarrative />
              </div>

              {/* Code + Preview tabs */}
              <div className="flex-1 overflow-hidden">
                <RunDetailTabs />
              </div>
            </>
          ) : (
          <>
          {/* Mobile back button */}
          <button
            onClick={() => setActiveRunId(null)}
            className="lg:hidden flex items-center gap-1.5 px-4 py-2.5 text-sm text-secondary hover:text-primary border-b border-[var(--border-default)] transition-colors"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to runs
          </button>

          {/* Header */}
          <div className="flex items-start justify-between px-4 py-3 border-b border-[var(--border-default)] flex-shrink-0">
            <div className="flex items-start gap-2.5 min-w-0">
              <GlowDot status={activeRun.status} className="mt-1 flex-shrink-0" />
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <p className="font-mono text-xs text-secondary">{activeRun.runId.slice(0, 16)}</p>
                  <StatusBadge status={activeRun.status} />
                </div>
                <p className="text-xs text-muted mt-0.5 line-clamp-1">
                  {describeRunStatus(activeRun.status, activeRun.tasks, activeRun.failureReason, activeRun.executionMode)}
                </p>
              </div>
            </div>
            <button onClick={() => setActiveRunId(null)} className="text-muted hover:text-secondary flex-shrink-0 ml-2 p-1.5 -m-1">
              <X className="h-4 w-4" />
            </button>
          </div>

          {/* Gate banner */}
          {activeRun.status === 'AWAITING_APPROVAL' && (
            <div className="flex-shrink-0">
              {/* Batch option cards — show when batch is available */}
              {activeRun.batchAvailable && (
                <div className="px-4 pt-3 pb-1">
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      onClick={() => handle(
                        () => approveRun(activeRun.runId, true, activeRun.creditsCharged ?? undefined, 'REALTIME'),
                        'Run approved (real-time)'
                      )}
                      className="rounded-lg border border-[var(--border-default)] bg-[var(--gray-100)] p-3 text-left hover:border-accent-blue/50 transition-colors"
                    >
                      <div className="text-xs font-semibold text-primary">Real-time</div>
                      <div className="text-xs text-muted mt-1">
                        {activeRun.creditsCharged ?? 1} credit{(activeRun.creditsCharged ?? 1) > 1 ? 's' : ''}
                      </div>
                      <div className="text-[10px] text-muted mt-0.5">Results in minutes</div>
                    </button>
                    <button
                      onClick={() => handle(
                        () => approveRun(activeRun.runId, true, activeRun.batchCredits ?? undefined, 'BATCH'),
                        'Run approved (batch)'
                      )}
                      className="rounded-lg border border-accent-emerald/30 bg-accent-emerald/5 p-3 text-left hover:border-accent-emerald/50 transition-colors"
                    >
                      <div className="text-xs font-semibold text-primary flex items-center gap-1.5">
                        Batch
                        <span className="text-[10px] font-medium text-accent-emerald bg-accent-emerald/10 px-1.5 py-0.5 rounded-full">
                          Save {activeRun.creditSavings ?? 0}
                        </span>
                      </div>
                      <div className="text-xs text-muted mt-1">
                        {activeRun.batchCredits ?? 1} credit{(activeRun.batchCredits ?? 1) > 1 ? 's' : ''}
                      </div>
                      <div className="text-[10px] text-muted mt-0.5">Results in ~4 hours</div>
                    </button>
                  </div>
                  <button
                    onClick={() => handle(() => approveRun(activeRun.runId, false), 'Run rejected')}
                    className="mt-2 text-xs text-muted hover:text-red-400 w-full text-center"
                  >
                    Reject Plan
                  </button>
                </div>
              )}
              {/* Standard gate banner — shown when batch not available */}
              {!activeRun.batchAvailable && (
              <GateBanner
                message={
                  activeRun.creditsCharged && activeRun.creditsCharged > 1
                    ? `This spec will use ${activeRun.creditsCharged} credits (~$${activeRun.costEstimateHigh?.toFixed(2) ?? '?'})`
                    : 'Review the plan and approve to continue'
                }
                icon={<AlertTriangle className="h-4 w-4 text-accent-amber" />}
                onApprove={() => handle(
                  () => approveRun(activeRun.runId, true, activeRun.creditsCharged ?? undefined, 'REALTIME'),
                  'Run approved'
                )}
                onReject={() => handle(() => approveRun(activeRun.runId, false), 'Run rejected')}
                confirming={confirming}
                setConfirming={setConfirming}
                approveLabel={
                  activeRun.creditsCharged && activeRun.creditsCharged > 1
                    ? `Approve (${activeRun.creditsCharged} credits)`
                    : 'Approve Plan'
                }
                rejectLabel="Reject"
              />
              )}
              {/* Edit Plan feedback area */}
              <div className="px-4 pb-3">
                {!editingPlan ? (
                  <button
                    onClick={() => setEditingPlan(true)}
                    className="text-xs text-secondary hover:text-primary underline underline-offset-2"
                  >
                    Edit Plan
                  </button>
                ) : (
                  <div className="space-y-2 mt-1">
                    <textarea
                      value={planFeedback}
                      onChange={(e) => setPlanFeedback(e.target.value)}
                      placeholder="Describe what to change in the plan..."
                      rows={3}
                      className="w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2 text-sm text-primary placeholder-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-accent-blue/50 resize-none"
                    />
                    <div className="flex gap-2">
                      <button
                        disabled={submittingPlanEdit || !planFeedback.trim()}
                        onClick={async () => {
                          setSubmittingPlanEdit(true);
                          try {
                            await editRunPlan(activeRun.runId, planFeedback.trim());
                            setPlanFeedback('');
                            setEditingPlan(false);
                            addToast({ type: 'success', message: 'Plan updated — review the new tasks' });
                            if (activeRunId) await useRunStore.getState().fetchRun(activeRunId);
                          } catch (e: any) {
                            addToast({ type: 'error', message: e.message });
                          } finally {
                            setSubmittingPlanEdit(false);
                          }
                        }}
                        className="px-3 py-1.5 text-xs font-medium rounded-md bg-accent-blue/20 text-accent-blue hover:bg-accent-blue/30 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5"
                      >
                        {submittingPlanEdit && <Loader2 className="h-3 w-3 animate-spin" />}
                        Submit Feedback
                      </button>
                      <button
                        onClick={() => { setEditingPlan(false); setPlanFeedback(''); }}
                        className="px-3 py-1.5 text-xs font-medium rounded-md text-secondary hover:text-primary"
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}
          {activeRun.status === 'AWAITING_CLARIFICATION' && (
            <div className="flex-shrink-0">
            <ClarificationBanner
              runId={activeRun.runId}
              input={clarificationInput}
              setInput={setClarificationInput}
              submitting={clarifying}
              onSubmit={async () => {
                if (!clarificationInput.trim()) return;
                setClarifying(true);
                try {
                  await clarifyRun(activeRun.runId, clarificationInput.trim());
                  setClarificationInput('');
                  addToast({ type: 'success', message: 'Clarification submitted' });
                } catch (e: any) {
                  addToast({ type: 'error', message: e.message });
                } finally {
                  setClarifying(false);
                }
              }}
              onSkip={async () => {
                setClarifying(true);
                try {
                  await clarifyRun(activeRun.runId, '');
                  addToast({ type: 'info', message: 'Continuing without clarification' });
                } catch (e: any) {
                  addToast({ type: 'error', message: e.message });
                } finally {
                  setClarifying(false);
                }
              }}
            />
            </div>
          )}
          {/* Batch progress indicator */}
          {(activeRun.status === 'IN_PROGRESS_BATCH' || activeRun.status === 'QUEUED_BATCH') && (
            <div className="mx-4 mt-3 p-3 rounded-sm border border-accent-blue/20 bg-accent-blue/5 flex-shrink-0">
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-xs font-semibold text-primary">
                  {activeRun.status === 'QUEUED_BATCH' ? 'Batch queued' : 'Batch in progress'}
                </span>
                {activeRun.batchProgressTotal != null && activeRun.batchProgressTotal > 0 && (
                  <span className="text-[10px] text-muted">
                    {activeRun.batchProgressCompleted ?? 0} / {activeRun.batchProgressTotal} tasks
                  </span>
                )}
              </div>
              <div className="h-1.5 rounded-full bg-[var(--gray-300)] overflow-hidden">
                <div
                  className="h-full rounded-full bg-accent-blue transition-all duration-500"
                  style={{
                    width: activeRun.batchProgressTotal
                      ? `${Math.round(((activeRun.batchProgressCompleted ?? 0) / activeRun.batchProgressTotal) * 100)}%`
                      : '0%',
                  }}
                />
              </div>
              <div className="text-[10px] text-muted mt-1.5">
                Batch runs typically complete in 1-4 hours. You'll be notified when done.
              </div>
            </div>
          )}

          {/* Review summary shown on COMPLETE runs */}
          {activeRun.status === 'COMPLETE' && review && (
            <div className="mx-4 mt-3 p-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-xs flex-shrink-0">
              <div className="flex items-center gap-2 mb-1">
                <span className={cn(
                  'font-semibold',
                  (review.recommendationCompat ?? review.recommendation) === 'MERGE' ? 'text-accent-green' :
                  (review.recommendationCompat ?? review.recommendation) === 'PARTIAL_MERGE' ? 'text-accent-amber' : 'text-accent-red'
                )}>
                  {(review.recommendationCompat ?? review.recommendation ?? 'MERGE').replace('_', ' ')}
                </span>
                <span className="text-muted">·</span>
                <span className={cn(
                  'text-secondary',
                  review.overallConfidence === 'HIGH' ? 'text-accent-green/80' :
                  review.overallConfidence === 'MEDIUM' ? 'text-accent-amber/80' : 'text-accent-red/80'
                )}>
                  {review.overallConfidence} confidence
                </span>
              </div>
              <p className="text-secondary leading-relaxed">{review.summary}</p>
            </div>
          )}
          {/* Failure reason banner */}
          {activeRun.status === 'FAILED' && activeRun.failureReason && (
            <div className="mx-4 mt-3 p-3 rounded-sm border border-accent-red/30 bg-accent-red/5 flex-shrink-0">
              <div className="flex items-center gap-2 mb-1">
                <AlertTriangle className="h-4 w-4 text-accent-red flex-shrink-0" />
                <p className="text-xs font-medium text-accent-red">Run failed</p>
              </div>
              <p className="text-xs text-secondary leading-relaxed">{activeRun.failureReason}</p>
            </div>
          )}
          {/* Paused banner — edit goal and restart */}
          {activeRun.status === 'PAUSED' && (
            <div className="mx-4 mt-3 p-3 rounded-sm border border-accent-amber/30 bg-accent-amber/5 space-y-2 flex-shrink-0">
              <div className="flex items-center gap-2">
                <Pause className="h-4 w-4 text-accent-amber flex-shrink-0" />
                <p className="text-xs font-medium text-accent-amber">Run paused</p>
              </div>
              <p className="text-xs text-secondary leading-relaxed">
                <span className="text-muted">Current goal:</span> {activeRun.userRequest}
              </p>
              {!editingGoal ? (
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleResume}
                    disabled={resuming}
                    className="flex items-center gap-1.5 rounded-md bg-accent-blue/20 hover:bg-accent-blue/30 border border-accent-blue/30 px-3 py-1.5 text-xs font-medium text-accent-blue transition-colors disabled:opacity-50"
                  >
                    <Play className="h-3 w-3" />
                    {resuming ? 'Resuming…' : 'Resume'}
                  </button>
                  <button
                    onClick={() => { setEditingGoal(true); setNewGoal(activeRun.userRequest ?? ''); }}
                    className="flex items-center gap-1.5 rounded-md bg-accent-amber/20 hover:bg-accent-amber/30 border border-accent-amber/30 px-3 py-1.5 text-xs font-medium text-accent-amber transition-colors"
                  >
                    Edit Goal & Restart
                  </button>
                </div>
              ) : (
                <div className="space-y-2">
                  <textarea
                    value={newGoal}
                    onChange={(e) => setNewGoal(e.target.value)}
                    rows={3}
                    className="w-full rounded-md bg-[var(--gray-100)] border border-[var(--border-default)] text-xs text-primary p-2 focus:outline-none focus:border-accent-amber/50 resize-none"
                    placeholder="Enter updated goal..."
                  />
                  <div className="flex items-center gap-2">
                    <button
                      onClick={handleEditAndRestart}
                      disabled={restarting || !newGoal.trim()}
                      className="flex items-center gap-1.5 rounded-md bg-accent-amber hover:bg-accent-amber/80 px-3 py-1.5 text-xs font-medium text-primary transition-colors disabled:opacity-50"
                    >
                      <Play className="h-3 w-3" />
                      {restarting ? 'Restarting…' : 'Restart with New Goal'}
                    </button>
                    <button
                      onClick={() => { setEditingGoal(false); setNewGoal(''); }}
                      className="text-xs text-muted hover:text-secondary transition-colors"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Run Timeline */}
          <div className="px-4 border-b border-[var(--border-default)] flex-shrink-0">
            <RunTimeline
              events={sseEntries.map(e => ({
                type: e.type,
                taskId: (e.data?.taskId as string) || undefined,
                payload: (e.data?.payload as string) || undefined,
                timestamp: new Date(e.timestamp).toISOString(),
              }))}
              status={activeRun.status}
              taskCount={activeRun.tasks?.length || 0}
              completedTaskCount={activeRun.tasks?.filter(t => t.status === 'COMPLETE').length || 0}
            />
          </div>

          {/* Action bar */}
          <div className="flex items-center gap-1 lg:gap-2 px-3 lg:px-4 py-2 flex-shrink-0 overflow-x-auto scrollbar-hide border-b border-[var(--border-default)]">
            {!['COMPLETE', 'FAILED', 'CANCELLED', 'PAUSED'].includes(activeRun.status) && (
              <>
                <button
                  onClick={handlePause}
                  disabled={pausing}
                  className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-amber transition-colors disabled:opacity-50 whitespace-nowrap"
                >
                  <Pause className="h-3.5 w-3.5" />
                  {pausing ? 'Pausing…' : 'Pause'}
                </button>
                <button
                  onClick={() => handle(() => cancelRun(activeRun.runId), 'Run cancelled')}
                  className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-red transition-colors whitespace-nowrap"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  Cancel
                </button>
              </>
            )}
            {['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL', 'PAUSED'].includes(activeRun.status) && (
              <>
                <button
                  onClick={handleReRun}
                  disabled={reRunning}
                  className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-blue transition-colors disabled:opacity-50 whitespace-nowrap"
                >
                  <RotateCcw className={cn('h-3.5 w-3.5', reRunning && 'animate-spin')} />
                  {reRunning ? 'Launching…' : 'Re-run'}
                </button>
                {failedTasks.length > 0 && (
                  <button
                    onClick={handleRetryFailed}
                    disabled={retryingFailed}
                    className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-amber transition-colors disabled:opacity-50 whitespace-nowrap"
                    title={`Retry ${failedTasks.length} failed task(s)`}
                  >
                    <Play className={cn('h-3.5 w-3.5', retryingFailed && 'animate-pulse')} />
                    {retryingFailed ? 'Retrying…' : `Retry Failed (${failedTasks.length})`}
                  </button>
                )}
              </>
            )}
            <div className="flex items-center gap-2 ml-auto">
              {['COMPLETE', 'PARTIAL'].includes(activeRun.status) && (
                <button
                  onClick={handleScmPush}
                  disabled={ghPushing}
                  className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-blue transition-colors disabled:opacity-50 whitespace-nowrap"
                  title={`Publish to ${scmLabel}`}
                >
                  <Github className="h-3.5 w-3.5" />
                  {ghPushing ? 'Publishing...' : scmLabel}
                </button>
              )}
              <button
                onClick={() => handle(() => downloadWorkspace(activeRun.runId), 'Download started')}
                className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-green transition-colors whitespace-nowrap"
                title="Download workspace as ZIP"
              >
                <Download className="h-3.5 w-3.5" />
                Download
              </button>
              {['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(activeRun.status) && !activeRun.archivedAt && (
                <button
                  onClick={() => setShowArchiveConfirm(true)}
                  className="flex items-center gap-1.5 px-2.5 py-2 min-h-[36px] text-xs text-muted hover:text-accent-red transition-colors whitespace-nowrap"
                  title="Archive this run"
                >
                  <Archive className="h-3.5 w-3.5" />
                  Archive
                </button>
              )}
              {activeRun.archivedAt && (
                <span className="flex items-center gap-1.5 px-2.5 py-2 text-xs text-muted whitespace-nowrap" title={`Archived on ${new Date(activeRun.archivedAt).toLocaleDateString()}`}>
                  <Archive className="h-3.5 w-3.5" />
                  Archived
                </span>
              )}
            </div>
          </div>

          {/* Reports + Tabs — single scrollable region */}
          <div className="flex-1 flex flex-col min-h-0 overflow-y-auto">
            <div className="flex-shrink-0">
              <MobileCollapsibleReports runId={activeRun.runId} status={activeRun.status} />
            </div>
            <div className="flex-1 min-h-0">
              <RunDetailTabs />
            </div>
          </div>
          </>
          )}
        </motion.div>
      )}
    </AnimatePresence>

    {/* GitHub PAT Modal */}
    {showGitHubModal && (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setShowGitHubModal(false)}>
        <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-lg p-5 w-full max-w-sm shadow-xl" onClick={(e) => e.stopPropagation()}>
          <div className="flex items-center gap-2 mb-4">
            <Github className="h-5 w-5 text-primary" />
            <h3 className="text-sm font-semibold text-primary">Connect GitHub</h3>
          </div>
          <p className="text-xs text-muted mb-3">Enter your GitHub Personal Access Token and username to publish this workspace.</p>
          <label className="block text-xs text-muted mb-1">Personal Access Token</label>
          <input
            type="password"
            value={ghPat}
            onChange={(e) => setGhPat(e.target.value)}
            placeholder="ghp_..."
            className="w-full px-3 py-2 mb-3 text-xs rounded border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue"
          />
          <label className="block text-xs text-muted mb-1">GitHub Owner / Username</label>
          <input
            type="text"
            value={ghOwner}
            onChange={(e) => setGhOwner(e.target.value)}
            placeholder="your-username"
            className="w-full px-3 py-2 mb-3 text-xs rounded border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted focus:outline-none focus:border-accent-blue"
          />
          {ghError && <p className="text-xs text-accent-red mb-3">{ghError}</p>}
          <div className="flex justify-end gap-2">
            <button
              onClick={() => { setShowGitHubModal(false); setGhPat(''); setGhError(''); }}
              className="px-3 py-1.5 text-xs text-muted hover:text-primary transition-colors"
            >Cancel</button>
            <button
              onClick={handleGitHubSaveAndPush}
              disabled={ghPushing || !ghPat.trim() || !ghOwner.trim()}
              className="px-3 py-1.5 text-xs bg-accent-blue text-white rounded hover:opacity-90 transition-opacity disabled:opacity-50"
            >{ghPushing ? 'Publishing…' : 'Save & Publish'}</button>
          </div>
        </div>
      </div>
    )}

    {/* Archive Confirmation Modal */}
    {showArchiveConfirm && (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setShowArchiveConfirm(false)}>
        <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-lg p-5 w-full max-w-sm shadow-xl" onClick={(e) => e.stopPropagation()}>
          <div className="flex items-center gap-2 mb-3">
            <AlertTriangle className="h-5 w-5 text-accent-red" />
            <h3 className="text-sm font-semibold text-primary">Archive this run?</h3>
          </div>
          <p className="text-xs text-secondary mb-1">This action is <strong className="text-primary">permanent and cannot be undone</strong>.</p>
          <p className="text-xs text-muted mb-4">Archived runs will be permanently deleted after <strong className="text-secondary">90 days</strong>.</p>
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setShowArchiveConfirm(false)}
              className="px-3 py-1.5 text-xs text-muted hover:text-primary transition-colors"
            >Cancel</button>
            <button
              onClick={async () => {
                setArchiving(true);
                try {
                  await archiveRun(activeRun!.runId);
                  addToast({ type: 'success', message: 'Run archived' });
                  setShowArchiveConfirm(false);
                  setActiveRunId(null);
                } catch (e: any) {
                  addToast({ type: 'error', message: e?.message || 'Failed to archive' });
                } finally {
                  setArchiving(false);
                }
              }}
              disabled={archiving}
              className="px-3 py-1.5 text-xs bg-accent-red text-white rounded hover:opacity-90 transition-opacity disabled:opacity-50"
            >{archiving ? 'Archiving…' : 'Archive'}</button>
          </div>
        </div>
      </div>
    )}
    </>
  );
}

// ── Mobile-friendly collapsible reports section ──────────────────────────

function MobileCollapsibleReports({ runId, status }: { runId: string; status: string }) {
  const isMobile = useIsMobile();
  const [collapsed, setCollapsed] = useState(true);

  // On desktop, always show reports inline (no collapsing)
  if (!isMobile) {
    return (
      <>
        <TestProgressTracker runId={runId} status={status} />
        <ValidationReportPanel runId={runId} status={status} />
        <TestReportPanel runId={runId} status={status} />
      </>
    );
  }

  return (
    <div className="flex-shrink-0 border-b border-[var(--border-default)]">
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="flex items-center gap-1.5 w-full px-4 py-1.5 text-xs text-muted hover:text-secondary transition-colors"
      >
        {collapsed ? <ChevronDown className="h-3 w-3" /> : <ChevronUp className="h-3 w-3" />}
        <span>Reports & Progress</span>
      </button>
      {!collapsed && (
        <div className="max-h-[30vh] overflow-y-auto">
          <TestProgressTracker runId={runId} status={status} />
          <ValidationReportPanel runId={runId} status={status} />
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

// ── Clarification Banner ──────────────────────────────────────────────────

interface ClarificationBannerProps {
  runId: string;
  input: string;
  setInput: (v: string) => void;
  submitting: boolean;
  onSubmit: () => void;
  onSkip: () => void;
}

function ClarificationBanner({ runId, input, setInput, submitting, onSubmit, onSkip }: ClarificationBannerProps) {
  return (
    <div className="mx-4 mt-3 p-3 rounded-sm border border-accent-blue/30 bg-accent-blue/5 space-y-2">
      <div className="flex items-center gap-2">
        <HelpCircle className="h-4 w-4 text-accent-blue flex-shrink-0" />
        <p className="text-xs text-accent-blue">
          Please provide the specific JIRA issue key (e.g., PROJ-123) or Confluence page URL to continue.
        </p>
      </div>
      <div className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && input.trim()) onSubmit(); }}
          placeholder="e.g., PROJ-123 or https://yourorg.atlassian.net/wiki/..."
          className="flex-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-1.5 text-xs text-primary placeholder-[var(--text-muted)] focus:border-accent-blue focus:outline-none"
          disabled={submitting}
        />
        <button
          onClick={onSubmit}
          disabled={submitting || !input.trim()}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-sm bg-accent-blue text-primary text-xs font-medium hover:bg-accent-blue/80 disabled:opacity-50 transition-colors"
        >
          {submitting ? <Loader2 className="h-3 w-3 animate-spin" /> : <Send className="h-3 w-3" />}
          Submit
        </button>
        <button
          onClick={onSkip}
          disabled={submitting}
          className="px-3 py-1.5 rounded-sm border border-[var(--border-default)] text-secondary hover:text-secondary text-xs transition-colors disabled:opacity-50"
        >
          Skip
        </button>
      </div>
    </div>
  );
}

// ── Gate Banner ───────────────────────────────────────────────────────────

function GateBanner({ message, icon, onApprove, onReject, approveLabel, rejectLabel, confirming, setConfirming }: GateBannerProps) {
  return (
    <div className="mx-4 mt-3 p-3 rounded-sm border border-accent-amber/30 bg-accent-amber/5 flex items-center justify-between gap-3">
      <div className="flex items-center gap-2 min-w-0">
        {icon}
        <p className="text-xs text-accent-amber truncate">{message}</p>
      </div>
      <div className="flex items-center gap-2 flex-shrink-0">
        {confirming === 'reject' ? (
          <>
            <span className="text-xs text-accent-red">{rejectLabel}?</span>
            <button onClick={onReject} className="text-xs text-accent-red font-medium">Yes</button>
            <button onClick={() => setConfirming(null)} className="text-xs text-muted">No</button>
          </>
        ) : confirming === 'approve' ? (
          <>
            <span className="text-xs text-accent-green">{approveLabel}?</span>
            <button onClick={onApprove} className="text-xs text-accent-green font-medium">Yes</button>
            <button onClick={() => setConfirming(null)} className="text-xs text-muted">No</button>
          </>
        ) : (
          <>
            <button
              onClick={() => setConfirming('reject')}
              className="px-2.5 py-1 rounded text-xs bg-accent-red/15 text-accent-red hover:bg-accent-red/25"
            >
              {rejectLabel}
            </button>
            <button
              onClick={() => setConfirming('approve')}
              className="px-2.5 py-1 rounded text-xs bg-accent-green/15 text-accent-green hover:bg-accent-green/25"
            >
              {approveLabel}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
