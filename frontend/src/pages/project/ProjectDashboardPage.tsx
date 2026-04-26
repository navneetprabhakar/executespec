import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FolderGit2, Rocket } from 'lucide-react';
import { useProjectStore } from '@/stores/useProjectStore';
import * as api from '@/lib/api';
import ProjectRepoManager from '@/components/project/ProjectRepoManager';
import ArchitectureViewer from '@/components/project/ArchitectureViewer';
import ProjectPrList from '@/components/project/ProjectPrList';
import ProjectScoreTrend from '@/components/project/ProjectScoreTrend';
import WorkspaceHealthPanel from '@/components/project/WorkspaceHealthPanel';
import ContractCoveragePanel from '@/components/project/ContractCoveragePanel';
import StandardsCompliancePanel from '@/components/project/StandardsCompliancePanel';
import ActivityTab from '@/components/project/ActivityTab';
import GeneratedDocsPanel from '@/components/project/GeneratedDocsPanel';
import PostMergeBar from '@/components/project/PostMergeBar';
import TechDebtPanel from '@/components/project/TechDebtPanel';
import ProjectMemoryPanel from '@/components/project/ProjectMemoryPanel';
import ProjectLaunchesList from '@/components/project/ProjectLaunchesList';
import ProjectPrRollup from '@/components/project/ProjectPrRollup';
import ProjectChatPanel from '@/components/project/ProjectChatPanel';
import ProjectDocumentsPanel from '@/components/project/ProjectDocumentsPanel';
import InlineRunDetail from '@/components/project/InlineRunDetail';
import StatusBadge from '@/components/shared/StatusBadge';
import EmptyState from '@/components/shared/EmptyState';
import { cn } from '@/lib/utils';
import type { TaskSummary } from '@/types/project';
import type { ContinueOnPrConfig } from '@/types/execution';
import type { RunDetail } from '@/lib/api';

type TabKey = 'overview' | 'launches' | 'prs' | 'tasks' | 'repos' | 'architecture' | 'docs' | 'memory' | 'activity' | 'chat' | 'team';

// E4.3 — IA expanded from 5 → 6 top-level groups (Activity inserted between
// Knowledge and Settings per plan §3.6). Tab IDs stay string-based so
// existing deep links keep working; the only mutation is the addition.
type TabGroup = 'overview' | 'work' | 'knowledge' | 'activity' | 'settings' | 'chat';

const TAB_GROUPS: { key: TabGroup; label: string; children: { key: TabKey; label: string }[] }[] = [
  { key: 'overview',  label: 'Overview',  children: [{ key: 'overview', label: 'Overview' }] },
  { key: 'work',      label: 'Work',      children: [
      { key: 'launches', label: 'Launches' },
      { key: 'prs', label: 'PRs' },
      { key: 'tasks', label: 'Tasks' },
  ]},
  { key: 'knowledge', label: 'Knowledge', children: [
      { key: 'architecture', label: 'Architecture' },
      { key: 'docs', label: 'Docs' },
      { key: 'memory', label: 'Memory' },
  ]},
  // E4.3 — single new top-level group; tab key 'activity' is the only mutation
  { key: 'activity',  label: 'Activity',  children: [{ key: 'activity', label: 'Activity' }] },
  { key: 'settings',  label: 'Settings',  children: [
      { key: 'repos', label: 'Repos' },
      { key: 'team', label: 'Team' },
  ]},
  { key: 'chat',      label: 'Chat',      children: [{ key: 'chat', label: 'Chat' }] },
];

function findTabGroup(tab: TabKey): TabGroup {
  for (const g of TAB_GROUPS) {
    if (g.children.some((c) => c.key === tab)) return g.key;
  }
  return 'overview';
}

// v2.5.12 — STATUS_COLOR map removed; status pills now use the shared
// <StatusBadge /> primitive (frontend/src/components/shared/StatusBadge.tsx)
// so colour, label, and statusReason tooltip stay consistent with every
// other status surface (Run detail, ValidationReportPanel, etc.).

function scoreColor(score: number | null | undefined): string {
  // Token-aligned: status-success / status-waiting / status-error.
  if (score == null) return 'text-muted';
  if (score >= 90) return 'text-status-success';
  if (score >= 75) return 'text-status-waiting';
  return 'text-status-error';
}

export default function ProjectDashboardPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [tab, setTab] = useState<TabKey>('overview');
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [projectLookup, setProjectLookup] = useState<'pending' | 'found' | 'missing'>('pending');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [runCache, setRunCache] = useState<Record<string, RunDetail | 'loading' | 'error'>>({});
  const {
    dashboard, scoreTrend, repos, loading, error,
    fetchDashboard, fetchScoreTrend, fetchRepos, reset,
  } = useProjectStore();

  useEffect(() => {
    if (!projectId) return;
    setProjectLookup('pending');
    api.getProject(projectId)
        .then((p) => { setProject(p); setProjectLookup('found'); })
        .catch(() => { setProject(null); setProjectLookup('missing'); });
    fetchDashboard(projectId);
    fetchScoreTrend(projectId);
    fetchRepos(projectId);
    return () => {
      reset();
      setProject(null);
      setProjectLookup('pending');
      setSelectedRunId(null);
      setRunCache({});
    };
  }, [projectId, fetchDashboard, fetchScoreTrend, reset]);

  const tasks: TaskSummary[] = dashboard?.recentTasks ?? [];

  // Auto-select first run so the right pane is never empty when runs exist
  useEffect(() => {
    if (!selectedRunId && tasks.length > 0) {
      setSelectedRunId(tasks[0].runId);
    }
  }, [tasks, selectedRunId]);

  const loadRun = async (runId: string) => {
    setRunCache((c) => ({ ...c, [runId]: 'loading' }));
    try {
      const run = await api.getRun(runId);
      setRunCache((c) => ({ ...c, [runId]: run }));
    } catch {
      setRunCache((c) => ({ ...c, [runId]: 'error' }));
    }
  };

  useEffect(() => {
    if (selectedRunId && !runCache[selectedRunId]) void loadRun(selectedRunId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRunId]);

  const selectedDetail = selectedRunId ? runCache[selectedRunId] : undefined;
  const showLeftList = tab !== 'chat';  // chat takes full pane

  const currentContent = useMemo(() => {
    if (!projectId) return null;
    switch (tab) {
      case 'overview':
        return <OverviewContent projectId={projectId} dashboard={dashboard} scoreTrend={scoreTrend} project={project} onProjectUpdate={setProject} repos={repos ?? []} />;
      case 'launches':
        return <ProjectLaunchesList projectId={projectId} />;
      case 'prs':
        return <ProjectPrRollup projectId={projectId} onAddressReview={(config) => {
          navigate(`/projects/${projectId}/executions/new`, { state: { continueOnPr: config } });
        }} />;
      case 'tasks':
        return selectedDetail === 'loading'
          ? <div className="text-xs text-muted p-3">Loading run…</div>
          : selectedDetail === 'error'
          ? <div className="text-xs text-accent-red p-3">Failed to load run.</div>
          : selectedDetail
          ? <InlineRunDetail run={selectedDetail} onRefresh={() => selectedRunId ? loadRun(selectedRunId) : Promise.resolve()} />
          : <div className="text-xs text-muted p-3">Select a run on the left.</div>;
      case 'repos':
        return <ProjectRepoManager projectId={projectId} />;
      case 'architecture':
        return <ArchitectureViewer projectId={projectId} repoCount={repos?.length ?? null} />;
      case 'docs':
        return <ProjectDocumentsPanel projectId={projectId} />;
      case 'memory':
        return <ProjectMemoryPanel projectId={projectId} />;
      case 'activity':
        return projectId ? <ActivityTab projectId={projectId} /> : null;
      case 'chat':
        return <ProjectChatPanel projectId={projectId} />;
      case 'team':
        return (
          <div className="text-xs text-muted p-3">
            Team management is available via the existing Project members page.
          </div>
        );
    }
  }, [projectId, tab, dashboard, scoreTrend, selectedDetail, selectedRunId]);

  if (!projectId) {
    return (
      <div className="h-full overflow-y-auto">
        <div className="mx-auto max-w-5xl p-4 text-sm text-muted">No project selected.</div>
      </div>
    );
  }
  if (projectLookup === 'missing') {
    return (
      <div className="h-full overflow-y-auto">
        <div className="mx-auto max-w-5xl p-4 space-y-3">
          <h1 className="text-[14px] font-medium text-primary">Project not found</h1>
          <p className="text-xs text-muted">
            <span className="font-mono">{projectId}</span> is not a valid project.
          </p>
          <Link to="/projects" className="text-xs text-accent-blue hover:underline">
            ← Back to projects
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col">
      {/* Top chrome: back link + header + metrics + tabs */}
      <div className="border-b border-[var(--border-default)] px-4 pt-3 pb-2 space-y-2.5 flex-shrink-0">
        <Link to="/projects" className="inline-flex items-center gap-1 text-xs text-muted hover:text-accent-blue transition-colors">
          <ArrowLeft className="h-3 w-3" />
          Projects
        </Link>

        {projectId && <PostMergeBar projectId={projectId} />}

        <header className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex items-start gap-3">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] text-accent-blue flex-shrink-0">
              <FolderGit2 className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <div className="font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue mb-0.5">Project</div>
              <h1 className="text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em] leading-tight truncate">
                {project?.name ?? 'Project'}
              </h1>
              <div className="mt-1 flex items-center gap-2 flex-wrap">
                {project?.status && (
                  <span className="text-[10.5px] font-mono uppercase tracking-[0.06em] text-muted border border-[var(--border-default)] px-1.5 py-0.5 rounded">
                    {project.status}
                  </span>
                )}
                {project?.aiProvider && (
                  <span className="text-[10.5px] font-mono text-muted">{project.aiProvider}</span>
                )}
                {project?.githubOwner && (
                  <span className="text-[10.5px] font-mono text-muted border border-[var(--border-default)] px-1.5 py-0.5 rounded">
                    {project.githubOwner}
                  </span>
                )}
              </div>
            </div>
          </div>
          <NewExecutionButton projectId={projectId} repoCount={repos?.length ?? 0} />
        </header>
        {project?.description && (
          <p className="text-xs text-secondary max-w-2xl">{project.description}</p>
        )}

        {loading && <div className="text-xs text-muted">Loading…</div>}
        {error && <div className="text-xs text-accent-red">{error}</div>}

        {/* Metric tiles: +tokens +cost */}
        <section className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
          <MetricTile label="Active"    value={dashboard?.activeRuns ?? '—'}    accent="text-accent-blue" />
          <MetricTile label="Completed" value={dashboard?.completedRuns ?? '—'} accent="text-status-success" />
          <MetricTile label="Failed"    value={dashboard?.failedRuns ?? '—'}    accent="text-accent-red" />
          <MetricTile
            label="Avg score"
            value={dashboard?.avgScore != null && dashboard.avgScore > 0 ? `${Math.round(dashboard.avgScore)}` : '—'}
            accent={scoreAccent(dashboard?.avgScore)}
          />
          <MetricTile
            label="Tokens"
            value={dashboard?.totalTokens != null && dashboard.totalTokens > 0 ? formatK(dashboard.totalTokens) : '—'}
          />
          <MetricTile
            label="Cost"
            value={dashboard?.totalCostUsd != null && dashboard.totalCostUsd > 0 ? `$${dashboard.totalCostUsd.toFixed(2)}` : '—'}
          />
        </section>

        {/* v2.5.14 Phase 2.4 — two-tier tab navigation.
            Row 1: top-level groups (Overview / Work / Knowledge / Settings).
            Row 2: child tabs within the active group. Overview group is single-tab so no row 2.
            Mobile: both rows scroll horizontally for no-overflow guarantee. */}
        {(() => {
          const activeGroup = findTabGroup(tab);
          const groupChildren = TAB_GROUPS.find((g) => g.key === activeGroup)?.children ?? [];
          return (
            <>
              <nav className="flex gap-0.5 border-b border-[var(--border-default)] overflow-x-auto -mx-4 px-4">
                {TAB_GROUPS.map((g) => {
                  const isActive = activeGroup === g.key;
                  return (
                    <button
                      key={g.key}
                      onClick={() => setTab(g.children[0].key)}
                      className={cn(
                        'px-3 py-1.5 text-xs font-semibold whitespace-nowrap transition-colors border-b-2 -mb-px',
                        isActive
                          ? 'border-accent-blue text-primary'
                          : 'border-transparent text-muted hover:text-secondary',
                      )}
                    >
                      {g.label}
                    </button>
                  );
                })}
              </nav>
              {groupChildren.length > 1 && (
                <nav className="flex gap-0.5 overflow-x-auto -mx-4 px-4 pt-1">
                  {groupChildren.map((t) => (
                    <button
                      key={t.key}
                      onClick={() => setTab(t.key)}
                      className={cn(
                        'px-2.5 py-1 text-[11px] font-medium whitespace-nowrap rounded-sm transition-colors',
                        tab === t.key
                          ? 'bg-accent-blue/10 text-accent-blue border border-accent-blue/30'
                          : 'border border-transparent text-muted hover:text-secondary hover:border-[var(--border-default)]',
                      )}
                    >
                      {t.label}
                    </button>
                  ))}
                </nav>
              )}
            </>
          );
        })()}
      </div>

      {/* Body: left runs list + right tab content.
          Mobile: stack vertically (runs list collapses to a horizontal scroller above content).
          Desktop ≥md: side-by-side with own scroll regions. */}
      <div className="flex-1 min-h-0 flex flex-col lg:flex-row overflow-hidden">
        {showLeftList && (
          <aside className="w-full lg:w-[300px] flex-shrink-0 border-b lg:border-b-0 lg:border-r border-[var(--border-default)] overflow-y-auto max-h-40 lg:max-h-none">
            <RunsSidebar
              tasks={tasks}
              selectedRunId={selectedRunId}
              onSelect={(id) => {
                setSelectedRunId(id);
                if (tab !== 'tasks') setTab('tasks');
              }}
            />
          </aside>
        )}
        <main
          className={cn(
            'flex-1 min-w-0 min-h-0',
            // Chat owns its own internal scroll + pinned input — give it the
            // raw pane with no padding/overflow so dimensions line up with
            // the surrounding layout.
            tab === 'chat' ? 'flex flex-col' : 'overflow-y-auto p-3 sm:p-4',
          )}
        >
          {currentContent}
        </main>
      </div>
    </div>
  );
}

function OverviewContent({
  projectId, dashboard, scoreTrend, project, onProjectUpdate, repos,
}: { projectId?: string; dashboard: any; scoreTrend: any; project: api.ProjectDto | null; onProjectUpdate: (p: api.ProjectDto) => void; repos: any[] }) {
  const tasks: TaskSummary[] = dashboard?.recentTasks ?? [];
  return (
    <div className="space-y-4 max-w-3xl">
      {projectId && project && <ProjectSettingsPanel projectId={projectId} project={project} onUpdate={onProjectUpdate} />}
      {projectId && <ProjectSummaryPanel projectId={projectId} />}
      {projectId && <GeneratedDocsPanel projectId={projectId} />}
      {projectId && <WorkspaceHealthPanel projectId={projectId} />}
      {/* E2.4 — one ContractCoveragePanel per linked repo (UX-preserved:
          same per-repo card pattern as the WorkspaceHealthPanel above). */}
      {projectId && repos.length > 0 && (
        <div className="space-y-2">
          {repos.map((r: any) => (
            <ContractCoveragePanel
              key={r.id ?? r.repoId}
              projectId={projectId}
              repoId={r.id ?? r.repoId}
              repoFullName={r.githubRepoFullName ?? r.repoName}
            />
          ))}
        </div>
      )}
      {projectId && <TechDebtPanel projectId={projectId} />}
      {projectId && project?.orgId && (
        <StandardsCompliancePanel projectId={projectId} orgId={project.orgId} />
      )}
      <div className="space-y-2">
        <h3 className="text-xs font-medium text-secondary">Recent submissions</h3>
        <ExecutionsPanel tasks={tasks} />
      </div>
      <div className="space-y-2">
        <h3 className="text-xs font-medium text-secondary">Recent pull requests</h3>
        <ProjectPrList prs={dashboard?.recentPrs ?? []} />
      </div>
      <div>
        <h3 className="text-xs font-medium text-secondary mb-2">Score trend</h3>
        <ProjectScoreTrend scores={scoreTrend} />
      </div>
    </div>
  );
}

function ProjectSettingsPanel({
  projectId, project, onUpdate,
}: { projectId: string; project: api.ProjectDto; onUpdate: (p: api.ProjectDto) => void }) {
  const [owner, setOwner] = useState(project.githubOwner ?? '');
  const [saving, setSaving] = useState(false);

  const dirty = owner.trim() !== (project.githubOwner ?? '').trim();

  const save = async () => {
    if (!dirty) return;
    setSaving(true);
    try {
      const updated = await api.updateProjectSettings(projectId, { githubOwner: owner.trim() || undefined });
      onUpdate(updated);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2">
      <h3 className="text-xs font-medium text-secondary">Project settings</h3>
      <div className="flex items-center gap-2">
        <label className="text-[11px] text-muted whitespace-nowrap">GitHub Owner</label>
        <input
          type="text"
          value={owner}
          onChange={(e) => setOwner(e.target.value)}
          placeholder="e.g. my-org"
          className="flex-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--bg-primary)] text-primary placeholder:text-muted/50 focus:outline-none focus:border-accent-blue"
        />
        {dirty && (
          <button
            onClick={save}
            disabled={saving}
            className="text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50"
          >
            {saving ? 'Saving...' : 'Save'}
          </button>
        )}
      </div>
      <p className="text-[10px] text-muted">
        Default GitHub org/owner for new repos created during a Launch. Overridable per-repo.
      </p>
    </div>
  );
}

function ProjectSummaryPanel({ projectId }: { projectId: string }) {
  const [content, setContent] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const refresh = () => {
    setLoading(true);
    api.getProjectSummary(projectId)
      .then((r) => setContent(r.content || ''))
      .catch(() => setContent(''))
      .finally(() => setLoading(false));
  };
  useEffect(refresh, [projectId]);

  const analyse = async () => {
    setBusy(true);
    try {
      await api.analyseProject(projectId);
      refresh();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-medium text-secondary">Project summary</h3>
        <button
          onClick={analyse}
          disabled={busy}
          className="inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50"
          title="Regenerate codemaps + architecture + summary"
        >
          {busy ? 'Analysing…' : 'Analyse'}
        </button>
      </div>
      {loading ? (
        <div className="text-xs text-muted">Loading…</div>
      ) : content ? (
        <p className="text-xs leading-relaxed text-secondary whitespace-pre-wrap">{content}</p>
      ) : (
        <p className="text-xs text-muted">No summary yet — click Analyse to generate one.</p>
      )}
    </div>
  );
}

function ExecutionsPanel({ tasks }: { tasks: TaskSummary[] }) {
  if (tasks.length === 0) {
    return (
      <EmptyState
        compact
        icon={Rocket}
        title="Nothing submitted yet"
        description='Click "Launch" above to kick off your first cross-repo execution.'
      />
    );
  }
  return (
    <ul className="space-y-1">
      {tasks.slice(0, 8).map((t) => {
        return (
          <li
            key={t.taskId}
            className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2"
          >
            <div className="flex items-center gap-2">
              <StatusBadge status={t.status} />
              <span className="text-xs text-primary truncate flex-1">{t.name || t.runId.slice(0, 8)}</span>
              <span className={cn('text-xs font-mono tabular-nums', scoreColor(t.score))}>
                {t.score != null ? `${t.score}/100` : '—'}
              </span>
            </div>
            <div className="text-[11px] text-muted font-mono mt-0.5">
              {t.runId.slice(0, 8)} · {t.createdAt ? new Date(t.createdAt).toLocaleString() : '—'}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

function RunsSidebar({
  tasks, selectedRunId, onSelect,
}: {
  tasks: TaskSummary[];
  selectedRunId: string | null;
  onSelect: (runId: string) => void;
}) {
  if (tasks.length === 0) {
    return <div className="text-xs text-muted p-3">No runs yet.</div>;
  }
  return (
    <div>
      <div className="px-3 py-2 text-[11px] uppercase tracking-wide text-muted border-b border-[var(--border-default)]">
        Runs ({tasks.length})
      </div>
      <ul>
        {tasks.map((t) => {
          const selected = t.runId === selectedRunId;
          return (
            <li
              key={t.taskId}
              onClick={() => onSelect(t.runId)}
              className={cn(
                'border-b border-[var(--border-default)] px-3 py-2 cursor-pointer transition-colors',
                selected ? 'bg-accent-blue/10' : 'hover:bg-[var(--gray-200)]',
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <StatusBadge status={t.status} />
                <span className={cn('text-[11px] font-mono tabular-nums', scoreColor(t.score))}>
                  {t.score != null ? t.score : '—'}
                </span>
              </div>
              <div className={cn(
                'text-sm truncate mt-0.5',
                selected ? 'text-primary' : 'text-secondary',
              )}>
                {t.name || t.runId.slice(0, 8)}
              </div>
              <div className="text-[11px] text-muted font-mono mt-0.5">
                {t.createdAt ? new Date(t.createdAt).toLocaleString() : '—'}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function MetricTile({ label, value, accent }: { label: string; value: string | number; accent?: string }) {
  const isEmpty = value === '—' || value === 0 || value === '0';
  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2.5 py-2">
      <div className="text-[11px] font-medium text-secondary uppercase tracking-wide">{label}</div>
      {isEmpty && value === '—' ? (
        <div className="text-[11px] text-muted italic mt-1.5">No data yet</div>
      ) : (
        <div className={cn('text-lg font-semibold tabular-nums mt-0.5', accent ?? 'text-primary')}>
          {value}
        </div>
      )}
    </div>
  );
}

function scoreAccent(score: number | null | undefined): string {
  // v2.6.0 UI polish — 4-band graduated palette (see docs/design/ui-tokens.md).
  // Binary >=90 green / <75 red was too harsh — borderline scores read as failures.
  if (score == null) return 'text-muted';
  if (score >= 85) return 'text-status-success';
  if (score >= 70) return 'text-accent-blue';
  if (score >= 50) return 'text-status-waiting';
  return 'text-status-error';
}

function formatK(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

function NewExecutionButton({ projectId, repoCount }: { projectId: string; repoCount: number }) {
  const navigate = useNavigate();
  const disabled = repoCount === 0;
  const title = disabled
    ? 'Link at least one repository to launch.'
    : 'Start a cross-repo Launch (ENTERPRISE / MAX)';
  return (
    <div className="flex flex-col items-end gap-1">
      <button
        onClick={() => !disabled && navigate(`/projects/${projectId}/executions/new`)}
        disabled={disabled}
        aria-label={title}
        className={
          'flex items-center gap-1.5 text-xs font-medium rounded-sm border px-2.5 py-1 transition-colors ' +
          (disabled
            ? 'border-[var(--border-default)] bg-[var(--gray-100)] text-muted cursor-not-allowed opacity-60'
            : 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20')
        }
        title={title}
      >
        <Rocket className="h-3 w-3" />
        Launch
      </button>
      {disabled && (
        <span className="text-[10px] text-muted italic whitespace-nowrap">
          Link a repo to enable
        </span>
      )}
    </div>
  );
}
