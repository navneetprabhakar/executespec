import { useCallback, useEffect, useRef, useState, useReducer } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import { useUiStore } from '@/stores/useUiStore';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsSuperAdmin, selectIsOrgMember } from '@/stores/useAuthStore';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import { useIsMobile } from '@/hooks/useIsMobile';
import {
  getFiles, getFile,
  getOpenApiSpec,
  getProjects, createProject, updateRunProject,
  applyToRun, editRunPlan,
  getTestPlan, getTestGraph,
  getLiveCost,
} from '@/lib/api';
import type { RunDetail, AgentTokenEntry, ProjectDto, TestTaskDto, TestGraphData } from '@/lib/api';
import GlowDot from '@/components/shared/GlowDot';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import TaskCard from './TaskCard';
import LiveLog from './LiveLog';
import RunConversation from './RunConversation';
import TaskGraph from './TaskGraph';
import PreviewTab from './PreviewTab';
import { PIPELINE_STEPS } from '@/lib/constants';
import { useViewMode } from '@/hooks/useViewMode';
import { fmtTokens, relativeTime, cn } from '@/lib/utils';
import {
  ChevronDown, ChevronRight,
  Folder, FolderOpen,
  FileCode2, FileText, File,
  MessageSquare,
  ChevronsDownUp, ChevronsUpDown, Copy, Check,
  Clock, Coins, Cpu, RefreshCw, FolderKanban, X, Plus, User, Wrench, Loader2,
  TestTube2,
  Maximize2, Minimize2, PanelLeftClose, PanelLeftOpen,
} from 'lucide-react';
import hljs from 'highlight.js';
import Editor from '@monaco-editor/react';
import type { editor as monacoEditor } from 'monaco-editor';

const BASE_TABS = [
  { value: 'overview',  label: 'Overview' },
  { value: 'plan',      label: 'Plan' },
  { value: 'tasks',     label: 'Tasks' },
  { value: 'graph',     label: 'Graph' },
  { value: 'log',       label: 'Live Log' },
  { value: 'code',      label: 'Code' },
];

export default function RunDetailTabs() {
  const { activeTab, setActiveTab } = useUiStore();
  const activeRun = useRunStore((s) => s.activeRun);
  const fetchRun = useRunStore((s) => s.fetchRun);
  const fetchRuns = useRunStore((s) => s.fetchRuns);
  const [refreshing, setRefreshing] = useState(false);

  // Hoist files so both Code and Preview tabs share them
  const [files, setFiles] = useState<string[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  // Bump this to force re-fetch files (e.g. on manual refresh)
  const [filesTick, bumpFiles] = useReducer((n: number) => n + 1, 0);

  const loadFiles = useCallback((runId: string) => {
    setFilesLoading(true);
    setFiles([]);
    getFiles(runId)
      .then(setFiles)
      .catch(() => {})
      .finally(() => setFilesLoading(false));
  }, []);

  useEffect(() => {
    if (!activeRun) return;
    loadFiles(activeRun.runId);
  }, [activeRun?.runId, filesTick, loadFiles]);
  // Test generation tasks
  const [testTasks, setTestTasks] = useState<TestTaskDto[]>([]);
  const [testGraph, setTestGraph] = useState<TestGraphData | null>(null);

  useEffect(() => {
    if (!activeRun) return;
    getTestPlan(activeRun.runId).then((plan) => {
      if (plan) setTestTasks(plan.tasks);
    }).catch(() => {});
    getTestGraph(activeRun.runId).then((graph) => {
      if (graph) setTestGraph(graph);
    }).catch(() => {});
  }, [activeRun?.runId, filesTick]);

  // Note: live task updates are driven entirely by SSE (taskStatus events → fetchRun).
  // useSse also re-fetches on reconnect to catch any events missed during a gap.

  const handleRefresh = useCallback(async () => {
    if (!activeRun || refreshing) return;
    setRefreshing(true);
    try {
      await fetchRun(activeRun.runId);
      await fetchRuns();
      bumpFiles();
    } catch {
      // silent
    } finally {
      setRefreshing(false);
    }
  }, [activeRun, refreshing, fetchRun, fetchRuns]);

  const { isVibe } = useViewMode();

  if (!activeRun) return null;

  /** Chat & Preview tabs are only shown for terminal (COMPLETE / PARTIAL) runs. */
  const showTerminalTabs = activeRun.status === 'COMPLETE' || activeRun.status === 'PARTIAL';
  const topLevelPermissions = useTierPermissions();

  const VIBE_TABS = [
    { value: 'code', label: 'Code' },
    ...(showTerminalTabs ? [{ value: 'preview', label: 'Preview' }] : []),
  ];

  const TABS = isVibe ? VIBE_TABS : [
    ...BASE_TABS.filter(t => t.value !== 'log' || topLevelPermissions?.canViewLiveLog !== false),
    ...(showTerminalTabs ? [
      { value: 'preview', label: 'Preview' },
      { value: 'chat', label: 'Chat' },
    ] : []),
  ];

  return (
    <Tabs.Root value={activeTab} onValueChange={setActiveTab} className="flex flex-col h-full">
      <Tabs.List className="flex gap-0 border-b border-white/[0.06] flex-shrink-0 px-2 lg:px-4 overflow-x-auto scrollbar-hide">
        {TABS.map((tab) => (
          <Tabs.Trigger
            key={tab.value}
            value={tab.value}
            className={cn(
              'px-2.5 lg:px-4 py-2 lg:py-2.5 text-[10px] lg:text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors whitespace-nowrap',
              'data-[state=active]:border-accent-violet data-[state=active]:text-white',
              'data-[state=inactive]:border-transparent data-[state=inactive]:text-zinc-500',
              'hover:text-zinc-300'
            )}
          >
            {tab.value === 'chat' ? (
              <span className="flex items-center gap-1.5">
                <MessageSquare className="h-3 w-3" />
                {tab.label}
              </span>
            ) : tab.label}
          </Tabs.Trigger>
        ))}
        <button
          onClick={handleRefresh}
          disabled={refreshing}
          className="ml-auto flex-shrink-0 p-2 min-h-[36px] min-w-[36px] flex items-center justify-center text-zinc-600 hover:text-zinc-300 transition-colors disabled:opacity-50"
          title="Refresh run data"
        >
          <RefreshCw className={cn('h-3.5 w-3.5', refreshing && 'animate-spin')} />
        </button>
      </Tabs.List>

      <div className="flex-1 overflow-hidden">
        <Tabs.Content value="overview" className="h-full overflow-y-auto p-4">
          <OverviewTab run={activeRun} fetchRun={fetchRun} fetchRuns={fetchRuns} />
        </Tabs.Content>

        <Tabs.Content value="tasks" className="h-full overflow-y-auto p-4 space-y-2">
          {activeRun.tasks.length === 0 && testTasks.length === 0
            ? <p className="text-sm text-zinc-500">No tasks yet.</p>
            : (
              <>
                {activeRun.tasks.map((t) => (
                  <TaskCard key={t.taskId} task={t} runId={activeRun.runId} runStatus={activeRun.status} />
                ))}

                {testTasks.length > 0 && (
                  <>
                    <div className="flex items-center gap-2 pt-4 pb-1 border-t border-white/[0.06]">
                      <TestTube2 className="h-4 w-4 text-violet-400" />
                      <span className="text-sm font-medium text-zinc-300">Test Generation Tasks</span>
                      <span className="text-xs text-zinc-500">({testTasks.length})</span>
                    </div>
                    {testTasks.map((tt) => (
                      <TestTaskCard key={tt.id} task={tt} />
                    ))}
                  </>
                )}
              </>
            )
          }
        </Tabs.Content>

        <Tabs.Content value="log" className="h-full">
          <LiveLog />
        </Tabs.Content>

        <Tabs.Content value="plan" className="h-full overflow-y-auto p-4">
          <PlanTab run={activeRun} />
        </Tabs.Content>

        <Tabs.Content value="code" className="h-full overflow-hidden">
          <CodeTab runId={activeRun.runId} files={files} filesLoading={filesLoading} repoUrl={activeRun.repoUrl} status={activeRun.status} />
        </Tabs.Content>

        <Tabs.Content value="graph" className="h-full overflow-auto p-4">
          <TaskGraph runId={activeRun.runId} />
          {testGraph && testGraph.nodes.length > 0 && (
            <TestGraphSection graph={testGraph} />
          )}
        </Tabs.Content>

        {showTerminalTabs && (
          <Tabs.Content value="preview" className="h-full overflow-hidden">
            <PreviewTab runId={activeRun.runId} />
          </Tabs.Content>
        )}

        {showTerminalTabs && (
          <Tabs.Content value="chat" className="h-full overflow-hidden">
            <RunConversation runId={activeRun.runId} />
          </Tabs.Content>
        )}
      </div>
    </Tabs.Root>
  );
}

// ── Overview tab ─────────────────────────────────────────────────────────────

/** Format duration in milliseconds to human-readable string. */
function fmtDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  if (m < 60) return `${m}m ${rs}s`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return `${h}h ${rm}m`;
}

/** Aggregate token counts per model from task list + pipeline agent entries (planner/reviewer/compiler). */
function tokensByModel(
  tasks: RunDetail['tasks'],
  agentTokens?: AgentTokenEntry[],
): { model: string; provider: string | null; input: number; output: number; role?: string }[] {
  const map = new Map<string, { provider: string | null; input: number; output: number; role?: string }>();
  // Worker task tokens
  for (const t of tasks) {
    const model = t.model || t.assignedModel || 'unknown';
    const entry = map.get(model) || { provider: null, input: 0, output: 0 };
    entry.input += t.inputTokens || 0;
    entry.output += t.outputTokens || 0;
    if (t.provider) entry.provider = t.provider;
    map.set(model, entry);
  }
  // Pipeline agent tokens (planner, reviewer, compiler) — keyed by "role:model" to avoid
  // merging planner (sonnet) with a worker that happens to use the same model name
  if (agentTokens) {
    for (const a of agentTokens) {
      const model = a.model || 'unknown';
      const key = `${a.role}:${model}`;
      const entry = map.get(key) || { provider: null, input: 0, output: 0, role: a.role };
      entry.input += a.inputTokens || 0;
      entry.output += a.outputTokens || 0;
      if (a.provider) entry.provider = a.provider;
      entry.role = a.role;
      map.set(key, entry);
    }
  }
  return Array.from(map.entries())
    .map(([key, { provider, input, output, role }]) => ({
      // Strip the "role:" prefix for agent entries to show the real model name
      model: key.includes(':') ? key.split(':').slice(1).join(':') : key,
      provider, input, output, role,
    }))
    .sort((a, b) => (b.input + b.output) - (a.input + a.output));
}

function OverviewTab({ run, fetchRun, fetchRuns }: {
  run: RunDetail;
  fetchRun: (id: string) => Promise<void>;
  fetchRuns: () => Promise<void>;
}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const permissions = useTierPermissions();

  // ── Live cost ──────────────────────────────────────────────────────
  const [liveCost, setLiveCost] = useState<number | null>(null);
  useEffect(() => {
    let cancelled = false;
    getLiveCost(run.runId)
      .then((res) => { if (!cancelled) setLiveCost(res.costUsd); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [run.runId, run.status]);

  // ── Project tagging ──────────────────────────────────────────────────
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [projectDropdownOpen, setProjectDropdownOpen] = useState(false);
  const [projectSaving, setProjectSaving] = useState(false);
  const [creatingProject, setCreatingProject] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  const projectDropdownRef = useRef<HTMLDivElement>(null);
  const newProjectInputRef = useRef<HTMLInputElement>(null);

  const refreshProjects = useCallback(() => {
    if (!isOrgMember) return;
    getProjects()
      .then((p) => setProjects(p.filter((proj) => proj.status === 'ACTIVE')))
      .catch(() => setProjects([]));
  }, [isOrgMember]);

  useEffect(() => { refreshProjects(); }, [refreshProjects]);

  // Close dropdown on outside click
  useEffect(() => {
    if (!projectDropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (projectDropdownRef.current && !projectDropdownRef.current.contains(e.target as Node)) {
        setProjectDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [projectDropdownOpen]);

  const handleProjectChange = useCallback(async (projectId: string | null) => {
    setProjectSaving(true);
    setProjectDropdownOpen(false);
    setCreatingProject(false);
    try {
      await updateRunProject(run.runId, projectId);
      await fetchRun(run.runId);
      await fetchRuns();
    } catch {
      // silent
    } finally {
      setProjectSaving(false);
    }
  }, [run.runId, fetchRun, fetchRuns]);

  const handleCreateProject = useCallback(async () => {
    const name = newProjectName.trim();
    if (!name) return;
    setProjectSaving(true);
    try {
      const created = await createProject({ name });
      refreshProjects();
      // Immediately assign the run to the new project
      await updateRunProject(run.runId, created.id);
      await fetchRun(run.runId);
      await fetchRuns();
    } catch {
      // silent
    } finally {
      setProjectSaving(false);
      setCreatingProject(false);
      setNewProjectName('');
      setProjectDropdownOpen(false);
    }
  }, [newProjectName, run.runId, fetchRun, fetchRuns, refreshProjects]);

  // STUCK occurs during the WORKING phase — map it there for pipeline step display
  const displayStatus = run.status === 'STUCK' ? 'WORKING' : run.status;
  const stepIndex = PIPELINE_STEPS.indexOf(displayStatus);
  const totalTokens = (run.totalInputTokens || 0) + (run.totalOutputTokens || 0);

  // Compute run duration
  const startMs = run.startedAt ? new Date(run.startedAt).getTime() : 0;
  const endMs = run.completedAt ? new Date(run.completedAt).getTime() : Date.now();
  const durationMs = startMs ? endMs - startMs : 0;
  const isTerminal = ['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(run.status);

  return (
    <div className="space-y-6">
      {/* Stuck warning banner */}
      {run.status === 'STUCK' && (
        <div className="flex items-start gap-2 p-3 rounded-sm bg-accent-rose/10 border border-accent-rose/20">
          <span className="text-accent-rose text-sm flex-shrink-0">⚠</span>
          <div>
            <p className="text-sm font-medium text-accent-rose">Run is stuck</p>
            <p className="text-xs text-accent-rose/70 mt-0.5">
              No activity detected for 30+ minutes. Recovery has been triggered automatically.
            </p>
          </div>
        </div>
      )}

      {/* Pipeline timeline */}
      <div className="space-y-0">
        {PIPELINE_STEPS.map((step, i) => {
          const isDone = i < stepIndex;
          const isActive = step === displayStatus;
          return (
            <div key={step} className="flex items-start gap-3 relative">
              {/* Connector line */}
              {i < PIPELINE_STEPS.length - 1 && (
                <div className={cn(
                  'absolute left-[11px] top-6 w-0.5 h-5 -bottom-1',
                  isDone ? 'bg-accent-emerald/40' : 'bg-white/[0.06]'
                )} />
              )}
              <div className={cn(
                'relative z-10 h-6 w-6 rounded-full border flex items-center justify-center flex-shrink-0 mt-0.5',
                isDone ? 'border-accent-emerald bg-accent-emerald/20' :
                isActive ? 'border-accent-violet bg-accent-violet/20' :
                'border-white/[0.10] bg-transparent'
              )}>
                {isDone ? (
                  <span className="text-accent-emerald text-xs">✓</span>
                ) : isActive ? (
                  <span className="h-2 w-2 rounded-full bg-accent-violet animate-pulse" />
                ) : (
                  <span className="h-1.5 w-1.5 rounded-full bg-white/[0.15]" />
                )}
              </div>
              <div className="pb-5">
                <p className={cn(
                  'text-sm font-medium',
                  isDone ? 'text-accent-emerald' :
                  isActive ? 'text-white' : 'text-zinc-600'
                )}>
                  {step.replace(/_/g, ' ')}
                </p>
              </div>
            </div>
          );
        })}
      </div>

      {/* Run stats: time taken */}
      <div className="pt-2 border-t border-white/[0.06]">
        <div className="flex items-center gap-2 mb-3">
          <Clock className="h-3.5 w-3.5 text-zinc-500" />
          <span className="text-xs font-medium text-zinc-400 uppercase tracking-wider">Run Stats</span>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <p className="text-xs text-zinc-600">Time taken</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">
              {durationMs > 0
                ? `${fmtDuration(durationMs)}${!isTerminal ? ' (running)' : ''}`
                : '—'}
            </p>
          </div>
          <div>
            <p className="text-xs text-zinc-600">Started</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">{relativeTime(run.startedAt)}</p>
          </div>
          <div>
            <p className="text-xs text-zinc-600">Tasks</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">{String(run.tasks.length)}</p>
          </div>
          <div>
            <p className="text-xs text-zinc-600">Total tokens</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono">
              {totalTokens > 0 ? fmtTokens(totalTokens) : '—'}
            </p>
          </div>
          {run.createdByName && (
            <div>
              <p className="text-xs text-zinc-600">Created by</p>
              <p className="text-sm text-zinc-200 mt-0.5 flex items-center gap-1.5">
                <User className="h-3 w-3 text-indigo-400" />
                {run.createdByName}
              </p>
            </div>
          )}
          {/* Project selector — org members can assign/change/remove/create project */}
          {isOrgMember ? (
            <div className="relative" ref={projectDropdownRef}>
              <p className="text-xs text-zinc-600">Project</p>
              <button
                onClick={() => { setProjectDropdownOpen((v) => !v); setCreatingProject(false); }}
                disabled={projectSaving}
                className={cn(
                  'mt-0.5 flex items-center gap-1.5 text-sm rounded px-1.5 py-0.5 -mx-1.5 transition-colors',
                  'hover:bg-white/[0.06] cursor-pointer',
                  projectSaving && 'opacity-50 pointer-events-none',
                )}
              >
                <FolderKanban className="h-3 w-3 text-cyan-400 flex-shrink-0" />
                <span className={run.projectName ? 'text-zinc-200' : 'text-zinc-500 italic'}>
                  {run.projectName ?? 'Assign project…'}
                </span>
                <ChevronDown className="h-3 w-3 text-zinc-500" />
              </button>

              {projectDropdownOpen && (
                <div className="absolute left-0 top-full mt-1 z-50 min-w-[200px] rounded-md border border-white/[0.08] bg-surface-2  py-1">
                  {/* Un-assign option — only if currently assigned */}
                  {run.projectId && (
                    <button
                      onClick={() => handleProjectChange(null)}
                      className="flex items-center gap-2 w-full px-3 py-1.5 text-sm text-zinc-400 hover:bg-white/[0.06] hover:text-zinc-200 transition-colors"
                    >
                      <X className="h-3 w-3" />
                      Remove from project
                    </button>
                  )}
                  {projects.map((proj) => (
                    <button
                      key={proj.id}
                      onClick={() => handleProjectChange(proj.id)}
                      className={cn(
                        'flex items-center gap-2 w-full px-3 py-1.5 text-sm transition-colors',
                        run.projectId === proj.id
                          ? 'text-cyan-400 bg-white/[0.04]'
                          : 'text-zinc-300 hover:bg-white/[0.06]',
                      )}
                    >
                      <FolderKanban className="h-3 w-3 flex-shrink-0" />
                      {proj.name}
                    </button>
                  ))}

                  {/* Divider */}
                  {(projects.length > 0 || run.projectId) && (
                    <div className="border-t border-white/[0.06] my-1" />
                  )}

                  {/* Create new project */}
                  {creatingProject ? (
                    <form
                      onSubmit={(e) => { e.preventDefault(); handleCreateProject(); }}
                      className="px-2 py-1.5"
                    >
                      <input
                        ref={newProjectInputRef}
                        type="text"
                        value={newProjectName}
                        onChange={(e) => setNewProjectName(e.target.value)}
                        placeholder="Project name…"
                        autoFocus
                        className="w-full bg-white/[0.04] border border-white/[0.10] rounded px-2 py-1 text-sm text-zinc-200 placeholder-zinc-600 outline-none focus:border-accent-violet/50"
                        onKeyDown={(e) => { if (e.key === 'Escape') { setCreatingProject(false); setNewProjectName(''); } }}
                      />
                      <div className="flex items-center gap-1.5 mt-1.5">
                        <button
                          type="submit"
                          disabled={!newProjectName.trim() || projectSaving}
                          className="flex-1 text-xs text-center py-1 rounded bg-accent-violet/20 text-accent-violet hover:bg-accent-violet/30 transition-colors disabled:opacity-40"
                        >
                          Create & assign
                        </button>
                        <button
                          type="button"
                          onClick={() => { setCreatingProject(false); setNewProjectName(''); }}
                          className="text-xs text-zinc-500 hover:text-zinc-300 px-2 py-1 transition-colors"
                        >
                          Cancel
                        </button>
                      </div>
                    </form>
                  ) : (
                    <button
                      onClick={() => { setCreatingProject(true); setTimeout(() => newProjectInputRef.current?.focus(), 0); }}
                      className="flex items-center gap-2 w-full px-3 py-1.5 text-sm text-accent-violet hover:bg-white/[0.06] transition-colors"
                    >
                      <Plus className="h-3 w-3" />
                      New project…
                    </button>
                  )}
                </div>
              )}
            </div>
          ) : run.projectName ? (
            <div>
              <p className="text-xs text-zinc-600">Project</p>
              <p className="text-sm text-zinc-200 mt-0.5 flex items-center gap-1.5">
                <FolderOpen className="h-3 w-3 text-cyan-400" />
                {run.projectName}
              </p>
            </div>
          ) : null}
        </div>
      </div>

      {/* Super admin: cost breakdown */}
      {isSuperAdmin && (
        <div className="pt-3 border-t border-white/[0.06]">
          <div className="flex items-center gap-2 mb-3">
            <Coins className="h-3.5 w-3.5 text-accent-amber/70" />
            <span className="text-xs font-medium text-zinc-400 uppercase tracking-wider">Cost Breakdown</span>
            <span className="text-[9px] text-zinc-600 bg-white/[0.04] px-1.5 py-0.5 rounded-full">Admin</span>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-xs text-zinc-600">Live cost</p>
              <p className="text-sm text-accent-emerald mt-0.5 font-mono font-medium">
                {liveCost != null ? `$${liveCost.toFixed(4)}` : run.actualCostUsd != null ? `$${run.actualCostUsd.toFixed(4)}` : '—'}
              </p>
              {run.costEstimateLow != null && run.costEstimateHigh != null && (
                <p className="text-[10px] text-zinc-600 mt-0.5 font-mono">
                  est. ${run.costEstimateLow.toFixed(2)} – ${run.costEstimateHigh.toFixed(2)}
                </p>
              )}
            </div>
            <div>
              <p className="text-xs text-zinc-600">Key source</p>
              <p className="text-sm text-zinc-200 mt-0.5 font-mono">
                {run.keySource ?? '—'}
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Token usage by model — visible to users with canViewTokenUsage */}
      {permissions?.canViewTokenUsage && tokensByModel(run.tasks, run.agentTokens).length > 0 && (
        <div className="pt-3 border-t border-white/[0.06]">
          <div className="flex items-center gap-2 mb-3">
            <Cpu className="h-3.5 w-3.5 text-zinc-500" />
            <span className="text-xs font-medium text-zinc-400 uppercase tracking-wider">Token Usage by Model</span>
          </div>
          <div className="space-y-1.5">
            {tokensByModel(run.tasks, run.agentTokens).map(({ model, provider, input, output, role }) => (
              <div key={role ? `${role}:${model}` : model} className="flex items-center gap-3 p-2 rounded bg-white/[0.02] border border-white/[0.04]">
                <div className="flex items-center gap-1.5 flex-1 min-w-0">
                  {role && (
                    <span className="text-[10px] font-medium text-zinc-500 bg-white/[0.04] px-1.5 py-0.5 rounded flex-shrink-0 capitalize">
                      {role}
                    </span>
                  )}
                  {provider && (
                    <span className="text-[10px] font-mono text-zinc-600 bg-white/[0.03] px-1 py-0.5 rounded flex-shrink-0">
                      {provider}
                    </span>
                  )}
                  <span className="text-xs font-mono text-zinc-300 truncate">{model}</span>
                </div>
                <div className="flex items-center gap-3 flex-shrink-0 text-[11px] font-mono">
                  <span className="text-accent-cyan/70">
                    {fmtTokens(input)} in
                  </span>
                  <span className="text-accent-violet/70">
                    {fmtTokens(output)} out
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Model assignment */}
      <div className="grid grid-cols-3 gap-3 pt-2 border-t border-white/[0.06]">
        {[
          { label: 'Planner', value: run.plannerModel },
          { label: 'Worker', value: run.workerModel },
          { label: 'Reviewer', value: run.reviewerModel },
        ].map(({ label, value }) => (
          <div key={label}>
            <p className="text-xs text-zinc-600">{label}</p>
            <p className="text-sm text-zinc-200 mt-0.5 font-mono truncate" title={value}>{value}</p>
          </div>
        ))}
      </div>

      {/* Goal — the user's original request (markdown rendered) */}
      {run.userRequest && (
        <div className="pt-3 border-t border-white/[0.06]">
          <p className="text-xs text-zinc-600 mb-1.5">Goal</p>
          <div className="line-clamp-8">
            <MarkdownContent content={run.userRequest} />
          </div>
        </div>
      )}
    </div>
  );
}

// ── Plan tab ──────────────────────────────────────────────────────────────────

function PlanTab({ run }: { run: RunDetail }) {
  if (!run.planJson) {
    return <p className="text-sm text-zinc-500">Plan not available yet.</p>;
  }

  let plan: any = null;
  try {
    plan = JSON.parse(run.planJson);
  } catch {
    return (
      <pre className="text-xs font-mono text-zinc-400 whitespace-pre-wrap break-all">
        {run.planJson}
      </pre>
    );
  }

  const canEdit = run.status === 'AWAITING_APPROVAL';

  return (
    <div className="space-y-4">
      {plan.plan_summary && (
        <div className="p-3 rounded-sm bg-white/[0.02] border border-white/[0.06]">
          <p className="text-[10px] uppercase tracking-wider text-zinc-600 mb-1">Plan Summary</p>
          <p className="text-sm text-zinc-200 leading-relaxed">{plan.plan_summary}</p>
        </div>
      )}

      {Array.isArray(plan.tasks) && plan.tasks.length > 0 && (
        <div className="space-y-3">
          <h4 className="text-xs font-medium text-zinc-500 uppercase tracking-wider">
            Tasks ({plan.tasks.length})
          </h4>
          {plan.tasks.map((t: any, i: number) => (
            <PlanTaskCard key={t.task_id ?? i} task={t} index={i} runId={run.runId} canEdit={canEdit} />
          ))}
        </div>
      )}
    </div>
  );
}

// ── Plan task card (expandable) ───────────────────────────────────────────────

function PlanTaskCard({ task: t, index: i, runId, canEdit }: { task: any; index: number; runId: string; canEdit: boolean }) {
  const [objExpanded, setObjExpanded] = useState(false);
  const [critExpanded, setCritExpanded] = useState(false);
  const [editing, setEditing] = useState(false);
  const [feedback, setFeedback] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const { addToast } = useUiStore();
  const TRUNCATE = 220;

  const objective  = t.objective ?? '';
  const criteria   = t.acceptance_criteria ?? '';
  const objLong    = objective.length > TRUNCATE;
  const critLong   = criteria.length > TRUNCATE;

  const displayObj  = objExpanded  || !objLong  ? objective  : objective.slice(0, TRUNCATE) + '…';
  const displayCrit = critExpanded || !critLong ? criteria  : criteria.slice(0, TRUNCATE) + '…';

  return (
    <div className="rounded-sm bg-white/[0.02] border border-white/[0.06] overflow-hidden">
      {/* ── Header: index · title · complexity badge ── */}
      <div className="flex items-center justify-between gap-2 px-3 py-2.5 border-b border-white/[0.04]">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-[10px] text-zinc-600 flex-shrink-0 font-mono tabular-nums">
            #{String(i + 1).padStart(2, '0')}
          </span>
          <p className="text-sm font-medium text-zinc-200 leading-snug">{t.title}</p>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          {canEdit && !editing && (
            <button
              onClick={() => setEditing(true)}
              className="text-[10px] text-zinc-500 hover:text-accent-blue transition-colors"
            >
              Edit
            </button>
          )}
          <span className={cn(
            'text-[10px] font-medium px-1.5 py-0.5 rounded',
            t.complexity === 'HIGH'   ? 'text-accent-rose bg-accent-rose/10' :
            t.complexity === 'MEDIUM' ? 'text-accent-amber bg-accent-amber/10' :
                                        'text-accent-emerald bg-accent-emerald/10'
          )}>
            {t.complexity ?? 'MEDIUM'}
          </span>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="px-3 py-2.5 space-y-3">

        {/* Objective */}
        {objective && (
          <div>
            <p className="text-[10px] uppercase tracking-wider text-zinc-600 mb-1">Objective</p>
            <p className="text-xs text-zinc-400 leading-relaxed whitespace-pre-wrap">{displayObj}</p>
            {objLong && (
              <button
                onClick={() => setObjExpanded(!objExpanded)}
                className="text-[10px] text-accent-violet hover:text-accent-violet/70 mt-1 transition-colors"
              >
                {objExpanded ? '▲ Show less' : '▼ Show more'}
              </button>
            )}
          </div>
        )}

        {/* Acceptance criteria */}
        {criteria && (
          <div>
            <p className="text-[10px] uppercase tracking-wider text-zinc-600 mb-1">Acceptance Criteria</p>
            <div className="bg-white/[0.02] border border-white/[0.04] rounded px-2 py-1.5">
              <p className="text-xs font-mono text-zinc-500 leading-relaxed whitespace-pre-wrap">{displayCrit}</p>
            </div>
            {critLong && (
              <button
                onClick={() => setCritExpanded(!critExpanded)}
                className="text-[10px] text-accent-violet hover:text-accent-violet/70 mt-1 transition-colors"
              >
                {critExpanded ? '▲ Show less' : '▼ Show more'}
              </button>
            )}
          </div>
        )}

        {/* Footer: model chip + depends_on chips */}
        <div className="flex items-center flex-wrap gap-2 pt-0.5">
          {t.assigned_model && (
            <span className="text-[10px] font-mono text-zinc-600 bg-white/[0.02] px-1.5 py-0.5 rounded border border-white/[0.04]">
              {t.assigned_model}
            </span>
          )}
          {Array.isArray(t.depends_on) && t.depends_on.length > 0 && (
            <div className="flex items-center gap-1 flex-wrap">
              <span className="text-[10px] text-zinc-600">needs:</span>
              {t.depends_on.map((dep: string) => (
                <span
                  key={dep}
                  className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-accent-violet/10 text-accent-violet/70 border border-accent-violet/10"
                >
                  {dep}
                </span>
              ))}
            </div>
          )}
        </div>

        {/* Inline task edit */}
        {editing && (
          <div className="px-3 py-2.5 border-t border-white/[0.04] space-y-2">
            <textarea
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              placeholder={`How should "${t.title}" be changed?`}
              rows={2}
              className="w-full rounded-md border border-white/10 bg-white/[0.03] px-3 py-2 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:ring-1 focus:ring-accent-blue/50 resize-none"
              autoFocus
            />
            <div className="flex gap-2">
              <button
                disabled={submitting || !feedback.trim()}
                onClick={async () => {
                  setSubmitting(true);
                  try {
                    const taskFeedback = `Change task "${t.title}": ${feedback.trim()}`;
                    await editRunPlan(runId, taskFeedback);
                    setFeedback('');
                    setEditing(false);
                    addToast({ type: 'success', message: 'Plan updated with task feedback' });
                    await useRunStore.getState().fetchRun(runId);
                  } catch (e: any) {
                    addToast({ type: 'error', message: e.message });
                  } finally {
                    setSubmitting(false);
                  }
                }}
                className="px-2.5 py-1 text-[10px] font-medium rounded bg-accent-blue/20 text-accent-blue hover:bg-accent-blue/30 disabled:opacity-50"
              >
                {submitting ? 'Updating…' : 'Update Task'}
              </button>
              <button
                onClick={() => { setEditing(false); setFeedback(''); }}
                className="px-2.5 py-1 text-[10px] font-medium rounded text-zinc-400 hover:text-zinc-200"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Review tab ────────────────────────────────────────────────────────────────

const VERDICT_COLORS: Record<string, string> = {
  APPROVE:  'text-accent-emerald',
  REJECT:   'text-accent-rose',
  PARTIAL:  'text-accent-amber',
};

const CATEGORY_LABELS: Record<string, string> = {
  'SPEC_MISMATCH': 'Spec Mismatch',
  'MISSING_ENDPOINT': 'Missing Endpoint',
  'LOGIC_ERROR': 'Logic Error',
  'SECURITY': 'Security',
  'ERROR_HANDLING': 'Error Handling',
  'CROSS_TASK_MISMATCH': 'Cross-Task Mismatch',
  'README_INCOMPLETE': 'README Incomplete',
  'CODE_QUALITY': 'Code Quality',
  'COMPLETENESS': 'Completeness',
};

function ReviewTab({ run }: { run: RunDetail }) {
  const permissions = useTierPermissions();
  const { addToast } = useUiStore();
  const [applyingReview, setApplyingReview] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);

  if (!run.reviewJson) {
    return <p className="text-sm text-zinc-500">Review not available yet.</p>;
  }

  let review: any = null;
  try {
    review = JSON.parse(run.reviewJson);
  } catch {
    return (
      <pre className="text-xs font-mono text-zinc-400 whitespace-pre-wrap break-all">
        {run.reviewJson}
      </pre>
    );
  }

  const verdict = review.recommendation ?? review.verdict;
  const hasIssues = Array.isArray(review.issues) && review.issues.length > 0;
  const isApplyable = ['COMPLETE', 'PARTIAL'].includes(run.status) && hasIssues;

  const handleApplyReview = async () => {
    if (applyingReview) return;
    setApplyingReview(true);
    setApplyError(null);
    try {
      const result = await applyToRun(run.runId, '', 'REVIEW');
      addToast({ type: 'success', message: `Applied ${result.taskCount} review fix task${result.taskCount !== 1 ? 's' : ''}` });
      // Refresh the run to show new tasks
      useRunStore.getState().fetchRun(run.runId);
    } catch (e: any) {
      const msg = e.message ?? '';
      if (msg.includes('429')) {
        setApplyError('Apply limit reached. Create a fresh run instead.');
      } else if (msg.includes('422')) {
        setApplyError('Review fixes require too many tasks. Please create a fresh run instead.');
      } else {
        setApplyError(msg || 'Failed to apply review. Please try again.');
      }
    } finally {
      setApplyingReview(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* Verdict + confidence */}
      <div className="flex items-center justify-between p-3 rounded-sm bg-white/[0.02] border border-white/[0.06]">
        <div>
          <p className="text-xs text-zinc-500 mb-1">Verdict</p>
          <p className={cn('text-sm font-semibold', VERDICT_COLORS[verdict] ?? 'text-zinc-300')}>
            {verdict ?? '—'}
          </p>
        </div>
        {review.overall_confidence != null && (
          <div className="text-right">
            <p className="text-xs text-zinc-500 mb-1">Confidence</p>
            <p className="text-sm text-zinc-300">
              {typeof review.overall_confidence === 'number'
                ? `${Math.round(review.overall_confidence * 100)}%`
                : review.overall_confidence}
            </p>
          </div>
        )}
      </div>

      {/* Summary */}
      {review.summary && (
        <div className="p-3 rounded-sm bg-white/[0.02] border border-white/[0.06]">
          <p className="text-xs text-zinc-500 mb-1">Summary</p>
          <p className="text-sm text-zinc-300">{review.summary}</p>
        </div>
      )}

      {/* Issues */}
      {hasIssues && (
        <div className="space-y-2">
          <h4 className="text-xs font-medium text-zinc-500 uppercase tracking-wider">Issues ({review.issues.length})</h4>
          {review.issues.map((issue: any, i: number) => (
            <div key={i} className="p-3 rounded-sm bg-white/[0.02] border border-white/[0.06]">
              <div className="flex items-center gap-2 mb-1">
                <span className={cn(
                  'text-xs px-1.5 py-0.5 rounded',
                  issue.severity === 'CRITICAL' ? 'text-accent-rose bg-accent-rose/10' :
                  issue.severity === 'MINOR'    ? 'text-accent-amber bg-accent-amber/10' :
                  'text-zinc-400 bg-zinc-400/10'
                )}>
                  {issue.severity ?? 'MINOR'}
                </span>
                <span className="text-xs text-zinc-500">{CATEGORY_LABELS[issue.category] ?? issue.category}</span>
              </div>
              {issue.file && (
                <p className="text-xs text-zinc-500 font-mono mt-0.5">{issue.file}</p>
              )}
              <p className="text-sm text-zinc-300">{issue.description}</p>
              {issue.fixInstruction && (
                <p className="text-xs text-zinc-500 mt-1 italic">{issue.fixInstruction}</p>
              )}
              {issue.autoFixed && (
                <span className={cn(
                  'inline-block text-xs mt-1',
                  issue.fixSucceeded ? 'text-emerald-400' : 'text-accent-rose'
                )}>
                  {issue.fixSucceeded ? 'Auto-fixed \u2713' : 'Auto-fix failed'}
                </span>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Post-merge suggestions hidden — validation report covers this */}

      {/* Apply Review button */}
      {isApplyable && permissions?.canUseChat && (
        <div className="pt-3 border-t border-white/[0.06]">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-zinc-500">Apply review fixes to this run</p>
              {permissions.maxApplySuggestions > 0 && (
                <p className="text-[10px] text-zinc-600 font-mono mt-0.5">
                  {run.applySuggestionCount ?? 0}/{permissions.maxApplySuggestions} applied
                </p>
              )}
            </div>
            <button
              onClick={handleApplyReview}
              disabled={applyingReview}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 rounded-sm text-xs font-medium transition-all',
                'bg-amber-500/15 text-amber-400 hover:bg-amber-500/25 border border-amber-500/20',
                'disabled:opacity-40 disabled:cursor-not-allowed'
              )}
            >
              {applyingReview
                ? <><Loader2 className="h-3.5 w-3.5 animate-spin" /> Applying&hellip;</>
                : <><Wrench className="h-3.5 w-3.5" /> Apply Review</>
              }
            </button>
          </div>
          {applyError && (
            <p className="text-xs text-accent-rose mt-2">{applyError}</p>
          )}
        </div>
      )}
    </div>
  );
}

// ── File tree helpers ─────────────────────────────────────────────────────────

interface FileTreeNode {
  name: string;
  path: string;
  isFile: boolean;
  children: FileTreeNode[];
}

function buildFileTree(files: string[]): FileTreeNode[] {
  const root: Record<string, any> = {};

  for (const filePath of files) {
    const parts = filePath.split('/');
    let current = root;
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      const isLast = i === parts.length - 1;
      if (!current[part]) {
        current[part] = { _isFile: false, _path: '', _children: {} };
      }
      if (isLast) {
        current[part]._isFile = true;
        current[part]._path = filePath;
      }
      current = current[part]._children;
    }
  }

  function mapToNodes(obj: Record<string, any>, prefix = ''): FileTreeNode[] {
    return Object.entries(obj)
      .sort(([aName, aNode], [bName, bNode]) => {
        // Folders first, then files; alphabetical within each group
        if (!aNode._isFile && bNode._isFile) return -1;
        if (aNode._isFile && !bNode._isFile) return 1;
        return aName.localeCompare(bName);
      })
      .map(([name, node]) => {
        const path = prefix ? `${prefix}/${name}` : name;
        return {
          name,
          path: node._isFile ? node._path : path,
          isFile: !!node._isFile,
          children: mapToNodes(node._children, path),
        };
      });
  }

  return mapToNodes(root);
}

function FileTypeIcon({ name }: { name: string }) {
  const lower = name.toLowerCase();
  const ext = lower.includes('.') ? lower.split('.').pop()! : '';

  const codeExts = new Set(['java', 'kt', 'ts', 'tsx', 'js', 'jsx', 'py', 'go', 'rs', 'cpp', 'c', 'cs', 'rb', 'php', 'swift', 'scala']);
  const textExts = new Set(['md', 'txt', 'yaml', 'yml', 'json', 'xml', 'html', 'css', 'scss', 'sql', 'sh', 'bash', 'properties', 'env', 'conf', 'toml', 'gradle', 'gitignore', 'dockerignore']);

  const colorMap: Record<string, string> = {
    java: 'text-orange-400', kt: 'text-purple-400',
    ts: 'text-blue-400', tsx: 'text-blue-400',
    js: 'text-yellow-400', jsx: 'text-yellow-400',
    py: 'text-green-400', go: 'text-sky-400',
    rs: 'text-orange-500', sql: 'text-blue-500',
    json: 'text-yellow-500', xml: 'text-red-400',
    yml: 'text-purple-400', yaml: 'text-purple-400',
    html: 'text-orange-400', css: 'text-blue-400',
    scss: 'text-pink-400', md: 'text-zinc-400',
    sh: 'text-green-400', bash: 'text-green-400',
    gradle: 'text-green-500', properties: 'text-zinc-400',
  };

  const color = colorMap[ext] ?? 'text-zinc-500';

  if (lower === 'dockerfile' || lower === '.dockerfile') {
    return <span className="text-sky-400 font-mono text-[9px] font-bold leading-none">🐳</span>;
  }
  if (codeExts.has(ext)) {
    return <FileCode2 className={cn('h-3 w-3 flex-shrink-0', color)} />;
  }
  if (textExts.has(ext)) {
    return <FileText className={cn('h-3 w-3 flex-shrink-0', color)} />;
  }
  return <File className="h-3 w-3 flex-shrink-0 text-zinc-500" />;
}

interface TreeNodeViewProps {
  node: FileTreeNode;
  selected: string | null;
  onSelect: (path: string) => void;
  depth?: number;
  /** Increment to force-expand all folders; 0 = initial state */
  expandSignal?: number;
  /** Increment to force-collapse all folders; 0 = initial state */
  collapseSignal?: number;
}

function TreeNodeView({
  node, selected, onSelect,
  depth = 0, expandSignal = 0, collapseSignal = 0,
}: TreeNodeViewProps) {
  const [open, setOpen] = useState(depth < 2);

  // React to expand / collapse all signals
  useEffect(() => { if (expandSignal > 0) setOpen(true); }, [expandSignal]);
  useEffect(() => { if (collapseSignal > 0) setOpen(false); }, [collapseSignal]);

  const indent = depth * 12 + 8;

  if (node.isFile) {
    return (
      <button
        onClick={() => onSelect(node.path)}
        className={cn(
          'w-full text-left flex items-center gap-1.5 py-[3px] pr-2 rounded text-xs font-mono truncate transition-colors',
          selected === node.path
            ? 'bg-accent-violet/15 text-accent-violet'
            : 'text-zinc-400 hover:text-zinc-200 hover:bg-white/[0.04]'
        )}
        style={{ paddingLeft: `${indent}px` }}
        title={node.path}
      >
        <FileTypeIcon name={node.name} />
        <span className="truncate">{node.name}</span>
      </button>
    );
  }

  return (
    <div>
      <button
        onClick={() => setOpen(!open)}
        className="w-full text-left flex items-center gap-1.5 py-[3px] pr-2 rounded text-xs text-zinc-500 hover:text-zinc-300 hover:bg-white/[0.04] transition-colors"
        style={{ paddingLeft: `${indent}px` }}
      >
        <ChevronRight className={cn('h-3 w-3 flex-shrink-0 transition-transform', open && 'rotate-90')} />
        {open
          ? <FolderOpen className="h-3 w-3 flex-shrink-0 text-accent-amber/60" />
          : <Folder className="h-3 w-3 flex-shrink-0 text-accent-amber/60" />
        }
        <span>{node.name}</span>
      </button>
      {open && (
        <div>
          {node.children.map((child) => (
            <TreeNodeView
              key={child.name}
              node={child}
              selected={selected}
              onSelect={onSelect}
              depth={depth + 1}
              expandSignal={expandSignal}
              collapseSignal={collapseSignal}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── highlight.js language map ─────────────────────────────────────────────────
const EXT_TO_LANG: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript',
  js: 'javascript', jsx: 'javascript',
  java: 'java', kt: 'kotlin',
  py: 'python', go: 'go', rs: 'rust',
  rb: 'ruby', php: 'php', swift: 'swift',
  cs: 'csharp', cpp: 'cpp', c: 'c', scala: 'scala',
  css: 'css', scss: 'scss',
  html: 'xml', xml: 'xml',
  json: 'json', yaml: 'yaml', yml: 'yaml',
  md: 'markdown', sql: 'sql',
  sh: 'bash', bash: 'bash',
  dockerfile: 'dockerfile',
  gradle: 'gradle', toml: 'ini',
  properties: 'properties',
};

function getLanguage(filePath: string): string {
  const name = filePath.split('/').pop()?.toLowerCase() ?? '';
  if (name === 'dockerfile') return 'dockerfile';
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  return EXT_TO_LANG[ext] ?? 'plaintext';
}

function highlightCode(code: string, language: string): string {
  try {
    if (language === 'plaintext') return escapeHtml(code);
    const result = hljs.highlight(code, { language, ignoreIllegals: true });
    return result.value;
  } catch {
    return escapeHtml(code);
  }
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

// ── Monaco language map (superset of hljs map) ───────────────────────────────
const MONACO_LANG_MAP: Record<string, string> = {
  java: 'java', kt: 'kotlin', py: 'python', js: 'javascript', jsx: 'javascript',
  ts: 'typescript', tsx: 'typescript', html: 'html', css: 'css', scss: 'scss',
  json: 'json', yml: 'yaml', yaml: 'yaml', xml: 'xml', md: 'markdown',
  sql: 'sql', sh: 'shell', bash: 'shell', dockerfile: 'dockerfile', toml: 'ini',
  rs: 'rust', go: 'go', rb: 'ruby', php: 'php', gradle: 'groovy', tf: 'hcl',
  properties: 'ini', txt: 'plaintext', cfg: 'ini', env: 'ini',
};

function getMonacoLanguage(filePath: string): string {
  const name = filePath.split('/').pop()?.toLowerCase() ?? '';
  if (name === 'dockerfile') return 'dockerfile';
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  return MONACO_LANG_MAP[ext] ?? 'plaintext';
}

// ── IDE file viewer (Monaco-based with native folding) ────────────────────────
function IDEFileViewer({
  filePath, content, loading,
}: { filePath: string | null; content: string; loading: boolean }) {
  const [copied, setCopied] = useState(false);
  const editorRef = useRef<monacoEditor.IStandaloneCodeEditor | null>(null);

  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  // Auto-collapse imports and collapse methods when file has >5 methods
  const handleEditorMount = useCallback((editor: monacoEditor.IStandaloneCodeEditor) => {
    editorRef.current = editor;

    // Wait for folding ranges to be computed by Monaco's built-in provider
    setTimeout(() => {
      try {
        const model = editor.getModel();
        if (!model) return;

        const lineCount = model.getLineCount();
        // Don't fold small files (< 10 lines)
        if (lineCount < 10) return;

        // Detect import block: consecutive lines starting with 'import' at the top
        let importEnd = 0;
        for (let i = 1; i <= Math.min(lineCount, 60); i++) {
          const line = model.getLineContent(i).trim();
          if (line.startsWith('import ') || line.startsWith('from ') || line === '') {
            if (line.startsWith('import ') || line.startsWith('from ')) importEnd = i;
          } else if (importEnd > 0) {
            break;
          }
        }

        // Collapse import block if it's 3+ lines
        if (importEnd >= 3) {
          editor.trigger('fold', 'editor.fold', { selectionLines: [1] });
        }

        // Count methods to decide on auto-collapse
        const text = model.getValue();
        // Rough method count: lines matching common method/function signatures
        const methodPatterns = /^\s*(public|private|protected|static|async|export)?\s*(function|def |class |void |[A-Z]\w+\s+\w+\s*\()/gm;
        const methodCount = (text.match(methodPatterns) || []).length;

        // If >5 methods, fold all then unfold the top level (class)
        if (methodCount > 5) {
          editor.trigger('fold', 'editor.foldAll', {});
          // Unfold top-level (line 1) so class/module is visible
          editor.trigger('fold', 'editor.unfold', { selectionLines: [1] });
          // Also unfold imports line to keep them visible (they'll be separately folded)
        }
      } catch {
        // Folding is best-effort — don't break the viewer
      }
    }, 300);
  }, [content]);

  if (!filePath) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-3 text-zinc-600">
        <FileCode2 className="h-10 w-10 opacity-30" />
        <span className="text-sm">Select a file to view its contents</span>
      </div>
    );
  }

  const fileName = filePath.split('/').pop()!;
  const monacoLang = getMonacoLanguage(filePath);
  const hljsLang = getLanguage(filePath);

  return (
    <div className="flex flex-col h-full overflow-hidden">
      {/* ── Tab bar (VS Code style) ── */}
      <div className="flex items-center border-b border-white/[0.06] bg-[#0d0d14] flex-shrink-0 overflow-x-auto">
        <div className="flex items-center gap-1.5 px-3 py-2 bg-[#13131f] border-r border-white/[0.06] border-t border-t-accent-violet/60 min-w-0">
          <FileTypeIcon name={fileName} />
          <span className="text-xs font-mono text-zinc-300 whitespace-nowrap">{fileName}</span>
        </div>
        {/* breadcrumb path */}
        <span className="text-[10px] font-mono text-zinc-600 px-3 truncate flex-1">
          {filePath.split('/').slice(0, -1).join(' / ')}
        </span>
        {/* copy button */}
        <button
          onClick={handleCopy}
          title="Copy file contents"
          className="flex items-center gap-1 px-3 py-2 text-xs text-zinc-500 hover:text-zinc-300 transition-colors flex-shrink-0"
        >
          {copied
            ? <><Check className="h-3 w-3 text-green-400" /><span className="text-green-400">Copied</span></>
            : <><Copy className="h-3 w-3" /><span>Copy</span></>}
        </button>
        {/* Fold controls */}
        <button
          onClick={() => editorRef.current?.trigger('fold', 'editor.foldAll', {})}
          title="Collapse All"
          className="flex items-center gap-1 px-2 py-2 text-xs text-zinc-600 hover:text-zinc-300 transition-colors flex-shrink-0"
        >
          <ChevronsDownUp className="h-3 w-3" />
        </button>
        <button
          onClick={() => editorRef.current?.trigger('fold', 'editor.unfoldAll', {})}
          title="Expand All"
          className="flex items-center gap-1 px-2 py-2 text-xs text-zinc-600 hover:text-zinc-300 transition-colors flex-shrink-0"
        >
          <ChevronsUpDown className="h-3 w-3" />
        </button>
        {/* language badge */}
        <span className="px-3 py-2 text-[10px] font-mono text-zinc-600 flex-shrink-0">
          {hljsLang}
        </span>
      </div>

      {/* ── Code area (Monaco editor — read-only with native folding) ── */}
      <div className="flex-1 overflow-hidden bg-[#0d0d14]">
        {loading ? (
          <div className="p-4 text-sm text-zinc-500 animate-pulse">Loading…</div>
        ) : (
          <Editor
            key={filePath}
            language={monacoLang}
            value={content}
            theme="vs-dark"
            onMount={handleEditorMount}
            options={{
              readOnly: true,
              minimap: { enabled: false },
              scrollBeyondLastLine: false,
              fontSize: 12,
              lineNumbers: 'on',
              folding: true,
              foldingStrategy: 'auto',
              showFoldingControls: 'always',
              wordWrap: 'off',
              renderLineHighlight: 'line',
              contextmenu: false,
              domReadOnly: true,
              cursorStyle: 'line-thin',
              scrollbar: {
                verticalScrollbarSize: 6,
                horizontalScrollbarSize: 6,
              },
              overviewRulerLanes: 0,
              hideCursorInOverviewRuler: true,
              overviewRulerBorder: false,
              guides: {
                indentation: true,
                bracketPairs: true,
              },
              padding: { top: 8 },
            }}
          />
        )}
      </div>
    </div>
  );
}

// ── Code tab ──────────────────────────────────────────────────────────────────

function CodeTab({
  runId,
  files,
  filesLoading,
  repoUrl,
  status,
}: {
  runId: string;
  files: string[];
  filesLoading: boolean;
  repoUrl?: string | null;
  status?: string;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const [content, setContent] = useState<string>('');
  const [loadingContent, setLoadingContent] = useState(false);
  const [expandSignal, setExpandSignal] = useState(0);
  const [collapseSignal, setCollapseSignal] = useState(0);

  useEffect(() => {
    setSelected(null);
    setContent('');
  }, [runId]);

  const openFile = async (path: string) => {
    if (selected === path) return;
    setSelected(path);
    setLoadingContent(true);
    setContent('');
    try {
      const text = await getFile(runId, path);
      setContent(typeof text === 'string' ? text : JSON.stringify(text, null, 2));
    } catch (e: any) {
      setContent(`// Error loading file: ${e.message}`);
    }
    setLoadingContent(false);
  };

  // Convert GitHub clone URL to web URL for "View on GitHub" link
  const githubWebUrl = repoUrl
    ? repoUrl.replace(/\.git$/, '').replace('https://github.com/', 'https://github.com/')
    : null;

  // Hooks must be called before any early returns (React rules of hooks)
  const isMobile = useIsMobile();
  const [maximized, setMaximized] = useState(false);
  const [showTree, setShowTree] = useState(!isMobile);
  useEffect(() => { setShowTree(!isMobile); }, [isMobile]);

  if (filesLoading) {
    return <div className="p-4 text-sm text-zinc-500">Loading files…</div>;
  }

  if (files.length === 0) {
    const isTerminal = ['COMPLETE', 'PARTIAL', 'FAILED'].includes(status || '');
    return (
      <div className="p-4 space-y-3">
        <p className="text-sm text-zinc-500">
          {isTerminal
            ? 'Local workspace has been cleaned up.'
            : 'No files generated yet. Files appear after the run completes.'}
        </p>
        {isTerminal && githubWebUrl && (
          <a
            href={githubWebUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1.5 text-sm text-accent-violet hover:text-accent-violet/80 transition-colors"
          >
            <svg className="h-4 w-4" viewBox="0 0 16 16" fill="currentColor"><path fillRule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>
            View on GitHub
          </a>
        )}
        {isTerminal && !githubWebUrl && (
          <p className="text-xs text-zinc-600">
            Tip: Push to GitHub to preserve code access after workspace cleanup.
          </p>
        )}
      </div>
    );
  }

  const tree = buildFileTree(files);

  const codeContent = (
    <div className={cn(
      'flex h-full overflow-hidden',
      maximized && 'fixed inset-0 z-50 bg-surface-1'
    )}>
      {/* ── File tree sidebar ── */}
      {showTree && (
        <div className={cn(
          'flex-shrink-0 border-r border-white/[0.06] flex flex-col overflow-hidden',
          isMobile ? 'w-full absolute inset-0 z-10 bg-surface-1' : 'w-56'
        )}>
          {/* Toolbar: Expand All / Collapse All / GitHub link */}
          <div className="flex items-center gap-1 px-2 py-1.5 border-b border-white/[0.06] flex-shrink-0">
            <span className="text-[10px] text-zinc-600 uppercase tracking-widest flex-1 pl-1">Explorer</span>
            {githubWebUrl && (
              <a
                href={githubWebUrl}
                target="_blank"
                rel="noopener noreferrer"
                title="View on GitHub"
                className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
              >
                <svg className="h-3 w-3" viewBox="0 0 16 16" fill="currentColor"><path fillRule="evenodd" d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>
              </a>
            )}
            <button
              title="Expand all folders"
              onClick={() => setExpandSignal(s => s + 1)}
              className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
            >
              <ChevronsUpDown className="h-3 w-3" />
            </button>
            <button
              title="Collapse all folders"
              onClick={() => setCollapseSignal(s => s + 1)}
              className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
            >
              <ChevronsDownUp className="h-3 w-3" />
            </button>
            {isMobile && (
              <button
                title="Close file tree"
                onClick={() => setShowTree(false)}
                className="p-1 rounded text-zinc-600 hover:text-zinc-300 hover:bg-white/[0.06] transition-colors"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>

          {/* Tree nodes */}
          <div className="overflow-y-auto flex-1 py-1">
            {tree.map((node) => (
              <TreeNodeView
                key={node.name}
                node={node}
                selected={selected}
                onSelect={(path) => {
                  openFile(path);
                  if (isMobile) setShowTree(false);
                }}
                expandSignal={expandSignal}
                collapseSignal={collapseSignal}
              />
            ))}
          </div>
        </div>
      )}

      {/* ── IDE viewer pane ── */}
      <div className="flex-1 overflow-hidden flex flex-col">
        {/* Mobile/maximize toolbar */}
        {(isMobile || maximized) && (
          <div className="flex items-center gap-1 px-2 py-1 border-b border-white/[0.06] flex-shrink-0 bg-[#0d0d14]">
            {isMobile && !showTree && (
              <button
                onClick={() => setShowTree(true)}
                className="flex items-center gap-1.5 px-2 py-1.5 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
                title="Show file tree"
              >
                <PanelLeftOpen className="h-3.5 w-3.5" />
                <span>Files</span>
              </button>
            )}
            <div className="flex-1" />
            <button
              onClick={() => setMaximized(!maximized)}
              className="flex items-center gap-1.5 px-2 py-1.5 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
              title={maximized ? 'Exit fullscreen' : 'Fullscreen'}
            >
              {maximized
                ? <><Minimize2 className="h-3.5 w-3.5" /><span>Exit</span></>
                : <><Maximize2 className="h-3.5 w-3.5" /><span>Fullscreen</span></>}
            </button>
          </div>
        )}
        <div className="flex-1 overflow-hidden">
          <IDEFileViewer
            filePath={selected}
            content={content}
            loading={loadingContent}
          />
        </div>
      </div>
    </div>
  );

  return codeContent;
}

// ── Test Task Card (for Tasks tab) ──────────────────────────────────────────

const TEST_STATUS_COLORS: Record<string, string> = {
  PENDING: 'bg-zinc-500/20 text-zinc-400',
  GENERATING: 'bg-amber-500/20 text-amber-400',
  COMPILED: 'bg-emerald-500/20 text-emerald-400',
  FAILED: 'bg-red-500/20 text-red-400',
};

function TestTaskCard({ task }: { task: TestTaskDto }) {
  return (
    <div className="rounded-sm border border-white/[0.06] bg-white/[0.02] p-3">
      <div className="flex items-center gap-2">
        <TestTube2 className="h-3.5 w-3.5 text-violet-400 flex-shrink-0" />
        <span className="text-sm text-zinc-200 font-medium truncate">{task.taskName}</span>
        {task.testCategory && (
          <span className="text-xs text-zinc-500 bg-zinc-800 rounded px-1.5 py-0.5">{task.testCategory}</span>
        )}
        <span className={cn('ml-auto text-xs rounded-sm px-2 py-0.5', TEST_STATUS_COLORS[task.status] || TEST_STATUS_COLORS.PENDING)}>
          {task.status}
        </span>
      </div>
      {task.taskDescription && (
        <p className="mt-1 ml-5.5 text-xs text-zinc-500 line-clamp-2">{task.taskDescription}</p>
      )}
      {task.targetEntity && (
        <p className="mt-0.5 ml-5.5 text-xs text-zinc-600">Target: {task.targetEntity}</p>
      )}
      {task.status === 'FAILED' && task.compilationError && (
        <p className="mt-1 ml-5.5 text-xs text-red-400/80 font-mono line-clamp-3">{task.compilationError}</p>
      )}
    </div>
  );
}

// ── Test Graph Section (for Graph tab) ──────────────────────────────────────

const NODE_STATUS_COLORS: Record<string, string> = {
  COMPLETED: 'border-emerald-500/50 bg-emerald-500/10',
  COMPILED: 'border-emerald-500/50 bg-emerald-500/10',
  PENDING: 'border-zinc-600 bg-zinc-800/50',
  GENERATING: 'border-amber-500/50 bg-amber-500/10',
  EXECUTING: 'border-violet-500/50 bg-violet-500/10',
  FAILED: 'border-red-500/50 bg-red-500/10',
};

function TestGraphSection({ graph }: { graph: TestGraphData }) {
  const agents = graph.nodes.filter(n => n.type === 'agent');
  const workers = graph.nodes.filter(n => n.type === 'worker');
  const gates = graph.nodes.filter(n => n.type === 'gate' || n.type === 'execution' || n.type === 'report');

  return (
    <div className="mt-6 border-t border-white/[0.06] pt-4">
      <div className="flex items-center gap-2 mb-4">
        <TestTube2 className="h-4 w-4 text-violet-400" />
        <span className="text-sm font-medium text-zinc-300">Test Generation Pipeline</span>
        {graph.testResults.total > 0 && (
          <span className="text-xs text-zinc-500 ml-2">
            {graph.testResults.passed}/{graph.testResults.total} passed
          </span>
        )}
      </div>

      {/* Pipeline flow: Planner → Workers → Compile → Execute → Report */}
      <div className="flex flex-col gap-3">
        {/* Planner */}
        {agents.map(node => (
          <GraphNode key={node.id} node={node} />
        ))}

        {/* Workers */}
        {workers.length > 0 && (
          <div className="ml-4 pl-4 border-l-2 border-dashed border-violet-500/30 space-y-2">
            {workers.map(node => (
              <GraphNode key={node.id} node={node} />
            ))}
          </div>
        )}

        {/* Gates: Compile, Execute, Report */}
        {gates.map(node => (
          <GraphNode key={node.id} node={node} />
        ))}
      </div>
    </div>
  );
}

function GraphNode({ node }: { node: { id: string; label: string; type: string; status: string; category?: string } }) {
  const colors = NODE_STATUS_COLORS[node.status] || NODE_STATUS_COLORS.PENDING;
  return (
    <div className={cn('rounded-sm border px-3 py-2 flex items-center gap-2', colors)}>
      <TestTube2 className="h-3.5 w-3.5 text-violet-400 flex-shrink-0" />
      <span className="text-xs text-zinc-200">{node.label}</span>
      {node.category && (
        <span className="text-xs text-zinc-500 bg-zinc-800/50 rounded px-1 py-0.5">{node.category}</span>
      )}
      <span className="ml-auto text-xs text-zinc-500">{node.status}</span>
    </div>
  );
}

