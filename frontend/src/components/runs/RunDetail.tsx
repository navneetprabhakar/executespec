import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { X, CheckCircle, XCircle, GitMerge, Trash2, RefreshCw, AlertTriangle } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import StatusBadge from '@/components/shared/StatusBadge';
import RunDetailTabs from './RunDetailTabs';
import { useRunStore } from '@/stores/useRunStore';
import { useUiStore } from '@/stores/useUiStore';
import { cn } from '@/lib/utils';

export default function RunDetail() {
  const { activeRun, activeRunId, setActiveRunId, approveRun, mergeRun, cancelRun } = useRunStore();
  const { addToast, setActiveTab } = useUiStore();
  const [confirming, setConfirming] = useState<string | null>(null);

  const handle = async (action: () => Promise<void>, successMsg: string) => {
    try {
      await action();
      addToast({ type: 'success', message: successMsg });
      setConfirming(null);
    } catch (e: any) {
      addToast({ type: 'error', message: e.message });
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
            <GateBanner
              message="Review changes and merge to complete"
              icon={<GitMerge className="h-4 w-4 text-accent-amber" />}
              onApprove={() => handle(() => mergeRun(activeRun.runId, true), 'Changes merged')}
              onReject={() => handle(() => mergeRun(activeRun.runId, false), 'Merge rejected')}
              confirming={confirming}
              setConfirming={setConfirming}
              approveLabel="Merge"
              rejectLabel="Reject"
            />
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
            <button
              onClick={() => { setActiveTab('log'); }}
              className="flex items-center gap-1.5 text-xs text-zinc-500 hover:text-accent-cyan transition-colors ml-auto"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              Live Log
            </button>
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
