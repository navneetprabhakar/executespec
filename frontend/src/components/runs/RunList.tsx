import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Plus } from 'lucide-react';
import RunCard from './RunCard';
import { RunCardSkeleton } from '@/components/shared/SkeletonLoader';
import EmptyState from '@/components/shared/EmptyState';
import NewRunModal from './NewRunModal';
import { useRunStore } from '@/stores/useRunStore';
import { cn } from '@/lib/utils';

const FILTERS = ['ALL', 'PLANNING', 'WORKING', 'AWAITING_APPROVAL', 'AWAITING_MERGE', 'COMPLETE', 'FAILED'];

export default function RunList() {
  const { runs, loading, activeRunId, statusFilter, setStatusFilter, setActiveRunId, fetchRuns } = useRunStore();
  const [showModal, setShowModal] = useState(false);

  useEffect(() => {
    fetchRuns();
    const interval = setInterval(fetchRuns, 10000);
    return () => clearInterval(interval);
  }, [statusFilter]);

  return (
    <div className="flex flex-col h-full">
      {/* Filter bar */}
      <div className="flex items-center gap-1.5 px-4 py-3 border-b border-white/[0.06] overflow-x-auto flex-shrink-0">
        {FILTERS.map((f) => (
          <button
            key={f}
            onClick={() => setStatusFilter(f)}
            className={cn(
              'px-3 py-1 rounded-full text-xs font-medium whitespace-nowrap transition-colors',
              statusFilter === f
                ? 'bg-accent-violet text-white'
                : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.04] hover:bg-white/[0.06]'
            )}
          >
            {f === 'ALL' ? 'All' : f.replace(/_/g, ' ')}
          </button>
        ))}
      </div>

      {/* List */}
      <div className="flex-1 overflow-y-auto px-3 py-3 space-y-2">
        {loading && runs.length === 0 ? (
          Array.from({ length: 5 }).map((_, i) => <RunCardSkeleton key={i} />)
        ) : runs.length === 0 ? (
          <EmptyState
            title="No runs yet"
            description="Launch your first run to start orchestrating agents."
            action={
              <button
                onClick={() => setShowModal(true)}
                className="px-4 py-2 rounded-lg bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
              >
                Launch Run
              </button>
            }
          />
        ) : (
          runs.map((run, i) => (
            <RunCard
              key={run.runId}
              run={run}
              index={i}
              isActive={run.runId === activeRunId}
              onClick={() => setActiveRunId(run.runId)}
            />
          ))
        )}
      </div>

      {/* FAB */}
      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onClick={() => setShowModal(true)}
        className="absolute bottom-6 right-4 h-12 w-12 rounded-full bg-accent-violet shadow-glow-violet flex items-center justify-center z-10"
      >
        <Plus className="h-5 w-5 text-white" />
      </motion.button>

      {showModal && <NewRunModal onClose={() => setShowModal(false)} />}
    </div>
  );
}
