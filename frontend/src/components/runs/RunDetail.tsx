import { useState, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { X, GitMerge, Trash2, RefreshCw, AlertTriangle, Download, Wand2 } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import StatusBadge from '@/components/shared/StatusBadge';
import RunDetailTabs from './RunDetailTabs';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { downloadWorkspace, type ReviewReport } from '@/lib/api';
import { cn } from '@/lib/utils';

export default function RunDetail() {
  const { activeRun, activeRunId, setActiveRunId, approveRun, mergeRun, cancelRun, reopenMergeGate, implementSuggestions } = useRunStore();
  const { addToast, setActiveTab } = useUiStore();
  const [confirming, setConfirming] = useState<string | null>(null);

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

  return (
    <AnimatePresence>
      {activeRun && (
        <motion.div
          initial={{ x: '100%', opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          exit={{ x: '100%', opacity: 0 }}
          transition={{ type: 'spring', stiffness: 300, damping: 30 }}
          className="flex flex-col h-full bg-surface-1 border-l border-white/[0.06]"
        >
          {/* Header */}
          <div className="flex items-start justify-between px-4 py-3 border-b border-white/[0.06] flex-shrink-0">
            <div className="flex items-start gap-2.5 min-w-0">
              <GlowDot status={activeRun.status} className="mt-1 flex-shrink-0" />
              <div className="min-w-0">
                <p className="font-mono text-xs text-zinc-400">{activeRun.runId.slice(0, 16)}</p>
                <div className="flex items-center gap-2 mt-0.5">
                  <StatusBadge status={activeRun.status} />
                </div>
              </div>
            </div>
            <button onClick={() => setActiveRunId(null)} className="text-zinc-500 hover:text-zinc-300 flex-shrink-0 ml-2">
              <X className="h-4 w-4" />
            </button>
          </div>

          {/* Gate banner */}
          {activeRun.status === 'AWAITING_APPROVAL' && (
            <GateBanner
              message="Review the plan and approve to continue"
              icon={<AlertTriangle className="h-4 w-4 text-accent-amber" />}
              onApprove={() => handle(() => approveRun(activeRun.runId, true), 'Run approved')}
              onReject={() => handle(() => approveRun(activeRun.runId, false), 'Run rejected')}
              confirming={confirming}
              setConfirming={setConfirming}
              approveLabel="Approve Plan"
              rejectLabel="Reject"
            />
          )}
          {activeRun.status === 'AWAITING_MERGE' && (
            <div className="mx-4 mt-3 space-y-2">
              {/* Reviewer summary card */}
              {review && (
                <div className="p-3 rounded-lg border border-zinc-700 bg-zinc-800/60 text-xs">
                  <div className="flex items-center gap-2 mb-1">
                    <span className={cn(
                      'font-semibold',
                      review.recommendation === 'MERGE' ? 'text-accent-emerald' :
                      review.recommendation === 'PARTIAL_MERGE' ? 'text-accent-amber' : 'text-accent-rose'
                    )}>
                      {review.recommendation.replace('_', ' ')}
                    </span>
                    <span className="text-zinc-500">·</span>
                    <span className={cn(
                      'text-zinc-400',
                      review.overallConfidence === 'HIGH' ? 'text-accent-emerald/80' :
                      review.overallConfidence === 'MEDIUM' ? 'text-accent-amber/80' : 'text-accent-rose/80'
                    )}>
                      {review.overallConfidence} confidence
                    </span>
                  </div>
                  <p className="text-zinc-400 leading-relaxed">{review.summary}</p>
                </div>
              )}

              {/* Merge blockers */}
              {review && review.mergeBlockers && review.mergeBlockers.length > 0 && (
                <div className="p-3 rounded-lg border border-accent-rose/30 bg-accent-rose/5">
                  <p className="text-xs font-medium text-accent-rose mb-1.5">⛔ Merge Blockers</p>
                  <ul className="text-xs text-accent-rose/80 space-y-0.5 list-disc list-inside">
                    {review.mergeBlockers.map((b, i) => <li key={i}>{b}</li>)}
                  </ul>
                </div>
              )}

              {/* Post-merge suggestions + Implement Suggestions button */}
              {review && review.postMergeSuggestions && review.postMergeSuggestions.length > 0 && (
                <div className="p-3 rounded-lg border border-accent-amber/30 bg-accent-amber/5">
                  <div className="flex items-center justify-between gap-2 mb-1.5">
                    <p className="text-xs font-medium text-accent-amber">
                      💡 {review.postMergeSuggestions.length} Suggestion{review.postMergeSuggestions.length !== 1 ? 's' : ''}
                    </p>
                    <button
                      onClick={() => handle(() => implementSuggestions(activeRun.runId), 'Implementing suggestions…')}
                      className="flex items-center gap-1.5 flex-shrink-0 rounded-md bg-accent-amber/20 hover:bg-accent-amber/30 border border-accent-amber/30 px-2.5 py-1 text-xs font-medium text-accent-amber transition-colors"
                    >
                      <Wand2 className="h-3 w-3" />
                      Implement
                    </button>
                  </div>
                  <ul className="text-xs text-accent-amber/80 space-y-0.5 list-disc list-inside">
                    {review.postMergeSuggestions.map((s, i) => <li key={i}>{s}</li>)}
                  </ul>
                </div>
              )}

              {/* Approve / Decline gate buttons */}
              <GateBanner
                message="Review changes and merge to complete"
                icon={<GitMerge className="h-4 w-4 text-accent-amber" />}
                onApprove={() => handle(() => mergeRun(activeRun.runId, true), 'Changes merged')}
                onReject={() => handle(() => mergeRun(activeRun.runId, false), 'Merge declined — run preserved as PARTIAL')}
                confirming={confirming}
                setConfirming={setConfirming}
                approveLabel="Merge"
                rejectLabel="Decline"
              />
            </div>
          )}
          {activeRun.status === 'PARTIAL' &&
           activeRun.tasks?.filter(t => t.taskId !== 'generate_preview').every(t => t.status === 'COMPLETE') && (
            <div className="mx-4 mt-3 p-3 rounded-lg border border-indigo-500/30 bg-indigo-500/5 flex items-center justify-between gap-3">
              <div className="flex items-center gap-2 min-w-0">
                <GitMerge className="h-4 w-4 text-indigo-400 flex-shrink-0" />
                <p className="text-xs text-indigo-300 truncate">All tasks complete — ready to merge</p>
              </div>
              <button
                onClick={() => handle(() => reopenMergeGate(activeRun.runId), 'Merge gate reopened')}
                className="flex-shrink-0 rounded-md bg-indigo-600/80 hover:bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white transition-colors"
              >
                Reopen Merge Gate
              </button>
            </div>
          )}
          {/* Retry Implementation banner — shown when suggestions impl failed */}
          {activeRun.status === 'PARTIAL' &&
           activeRun.failureReason?.includes('Suggestions implementation failed') && (
            <div className="mx-4 mt-3 p-3 rounded-lg border border-accent-amber/30 bg-accent-amber/5 flex items-center justify-between gap-3">
              <div className="flex items-center gap-2 min-w-0">
                <Wand2 className="h-4 w-4 text-accent-amber flex-shrink-0" />
                <div className="min-w-0">
                  <p className="text-xs font-medium text-accent-amber">Suggestions could not be applied</p>
                  <p className="text-xs text-accent-amber/70 truncate">{activeRun.failureReason}</p>
                </div>
              </div>
              <button
                onClick={() => handle(() => implementSuggestions(activeRun.runId), 'Retrying suggestions…')}
                className="flex-shrink-0 flex items-center gap-1.5 rounded-md bg-accent-amber/20 hover:bg-accent-amber/30 border border-accent-amber/30 px-3 py-1.5 text-xs font-medium text-accent-amber transition-colors"
              >
                <Wand2 className="h-3 w-3" />
                Retry
              </button>
            </div>
          )}

          {/* Action bar */}
          <div className="flex items-center gap-2 px-4 py-2 border-b border-white/[0.06] flex-shrink-0">
            {!['COMPLETE', 'FAILED', 'CANCELLED'].includes(activeRun.status) && (
              <button
                onClick={() => handle(() => cancelRun(activeRun.runId), 'Run cancelled')}
                className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-accent-rose transition-colors"
              >
                <Trash2 className="h-3.5 w-3.5" />
                Cancel
              </button>
            )}
            <div className="flex items-center gap-3 ml-auto">
              <button
                onClick={() => downloadWorkspace(activeRun.runId)}
                className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-accent-emerald transition-colors"
                title="Download workspace as zip"
              >
                <Download className="h-3.5 w-3.5" />
                Download
              </button>
              <button
                onClick={() => { setActiveTab('log'); }}
                className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-accent-cyan transition-colors"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                Live Log
              </button>
            </div>
          </div>

          {/* Tabs */}
          <div className="flex-1 overflow-hidden">
            <RunDetailTabs />
          </div>
        </motion.div>
      )}
    </AnimatePresence>
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

function GateBanner({ message, icon, onApprove, onReject, approveLabel, rejectLabel, confirming, setConfirming }: GateBannerProps) {
  return (
    <div className="mx-4 mt-3 p-3 rounded-lg border border-accent-amber/30 bg-accent-amber/5 flex items-center justify-between gap-3">
      <div className="flex items-center gap-2 min-w-0">
        {icon}
        <p className="text-xs text-accent-amber truncate">{message}</p>
      </div>
      <div className="flex items-center gap-2 flex-shrink-0">
        {confirming === 'reject' ? (
          <>
            <span className="text-xs text-zinc-400">Confirm?</span>
            <button onClick={onReject} className="text-xs text-accent-rose font-medium">Yes</button>
            <button onClick={() => setConfirming(null)} className="text-xs text-zinc-500">No</button>
          </>
        ) : confirming === 'approve' ? (
          <>
            <span className="text-xs text-zinc-400">Confirm?</span>
            <button onClick={onApprove} className="text-xs text-accent-emerald font-medium">Yes</button>
            <button onClick={() => setConfirming(null)} className="text-xs text-zinc-500">No</button>
          </>
        ) : (
          <>
            <button
              onClick={() => setConfirming('reject')}
              className="px-2.5 py-1 rounded text-xs bg-accent-rose/15 text-accent-rose hover:bg-accent-rose/25"
            >
              {rejectLabel}
            </button>
            <button
              onClick={() => setConfirming('approve')}
              className="px-2.5 py-1 rounded text-xs bg-accent-emerald/15 text-accent-emerald hover:bg-accent-emerald/25"
            >
              {approveLabel}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
