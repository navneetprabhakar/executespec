import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FolderGit2, Rocket } from 'lucide-react';
import { useProjectStore } from '@/stores/useProjectStore';
import * as api from '@/lib/api';
import ProjectRepoManager from '@/components/project/ProjectRepoManager';
import ArchitectureViewer from '@/components/project/ArchitectureViewer';
import ProjectTaskList from '@/components/project/ProjectTaskList';
import ProjectPrList from '@/components/project/ProjectPrList';
import ProjectScoreTrend from '@/components/project/ProjectScoreTrend';
import ProjectMemoryPanel from '@/components/project/ProjectMemoryPanel';
import ProjectLaunchesList from '@/components/project/ProjectLaunchesList';
import ProjectPrRollup from '@/components/project/ProjectPrRollup';
import ProjectChatPanel from '@/components/project/ProjectChatPanel';
import { cn } from '@/lib/utils';

type TabKey = 'overview' | 'launches' | 'prs' | 'tasks' | 'repos' | 'architecture' | 'memory' | 'chat' | 'team';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'launches', label: 'Launches' },
  { key: 'prs', label: 'PRs' },
  { key: 'tasks', label: 'Tasks' },
  { key: 'repos', label: 'Repos' },
  { key: 'architecture', label: 'Architecture' },
  { key: 'memory', label: 'Memory' },
  { key: 'chat', label: 'Chat' },
  { key: 'team', label: 'Team' },
];

export default function ProjectDashboardPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const [tab, setTab] = useState<TabKey>('overview');
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [projectLookup, setProjectLookup] = useState<'pending' | 'found' | 'missing'>('pending');
  const {
    dashboard, scoreTrend, loading, error,
    fetchDashboard, fetchScoreTrend, reset,
  } = useProjectStore();

  useEffect(() => {
    if (!projectId) return;
    setProjectLookup('pending');
    api.getProject(projectId)
        .then((p) => { setProject(p); setProjectLookup('found'); })
        .catch(() => { setProject(null); setProjectLookup('missing'); });
    fetchDashboard(projectId);
    fetchScoreTrend(projectId);
    return () => {
      reset();
      setProject(null);
      setProjectLookup('pending');
    };
  }, [projectId, fetchDashboard, fetchScoreTrend, reset]);

  if (!projectId) {
    return (
      <div className="h-full overflow-y-auto">
        <div className="mx-auto max-w-5xl p-4 text-[12px] text-muted">No project selected.</div>
      </div>
    );
  }
  if (projectLookup === 'missing') {
    return (
      <div className="h-full overflow-y-auto">
        <div className="mx-auto max-w-5xl p-4 space-y-3">
          <h1 className="text-[14px] font-medium text-primary">Project not found</h1>
          <p className="text-[11px] text-muted">
            <span className="font-mono">{projectId}</span> is not a valid project.
          </p>
          <Link to="/projects" className="text-[11px] text-accent-blue hover:underline">
            ← Back to projects
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto">
    <div className="mx-auto max-w-6xl p-4 space-y-3">
      <Link to="/projects" className="inline-flex items-center gap-1 text-[11px] text-muted hover:text-accent-blue transition-colors">
        <ArrowLeft className="h-3 w-3" />
        Projects
      </Link>

      {/* Header */}
      <header className="flex items-center justify-between gap-3">
        <div className="min-w-0 flex items-center gap-2">
          <FolderGit2 className="h-4 w-4 text-accent-blue flex-shrink-0" />
          <h1 className="text-[14px] font-medium text-primary truncate">
            {project?.name ?? 'Project'}
          </h1>
          {project?.status && (
            <span className="text-[10px] font-mono uppercase tracking-wide text-muted border border-[var(--border-default)] px-1.5 py-0.5 rounded">
              {project.status}
            </span>
          )}
          {project?.aiProvider && (
            <span className="text-[10px] font-mono text-muted">{project.aiProvider}</span>
          )}
        </div>
        <NewExecutionButton projectId={projectId} />
      </header>
      {project?.description && (
        <p className="text-[11px] text-muted -mt-1">{project.description}</p>
      )}

      {loading && <div className="text-[11px] text-muted">Loading…</div>}
      {error && <div className="text-[11px] text-accent-red">{error}</div>}

      {/* Metric tiles */}
      <section className="grid grid-cols-2 md:grid-cols-4 gap-2">
        <MetricTile label="Active runs"  value={dashboard?.activeRuns ?? '—'}  accent="text-accent-blue" />
        <MetricTile label="Completed"    value={dashboard?.completedRuns ?? '—'} accent="text-emerald-400" />
        <MetricTile label="Failed"       value={dashboard?.failedRuns ?? '—'}   accent="text-accent-red" />
        <MetricTile
          label="Avg score"
          value={dashboard?.avgScore != null ? `${Math.round(dashboard.avgScore)}/100` : '—'}
          accent={scoreAccent(dashboard?.avgScore)}
        />
      </section>

      {/* Tabs */}
      <nav className="flex gap-0.5 border-b border-[var(--border-default)] overflow-x-auto">
        {TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={cn(
              'px-2.5 py-1.5 text-[11px] font-medium whitespace-nowrap transition-colors border-b-2 -mb-px',
              tab === t.key
                ? 'border-accent-blue text-primary'
                : 'border-transparent text-muted hover:text-secondary',
            )}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {/* Body */}
      <section className="text-[12px]">
        {tab === 'overview' && (
          <div className="space-y-4">
            <div className="space-y-2">
              <h3 className="text-[11px] font-medium text-secondary">Recent runs</h3>
              <ProjectTaskList tasks={dashboard?.recentTasks ?? []} />
            </div>
            <div className="space-y-2">
              <h3 className="text-[11px] font-medium text-secondary">Recent pull requests</h3>
              <ProjectPrList prs={dashboard?.recentPrs ?? []} />
            </div>
            <div>
              <ProjectScoreTrend scores={scoreTrend} />
            </div>
          </div>
        )}
        {tab === 'launches' && <ProjectLaunchesList projectId={projectId} />}
        {tab === 'prs' && <ProjectPrRollup projectId={projectId} />}
        {tab === 'tasks' && <ProjectTaskList tasks={dashboard?.recentTasks ?? []} />}
        {tab === 'repos' && <ProjectRepoManager projectId={projectId} />}
        {tab === 'architecture' && <ArchitectureViewer projectId={projectId} />}
        {tab === 'memory' && <ProjectMemoryPanel projectId={projectId} />}
        {tab === 'chat' && <ProjectChatPanel projectId={projectId} />}
        {tab === 'team' && (
          <div className="text-[11px] text-muted">
            Team management is available via the existing Project members page.
          </div>
        )}
      </section>
    </div>
    </div>
  );
}

function MetricTile({ label, value, accent }: { label: string; value: string | number; accent?: string }) {
  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2.5">
      <div className="text-[11px] font-medium text-secondary">{label}</div>
      <div className={cn('text-2xl font-semibold tabular-nums mt-1', accent ?? 'text-primary')}>
        {value}
      </div>
    </div>
  );
}

function scoreAccent(score: number | null | undefined): string {
  if (score == null) return 'text-muted';
  if (score >= 90) return 'text-emerald-400';
  if (score >= 75) return 'text-accent-amber';
  return 'text-accent-red';
}

function NewExecutionButton({ projectId }: { projectId: string }) {
  const navigate = useNavigate();
  return (
    <button
      onClick={() => navigate(`/projects/${projectId}/executions/new`)}
      className="flex items-center gap-1.5 text-[11px] font-medium rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 transition-colors px-2.5 py-1"
      title="Start a cross-repo Launch (ENTERPRISE / MAX)"
    >
      <Rocket className="h-3 w-3" />
      Launch
    </button>
  );
}
