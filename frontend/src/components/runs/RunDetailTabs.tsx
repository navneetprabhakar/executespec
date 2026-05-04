import {useCallback, useEffect, useRef, useState, useReducer} from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import {useUiStore} from '@/stores/useUiStore';
import {Glossary} from '@/components/shared/Glossary';
import {useRunStore} from '@/stores/useRunStore';
import {
  useAuthStore,
  selectIsSuperAdmin,
  selectIsOrgAdmin,
  selectIsOrgMember,
} from '@/stores/useAuthStore';
import {useTierPermissions} from '@/hooks/useTierPermissions';
import {useIsMobile} from '@/hooks/useIsMobile';
import {
  getFiles,
  getFilesWithStatus,
  type FileChangeStatus,
  type FileDiffPayload,
  getFile,
  getFileDiff,
  getOpenApiSpec,
  getProjects,
  createProject,
  updateRunProject,
  applyToRun,
  editRunPlan,
  getTestPlan,
  getLiveCost,
  getRunPrs,
} from '@/lib/api';
import type {PrSummary} from '@/types/project';
import type {RunDetail, AgentTokenEntry, ProjectDto, TestTaskDto} from '@/lib/api';
import GlowDot from '@/components/shared/GlowDot';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import TaskCard from './TaskCard';
import LiveLog from './LiveLog';
import RunConversation from './RunConversation';
import TaskTimeline from './TaskTimeline';
import PreviewTab from './PreviewTab';
import {PIPELINE_STEPS, V2_LEGACY_UI} from '@/lib/constants';
import {useSseStore} from '@/stores/useSseStore';
import {fmtTokens, fmtMs, relativeTime, cn} from '@/lib/utils';
import {
  ChevronDown,
  ChevronRight,
  Folder,
  FolderOpen,
  FileCode2,
  FileText,
  File,
  MessageSquare,
  ChevronsDownUp,
  ChevronsUpDown,
  Copy,
  Check,
  Clock,
  Coins,
  Cpu,
  RefreshCw,
  FolderKanban,
  X,
  Plus,
  User,
  Wrench,
  Loader2,
  TestTube2,
  Maximize2,
  Minimize2,
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-react';
import hljs from 'highlight.js';
import Editor, {DiffEditor} from '@monaco-editor/react';
import {MonacoSafeMount} from './MonacoSafeMount';
import type {editor as monacoEditor} from 'monaco-editor';
import {GEIST_THEME_NAME} from '@/lib/monacoGeistTheme';
import '@/lib/monacoSetup';

const BASE_TABS = [
  {value: 'overview', label: 'Overview'},
  // 2026-05-02 (corrected) — earlier this session the Plan tab was removed and the
  // plan_summary banner was banner-attached above the live Tasks cards. User correction:
  // keep Plan as the primary, drop Tasks, surface per-task live data (status + duration +
  // token count + reason on failure) inside the PlanTaskCard footer. PlanTab now takes the
  // live `tasks` array so each plan task can render its real-time signal alongside the
  // planner's static metadata (objective / acceptance criteria / complexity / model).
  {value: 'plan', label: 'Plan'},
  {value: 'timeline', label: 'Timeline'},
  {value: 'log', label: 'Live Log'},
  {value: 'code', label: 'Code'},
];

export default function RunDetailTabs() {
  const {activeTab, setActiveTab} = useUiStore();
  const activeRun = useRunStore((s) => s.activeRun);
  const fetchRun = useRunStore((s) => s.fetchRun);
  const fetchRuns = useRunStore((s) => s.fetchRuns);
  const [refreshing, setRefreshing] = useState(false);

  // R-06 (UX audit, 2026-05-03): on non-terminal runs (PLANNING / WORKING /
  // REVIEWING / AWAITING_APPROVAL), default the active tab to "Live Log" so
  // the user immediately sees what the agent is doing instead of a static
  // Overview page. Skip when the user has explicitly picked another tab in
  // this session (activeTab !== default) — only steer the FIRST view per run.
  const seededRunIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!activeRun) return;
    if (seededRunIdRef.current === activeRun.runId) return;
    seededRunIdRef.current = activeRun.runId;
    const TERMINAL = new Set(['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL']);
    const isInFlight = !TERMINAL.has(activeRun.status);
    if (isInFlight && activeTab === 'overview') {
      setActiveTab('log');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeRun?.runId, activeRun?.status]);

  // Hoist files so both Code and Preview tabs share them
  const [files, setFiles] = useState<string[]>([]);
  // 2026-05-02 — git-derived per-path change status. Map keyed on workspace-relative path
  // (e.g. `repos/frontend/app/page.tsx`) → 'ADDED' | 'MODIFIED' | 'UNCHANGED'. Drives the
  // A / M badges + collapsed-folder dot in the file tree.
  const [fileStatus, setFileStatus] = useState<Map<string, FileChangeStatus>>(new Map());
  const [filesLoading, setFilesLoading] = useState(false);
  // Bump this to force re-fetch files (e.g. on manual refresh)
  const [filesTick, bumpFiles] = useReducer((n: number) => n + 1, 0);

  const loadFiles = useCallback((runId: string) => {
    setFilesLoading(true);
    setFiles([]);
    setFileStatus(new Map());
    getFilesWithStatus(runId)
      .then((rows) => {
        setFiles(rows.map((r) => r.path));
        setFileStatus(new Map(rows.map((r) => [r.path, r.status])));
      })
      .catch(() => {
        // Endpoint not available (older backend) → fall through to plain listing without status.
        getFiles(runId)
          .then(setFiles)
          .catch(() => {});
      })
      .finally(() => setFilesLoading(false));
  }, []);

  useEffect(() => {
    if (!activeRun) return;
    loadFiles(activeRun.runId);
  }, [activeRun?.runId, filesTick, loadFiles]);
  // Test generation tasks — only fetch after test gen has started (avoids 404 noise)
  const [testTasks, setTestTasks] = useState<TestTaskDto[]>([]);
  const sseEntries = useSseStore((s) => s.entries);
  const testGenStarted = sseEntries.some(
    (e) => e.type === 'TEST_GEN_STARTED' || e.type === 'TEST_REPORT_READY',
  );

  useEffect(() => {
    if (!activeRun || !testGenStarted) return;
    getTestPlan(activeRun.runId)
      .then((plan) => {
        if (plan) setTestTasks(plan.tasks);
      })
      .catch(() => {});
  }, [activeRun?.runId, filesTick, testGenStarted]);

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

  // R-190 (post-cutover) — useViewMode no longer exposes isVibe; the tab
  // list always renders the full Pro set (with the Code+Preview-only Vibe
  // subset deleted with the surface).

  if (!activeRun) return null;

  const isTerminal =
    activeRun.status === 'COMPLETE' ||
    activeRun.status === 'PARTIAL' ||
    activeRun.status === 'FAILED';
  const isActive =
    activeRun.status === 'WORKING' ||
    activeRun.status === 'COMPILING' ||
    activeRun.status === 'REVIEWING' ||
    activeRun.status === 'MERGING' ||
    activeRun.status === 'AWAITING_MERGE';
  /** Preview content is ready when the pipeline is active or finished. */
  const previewReady = isTerminal || isActive;
  /** Chat is only functional after the run finishes (success or failed). */
  const chatReady = isTerminal;
  const topLevelPermissions = useTierPermissions();

  // harness-v2-remaining-work Gap 3: Chat tab is hidden while the V2 chat/apply
  // endpoints return 410 Gone. Re-enable via V2_LEGACY_UI.runConversation once
  // the V2 ConversationAgent is wired.
  const chatTab = V2_LEGACY_UI.runConversation ? [{value: 'chat', label: 'Chat'}] : [];

  // R-190 (post-cutover) — VIBE_TABS (Code + Preview only) deleted with
  // the Vibe surface. TABS is now always the full Pro set.
  const TABS = [
    ...BASE_TABS.filter((t) => t.value !== 'log' || topLevelPermissions?.canViewLiveLog !== false),
    {value: 'preview', label: 'Preview'},
    ...chatTab,
  ];

  return (
    <Tabs.Root value={activeTab} onValueChange={setActiveTab} className='flex flex-col'>
      <Tabs.List className='flex gap-0 border-b border-[var(--border-default)] flex-shrink-0 px-2 sm:px-3 lg:px-4 overflow-x-auto scrollbar-hide sticky top-0 z-10 bg-[var(--gray-100)]'>
        {TABS.map((tab) => (
          <Tabs.Trigger
            key={tab.value}
            value={tab.value}
            className={cn(
              'px-2.5 lg:px-4 py-2 lg:py-2.5 text-[10px] sm:text-[11px] lg:text-xs font-mono font-medium uppercase tracking-wider border-b-2 -mb-px transition-colors whitespace-nowrap',
              'data-[state=active]:border-[var(--text-primary)] data-[state=active]:text-primary',
              'data-[state=inactive]:border-transparent data-[state=inactive]:text-muted',
              'hover:text-secondary',
            )}
          >
            {tab.value === 'chat' ? (
              <span className='flex items-center gap-1.5'>
                <MessageSquare className='h-3 w-3' />
                {tab.label}
              </span>
            ) : (
              tab.label
            )}
          </Tabs.Trigger>
        ))}
        <button
          onClick={handleRefresh}
          disabled={refreshing}
          className='ml-auto flex-shrink-0 p-2 min-h-[36px] min-w-[36px] flex items-center justify-center text-muted hover:text-secondary transition-colors disabled:opacity-50'
          title='Refresh run data'
        >
          <RefreshCw className={cn('h-3.5 w-3.5', refreshing && 'animate-spin')} />
        </button>
      </Tabs.List>

      <div>
        <Tabs.Content value='overview' className='p-4'>
          <OverviewTab run={activeRun} fetchRun={fetchRun} fetchRuns={fetchRuns} />
        </Tabs.Content>

        <Tabs.Content value='plan' className='p-4 space-y-2'>
          <PlanTab run={activeRun} />

          {/* Gap-closing tasks — background best-effort, shown collapsed under the plan */}
          {(() => {
            const gapTasks = activeRun.tasks.filter((t) => t.taskId?.includes('_gap_'));
            const seen = new Map<string, (typeof gapTasks)[0]>();
            gapTasks.forEach((t) => seen.set(t.title, t));
            const uniqueGapTasks = Array.from(seen.values());
            if (uniqueGapTasks.length === 0) return null;
            const doneCount = uniqueGapTasks.filter((t) => t.status === 'COMPLETE').length;
            return (
              <details className='mt-4 border-t border-[var(--border-default)]'>
                <summary className='flex items-center gap-2 pt-3 pb-1 cursor-pointer select-none text-muted hover:text-secondary'>
                  <Wrench className='h-3.5 w-3.5' />
                  <span className='text-xs font-medium'>Gap fixes (background)</span>
                  <span className='text-xs opacity-60'>
                    {doneCount}/{uniqueGapTasks.length} completed
                  </span>
                </summary>
                <div className='mt-1 space-y-1'>
                  {uniqueGapTasks.map((t) => (
                    <TaskCard
                      key={t.taskId}
                      task={t}
                      runId={activeRun.runId}
                      runStatus={activeRun.status}
                    />
                  ))}
                </div>
              </details>
            );
          })()}

          {testTasks.length > 0 && (
            <>
              <div className='flex items-center gap-2 pt-4 pb-1 border-t border-[var(--border-default)]'>
                <TestTube2 className='h-4 w-4 text-accent-blue' />
                <span className='text-sm font-medium text-secondary'>Test Generation Tasks</span>
                <span className='text-xs text-muted'>({testTasks.length})</span>
              </div>
              {testTasks.map((tt) => (
                <TestTaskCard key={tt.id} task={tt} />
              ))}
            </>
          )}
        </Tabs.Content>

        <Tabs.Content value='log' className='h-[calc(100vh-180px)]'>
          <LiveLog />
        </Tabs.Content>

        <Tabs.Content value='code' className='h-[calc(100vh-180px)] overflow-hidden'>
          <CodeTab
            runId={activeRun.runId}
            files={files}
            filesLoading={filesLoading}
            repoUrl={activeRun.repoUrl}
            status={activeRun.status}
            scmProvider={activeRun.scmProvider}
            fileStatus={fileStatus}
          />
        </Tabs.Content>

        <Tabs.Content value='timeline' className='p-4 overflow-auto'>
          <TaskTimeline tasks={activeRun.tasks} runStartedAt={activeRun.startedAt} />
        </Tabs.Content>

        <Tabs.Content value='preview' className='h-[calc(100vh-180px)] overflow-hidden'>
          {previewReady ? (
            <PreviewTab runId={activeRun.runId} />
          ) : (
            <div className='flex flex-col items-center justify-center h-full text-muted gap-2'>
              <Loader2 className='h-5 w-5 animate-spin text-muted' />
              <p className='text-sm'>
                Preview will be available once the run starts generating code.
              </p>
            </div>
          )}
        </Tabs.Content>

        <Tabs.Content value='chat' className='h-[calc(100vh-180px)] overflow-hidden'>
          {chatReady ? (
            <RunConversation runId={activeRun.runId} />
          ) : (
            <div className='flex flex-col items-center justify-center h-full text-muted gap-2'>
              <MessageSquare className='h-5 w-5 text-muted' />
              <p className='text-sm'>Chat will be available once the run finishes.</p>
            </div>
          )}
        </Tabs.Content>
      </div>
    </Tabs.Root>
  );
}

/** Shows a subtle indicator while review & tests run in the background. */
function BackgroundValidationIndicator() {
  const entries = useSseStore((s) => s.entries);
  const hasValidationStarted = entries.some(
    (e) => e.type === 'REVIEW_STARTED' || e.type === 'TEST_GEN_STARTED',
  );
  const hasValidationFinished = entries.some((e) => e.type === 'VALIDATION_REPORT_READY');

  if (!hasValidationStarted || hasValidationFinished) return null;

  return (
    <div className='flex items-center gap-2 px-1 py-1.5'>
      <span className='h-1.5 w-1.5 rounded-full bg-accent-blue/60 animate-pulse' />
      <span className='text-[11px] text-muted'>Review & tests running in background...</span>
    </div>
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

/** Group tasks by copilot/review source prefix. Returns only cop/rev groups (excludes original tasks). */
function groupTasksBySource(
  tasks: RunDetail['tasks'],
): {label: string; taskCount: number; inputTokens: number; outputTokens: number; order: number}[] {
  const groups = new Map<
    string,
    {label: string; taskCount: number; inputTokens: number; outputTokens: number; order: number}
  >();
  for (const t of tasks) {
    const copMatch = t.taskId.match(/_cop(\d+)_/);
    const revMatch = t.taskId.match(/_rev(\d+)_/);
    let key: string | null = null;
    let label = '';
    let order = 0;
    if (copMatch) {
      const idx = parseInt(copMatch[1], 10);
      key = `cop${idx}`;
      label = `Copilot ${idx}`;
      order = 100 + idx;
    } else if (revMatch) {
      const idx = parseInt(revMatch[1], 10);
      key = `rev${idx}`;
      label = `Review Fix ${idx}`;
      order = 200 + idx;
    }
    if (!key) continue;
    const entry = groups.get(key) || {label, taskCount: 0, inputTokens: 0, outputTokens: 0, order};
    entry.taskCount++;
    entry.inputTokens += t.inputTokens || 0;
    entry.outputTokens += t.outputTokens || 0;
    groups.set(key, entry);
  }
  return Array.from(groups.values()).sort((a, b) => a.order - b.order);
}

/** Maps internal provider ID to user-facing display name. */
function displayProvider(provider: string | null | undefined): string {
  if (!provider) return '—';
  switch (provider.toUpperCase()) {
    case 'GEMINI':
    case 'GOOGLE':
      return 'Gemini'; // GOOGLE retained for pre-V119 historical runs
    case 'ANTHROPIC':
      return 'Anthropic';
    case 'OPENAI':
      return 'OpenAI';
    default:
      return provider;
  }
}

/** Aggregate token counts per model from task list + pipeline agent entries (planner/reviewer/compiler). */
function tokensByModel(
  tasks: RunDetail['tasks'],
  agentTokens?: AgentTokenEntry[],
): {model: string; provider: string | null; input: number; output: number; role?: string}[] {
  const map = new Map<
    string,
    {provider: string | null; input: number; output: number; role?: string}
  >();
  // Worker task tokens
  for (const t of tasks) {
    const model = t.model || t.assignedModel || 'unknown';
    const entry = map.get(model) || {provider: null, input: 0, output: 0};
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
      const entry = map.get(key) || {provider: null, input: 0, output: 0, role: a.role};
      entry.input += a.inputTokens || 0;
      entry.output += a.outputTokens || 0;
      if (a.provider) entry.provider = a.provider;
      entry.role = a.role;
      map.set(key, entry);
    }
  }
  return Array.from(map.entries())
    .map(([key, {provider, input, output, role}]) => ({
      // Strip the "role:" prefix for agent entries to show the real model name
      model: key.includes(':') ? key.split(':').slice(1).join(':') : key,
      provider,
      input,
      output,
      role,
    }))
    .sort((a, b) => b.input + b.output - (a.input + a.output));
}

function OverviewTab({
  run,
  fetchRun,
  fetchRuns,
}: {
  run: RunDetail;
  fetchRun: (id: string) => Promise<void>;
  fetchRuns: () => Promise<void>;
}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const isOrgMember = useAuthStore(selectIsOrgMember);
  const permissions = useTierPermissions();

  // ── Live cost + tokens ─────────────────────────────────────────────
  // During V2 runs the stored `total_input_tokens` / `total_output_tokens`
  // lag actual usage — they're flushed only at phase boundaries. The /cost
  // endpoint returns the live accumulator snapshot alongside cost so the UI
  // shows an internally-consistent pair (tokens and cost in lockstep)
  // instead of "Total 3.4K / Live $3.31".
  const [liveCost, setLiveCost] = useState<number | null>(null);
  const [liveTokens, setLiveTokens] = useState<{input: number; output: number} | null>(null);
  useEffect(() => {
    let cancelled = false;
    const fetchCost = () => {
      getLiveCost(run.runId)
        .then((res) => {
          if (cancelled) return;
          setLiveCost(res.costUsd);
          if (typeof res.inputTokens === 'number' && typeof res.outputTokens === 'number') {
            setLiveTokens({input: res.inputTokens, output: res.outputTokens});
          }
        })
        .catch(() => {});
    };
    fetchCost();
    // v2.6.1 — poll while non-terminal. Without this the chip showed the
    // mount-time snapshot for the entire worker phase (status doesn't flip
    // between PLANNING → WORKING → REVIEWING for cost accumulation, but the
    // accumulator updates on every LLM call).
    const isTerminal =
      run.status === 'COMPLETE' ||
      run.status === 'FAILED' ||
      run.status === 'CANCELLED' ||
      run.status === 'PARTIAL';
    if (isTerminal) {
      return () => {
        cancelled = true;
      };
    }
    const interval = setInterval(fetchCost, 5000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
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

  useEffect(() => {
    refreshProjects();
  }, [refreshProjects]);

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

  const handleProjectChange = useCallback(
    async (projectId: string | null) => {
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
    },
    [run.runId, fetchRun, fetchRuns],
  );

  const handleCreateProject = useCallback(async () => {
    const name = newProjectName.trim();
    if (!name) return;
    setProjectSaving(true);
    try {
      const created = await createProject({name});
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
  // Prefer live tokens from the /cost snapshot when available (mid-run the
  // DB totals lag — see liveTokens comment above). Fall back to DB for
  // legacy runs and terminal states where the snapshot has been finalised.
  const liveTotal = liveTokens ? liveTokens.input + liveTokens.output : 0;
  const dbTotal = (run.totalInputTokens || 0) + (run.totalOutputTokens || 0);
  const totalTokens = Math.max(liveTotal, dbTotal);

  // v2.5.8 — V2 runs have a simpler phase sequence than V1. The hardcoded
  // PIPELINE_STEPS (V1: QUEUED → PLANNING → AWAITING_APPROVAL → WORKING →
  // COMPILING → COMPLETE) always leaves V2 runs looking half-done because V2
  // skips AWAITING_APPROVAL (in PROTOTYPE mode) and never emits COMPILING.
  // Render a V2-appropriate 4-step list when we detect a V2 run.
  const isV2 = run.harnessVersion === 'v2';
  const effectiveSteps = isV2
    ? ['QUEUED', 'PLANNING', 'WORKING', 'REVIEWING', 'COMPLETE']
    : PIPELINE_STEPS;
  // V2 terminal statuses map onto the last step for display purposes.
  const v2DisplayStatus =
    isV2 && ['COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(displayStatus)
      ? 'COMPLETE'
      : displayStatus;
  const stepIndex = effectiveSteps.indexOf(isV2 ? v2DisplayStatus : displayStatus);

  // Compute run duration
  const startMs = run.startedAt ? new Date(run.startedAt).getTime() : 0;
  const endMs = run.completedAt ? new Date(run.completedAt).getTime() : Date.now();
  const durationMs = startMs ? endMs - startMs : 0;
  const isTerminal = ['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(run.status);

  return (
    <div className='space-y-6'>
      {/* Stuck warning banner */}
      {run.status === 'STUCK' && (
        <div className='flex items-start gap-2 p-3 rounded-sm bg-accent-red/10 border border-accent-red/20'>
          <span className='text-accent-red text-sm flex-shrink-0'>⚠</span>
          <div>
            <p className='text-sm font-medium text-accent-red'>Run is stuck</p>
            <p className='text-xs text-accent-red/70 mt-0.5'>
              No activity detected for 30+ minutes. Recovery has been triggered automatically.
            </p>
          </div>
        </div>
      )}

      {/* Pipeline timeline — V1 and V2 have different phase sequences */}
      <div className='space-y-0'>
        {effectiveSteps.map((step, i) => {
          const isDone = i < stepIndex;
          const isActive = step === (isV2 ? v2DisplayStatus : displayStatus);
          return (
            <div key={step} className='flex items-start gap-3 relative'>
              {/* Connector line */}
              {i < effectiveSteps.length - 1 && (
                <div
                  className={cn(
                    'absolute left-[11px] top-6 w-0.5 h-5 -bottom-1',
                    isDone ? 'bg-accent-green/40' : 'bg-[var(--gray-200)]',
                  )}
                />
              )}
              <div
                className={cn(
                  'relative z-10 h-6 w-6 rounded-full border flex items-center justify-center flex-shrink-0 mt-0.5',
                  isDone
                    ? 'border-accent-green bg-accent-green/20'
                    : isActive
                      ? 'border-accent-blue bg-accent-blue/20'
                      : 'border-[var(--border-hover)] bg-transparent',
                )}
              >
                {isDone ? (
                  <span className='text-accent-green text-xs'>✓</span>
                ) : isActive ? (
                  <span className='h-2 w-2 rounded-full bg-accent-blue animate-pulse' />
                ) : (
                  <span className='h-1.5 w-1.5 rounded-full bg-[var(--border-default)]' />
                )}
              </div>
              <div className='pb-5'>
                <p
                  className={cn(
                    'text-sm font-medium',
                    isDone ? 'text-accent-green' : isActive ? 'text-primary' : 'text-muted',
                  )}
                >
                  {step === 'COMPLETE' ? 'CODE GENERATED' : step.replace(/_/g, ' ')}
                </p>
              </div>
            </div>
          );
        })}
      </div>

      {/* Background validation indicator */}
      {run.status === 'COMPLETE' && <BackgroundValidationIndicator />}

      {/* Run stats: time taken */}
      <div className='pt-2 border-t border-[var(--border-default)]'>
        <div className='flex items-center gap-2 mb-3'>
          <Clock className='h-3.5 w-3.5 text-muted' />
          <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
            Run Stats
          </span>
        </div>
        <div className='grid grid-cols-1 lg:grid-cols-2 gap-3'>
          <div>
            <p className='text-xs text-muted'>Time taken</p>
            <p className='text-sm text-primary mt-0.5 font-mono'>
              {durationMs > 0
                ? `${fmtDuration(durationMs)}${!isTerminal ? ' (running)' : ''}`
                : '—'}
            </p>
          </div>
          <div>
            <p className='text-xs text-muted'>Started</p>
            <p className='text-sm text-primary mt-0.5 font-mono'>{relativeTime(run.startedAt)}</p>
          </div>
          <div>
            <p className='text-xs text-muted'>Tasks</p>
            <p className='text-sm text-primary mt-0.5 font-mono'>{String(run.tasks.length)}</p>
          </div>
          <div>
            <p className='text-xs text-muted'>Total tokens</p>
            <p className='text-sm text-primary mt-0.5 font-mono'>
              {totalTokens > 0 ? fmtTokens(totalTokens) : '—'}
            </p>
          </div>
          {run.createdByName && (
            <div>
              <p className='text-xs text-muted'>Created by</p>
              <p className='text-sm text-primary mt-0.5 flex items-center gap-1.5'>
                <User className='h-3 w-3 text-accent-blue' />
                {run.createdByName}
              </p>
            </div>
          )}
          {/* Project selector — org members can assign/change/remove/create project */}
          {isOrgMember ? (
            <div className='relative' ref={projectDropdownRef}>
              <p className='text-xs text-muted'>Project</p>
              <button
                onClick={() => {
                  setProjectDropdownOpen((v) => !v);
                  setCreatingProject(false);
                }}
                disabled={projectSaving}
                className={cn(
                  'mt-0.5 flex items-center gap-1.5 text-sm rounded px-1.5 py-0.5 -mx-1.5 transition-colors',
                  'hover:bg-[var(--gray-200)] cursor-pointer',
                  projectSaving && 'opacity-50 pointer-events-none',
                )}
              >
                <FolderKanban className='h-3 w-3 text-cyan-400 flex-shrink-0' />
                <span className={run.projectName ? 'text-primary' : 'text-muted italic'}>
                  {run.projectName ?? 'Assign project…'}
                </span>
                <ChevronDown className='h-3 w-3 text-muted' />
              </button>

              {projectDropdownOpen && (
                <div className='absolute left-0 top-full mt-1 z-50 min-w-[200px] rounded-md border border-[var(--border-default)] bg-[var(--gray-200)]  py-1'>
                  {/* Un-assign option — only if currently assigned */}
                  {run.projectId && (
                    <button
                      onClick={() => handleProjectChange(null)}
                      className='flex items-center gap-2 w-full px-3 py-1.5 text-sm text-secondary hover:bg-[var(--gray-200)] hover:text-primary transition-colors'
                    >
                      <X className='h-3 w-3' />
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
                          ? 'text-cyan-400 bg-[var(--gray-200)]'
                          : 'text-secondary hover:bg-[var(--gray-200)]',
                      )}
                    >
                      <FolderKanban className='h-3 w-3 flex-shrink-0' />
                      {proj.name}
                    </button>
                  ))}

                  {/* Divider */}
                  {(projects.length > 0 || run.projectId) && (
                    <div className='border-t border-[var(--border-default)] my-1' />
                  )}

                  {/* Create new project */}
                  {creatingProject ? (
                    <form
                      onSubmit={(e) => {
                        e.preventDefault();
                        handleCreateProject();
                      }}
                      className='px-2 py-1.5'
                    >
                      <input
                        ref={newProjectInputRef}
                        type='text'
                        value={newProjectName}
                        onChange={(e) => setNewProjectName(e.target.value)}
                        placeholder='Project name…'
                        autoFocus
                        className='w-full bg-[var(--gray-200)] border border-[var(--border-hover)] rounded px-2 py-1 text-sm text-primary placeholder-[var(--text-muted)] outline-none focus:border-accent-blue/50'
                        onKeyDown={(e) => {
                          if (e.key === 'Escape') {
                            setCreatingProject(false);
                            setNewProjectName('');
                          }
                        }}
                      />
                      <div className='flex items-center gap-1.5 mt-1.5'>
                        <button
                          type='submit'
                          disabled={!newProjectName.trim() || projectSaving}
                          className='flex-1 text-xs text-center py-1 rounded bg-accent-blue/20 text-accent-blue hover:bg-accent-blue/30 transition-colors disabled:opacity-40'
                        >
                          Create & assign
                        </button>
                        <button
                          type='button'
                          onClick={() => {
                            setCreatingProject(false);
                            setNewProjectName('');
                          }}
                          className='text-xs text-muted hover:text-secondary px-2 py-1 transition-colors'
                        >
                          Cancel
                        </button>
                      </div>
                    </form>
                  ) : (
                    <button
                      onClick={() => {
                        setCreatingProject(true);
                        setTimeout(() => newProjectInputRef.current?.focus(), 0);
                      }}
                      className='flex items-center gap-2 w-full px-3 py-1.5 text-sm text-accent-blue hover:bg-[var(--gray-200)] transition-colors'
                    >
                      <Plus className='h-3 w-3' />
                      New project…
                    </button>
                  )}
                </div>
              )}
            </div>
          ) : run.projectName ? (
            <div>
              <p className='text-xs text-muted'>Project</p>
              <p className='text-sm text-primary mt-0.5 flex items-center gap-1.5'>
                <FolderOpen className='h-3 w-3 text-cyan-400' />
                {run.projectName}
              </p>
            </div>
          ) : null}
        </div>
      </div>

      {/* Credits charged — visible to all users */}
      {run.creditsCharged != null && (
        <div className='pt-3 border-t border-[var(--border-default)]'>
          <div className='flex items-center gap-2 mb-2'>
            <Coins className='h-3.5 w-3.5 text-accent-amber/70' />
            <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
              Credits
            </span>
          </div>
          <div className='flex items-baseline gap-2'>
            <span className='text-sm text-primary font-mono font-medium'>
              {run.creditsCharged} credit{run.creditsCharged > 1 ? 's' : ''}
            </span>
            {run.approvedExecutionMode === 'BATCH' && (
              <span className='text-[10px] font-medium text-accent-emerald bg-accent-emerald/10 px-1.5 py-0.5 rounded-full'>
                Batch
              </span>
            )}
            {run.approvedExecutionMode === 'REALTIME' && (
              <span className='text-[10px] text-muted'>Real-time</span>
            )}
          </div>
        </div>
      )}

      {/* Super admin: cost breakdown */}
      {isSuperAdmin && (
        <div className='pt-3 border-t border-[var(--border-default)]'>
          <div className='flex items-center gap-2 mb-3'>
            <Coins className='h-3.5 w-3.5 text-accent-amber/70' />
            <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
              Cost Breakdown
            </span>
            <span className='text-[9px] text-muted bg-[var(--gray-200)] px-1.5 py-0.5 rounded-full'>
              Admin
            </span>
          </div>

          <div className='grid grid-cols-2 gap-3'>
            <div>
              <p className='text-xs text-muted'>Live cost</p>
              <p className='text-sm text-accent-green mt-0.5 font-mono font-medium'>
                {liveCost != null
                  ? `$${liveCost.toFixed(4)}`
                  : run.actualCostUsd != null
                    ? `$${run.actualCostUsd.toFixed(4)}`
                    : '—'}
              </p>
              {run.costEstimateLow != null && run.costEstimateHigh != null && (
                <p className='text-[10px] text-muted mt-0.5 font-mono'>
                  est. ${run.costEstimateLow.toFixed(2)} – ${run.costEstimateHigh.toFixed(2)}
                </p>
              )}
            </div>
            <div>
              <p className='text-xs text-muted'>Key source</p>
              <p className='text-sm text-primary mt-0.5 font-mono'>{run.keySource ?? '—'}</p>
            </div>
          </div>

          {/* v2.6.2-track — hierarchical-planner per-phase cost breakdown.
              Only renders when planStrategy is set (the run actually used the
              new pipeline) and at least one phase has tokens. Pure
              additive — falls back silently for legacy runs. */}
          <PlannerPhaseBreakdown run={run} />
        </div>
      )}

      {/* Copilot / Review runs token breakdown */}
      {(() => {
        const copilotGroups = groupTasksBySource(run.tasks);
        if (copilotGroups.length === 0) return null;
        return (
          <div className='pt-3 border-t border-[var(--border-default)]'>
            <div className='flex items-center gap-2 mb-3'>
              <RefreshCw className='h-3.5 w-3.5 text-accent-blue/70' />
              <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
                Copilot Runs ({copilotGroups.length})
              </span>
            </div>
            <div className='space-y-1.5'>
              {copilotGroups.map((g) => (
                <div
                  key={g.label}
                  className='flex items-center justify-between p-2 rounded bg-[var(--gray-100)] border border-[var(--border-default)]'
                >
                  <span className='text-xs font-medium text-secondary'>{g.label}</span>
                  <div className='flex items-center gap-3 text-[11px] font-mono'>
                    <span className='text-muted'>{g.taskCount} tasks</span>
                    <span className='text-secondary/70'>{fmtTokens(g.inputTokens)} in</span>
                    <span className='text-accent-blue/70'>{fmtTokens(g.outputTokens)} out</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        );
      })()}

      {/* Token usage by model — visible to Org Admins and Super Admins */}
      {isOrgAdmin && tokensByModel(run.tasks, run.agentTokens).length > 0 && (
        <div className='pt-3 border-t border-[var(--border-default)]'>
          <div className='flex items-center gap-2 mb-3'>
            <Cpu className='h-3.5 w-3.5 text-muted' />
            <span className='text-xs font-medium text-secondary uppercase tracking-wider'>
              Token Usage by Model
            </span>
          </div>
          <div className='space-y-1.5'>
            {tokensByModel(run.tasks, run.agentTokens).map(
              ({model, provider, input, output, role}) => (
                <div
                  key={role ? `${role}:${model}` : model}
                  className='flex items-center gap-3 p-2 rounded bg-[var(--gray-100)] border border-[var(--border-default)]'
                >
                  <div className='flex items-center gap-1.5 flex-1 min-w-0'>
                    {role && (
                      <span className='text-[10px] font-medium text-muted bg-[var(--gray-200)] px-1.5 py-0.5 rounded flex-shrink-0 capitalize'>
                        {role}
                      </span>
                    )}
                    {provider && (
                      <span className='text-[10px] font-mono text-muted bg-[var(--gray-200)] px-1 py-0.5 rounded flex-shrink-0'>
                        {displayProvider(provider)}
                      </span>
                    )}
                    <span className='text-xs font-mono text-secondary truncate'>{model}</span>
                  </div>
                  <div className='flex items-center gap-3 flex-shrink-0 text-[11px] font-mono'>
                    <span className='text-secondary/70'>{fmtTokens(input)} in</span>
                    <span className='text-accent-blue/70'>{fmtTokens(output)} out</span>
                  </div>
                </div>
              ),
            )}
          </div>
        </div>
      )}

      {/* AI Provider */}
      <div className='pt-2 border-t border-[var(--border-default)]'>
        <p className='text-xs text-muted'>Provider</p>
        <p className='text-sm text-primary mt-0.5 font-mono'>
          {displayProvider(
            run.aiProvider ??
              run.tasks?.[0]?.provider ??
              (run.plannerModel?.startsWith('claude')
                ? 'ANTHROPIC'
                : run.plannerModel?.startsWith('gpt')
                  ? 'OPENAI'
                  : run.plannerModel?.startsWith('gemini')
                    ? 'GEMINI'
                    : null),
          )}
        </p>
      </div>

      {/* Pull requests — pilot R5 list (falls back to legacy single link). */}
      <RunPrList run={run} />

      {/* Goal — the user's original request (markdown rendered) */}
      {run.userRequest && <GoalSection content={run.userRequest} />}
    </div>
  );
}

// ── Goal section with copy button ─────────────────────────────────────────────

function GoalSection({content}: {content: string}) {
  const [copied, setCopied] = useState(false);
  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };
  return (
    <div className='pt-3 border-t border-[var(--border-default)]'>
      <div className='flex items-center justify-between mb-1.5'>
        <p className='text-xs text-muted'>Goal</p>
        <button
          onClick={handleCopy}
          className='flex items-center gap-1 px-1.5 py-0.5 text-[10px] text-muted hover:text-secondary transition-colors rounded hover:bg-[var(--gray-200)]'
          title='Copy goal to clipboard'
        >
          {copied ? <Check className='h-3 w-3 text-accent-green' /> : <Copy className='h-3 w-3' />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <div className='line-clamp-8'>
        <MarkdownContent content={content} />
      </div>
    </div>
  );
}

// ── Plan tab ──────────────────────────────────────────────────────────────────

function PlanTab({run}: {run: RunDetail}) {
  if (!run.planJson) {
    return <p className='text-sm text-muted'>Plan not available yet.</p>;
  }

  let plan: any = null;
  try {
    plan = JSON.parse(run.planJson);
  } catch {
    return (
      <pre className='text-xs font-mono text-secondary whitespace-pre-wrap break-all'>
        {run.planJson}
      </pre>
    );
  }

  const canEdit = run.status === 'AWAITING_APPROVAL';

  // 2026-05-02 — index live tasks so the per-card render can pull real-time status / duration /
  // token consumption / failure reason alongside the planner-static metadata. The plan stores
  // tasks under the field `id` (e.g. "T1"); the live row's `taskId` is prefixed with the run-id
  // short, e.g. "b171fa52_T1". Match by exact taskId first, then by `endsWith("_" + plan.id)`
  // so both shapes resolve to the same live row. Falls back to undefined when the live row
  // hasn't been written yet (pre-execution / plan-approval gate).
  const liveByTaskId = new Map<string, (typeof run.tasks)[number]>();
  const liveByPlanIdSuffix = new Map<string, (typeof run.tasks)[number]>();
  for (const t of run.tasks) {
    if (!t.taskId) continue;
    liveByTaskId.set(t.taskId, t);
    const idx = t.taskId.lastIndexOf('_');
    if (idx >= 0) liveByPlanIdSuffix.set(t.taskId.slice(idx + 1), t);
  }
  const matchLive = (planTaskId: unknown): (typeof run.tasks)[number] | undefined => {
    if (typeof planTaskId !== 'string' || !planTaskId) return undefined;
    return liveByTaskId.get(planTaskId) ?? liveByPlanIdSuffix.get(planTaskId);
  };

  // Roll-up across plan tasks: total tokens consumed + per-task progress.
  let totalTokens = 0;
  let completed = 0;
  const planTasks: any[] = Array.isArray(plan.tasks) ? plan.tasks : [];
  for (const pt of planTasks) {
    const live = matchLive(pt.id ?? pt.task_id);
    if (live) {
      totalTokens += (live.inputTokens ?? 0) + (live.outputTokens ?? 0);
      if (live.status === 'COMPLETE') completed++;
    }
  }

  return (
    <div className='space-y-4'>
      {plan.plan_summary && (
        <div className='p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
          <p className='text-[10px] uppercase tracking-wider text-muted mb-1'>Plan Summary</p>
          <p className='text-sm text-primary leading-relaxed'>{plan.plan_summary}</p>
        </div>
      )}

      {planTasks.length > 0 && (
        <div className='space-y-3'>
          <div className='flex items-center justify-between gap-2'>
            <h4 className='text-xs font-medium text-muted uppercase tracking-wider'>
              Tasks ({planTasks.length})
            </h4>
            {/* Live roll-up — replaces what the old Tasks tab carried at the top. */}
            <div className='flex items-center gap-3 text-[10px] text-muted'>
              <span>
                <span className='text-secondary'>{completed}</span>/{planTasks.length} complete
              </span>
              {totalTokens > 0 && (
                <span>
                  <span className='text-secondary'>{fmtTokens(totalTokens)}</span> total tokens
                </span>
              )}
            </div>
          </div>
          {planTasks.map((t: any, i: number) => (
            <PlanTaskCard
              key={t.id ?? t.task_id ?? i}
              task={t}
              index={i}
              runId={run.runId}
              canEdit={canEdit}
              live={matchLive(t.id ?? t.task_id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ── Plan task card (expandable) ───────────────────────────────────────────────

function PlanTaskCard({
  task: t,
  index: i,
  runId,
  canEdit,
  live,
}: {
  task: any;
  index: number;
  runId: string;
  canEdit: boolean;
  /** Matched live row from `activeRun.tasks` — undefined pre-execution. */
  live?: {
    status?: string | null;
    inputTokens?: number | null;
    outputTokens?: number | null;
    durationMs?: number | null;
    failureReason?: string | null;
  };
}) {
  const [objExpanded, setObjExpanded] = useState(false);
  const [critExpanded, setCritExpanded] = useState(false);
  const [editing, setEditing] = useState(false);
  const [feedback, setFeedback] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const {addToast} = useUiStore();
  const TRUNCATE = 220;

  const objective = t.objective ?? '';
  const criteria = t.acceptance_criteria ?? '';
  const objLong = objective.length > TRUNCATE;
  const critLong = criteria.length > TRUNCATE;

  const displayObj = objExpanded || !objLong ? objective : objective.slice(0, TRUNCATE) + '…';
  const displayCrit = critExpanded || !critLong ? criteria : criteria.slice(0, TRUNCATE) + '…';

  // 2026-05-02 — live signals previously rendered inside the (now-removed) Tasks tab.
  const liveTokens = live ? (live.inputTokens ?? 0) + (live.outputTokens ?? 0) : 0;
  const liveStatus = live?.status;
  const statusTone =
    liveStatus === 'COMPLETE'
      ? 'text-accent-green bg-accent-green/10'
      : liveStatus === 'FAILED'
        ? 'text-accent-red bg-accent-red/10'
        : liveStatus === 'PARTIAL'
          ? 'text-accent-amber bg-accent-amber/10'
          : liveStatus === 'IN_PROGRESS' || liveStatus === 'WORKING'
            ? 'text-accent-blue bg-accent-blue/10'
            : 'text-muted bg-[var(--gray-200)]';

  return (
    <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] overflow-hidden'>
      {/* ── Header: index · title · status · complexity badge ── */}
      <div className='flex items-center justify-between gap-2 px-3 py-2.5 border-b border-[var(--border-default)]'>
        <div className='flex items-center gap-2 min-w-0'>
          <span className='text-[10px] text-muted flex-shrink-0 font-mono tabular-nums'>
            #{String(i + 1).padStart(2, '0')}
          </span>
          <p className='text-sm font-medium text-primary leading-snug'>{t.title}</p>
        </div>
        <div className='flex items-center gap-2 flex-shrink-0'>
          {V2_LEGACY_UI.editRunPlan && canEdit && !editing && (
            <button
              onClick={() => setEditing(true)}
              className='text-[10px] text-muted hover:text-accent-blue transition-colors'
            >
              Edit
            </button>
          )}
          {liveStatus && (
            <span
              className={cn(
                'text-[10px] font-medium px-1.5 py-0.5 rounded uppercase tracking-wider',
                statusTone,
              )}
              title={live?.failureReason ?? ''}
            >
              {liveStatus}
            </span>
          )}
          <span
            className={cn(
              'text-[10px] font-medium px-1.5 py-0.5 rounded',
              t.complexity === 'HIGH'
                ? 'text-accent-red bg-accent-red/10'
                : t.complexity === 'MEDIUM'
                  ? 'text-accent-amber bg-accent-amber/10'
                  : 'text-accent-green bg-accent-green/10',
            )}
          >
            {t.complexity ?? 'MEDIUM'}
          </span>
        </div>
      </div>

      {/* ── Body ── */}
      <div className='px-3 py-2.5 space-y-3'>
        {/* Objective */}
        {objective && (
          <div>
            <p className='text-[10px] uppercase tracking-wider text-muted mb-1'>Objective</p>
            <p className='text-xs text-secondary leading-relaxed whitespace-pre-wrap'>
              {displayObj}
            </p>
            {objLong && (
              <button
                onClick={() => setObjExpanded(!objExpanded)}
                className='text-[10px] text-accent-blue hover:text-accent-blue/70 mt-1 transition-colors'
              >
                {objExpanded ? '▲ Show less' : '▼ Show more'}
              </button>
            )}
          </div>
        )}

        {/* Acceptance criteria */}
        {criteria && (
          <div>
            <p className='text-[10px] uppercase tracking-wider text-muted mb-1'>
              Acceptance Criteria
            </p>
            <div className='bg-[var(--gray-100)] border border-[var(--border-default)] rounded px-2 py-1.5'>
              <p className='text-xs font-mono text-muted leading-relaxed whitespace-pre-wrap'>
                {displayCrit}
              </p>
            </div>
            {critLong && (
              <button
                onClick={() => setCritExpanded(!critExpanded)}
                className='text-[10px] text-accent-blue hover:text-accent-blue/70 mt-1 transition-colors'
              >
                {critExpanded ? '▲ Show less' : '▼ Show more'}
              </button>
            )}
          </div>
        )}

        {/* Footer: model chip + live tokens + duration + depends_on chips. */}
        <div className='flex items-center flex-wrap gap-2 pt-0.5'>
          {t.assigned_model && (
            <span className='text-[10px] font-mono text-muted bg-[var(--gray-100)] px-1.5 py-0.5 rounded border border-[var(--border-default)]'>
              {t.assigned_model}
            </span>
          )}
          {liveTokens > 0 && (
            <span
              className='text-[10px] font-mono text-muted bg-[var(--gray-100)] px-1.5 py-0.5 rounded border border-[var(--border-default)]'
              title={`Input ${fmtTokens(live?.inputTokens ?? 0)} · Output ${fmtTokens(live?.outputTokens ?? 0)}`}
            >
              {fmtTokens(liveTokens)} tok
            </span>
          )}
          {live?.durationMs != null && live.durationMs > 0 && (
            <span className='text-[10px] font-mono text-muted bg-[var(--gray-100)] px-1.5 py-0.5 rounded border border-[var(--border-default)]'>
              {fmtMs(live.durationMs)}
            </span>
          )}
          {Array.isArray(t.depends_on) && t.depends_on.length > 0 && (
            <div className='flex items-center gap-1 flex-wrap'>
              <span className='text-[10px] text-muted'>needs:</span>
              {t.depends_on.map((dep: string) => (
                <span
                  key={dep}
                  className='text-[10px] font-mono px-1.5 py-0.5 rounded bg-accent-blue/10 text-accent-blue/70 border border-accent-blue/10'
                >
                  {dep}
                </span>
              ))}
            </div>
          )}
        </div>

        {/* Failure reason — surface concisely so users see WHY without expanding logs. */}
        {live?.failureReason && (liveStatus === 'FAILED' || liveStatus === 'PARTIAL') && (
          <div className='mt-2 px-2 py-1.5 rounded bg-accent-red/5 border border-accent-red/20'>
            <p className='text-[10px] uppercase tracking-wider text-accent-red/80 mb-0.5'>
              Failure reason
            </p>
            <p className='text-xs text-accent-red/90 leading-snug'>{live.failureReason}</p>
          </div>
        )}

        {/* Inline task edit */}
        {editing && (
          <div className='px-3 py-2.5 border-t border-[var(--border-default)] space-y-2'>
            <textarea
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              placeholder={`How should "${t.title}" be changed?`}
              rows={2}
              className='w-full rounded-md border border-[var(--border-default)] bg-[var(--gray-200)] px-3 py-2 text-xs text-primary placeholder-[var(--text-muted)] focus:outline-none focus:ring-1 focus:ring-accent-blue/50 resize-none'
              autoFocus
            />
            <div className='flex gap-2'>
              <button
                disabled={submitting || !feedback.trim()}
                onClick={async () => {
                  setSubmitting(true);
                  try {
                    const taskFeedback = `Change task "${t.title}": ${feedback.trim()}`;
                    await editRunPlan(runId, taskFeedback);
                    setFeedback('');
                    setEditing(false);
                    addToast({type: 'success', message: 'Plan updated with task feedback'});
                    await useRunStore.getState().fetchRun(runId);
                  } catch (e: any) {
                    addToast({type: 'error', message: e.message});
                  } finally {
                    setSubmitting(false);
                  }
                }}
                className='px-2.5 py-1 text-[10px] font-medium rounded bg-accent-blue/20 text-accent-blue hover:bg-accent-blue/30 disabled:opacity-50'
              >
                {submitting ? 'Updating…' : 'Update Task'}
              </button>
              <button
                onClick={() => {
                  setEditing(false);
                  setFeedback('');
                }}
                className='px-2.5 py-1 text-[10px] font-medium rounded text-secondary hover:text-primary'
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
  APPROVE: 'text-accent-green',
  REJECT: 'text-accent-red',
  PARTIAL: 'text-accent-amber',
};

const CATEGORY_LABELS: Record<string, string> = {
  SPEC_MISMATCH: 'Spec Mismatch',
  MISSING_ENDPOINT: 'Missing Endpoint',
  LOGIC_ERROR: 'Logic Error',
  SECURITY: 'Security',
  ERROR_HANDLING: 'Error Handling',
  CROSS_TASK_MISMATCH: 'Cross-Task Mismatch',
  README_INCOMPLETE: 'README Incomplete',
  CODE_QUALITY: 'Code Quality',
  COMPLETENESS: 'Completeness',
};

function ReviewTab({run}: {run: RunDetail}) {
  const permissions = useTierPermissions();
  const {addToast} = useUiStore();
  const [applyingReview, setApplyingReview] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);

  if (!run.reviewJson) {
    return <p className='text-sm text-muted'>Review not available yet.</p>;
  }

  let review: any = null;
  try {
    review = JSON.parse(run.reviewJson);
  } catch {
    return (
      <pre className='text-xs font-mono text-secondary whitespace-pre-wrap break-all'>
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
      addToast({
        type: 'success',
        message: `Applied ${result.taskCount} review fix task${result.taskCount !== 1 ? 's' : ''}`,
      });
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
    <div className='space-y-4'>
      {/* Verdict + confidence */}
      <div className='flex items-center justify-between p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
        <div>
          <p className='text-xs text-muted mb-1'>Verdict</p>
          <p className={cn('text-sm font-semibold', VERDICT_COLORS[verdict] ?? 'text-secondary')}>
            {verdict ?? '—'}
          </p>
        </div>
        {review.overall_confidence != null && (
          <div className='text-right'>
            <p className='text-xs text-muted mb-1'>Confidence</p>
            <p className='text-sm text-secondary'>
              {typeof review.overall_confidence === 'number'
                ? `${Math.round(review.overall_confidence * 100)}%`
                : review.overall_confidence}
            </p>
          </div>
        )}
      </div>

      {/* Summary */}
      {review.summary && (
        <div className='p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'>
          <p className='text-xs text-muted mb-1'>Summary</p>
          <p className='text-sm text-secondary'>{review.summary}</p>
        </div>
      )}

      {/* Issues */}
      {hasIssues && (
        <div className='space-y-2'>
          <h4 className='text-xs font-medium text-muted uppercase tracking-wider'>
            Issues ({review.issues.length})
          </h4>
          {review.issues.map((issue: any, i: number) => (
            <div
              key={i}
              className='p-3 rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)]'
            >
              <div className='flex items-center gap-2 mb-1'>
                <span
                  className={cn(
                    'text-xs px-1.5 py-0.5 rounded',
                    issue.severity === 'CRITICAL'
                      ? 'text-accent-red bg-accent-red/10'
                      : issue.severity === 'MINOR'
                        ? 'text-accent-amber bg-accent-amber/10'
                        : 'text-secondary bg-[var(--gray-200)]',
                  )}
                >
                  {issue.severity ?? 'MINOR'}
                </span>
                <span className='text-xs text-muted'>
                  {CATEGORY_LABELS[issue.category] ?? issue.category}
                </span>
              </div>
              {issue.file && <p className='text-xs text-muted font-mono mt-0.5'>{issue.file}</p>}
              <p className='text-sm text-secondary'>{issue.description}</p>
              {issue.fixInstruction && (
                <p className='text-xs text-muted mt-1 italic'>{issue.fixInstruction}</p>
              )}
              {issue.autoFixed && (
                <span
                  className={cn(
                    'inline-block text-xs mt-1',
                    issue.fixSucceeded ? 'text-status-success' : 'text-accent-red',
                  )}
                >
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
        <div className='pt-3 border-t border-[var(--border-default)]'>
          <div className='flex items-center justify-between'>
            <div>
              <p className='text-xs text-muted'>Apply review fixes to this run</p>
              {permissions.maxApplySuggestions > 0 && (
                <p className='text-[10px] text-muted font-mono mt-0.5'>
                  {run.applySuggestionCount ?? 0}/{permissions.maxApplySuggestions} applied
                </p>
              )}
            </div>
            <button
              onClick={handleApplyReview}
              disabled={applyingReview}
              className={cn(
                'flex items-center gap-1.5 px-3 py-2 rounded-sm text-xs font-medium transition-all',
                'bg-status-waiting/15 text-accent-amber hover:bg-status-waiting/25 border border-status-waiting/20',
                'disabled:opacity-40 disabled:cursor-not-allowed',
              )}
            >
              {applyingReview ? (
                <>
                  <Loader2 className='h-3.5 w-3.5 animate-spin' /> Applying&hellip;
                </>
              ) : (
                <>
                  <Wrench className='h-3.5 w-3.5' /> Apply Review
                </>
              )}
            </button>
          </div>
          {applyError && <p className='text-xs text-accent-red mt-2'>{applyError}</p>}
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
        current[part] = {_isFile: false, _path: '', _children: {}};
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

function FileTypeIcon({name}: {name: string}) {
  const lower = name.toLowerCase();
  const ext = lower.includes('.') ? lower.split('.').pop()! : '';

  const codeExts = new Set([
    'java',
    'kt',
    'ts',
    'tsx',
    'js',
    'jsx',
    'py',
    'go',
    'rs',
    'cpp',
    'c',
    'cs',
    'swift',
    'scala',
  ]);
  const textExts = new Set([
    'md',
    'txt',
    'yaml',
    'yml',
    'json',
    'xml',
    'html',
    'css',
    'scss',
    'sql',
    'sh',
    'bash',
    'properties',
    'env',
    'conf',
    'toml',
    'gradle',
    'gitignore',
    'dockerignore',
  ]);

  const colorMap: Record<string, string> = {
    java: 'text-accent-amber',
    kt: 'text-[var(--text-secondary)]',
    ts: 'text-accent-blue',
    tsx: 'text-accent-blue',
    js: 'text-status-waiting',
    jsx: 'text-status-waiting',
    py: 'text-[var(--status-success)]',
    go: 'text-sky-400',
    rs: 'text-status-partial',
    sql: 'text-accent-blue',
    json: 'text-status-waiting',
    xml: 'text-accent-red',
    yml: 'text-[var(--text-secondary)]',
    yaml: 'text-[var(--text-secondary)]',
    html: 'text-accent-amber',
    css: 'text-accent-blue',
    scss: 'text-accent-red',
    md: 'text-secondary',
    sh: 'text-[var(--status-success)]',
    bash: 'text-[var(--status-success)]',
    gradle: 'text-accent-green',
    properties: 'text-secondary',
  };

  const color = colorMap[ext] ?? 'text-muted';

  if (lower === 'dockerfile' || lower === '.dockerfile') {
    return <span className='text-sky-400 font-mono text-[9px] font-bold leading-none'>🐳</span>;
  }
  if (codeExts.has(ext)) {
    return <FileCode2 className={cn('h-3 w-3 flex-shrink-0', color)} />;
  }
  if (textExts.has(ext)) {
    return <FileText className={cn('h-3 w-3 flex-shrink-0', color)} />;
  }
  return <File className='h-3 w-3 flex-shrink-0 text-muted' />;
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
  /**
   * 2026-05-02 — git-derived per-path change status. Map keyed on workspace-relative path
   * (e.g. `repos/frontend/app/page.tsx`). Drives the A / M leaf badge and a colored dot on
   * directory rows when a descendant is changed.
   */
  fileStatus?: Map<string, FileChangeStatus>;
}

function TreeNodeView({
  node,
  selected,
  onSelect,
  depth = 0,
  expandSignal = 0,
  collapseSignal = 0,
  fileStatus,
}: TreeNodeViewProps) {
  const [open, setOpen] = useState(depth < 2);

  // React to expand / collapse all signals
  useEffect(() => {
    if (expandSignal > 0) setOpen(true);
  }, [expandSignal]);
  useEffect(() => {
    if (collapseSignal > 0) setOpen(false);
  }, [collapseSignal]);

  const indent = depth * 12 + 8;

  // 2026-05-02 — leaf rows show an A/M chip; directory rows show a small colored dot when any
  // descendant changed. Lookup is cheap since fileStatus is a Map; aggregateChildStatus walks
  // each render-time but the tree is small enough that memoising adds more code than it saves.
  const leafStatus = node.isFile && fileStatus ? fileStatus.get(node.path) : undefined;
  const dirStatus = !node.isFile && fileStatus ? aggregateChildStatus(node, fileStatus) : undefined;

  if (node.isFile) {
    return (
      <button
        onClick={() => onSelect(node.path)}
        className={cn(
          'w-full text-left flex items-center gap-1.5 py-[3px] pr-2 rounded text-xs font-mono truncate transition-colors',
          selected === node.path
            ? 'bg-accent-blue/15 text-accent-blue'
            : 'text-secondary hover:text-primary hover:bg-[var(--gray-200)]',
        )}
        style={{paddingLeft: `${indent}px`}}
        title={node.path}
      >
        <FileTypeIcon name={node.name} />
        <span className='truncate flex-1'>{node.name}</span>
        {leafStatus && leafStatus !== 'UNCHANGED' && renderLeafBadge(leafStatus)}
      </button>
    );
  }

  return (
    <div>
      <button
        onClick={() => setOpen(!open)}
        className='w-full text-left flex items-center gap-1.5 py-[3px] pr-2 rounded text-xs text-muted hover:text-secondary hover:bg-[var(--gray-200)] transition-colors'
        style={{paddingLeft: `${indent}px`}}
      >
        <ChevronRight
          className={cn('h-3 w-3 flex-shrink-0 transition-transform', open && 'rotate-90')}
        />
        {open ? (
          <FolderOpen className='h-3 w-3 flex-shrink-0 text-accent-amber/60' />
        ) : (
          <Folder className='h-3 w-3 flex-shrink-0 text-accent-amber/60' />
        )}
        <span className='flex-1'>{node.name}</span>
        {dirStatus && dirStatus !== 'UNCHANGED' && !open && renderDirDot(dirStatus)}
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
              fileStatus={fileStatus}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** Walk the subtree once and return the dominant change status (ADDED ⩾ MODIFIED ⩾ undefined). */
function aggregateChildStatus(
  node: FileTreeNode,
  fileStatus: Map<string, FileChangeStatus>,
): FileChangeStatus | undefined {
  let best: FileChangeStatus | undefined;
  function walk(n: FileTreeNode) {
    if (n.isFile) {
      const s = fileStatus.get(n.path);
      if (s === 'ADDED') {
        best = 'ADDED';
      } else if (s === 'MODIFIED' && best !== 'ADDED') {
        best = 'MODIFIED';
      }
      return;
    }
    for (const c of n.children) {
      walk(c);
      if (best === 'ADDED') return; // can't get higher
    }
  }
  walk(node);
  return best;
}

function renderLeafBadge(status: FileChangeStatus) {
  const base = 'text-[9px] font-bold leading-none px-1 py-[1px] rounded-sm flex-shrink-0 border';
  if (status === 'ADDED') {
    return (
      <span
        className={`${base} text-status-success border-status-success/40 bg-status-success/10`}
        title='Added by this run'
      >
        A
      </span>
    );
  }
  return (
    <span
      className={`${base} text-accent-amber border-accent-amber/40 bg-accent-amber/10`}
      title='Modified by this run'
    >
      M
    </span>
  );
}

function renderDirDot(status: FileChangeStatus) {
  const cls = status === 'ADDED' ? 'bg-status-success' : 'bg-accent-amber';
  return (
    <span
      className={`inline-block h-1.5 w-1.5 rounded-full flex-shrink-0 ${cls}`}
      title={
        status === 'ADDED'
          ? 'Contains files added by this run'
          : 'Contains files modified by this run'
      }
    />
  );
}

// ── highlight.js language map ─────────────────────────────────────────────────
const EXT_TO_LANG: Record<string, string> = {
  ts: 'typescript',
  tsx: 'typescript',
  js: 'javascript',
  jsx: 'javascript',
  java: 'java',
  kt: 'kotlin',
  py: 'python',
  go: 'go',
  rs: 'rust',
  swift: 'swift',
  cs: 'csharp',
  cpp: 'cpp',
  c: 'c',
  scala: 'scala',
  css: 'css',
  scss: 'scss',
  html: 'xml',
  xml: 'xml',
  json: 'json',
  yaml: 'yaml',
  yml: 'yaml',
  md: 'markdown',
  sql: 'sql',
  sh: 'bash',
  bash: 'bash',
  dockerfile: 'dockerfile',
  gradle: 'gradle',
  toml: 'ini',
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
    const result = hljs.highlight(code, {language, ignoreIllegals: true});
    return result.value;
  } catch {
    return escapeHtml(code);
  }
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ── Monaco language map (superset of hljs map) ───────────────────────────────
const MONACO_LANG_MAP: Record<string, string> = {
  java: 'java',
  kt: 'kotlin',
  py: 'python',
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  html: 'html',
  css: 'css',
  scss: 'scss',
  json: 'json',
  yml: 'yaml',
  yaml: 'yaml',
  xml: 'xml',
  md: 'markdown',
  sql: 'sql',
  sh: 'shell',
  bash: 'shell',
  dockerfile: 'dockerfile',
  toml: 'ini',
  rs: 'rust',
  go: 'go',
  gradle: 'groovy',
  tf: 'hcl',
  properties: 'ini',
  txt: 'plaintext',
  cfg: 'ini',
  env: 'ini',
};

function getMonacoLanguage(filePath: string): string {
  const name = filePath.split('/').pop()?.toLowerCase() ?? '';
  if (name === 'dockerfile') return 'dockerfile';
  const ext = name.includes('.') ? name.split('.').pop()! : '';
  return MONACO_LANG_MAP[ext] ?? 'plaintext';
}

// ── IDE file viewer (Monaco-based with native folding + 2026-05-02 diff mode) ──
function IDEFileViewer({
  filePath,
  content,
  loading,
  runId,
  fileStatus,
}: {
  filePath: string | null;
  content: string;
  loading: boolean;
  /** 2026-05-02 — needed to lazy-fetch the diff payload via getFileDiff. */
  runId?: string;
  /** ADDED/MODIFIED → default to diff view; UNCHANGED → full view + toggle disabled. */
  fileStatus?: FileChangeStatus;
}) {
  const [copied, setCopied] = useState(false);
  const editorRef = useRef<monacoEditor.IStandaloneCodeEditor | null>(null);

  // 2026-05-02 — view-mode toggle. Default is 'diff' for ADDED/MODIFIED files (the user's
  // ask: "I can see which file changed in Code section, but I can't see what changed"). Falls
  // back to 'full' for UNCHANGED files (no diff to show) or when the run id isn't wired through.
  const canShowDiff = !!runId && (fileStatus === 'ADDED' || fileStatus === 'MODIFIED');
  const [viewMode, setViewMode] = useState<'full' | 'diff'>(canShowDiff ? 'diff' : 'full');
  const [diff, setDiff] = useState<FileDiffPayload | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  // Reset to default view-mode whenever the file changes — picks 'diff' for changed files,
  // 'full' for unchanged ones — and clear any cached diff payload from the previous file.
  useEffect(() => {
    setViewMode(canShowDiff ? 'diff' : 'full');
    setDiff(null);
  }, [filePath, canShowDiff]);

  // Lazy-fetch diff content when entering diff mode for a new file.
  useEffect(() => {
    if (viewMode !== 'diff' || !filePath || !runId) return;
    let cancelled = false;
    setDiffLoading(true);
    getFileDiff(runId, filePath)
      .then((d) => {
        if (!cancelled) {
          setDiff(d);
          setDiffLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDiff({path: filePath, base: '', head: content, status: 'ADDED'});
          setDiffLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [viewMode, filePath, runId, content]);

  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  // Auto-collapse imports and collapse methods when file has >5 methods
  const handleEditorMount = useCallback(
    (editor: monacoEditor.IStandaloneCodeEditor) => {
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
            editor.trigger('fold', 'editor.fold', {selectionLines: [1]});
          }

          // Count methods to decide on auto-collapse
          const text = model.getValue();
          // Rough method count: lines matching common method/function signatures
          const methodPatterns =
            /^\s*(public|private|protected|static|async|export)?\s*(function|def |class |void |[A-Z]\w+\s+\w+\s*\()/gm;
          const methodCount = (text.match(methodPatterns) || []).length;

          // If >5 methods, fold all then unfold the top level (class)
          if (methodCount > 5) {
            editor.trigger('fold', 'editor.foldAll', {});
            // Unfold top-level (line 1) so class/module is visible
            editor.trigger('fold', 'editor.unfold', {selectionLines: [1]});
            // Also unfold imports line to keep them visible (they'll be separately folded)
          }
        } catch {
          // Folding is best-effort — don't break the viewer
        }
      }, 300);
    },
    [content],
  );

  if (!filePath) {
    return (
      <div className='flex flex-col items-center justify-center h-full gap-3 text-muted'>
        <FileCode2 className='h-10 w-10 opacity-30' />
        <span className='text-sm'>Select a file to view its contents</span>
      </div>
    );
  }

  const fileName = filePath.split('/').pop()!;
  const monacoLang = getMonacoLanguage(filePath);
  const hljsLang = getLanguage(filePath);

  return (
    <div className='flex flex-col h-full overflow-hidden'>
      {/* ── Tab bar (VS Code style) ── */}
      <div className='flex items-center border-b border-[var(--border-default)] bg-[var(--geist-bg)] flex-shrink-0 overflow-x-auto'>
        <div className='flex items-center gap-1.5 px-3 py-2 bg-[var(--gray-100)] border-r border-[var(--border-default)] border-t border-t-[var(--text-primary)] min-w-0'>
          <FileTypeIcon name={fileName} />
          <span className='text-xs font-mono text-secondary whitespace-nowrap'>{fileName}</span>
        </div>
        {/* breadcrumb path */}
        <span className='text-[10px] font-mono text-muted px-3 truncate flex-1'>
          {filePath.split('/').slice(0, -1).join(' / ')}
        </span>
        {/* copy button */}
        <button
          onClick={handleCopy}
          title='Copy file contents'
          className='flex items-center gap-1 px-3 py-2 text-xs text-muted hover:text-secondary transition-colors flex-shrink-0'
        >
          {copied ? (
            <>
              <Check className='h-3 w-3 text-[var(--status-success)]' />
              <span className='text-[var(--status-success)]'>Copied</span>
            </>
          ) : (
            <>
              <Copy className='h-3 w-3' />
              <span>Copy</span>
            </>
          )}
        </button>
        {/* 2026-05-02 — Diff/Full toggle. Visible only when the file has a diff to show
             (ADDED/MODIFIED + runId wired). Hidden for UNCHANGED files (nothing to diff). */}
        {canShowDiff && (
          <button
            onClick={() => setViewMode((m) => (m === 'diff' ? 'full' : 'diff'))}
            title={viewMode === 'diff' ? 'Show full file' : 'Show only the diff'}
            className={cn(
              'flex items-center gap-1 px-2 py-2 text-xs transition-colors flex-shrink-0',
              viewMode === 'diff'
                ? 'text-accent-blue hover:text-accent-blue/80'
                : 'text-muted hover:text-secondary',
            )}
          >
            <span className='font-mono'>{viewMode === 'diff' ? 'Diff' : 'Full'}</span>
          </button>
        )}
        {/* Fold controls */}
        <button
          onClick={() => editorRef.current?.trigger('fold', 'editor.foldAll', {})}
          title='Collapse All'
          className='flex items-center gap-1 px-2 py-2 text-xs text-muted hover:text-secondary transition-colors flex-shrink-0'
        >
          <ChevronsDownUp className='h-3 w-3' />
        </button>
        <button
          onClick={() => editorRef.current?.trigger('fold', 'editor.unfoldAll', {})}
          title='Expand All'
          className='flex items-center gap-1 px-2 py-2 text-xs text-muted hover:text-secondary transition-colors flex-shrink-0'
        >
          <ChevronsUpDown className='h-3 w-3' />
        </button>
        {/* language badge */}
        <span className='px-3 py-2 text-[10px] font-mono text-muted flex-shrink-0'>{hljsLang}</span>
      </div>

      {/* ── Code area (Monaco editor — diff or full file) ── */}
      <div className='flex-1 overflow-hidden bg-[var(--geist-bg)]'>
        {viewMode === 'diff' ? (
          diffLoading || !diff ? (
            <div className='p-4 text-sm text-muted animate-pulse'>Loading diff…</div>
          ) : (
            <MonacoSafeMount
              resetKey={`${filePath}-diff`}
              fallback={
                <div className='p-4 text-sm text-muted'>
                  Diff viewer hit a transient render error — pick another file or refresh to retry.
                </div>
              }
            >
              <DiffEditor
                key={`${filePath}-diff`}
                language={monacoLang}
                original={diff.base}
                modified={diff.head}
                theme={GEIST_THEME_NAME}
                options={{
                  readOnly: true,
                  renderSideBySide: false,
                  hideUnchangedRegions: {
                    enabled: true,
                    contextLineCount: 3,
                    minimumLineCount: 3,
                    revealLineCount: 20,
                  },
                  minimap: {enabled: false},
                  scrollBeyondLastLine: false,
                  fontSize: 12,
                  lineNumbers: 'on',
                  folding: true,
                  showFoldingControls: 'always',
                  wordWrap: 'off',
                  renderLineHighlight: 'line',
                  contextmenu: false,
                  domReadOnly: true,
                  cursorStyle: 'line-thin',
                  scrollbar: {verticalScrollbarSize: 6, horizontalScrollbarSize: 6},
                  overviewRulerLanes: 0,
                  hideCursorInOverviewRuler: true,
                  overviewRulerBorder: false,
                  guides: {indentation: true, bracketPairs: true},
                  padding: {top: 8},
                }}
              />
            </MonacoSafeMount>
          )
        ) : loading ? (
          <div className='p-4 text-sm text-muted animate-pulse'>Loading…</div>
        ) : (
          <Editor
            key={filePath}
            language={monacoLang}
            value={content}
            theme={GEIST_THEME_NAME}
            onMount={handleEditorMount}
            options={{
              readOnly: true,
              minimap: {enabled: false},
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
              padding: {top: 8},
            }}
          />
        )}
      </div>
    </div>
  );
}

// ── Code tab ──────────────────────────────────────────────────────────────────

export function CodeTab({
  runId,
  files,
  filesLoading,
  repoUrl,
  status,
  scmProvider,
  fileStatus,
}: {
  runId: string;
  files: string[];
  filesLoading: boolean;
  repoUrl?: string | null;
  status?: string;
  scmProvider?: string | null;
  /** 2026-05-02 — git-derived per-path change status; drives A/M chips on file rows. */
  fileStatus?: Map<string, FileChangeStatus>;
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

  // Convert clone URL to web URL for "View on SCM" link
  const scmWebUrl = repoUrl ? repoUrl.replace(/\.git$/, '') : null;
  const scmLinkLabel = scmProvider === 'BITBUCKET' ? 'View on Bitbucket' : 'View on GitHub';

  // Hooks must be called before any early returns (React rules of hooks)
  const isMobile = useIsMobile();
  const [maximized, setMaximized] = useState(false);
  const [showTree, setShowTree] = useState(!isMobile);
  useEffect(() => {
    setShowTree(!isMobile);
  }, [isMobile]);

  if (filesLoading) {
    return <div className='p-4 text-sm text-muted'>Loading files…</div>;
  }

  if (files.length === 0) {
    const isTerminal = ['COMPLETE', 'PARTIAL', 'FAILED'].includes(status || '');
    return (
      <div className='p-4 space-y-3'>
        <p className='text-sm text-muted'>
          {isTerminal
            ? 'Local workspace has been cleaned up.'
            : 'No files generated yet. Files appear after the run completes.'}
        </p>
        {isTerminal && scmWebUrl && (
          <a
            href={scmWebUrl}
            target='_blank'
            rel='noopener noreferrer'
            className='inline-flex items-center gap-1.5 text-sm text-accent-blue hover:text-accent-blue/80 transition-colors'
          >
            <svg className='h-4 w-4' viewBox='0 0 16 16' fill='currentColor'>
              <path
                fillRule='evenodd'
                d='M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z'
              />
            </svg>
            {scmLinkLabel}
          </a>
        )}
        {isTerminal && !scmWebUrl && (
          <p className='text-xs text-muted'>
            Tip: Push to GitHub or Bitbucket to preserve code access after workspace cleanup.
          </p>
        )}
      </div>
    );
  }

  const tree = buildFileTree(files);

  const codeContent = (
    <div
      className={cn(
        'flex h-full overflow-hidden',
        maximized && 'fixed inset-0 z-50 bg-[var(--gray-100)]',
      )}
    >
      {/* ── File tree sidebar ── */}
      {showTree && (
        <div
          className={cn(
            'flex-shrink-0 border-r border-[var(--border-default)] flex flex-col overflow-hidden',
            isMobile ? 'w-full absolute inset-0 z-10 bg-[var(--gray-100)]' : 'w-56',
          )}
        >
          {/* Toolbar: Expand All / Collapse All / GitHub link */}
          <div className='flex items-center gap-1 px-2 py-1.5 border-b border-[var(--border-default)] flex-shrink-0'>
            <span className='text-[10px] text-muted uppercase tracking-widest flex-1 pl-1'>
              Explorer
            </span>
            {scmWebUrl && (
              <a
                href={scmWebUrl}
                target='_blank'
                rel='noopener noreferrer'
                title={scmLinkLabel}
                className='p-1 rounded text-muted hover:text-secondary hover:bg-[var(--gray-200)] transition-colors'
              >
                <svg className='h-3 w-3' viewBox='0 0 16 16' fill='currentColor'>
                  <path
                    fillRule='evenodd'
                    d='M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z'
                  />
                </svg>
              </a>
            )}
            <button
              title='Expand all folders'
              onClick={() => setExpandSignal((s) => s + 1)}
              className='p-1 rounded text-muted hover:text-secondary hover:bg-[var(--gray-200)] transition-colors'
            >
              <ChevronsUpDown className='h-3 w-3' />
            </button>
            <button
              title='Collapse all folders'
              onClick={() => setCollapseSignal((s) => s + 1)}
              className='p-1 rounded text-muted hover:text-secondary hover:bg-[var(--gray-200)] transition-colors'
            >
              <ChevronsDownUp className='h-3 w-3' />
            </button>
            {isMobile && (
              <button
                title='Close file tree'
                onClick={() => setShowTree(false)}
                className='p-1 rounded text-muted hover:text-secondary hover:bg-[var(--gray-200)] transition-colors'
              >
                <X className='h-3 w-3' />
              </button>
            )}
          </div>

          {/* Tree nodes */}
          <div className='overflow-y-auto flex-1 py-1'>
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
                fileStatus={fileStatus}
              />
            ))}
          </div>
        </div>
      )}

      {/* ── IDE viewer pane ── */}
      <div className='flex-1 overflow-hidden flex flex-col'>
        {/* Mobile/maximize toolbar */}
        {(isMobile || maximized) && (
          <div className='flex items-center gap-1 px-2 py-1 border-b border-[var(--border-default)] flex-shrink-0 bg-[var(--geist-bg)]'>
            {isMobile && !showTree && (
              <button
                onClick={() => setShowTree(true)}
                className='flex items-center gap-1.5 px-2 py-1.5 text-xs text-muted hover:text-secondary transition-colors'
                title='Show file tree'
              >
                <PanelLeftOpen className='h-3.5 w-3.5' />
                <span>Files</span>
              </button>
            )}
            <div className='flex-1' />
            <button
              onClick={() => setMaximized(!maximized)}
              className='flex items-center gap-1.5 px-2 py-1.5 text-xs text-muted hover:text-secondary transition-colors'
              title={maximized ? 'Exit fullscreen' : 'Fullscreen'}
            >
              {maximized ? (
                <>
                  <Minimize2 className='h-3.5 w-3.5' />
                  <span>Exit</span>
                </>
              ) : (
                <>
                  <Maximize2 className='h-3.5 w-3.5' />
                  <span>Fullscreen</span>
                </>
              )}
            </button>
          </div>
        )}
        <div className='flex-1 overflow-hidden'>
          <IDEFileViewer
            filePath={selected}
            content={content}
            loading={loadingContent}
            runId={runId}
            fileStatus={selected ? fileStatus?.get(selected) : undefined}
          />
        </div>
      </div>
    </div>
  );

  return codeContent;
}

// ── Test Task Card (for Tasks tab) ──────────────────────────────────────────

const TEST_STATUS_COLORS: Record<string, string> = {
  PENDING: 'bg-[var(--gray-200)] text-secondary',
  GENERATING: 'bg-status-waiting/20 text-accent-amber',
  COMPILED: 'bg-status-success/20 text-status-success',
  FAILED: 'bg-status-error/20 text-accent-red',
};

function TestTaskCard({task}: {task: TestTaskDto}) {
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
      <div className='flex items-center gap-2'>
        <TestTube2 className='h-3.5 w-3.5 text-accent-blue flex-shrink-0' />
        <span className='text-sm text-primary font-medium truncate'>{task.taskName}</span>
        {task.testCategory && (
          <span className='text-xs text-muted bg-[var(--gray-200)] rounded px-1.5 py-0.5'>
            {task.testCategory}
          </span>
        )}
        <span
          className={cn(
            'ml-auto text-xs rounded-sm px-2 py-0.5',
            TEST_STATUS_COLORS[task.status] || TEST_STATUS_COLORS.PENDING,
          )}
        >
          {task.status}
        </span>
      </div>
      {task.taskDescription && (
        <p className='mt-1 ml-5.5 text-xs text-muted line-clamp-2'>{task.taskDescription}</p>
      )}
      {task.targetEntity && (
        <p className='mt-0.5 ml-5.5 text-xs text-muted'>Target: {task.targetEntity}</p>
      )}
      {task.status === 'FAILED' && task.compilationError && (
        <p className='mt-1 ml-5.5 text-xs text-accent-red/80 font-mono line-clamp-3'>
          {task.compilationError}
        </p>
      )}
    </div>
  );
}

// ─── Pilot R5: Pull request list ────────────────────────────────────────────
function RunPrList({run}: {run: RunDetail}) {
  const [prs, setPrs] = useState<PrSummary[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getRunPrs(run.runId)
      .then((list) => {
        if (!cancelled) {
          setPrs(list);
          setLoaded(true);
        }
      })
      .catch(() => {
        if (!cancelled) setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [run.runId, run.status]);

  // Fallback: legacy runs without PullRequestRecord rows still surface the single PR link.
  const legacyUrl = run.scmPrUrl || run.githubPrUrl || null;
  if (!loaded) return null;
  if (prs.length === 0 && !legacyUrl) return null;

  return (
    <div className='pt-2 border-t border-[var(--border-default)]'>
      <p className='text-xs text-muted mb-1'>Pull requests</p>
      {prs.length > 0 ? (
        <ul className='space-y-1'>
          {prs.map((pr) => (
            <li
              key={`${pr.repoFullName}-${pr.prNumber}`}
              className='flex items-center gap-2 text-sm'
            >
              <span
                className={cn(
                  'inline-flex items-center px-1.5 py-0.5 rounded text-[10px] uppercase tracking-wide',
                  pr.status === 'MERGED' && 'bg-purple-500/10 text-purple-500',
                  pr.status === 'OPEN' && 'bg-blue-500/10 text-blue-500',
                  pr.status === 'CLOSED' && 'bg-status-error/10 text-status-error',
                )}
              >
                {pr.status}
              </span>
              <a
                href={pr.prUrl}
                target='_blank'
                rel='noopener noreferrer'
                className='font-mono text-accent-blue hover:underline break-all'
              >
                {pr.repoFullName}#{pr.prNumber}
              </a>
            </li>
          ))}
        </ul>
      ) : legacyUrl ? (
        <a
          href={legacyUrl}
          target='_blank'
          rel='noopener noreferrer'
          className='text-sm text-accent-blue hover:underline font-mono break-all'
        >
          {legacyUrl.replace('https://github.com/', '').replace('https://bitbucket.org/', '')}
        </a>
      ) : null}
    </div>
  );
}

/**
 * v2.6.2-track — hierarchical-planner per-phase cost breakdown for the
 * RunDetail Overview tab. Renders nothing when the run didn't use the
 * hierarchical pipeline (planStrategy null) or when no phase has tokens
 * recorded yet — keeps legacy runs free of empty rows.
 */
function PlannerPhaseBreakdown({run}: {run: RunDetail}) {
  if (!run.planStrategy) return null;
  type Row = {
    label: string;
    cost: number | null | undefined;
    inTok: number | null | undefined;
    outTok: number | null | undefined;
  };
  const rows: Row[] = [
    {
      label: 'Classifier',
      cost: run.classifierCostUsd,
      inTok: run.classifierInputTokens,
      outTok: run.classifierOutputTokens,
    },
    {
      label: 'Outline',
      cost: run.outlineCostUsd,
      inTok: run.outlineInputTokens,
      outTok: run.outlineOutputTokens,
    },
    {
      label: 'Expansion',
      cost: run.expansionCostUsd,
      inTok: run.expansionInputTokens,
      outTok: run.expansionOutputTokens,
    },
    {
      label: 'Coherence',
      cost: run.coherenceCostUsd,
      inTok: run.coherenceInputTokens,
      outTok: run.coherenceOutputTokens,
    },
  ];
  const populated = rows.filter(
    (r) => (r.cost ?? 0) > 0 || (r.inTok ?? 0) > 0 || (r.outTok ?? 0) > 0,
  );
  if (populated.length === 0) return null;

  const totalCost = populated.reduce((acc, r) => acc + (r.cost ?? 0), 0);
  const fmtCost = (v: number | null | undefined) =>
    v == null || v === 0 ? '—' : `$${v.toFixed(4)}`;
  const fmtTok = (v: number | null | undefined) =>
    v == null || v === 0 ? '—' : v.toLocaleString();

  return (
    <div className='mt-3 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
      <div className='flex items-center justify-between mb-2'>
        <p className='text-[11px] uppercase tracking-wide text-muted'>
          Planner phases{' '}
          <span className='text-secondary normal-case tracking-normal'>· {run.planStrategy}</span>
        </p>
        <p className='text-[11px] font-mono text-secondary'>phase total {fmtCost(totalCost)}</p>
      </div>
      {run.classifierReasoning && (
        <p className='text-[10.5px] text-muted italic mb-2 leading-snug'>
          {run.classifierReasoning}
        </p>
      )}
      <div className='space-y-1'>
        {populated.map((r) => (
          <div
            key={r.label}
            className='grid grid-cols-[5.5rem_1fr_1fr_1fr] gap-2 text-[11px] font-mono'
          >
            {/* M-006 (Wave 1) — wrap "Classifier" with Glossary so the tooltip explains the phase. */}
            <span className='text-secondary'>
              {r.label === 'Classifier' ? (
                <Glossary term='classifier'>Classifier</Glossary>
              ) : (
                r.label
              )}
            </span>
            <span className='text-muted'>{fmtCost(r.cost)}</span>
            <span className='text-muted'>in {fmtTok(r.inTok)}</span>
            <span className='text-muted'>out {fmtTok(r.outTok)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
