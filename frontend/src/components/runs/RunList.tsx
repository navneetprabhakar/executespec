import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Plus, FolderOpen, Users, User, Globe, ChevronLeft, ChevronRight, Archive } from 'lucide-react';
import { useIsMobile } from '@/hooks/useIsMobile';
import RunCard from './RunCard';
import { RunCardSkeleton } from '@/components/shared/SkeletonLoader';
import EmptyState from '@/components/shared/EmptyState';
import FirstRunSuggestions from './FirstRunSuggestions';
import NewRunModal from './NewRunModal';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsOrgMember, selectIsSuperAdmin } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';
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
    archivedFilter, setArchivedFilter,
    setActiveRunId, fetchRuns,
    page, totalElements, totalPages, hasNext, hasPrevious, setPage,
  } = useRunStore();
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const [showModal, setShowModal] = useState(false);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const { pendingNewRunConfig, setPendingNewRunConfig } = useUiStore();
  // R-190 (post-cutover) — useViewMode no longer exposes isVibe; the
  // RunCard `compact` prop was previously fed by isVibe. Default cards
  // (full Pro layout) render unconditionally now.
  const isMobile = useIsMobile();

  // Open modal when a pending config is set (e.g., from Re-run or Copilot "Iterate as New Run")
  useEffect(() => {
    if (pendingNewRunConfig) setShowModal(true);
  }, [pendingNewRunConfig]);

  // Load projects once (for org members only)
  useEffect(() => {
    if (isOrgMember) {
      api.getProjects().then(setProjects).catch(() => setProjects([]));
    }
  }, [isOrgMember]);

  // Re-fetch whenever any filter changes
  useEffect(() => {
    fetchRuns();
    const interval = setInterval(() => { if (!document.hidden) fetchRuns(); }, 15000);
    return () => clearInterval(interval);
  }, [statusFilter, projectFilter, scopeFilter, archivedFilter]);

  return (
    <div className="flex flex-col h-full">
      {/* Mobile: New Run button at top — v2.5.13: switched to accent-blue so it
          reads as a dark-theme primary action instead of an inverted-light bar
          with black text (which looked like an empty/broken element). */}
      {isMobile && (
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center justify-center gap-2 mx-3 mt-3 mb-1 px-4 py-2.5 rounded-sm bg-accent-blue text-primary text-sm font-medium hover:bg-accent-blue/80 transition-colors"
        >
          <Plus className="h-4 w-4" />
          New run
        </button>
      )}

      {/* Scope toggle — SUPER_ADMIN: 3 views; org members: 2 views */}
      {(isSuperAdmin || isOrgMember) && (
        <div className="flex items-center gap-0 px-3 lg:px-4 border-b border-[var(--border-default)] flex-shrink-0 overflow-x-auto scrollbar-hide">
          <button
            onClick={() => setScopeFilter('mine')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 lg:py-2.5 text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors',
              scopeFilter === 'mine'
                ? 'border-[var(--text-primary)] text-primary'
                : 'border-transparent text-muted hover:text-secondary'
            )}
          >
            <User className="h-3 w-3" />
            My Runs
          </button>
          <button
            onClick={() => setScopeFilter(isSuperAdmin ? 'org' : null)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 lg:py-2.5 text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors',
              (isSuperAdmin ? scopeFilter === 'org' : scopeFilter === null)
                ? 'border-[var(--text-primary)] text-primary'
                : 'border-transparent text-muted hover:text-secondary'
            )}
          >
            <Users className="h-3 w-3" />
            Org Runs
          </button>
          {isSuperAdmin && (
            <button
              onClick={() => setScopeFilter(null)}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 lg:py-2.5 text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors',
                scopeFilter === null
                  ? 'border-[var(--text-primary)] text-primary'
                  : 'border-transparent text-muted hover:text-secondary'
              )}
            >
              <Globe className="h-3 w-3" />
              All Runs
            </button>
          )}
        </div>
      )}

      {/* Status filter bar */}
      <div className="flex items-center gap-0 px-3 lg:px-4 border-b border-[var(--border-default)] overflow-x-auto flex-shrink-0 scrollbar-hide">
        {FILTERS.map((f) => (
          <button
            key={f}
            onClick={() => { setArchivedFilter(false); setStatusFilter(f); }}
            className={cn(
              'px-2.5 lg:px-3 py-2 lg:py-2.5 text-[10px] lg:text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors whitespace-nowrap',
              !archivedFilter && statusFilter === f
                ? 'border-[var(--text-primary)] text-primary'
                : 'border-transparent text-muted hover:text-secondary'
            )}
          >
            {f === 'ALL' ? 'All' : toTitleCase(f)}
          </button>
        ))}
        <div className="w-px h-4 bg-[var(--border-default)] mx-1" />
        <button
          onClick={() => setArchivedFilter(!archivedFilter)}
          className={cn(
            'flex items-center gap-1 px-2.5 lg:px-3 py-2 lg:py-2.5 text-[10px] lg:text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors whitespace-nowrap',
            archivedFilter
              ? 'border-[var(--text-primary)] text-primary'
              : 'border-transparent text-muted hover:text-secondary'
          )}
        >
          <Archive className="h-3 w-3" />
          Archived
        </button>
      </div>

      {/* Project filter bar — visible for org members with projects */}
      {isOrgMember && projects.length > 0 && (
        <div className="flex items-center gap-1 lg:gap-1.5 px-3 lg:px-4 py-2 border-b border-[var(--border-default)] overflow-x-auto flex-shrink-0 scrollbar-hide">
          <FolderOpen className="h-3.5 w-3.5 text-muted flex-shrink-0" />
          <button
            onClick={() => setProjectFilter(null)}
            className={cn(
              'px-2.5 py-1.5 lg:py-0.5 rounded-sm text-xs font-medium whitespace-nowrap transition-colors',
              projectFilter === null
                ? 'bg-[var(--gray-300)] text-primary'
                : 'text-muted hover:text-secondary bg-[var(--gray-200)] hover:bg-[var(--gray-300)]'
            )}
          >
            All Runs
          </button>
          <button
            onClick={() => setProjectFilter('__none__')}
            className={cn(
              'px-2.5 py-1.5 lg:py-0.5 rounded-sm text-xs font-medium whitespace-nowrap transition-colors',
              projectFilter === '__none__'
                ? 'bg-[var(--gray-300)] text-primary'
                : 'text-muted hover:text-secondary bg-[var(--gray-200)] hover:bg-[var(--gray-300)]'
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
                    ? 'bg-[var(--gray-300)] text-primary'
                    : 'text-muted hover:text-secondary bg-[var(--gray-200)] hover:bg-[var(--gray-300)]'
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
          archivedFilter ? (
            <EmptyState
              title="No archived runs"
              description="Runs you archive will appear here."
            />
          ) : (
            <FirstRunSuggestions onSelect={(goal) => { setPendingNewRunConfig({ goal }); setShowModal(true); }} />
          )
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

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between px-4 py-2 border-t border-[var(--border-default)] flex-shrink-0 bg-[var(--gray-100)]">
          <span className="text-xs text-muted">
            {totalElements} runs
          </span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => setPage(page - 1)}
              disabled={!hasPrevious}
              aria-label="Previous page"
              title="Previous page"
              className={cn(
                'p-1 rounded transition-colors',
                hasPrevious ? 'text-secondary hover:text-primary hover:bg-[var(--gray-200)]' : 'text-[var(--gray-500)] cursor-not-allowed'
              )}
            >
              <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            </button>
            <span className="text-xs text-secondary min-w-[4rem] text-center" aria-live="polite">
              {page + 1} / {totalPages}
            </span>
            <button
              onClick={() => setPage(page + 1)}
              disabled={!hasNext}
              aria-label="Next page"
              title="Next page"
              className={cn(
                'p-1 rounded transition-colors',
                hasNext ? 'text-secondary hover:text-primary hover:bg-[var(--gray-200)]' : 'text-[var(--gray-500)] cursor-not-allowed'
              )}
            >
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
        </div>
      )}

      {/* New Run button — desktop only (mobile has top button) */}
      {!isMobile && (
        <div className="px-3 py-3 border-t border-[var(--border-default)] flex-shrink-0">
          <button
            onClick={() => setShowModal(true)}
            className="flex items-center justify-center gap-2 w-full px-4 py-2.5 rounded-md bg-accent-blue text-primary text-sm font-medium hover:bg-[var(--accent-blue-hover)] transition-colors"
          >
            <Plus className="h-4 w-4" />
            New run
          </button>
        </div>
      )}

      {showModal && (
        <NewRunModal
          initialConfig={pendingNewRunConfig ?? undefined}
          onClose={() => { setShowModal(false); setPendingNewRunConfig(null); }}
        />
      )}
    </div>
  );
}
