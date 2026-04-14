import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useProjectStore } from '@/stores/useProjectStore';
import * as api from '@/lib/api';
import ProjectRepoManager from '@/components/project/ProjectRepoManager';
import ArchitectureViewer from '@/components/project/ArchitectureViewer';
import ProjectTaskList from '@/components/project/ProjectTaskList';
import ProjectPrList from '@/components/project/ProjectPrList';
import ProjectScoreTrend from '@/components/project/ProjectScoreTrend';

type TabKey = 'overview' | 'tasks' | 'repos' | 'architecture' | 'team';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'tasks', label: 'Tasks' },
  { key: 'repos', label: 'Repos' },
  { key: 'architecture', label: 'Architecture' },
  { key: 'team', label: 'Team' },
];

export default function ProjectDashboardPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const [tab, setTab] = useState<TabKey>('overview');
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const {
    dashboard, scoreTrend, loading, error,
    fetchDashboard, fetchScoreTrend, reset,
  } = useProjectStore();

  useEffect(() => {
    if (!projectId) return;
    api.getProject(projectId).then(setProject).catch(() => setProject(null));
    fetchDashboard(projectId);
    fetchScoreTrend(projectId);
    return () => {
      reset();
      setProject(null);
    };
  }, [projectId, fetchDashboard, fetchScoreTrend, reset]);

  if (!projectId) return <div className="p-6">No project selected.</div>;

  return (
    <div className="p-6 space-y-6">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{project?.name ?? 'Project'}</h1>
          {project?.description && (
            <p className="text-sm text-gray-500 mt-0.5">{project.description}</p>
          )}
        </div>
        {project && (
          <span className="text-xs text-gray-500">
            {project.status} · {project.aiProvider}
          </span>
        )}
      </header>

      {loading && <div className="text-gray-500">Loading…</div>}
      {error && <div className="text-red-600">{error}</div>}

      <section className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <MetricTile label="Active runs" value={dashboard?.activeRuns ?? '—'} />
        <MetricTile label="Completed" value={dashboard?.completedRuns ?? '—'} />
        <MetricTile label="Failed" value={dashboard?.failedRuns ?? '—'} />
        <MetricTile
          label="Avg score"
          value={dashboard?.avgScore != null ? `${Math.round(dashboard.avgScore)}/100` : '—'}
        />
      </section>

      <nav className="border-b flex gap-6">
        {TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`py-2 px-1 border-b-2 text-sm ${
              tab === t.key
                ? 'border-blue-600 text-blue-700'
                : 'border-transparent text-gray-600 hover:text-gray-900'
            }`}
          >
            {t.label}
          </button>
        ))}
      </nav>

      <section>
        {tab === 'overview' && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <div className="space-y-4">
              <h3 className="text-sm font-medium">Recent runs</h3>
              <ProjectTaskList tasks={dashboard?.recentTasks ?? []} />
            </div>
            <div className="space-y-4">
              <h3 className="text-sm font-medium">Recent pull requests</h3>
              <ProjectPrList prs={dashboard?.recentPrs ?? []} />
            </div>
            <div className="lg:col-span-2">
              <ProjectScoreTrend scores={scoreTrend} />
            </div>
          </div>
        )}
        {tab === 'tasks' && <ProjectTaskList tasks={dashboard?.recentTasks ?? []} />}
        {tab === 'repos' && <ProjectRepoManager projectId={projectId} />}
        {tab === 'architecture' && <ArchitectureViewer projectId={projectId} />}
        {tab === 'team' && (
          <div className="text-gray-500 text-sm">
            Team management is available via the existing Project members page.
          </div>
        )}
      </section>
    </div>
  );
}

function MetricTile({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="border rounded-lg p-4 bg-white shadow-sm">
      <div className="text-xs uppercase tracking-wide text-gray-500">{label}</div>
      <div className="text-2xl font-semibold mt-1">{value}</div>
    </div>
  );
}
