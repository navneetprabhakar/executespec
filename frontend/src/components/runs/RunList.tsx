import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Plus, FolderOpen, Users, User, Globe, ChevronLeft, ChevronRight, Sparkles } from 'lucide-react';
import { useIsMobile } from '@/hooks/useIsMobile';
import RunCard from './RunCard';
import { RunCardSkeleton } from '@/components/shared/SkeletonLoader';
import EmptyState from '@/components/shared/EmptyState';
import NewRunModal from './NewRunModal';
import VibeRunSubmit from './VibeRunSubmit';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsOrgMember, selectIsSuperAdmin } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';
import * as api from '@/lib/api';

const FILTERS = ['ALL', 'PLANNING', 'WORKING', 'AWAITING_APPROVAL', 'COMPLETE', 'PAUSED', 'FAILED'];

const toTitleCase = (s: string) =>
  s.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());

export default function RunList() {
  const {
    runs, loading, activeRunId,
    statusFilter, setStatusFilter,
    projectFilter, setProjectFilter,
    scopeFilter, setScopeFilter,
    setActiveRunId, fetchRuns,
    page, totalElements, totalPages, hasNext, hasPrevious, setPage,
  } = useRunStore();
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const [showModal, setShowModal] = useState(false);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const { pendingNewRunGoal, setPendingNewRunGoal } = useUiStore();
  const { isVibe } = useViewMode();
  const isMobile = useIsMobile();

  // Open modal when a pending goal is set (e.g., from RunConversation "Implement as New Run")
  useEffect(() => {
    if (pendingNewRunGoal) setShowModal(true);
  }, [pendingNewRunGoal]);

  // Load projects once (for org members only)
  useEffect(() => {
    if (isOrgMember) {
      api.getProjects().then(setProjects).catch(() => setProjects([]));
    }
  }, [isOrgMember]);

  // Re-fetch whenever any filter changes
  useEffect(() => {
    fetchRuns();
    const interval = setInterval(fetchRuns, 5000);
    return () => clearInterval(interval);
  }, [statusFilter, projectFilter, scopeFilter]);

  return (
    <div className="flex flex-col h-full">
      {/* Mobile: New Run button at top */}
      {isMobile && (
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center justify-center gap-2 mx-3 mt-3 mb-1 px-4 py-2.5 rounded-sm bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
        >
          <Sparkles className="h-4 w-4" />
          New Run
        </button>
      )}

      {/* Scope toggle — SUPER_ADMIN: 3 views; org members: 2 views */}
      {(isSuperAdmin || isOrgMember) && (
        <div className="flex items-center gap-1 px-4 py-2 border-b border-white/[0.06] flex-shrink-0">
          <button
            onClick={() => setScopeFilter('mine')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 lg:py-1 rounded-sm text-xs font-medium transition-colors',
              scopeFilter === 'mine'
                ? 'bg-accent-violet text-white'
                : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.04] hover:bg-white/[0.06]'
            )}
          >
            <User className="h-3 w-3" />
            My Runs
          </button>
          <button
            onClick={() => setScopeFilter(isSuperAdmin ? 'org' : null)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 lg:py-1 rounded-sm text-xs font-medium transition-colors',
              (isSuperAdmin ? scopeFilter === 'org' : scopeFilter === null)
                ? 'bg-accent-violet text-white'
                : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.04] hover:bg-white/[0.06]'
            )}
          >
            <Users className="h-3 w-3" />
            Org Runs
          </button>
          {isSuperAdmin && (
            <button
              onClick={() => setScopeFilter(null)}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 lg:py-1 rounded-sm text-xs font-medium transition-colors',
                scopeFilter === null
                  ? 'bg-accent-violet text-white'
                  : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.04] hover:bg-white/[0.06]'
              )}
            >
              <Globe className="h-3 w-3" />
              All Runs
            </button>
          )}
        </div>
      )}

      {/* Status filter bar */}
      <div className="flex items-center gap-1 lg:gap-1.5 px-3 lg:px-4 py-2.5 lg:py-3 border-b border-white/[0.06] overflow-x-auto flex-shrink-0 scrollbar-hide">
        {FILTERS.map((f) => (
          <button
            key={f}
            onClick={() => setStatusFilter(f)}
            className={cn(
              'px-2 lg:px-3 py-1.5 lg:py-1 rounded-sm text-[10px] lg:text-xs font-medium whitespace-nowrap transition-colors',
              statusFilter === f
                ? 'bg-accent-violet text-white'
                : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.04] hover:bg-white/[0.06]'
            )}
          >
            {f === 'ALL' ? 'All' : toTitleCase(f)}
          </button>
        ))}
      </div>

      {/* Project filter bar — visible for org members with projects */}
      {isOrgMember && projects.length > 0 && (
        <div className="flex items-center gap-1 lg:gap-1.5 px-3 lg:px-4 py-2 border-b border-white/[0.04] overflow-x-auto flex-shrink-0 scrollbar-hide bg-white/[0.01]">
          <FolderOpen className="h-3.5 w-3.5 text-zinc-600 flex-shrink-0" />
          <button
            onClick={() => setProjectFilter(null)}
            className={cn(
              'px-2.5 py-1.5 lg:py-0.5 rounded-sm text-xs font-medium whitespace-nowrap transition-colors',
              projectFilter === null
                ? 'bg-zinc-700 text-zinc-100'
                : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.03] hover:bg-white/[0.05]'
            )}
          >
            All Runs
          </button>
          <button
            onClick={() => setProjectFilter('__none__')}
            className={cn(
              'px-2.5 py-1.5 lg:py-0.5 rounded-sm text-xs font-medium whitespace-nowrap transition-colors',
              projectFilter === '__none__'
                ? 'bg-zinc-600 text-zinc-100'
                : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.03] hover:bg-white/[0.05]'
            )}
          >
            Unassigned
          </button>
          {projects
            .filter((p) => p.status === 'ACTIVE')
            .map((p) => (
              <button
                key={p.id}
                onClick={() => setProjectFilter(p.id)}
                className={cn(
                  'px-2.5 py-1.5 lg:py-0.5 rounded-sm text-xs font-medium whitespace-nowrap transition-colors',
                  projectFilter === p.id
                    ? 'bg-accent-violet text-white'
                    : 'text-zinc-500 hover:text-zinc-300 bg-white/[0.03] hover:bg-white/[0.05]'
                )}
              >
                {p.name}
              </button>
            ))}
        </div>
      )}

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
                className="px-4 py-2 rounded-sm bg-accent-violet text-white text-sm font-medium hover:bg-accent-violet/80 transition-colors"
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
              compact={isVibe}
            />
          ))
        )}
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between px-4 py-2 border-t border-white/[0.06] flex-shrink-0 bg-zinc-900/50">
          <span className="text-xs text-zinc-500">
            {totalElements} runs
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setPage(page - 1)}
              disabled={!hasPrevious}
              className={cn(
                'p-1 rounded transition-colors',
                hasPrevious ? 'text-zinc-400 hover:text-white hover:bg-white/[0.06]' : 'text-zinc-700 cursor-not-allowed'
              )}
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <span className="text-xs text-zinc-400 min-w-[4rem] text-center">
              {page + 1} / {totalPages}
            </span>
            <button
              onClick={() => setPage(page + 1)}
              disabled={!hasNext}
              className={cn(
                'p-1 rounded transition-colors',
                hasNext ? 'text-zinc-400 hover:text-white hover:bg-white/[0.06]' : 'text-zinc-700 cursor-not-allowed'
              )}
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      {/* FAB — desktop only (mobile has top button) */}
      {!isMobile && (
        <motion.button
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.95 }}
          onClick={() => setShowModal(true)}
          className="absolute bottom-6 right-4 h-12 w-12 rounded-sm bg-accent-violet flex items-center justify-center z-10"
        >
          <Plus className="h-5 w-5 text-white" />
        </motion.button>
      )}

      {showModal && (
        isVibe ? (
          <VibeRunSubmit onClose={() => { setShowModal(false); setPendingNewRunGoal(null); }} />
        ) : (
          <NewRunModal
            initialGoal={pendingNewRunGoal ?? undefined}
            onClose={() => { setShowModal(false); setPendingNewRunGoal(null); }}
          />
        )
      )}
    </div>
  );
}
