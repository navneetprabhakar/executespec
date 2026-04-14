import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { FolderGit2, Sparkles, ChevronRight } from 'lucide-react';
import { getProjects, type ProjectDto } from '@/lib/api';
import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:   'text-emerald-400 border-emerald-500/40 bg-emerald-500/10',
  ARCHIVED: 'text-muted border-[var(--border-default)] bg-[var(--gray-200)]',
  DELETED:  'text-accent-red border-red-500/40 bg-red-500/10',
};

/**
 * Landing page for PROJECT view mode (v2.4.0). Lists projects the user has
 * access to; clicking a project opens its dashboard with Launches + PRs +
 * Overview tabs. ENTERPRISE / MAX tiers only — lower tiers bounce to /.
 */
export default function ProjectsListPage() {
  const { canUseProjectMode } = useViewMode();
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!canUseProjectMode) return;
    getProjects()
        .then(setProjects)
        .catch((e: any) => setError(e?.message ?? 'Failed to load projects'));
  }, [canUseProjectMode]);

  if (!canUseProjectMode) return <Navigate to="/" replace />;

  return (
    <div className="h-full overflow-y-auto">
    <div className="mx-auto max-w-5xl p-4 space-y-4">
      {/* Header */}
      <div className="flex items-end justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <FolderGit2 className="h-4 w-4 text-accent-blue flex-shrink-0" />
            <h1 className="text-[14px] font-medium text-primary">Projects</h1>
            <span className="text-[10px] font-mono uppercase tracking-wider text-accent-blue border border-accent-blue/40 bg-accent-blue/10 px-1.5 py-0.5 rounded">
              Enterprise · Max
            </span>
          </div>
          <p className="text-[11px] text-muted mt-1">
            Multi-repo workspaces with launches, pull requests, and memory.
          </p>
        </div>
        <Link
          to="/org/projects"
          className="text-[11px] text-muted hover:text-secondary border border-[var(--border-default)] rounded-sm px-2 py-1"
        >
          Manage
        </Link>
      </div>

      {error && (
        <div className="text-[11px] text-accent-red border border-red-500/40 bg-red-500/10 rounded-sm px-2.5 py-1.5">
          {error}
        </div>
      )}

      {/* Body */}
      {projects == null ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i}
              className="h-24 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] animate-pulse" />
          ))}
        </div>
      ) : projects.length === 0 ? (
        <div className="text-center py-12 space-y-2">
          <Sparkles className="h-5 w-5 text-muted mx-auto" />
          <div className="text-[12px] text-muted">
            No projects yet. Head to{' '}
            <Link to="/org/projects" className="text-accent-blue hover:underline">
              Org → Projects
            </Link>{' '}
            to create one.
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {projects.map((p) => {
            const badge = STATUS_COLOR[p.status ?? 'ACTIVE'] ?? STATUS_COLOR.ACTIVE;
            return (
              <Link
                key={p.id}
                to={`/projects/${p.id}/dashboard`}
                className={cn(
                  'group flex flex-col gap-2 rounded-sm border border-[var(--border-default)]',
                  'bg-[var(--gray-100)] hover:bg-[var(--gray-200)] transition-colors',
                  'px-3 py-2.5',
                )}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <FolderGit2 className="h-3.5 w-3.5 text-muted flex-shrink-0" />
                  <span className="text-[13px] font-medium text-primary truncate">{p.name}</span>
                  <ChevronRight className="h-3 w-3 text-muted ml-auto opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0" />
                </div>
                {p.description && (
                  <p className="text-[11px] text-secondary line-clamp-2 leading-snug">
                    {p.description}
                  </p>
                )}
                <div className="flex items-center gap-1.5 mt-auto">
                  <span className={cn(
                    'text-[10px] font-mono uppercase tracking-wide px-1.5 py-0.5 rounded border',
                    badge,
                  )}>
                    {p.status ?? 'ACTIVE'}
                  </span>
                  {p.aiProvider && (
                    <span className="text-[10px] font-mono text-muted">{p.aiProvider}</span>
                  )}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </div>
    </div>
  );
}
