import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { Sparkles, ChevronRight, FolderGit2 } from 'lucide-react';
import PageShell from '@/components/shared/PageShell';
import { getProjects, type ProjectDto } from '@/lib/api';
import { useViewMode } from '@/hooks/useViewMode';
import { cn } from '@/lib/utils';

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:   'text-status-success border-status-success/40 bg-status-success/10',
  ARCHIVED: 'text-muted border-[var(--border-default)] bg-[var(--gray-200)]',
  DELETED:  'text-status-error border-status-error/40 bg-status-error/10',
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
    <PageShell
      eyebrow="Projects · Enterprise / Max"
      eyebrowTone="purple"
      title="Multi-repo workspaces."
      accent="Multi-repo"
      description="Run specs across one or many repos. Architecture docs, tech-debt panel, and project memory."
      maxWidth="5xl"
      hero
      actions={
        <Link
          to="/org/projects"
          className="inline-flex items-center gap-1.5 text-xs text-secondary hover:text-primary border border-[var(--border-default)] rounded-md px-3 py-1.5 hover:border-accent-blue/40 hover:bg-[var(--gray-200)] transition-colors"
        >
          Manage projects &rarr;
        </Link>
      }
    >
      {error && (
        <div className="text-xs text-status-error border border-status-error/40 bg-status-error/10 rounded-sm px-2.5 py-1.5">
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
          <div className="text-sm text-muted">
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
                  <p className="text-xs text-secondary line-clamp-2 leading-snug">
                    {p.description}
                  </p>
                )}
                <div className="flex items-center gap-1.5 mt-auto">
                  <span className={cn(
                    'text-[11px] font-mono uppercase tracking-wide px-1.5 py-0.5 rounded border',
                    badge,
                  )}>
                    {p.status ?? 'ACTIVE'}
                  </span>
                  {p.aiProvider && (
                    <span className="text-[11px] font-mono text-muted">{p.aiProvider}</span>
                  )}
                </div>
              </Link>
            );
          })}
        </div>
      )}
    </PageShell>
  );
}
