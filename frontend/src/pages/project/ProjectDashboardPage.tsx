import {useEffect, useMemo, useState} from 'react';
import {Link, useLocation, useNavigate, useParams} from 'react-router-dom';
import {ArrowLeft, FolderGit2, Rocket, MoreHorizontal, Plus, Sparkles} from 'lucide-react';
import {useProjectStore} from '@/stores/useProjectStore';
import * as api from '@/lib/api';
import ProjectPrRollup from '@/components/project/ProjectPrRollup';
import ProjectChatPanel from '@/components/project/ProjectChatPanel';
import InlineRunDetail from '@/components/project/InlineRunDetail';
import ProjectLaunchesList from '@/components/project/ProjectLaunchesList';
import PostMergeBar from '@/components/project/PostMergeBar';
import StatusBadge from '@/components/shared/StatusBadge';
import PulseTab from '@/components/project/PulseTab';
import CodeTab, {type CodeSubTab} from '@/components/project/CodeTab';
// Single-surface retirement (2026-05-06) — chat-thread + composer
// extracted from the now-deleted ProjectHomePage.
import ComposeTab from '@/v3/components/ComposeTab';
import EmptyProjectLanding from '@/components/project/EmptyProjectLanding';
import ProjectRepoManager from '@/components/project/ProjectRepoManager';
import MoreDrawer, {useDrawerState, type DrawerPanel} from '@/components/project/MoreDrawer';
import {useIsProjectAdmin} from '@/hooks/useIsProjectAdmin';
import {useUiStore} from '@/stores/useUiStore';
import {cn} from '@/lib/utils';
import type {TaskSummary} from '@/types/project';
import type {RunDetail} from '@/lib/api';

/**
 * v2.6.4 — IA cleanup per UX & Wireframe Report (2026-04-26 §5.3).
 *
 * Old layout: 6 top-level groups (Overview / Work / Knowledge / Activity /
 * Settings / Chat) + 9 sub-tabs = 25 affordances on first paint and no
 * primary action.
 *
 * New layout: 4 top-level tabs + a "More" drawer for the long tail.
 *   • Pulse — last run hero + verified score + health rollup + activity preview
 *   • Runs  — launches list + PRs + selected run detail (today's "Work")
 *   • Code  — architecture + repos + tech-debt + standards + contracts inline
 *   • Chat  — unchanged
 *   • More ▾ — settings · generated docs · memory · activity (full feed) · documents · team
 *
 * Backward compat for deep links / muscle memory:
 *   ?tab=overview         → pulse
 *   ?tab=launches/prs     → runs
 *   ?tab=tasks            → runs (with selected run)
 *   ?tab=architecture     → code (sub=architecture)
 *   ?tab=repos            → code (sub=repos)
 *   ?tab=docs/memory      → drawer
 *   ?tab=activity         → drawer (panel=activity)
 *   ?tab=team             → drawer (panel=team)
 *
 * The drawer also accepts ?drawer=<panel> for deep linking from sibling
 * panels (PulseTab "Show all activity" link, EmptyProjectLanding "Link a
 * repo" link).
 */
// v2.7.2 — Repos promoted from Code → Repos sub-tab to a top-level Project
// tab. Differentiates the Project surface from the legacy generic shell
// (the actual frontend/build/* Vite split is documented as a follow-up in
// docs/plans/pending-project-surface-vite-split.md).
// Single-surface retirement (2026-05-06) — `compose` is the new
// default tab. It hosts the chat-thread + composer that previously
// lived on the deleted ProjectHomePage. Pulse / Repos / Runs / Code /
// Chat panels are still one click away on the same URL.
type TabKey = 'compose' | 'pulse' | 'repos' | 'runs' | 'code' | 'chat';
type RunsSubTab = 'launches' | 'prs' | 'detail';

function legacyTabToNew(legacy: string | null): {
  tab: TabKey;
  sub?: RunsSubTab;
  codeSub?: CodeSubTab;
  drawer?: DrawerPanel;
} {
  switch (legacy) {
    // Legacy aliases (pre-v2.6.4 IA cleanup) — bookmarked URLs from older
    // builds + cross-link emitters that still ship those names.
    case 'overview':
      return {tab: 'pulse'};
    case 'launches':
      return {tab: 'runs', sub: 'launches'};
    case 'prs':
      return {tab: 'runs', sub: 'prs'};
    case 'tasks':
      return {tab: 'runs', sub: 'detail'};
    case 'architecture':
      return {tab: 'code', codeSub: 'architecture'};
    case 'docs':
      return {tab: 'pulse', drawer: 'docs'};
    case 'memory':
      return {tab: 'pulse', drawer: 'memory'};
    case 'activity':
      return {tab: 'pulse', drawer: 'activity'};
    case 'team':
      return {tab: 'pulse', drawer: 'team'};
    // Canonical TabKey identities — keep ?tab=<canonical> working so
    // emitters like RepoContextPanel "Open Architecture →" linking to
    // ?tab=code don't fall through to the default.
    case 'compose':
      return {tab: 'compose'};
    case 'pulse':
      return {tab: 'pulse'};
    case 'repos':
      return {tab: 'repos'};
    case 'runs':
      return {tab: 'runs'};
    case 'code':
      return {tab: 'code'};
    case 'chat':
      return {tab: 'chat'};
    default:
      // Single-surface retirement (2026-05-06): default landing is
      // Compose (chat-thread + composer).
      return {tab: 'compose'};
  }
}

const TAB_LABELS: {key: TabKey; label: string}[] = [
  {key: 'compose', label: 'Compose'},
  {key: 'pulse', label: 'Pulse'},
  {key: 'repos', label: 'Repos'},
  {key: 'runs', label: 'Runs'},
  {key: 'code', label: 'Code'},
  {key: 'chat', label: 'Chat'},
];

export default function ProjectDashboardPage() {
  const {projectId} = useParams<{projectId: string}>();
  const navigate = useNavigate();
  const location = useLocation();
  const drawer = useDrawerState();

  // P-06 (UX audit, 2026-05-03) — when OrgProjectsPage routes here with
  // ?firstRun=1 (the immediate post-create redirect), fire a one-time toast
  // pointing the user at the FirstRunChecklist that EmptyProjectLanding will
  // render naturally (since the new project has no repos + no runs). Strip
  // the param so a refresh doesn't keep retoasting.
  useEffect(() => {
    const sp = new URLSearchParams(window.location.search);
    if (sp.get('firstRun') === '1') {
      useUiStore.getState().addToast({
        type: 'info',
        message: 'Project created · Link your first repo to begin.',
      });
      sp.delete('firstRun');
      const next = `${window.location.pathname}${sp.toString() ? '?' + sp.toString() : ''}`;
      window.history.replaceState(null, '', next);
    }
    // run-once on mount; the rest of the URL handling is in `initial`/setTab
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Read deep-link tab from query string on mount. P-23 (UX audit, 2026-05-03):
  // we now ALSO accept the canonical `sub` + `codeSub` query params alongside
  // the legacy `tab` key so a URL like ?tab=code&codeSub=repos lands directly
  // on the Code → Repos sub-pane. The legacy single-`tab` keys (`overview`,
  // `architecture`, etc.) still map via legacyTabToNew for back-compat with
  // bookmarked / shared links from before the IA cleanup.
  const initial = useMemo(() => {
    const sp = new URLSearchParams(window.location.search);
    const legacy = sp.get('tab');
    const mapped = legacyTabToNew(legacy);
    const subParam = sp.get('sub') as RunsSubTab | null;
    const codeSubParam = sp.get('codeSub') as CodeSubTab | null;
    return {
      ...mapped,
      sub: subParam ?? mapped.sub,
      codeSub: codeSubParam ?? mapped.codeSub,
    };
  }, []);

  const [tab, setTabRaw] = useState<TabKey>(initial.tab);
  const [runsSub, setRunsSubRaw] = useState<RunsSubTab>(initial.sub ?? 'launches');
  const [codeSub, setCodeSubRaw] = useState<CodeSubTab>(initial.codeSub ?? 'architecture');

  // P-23 (UX audit, 2026-05-03) — URL deep-linking. Pre-fix: setTab/setRunsSub/
  // setCodeSub updated state without touching the URL, so ?tab=foo was read once
  // on mount and never written back. Result: bookmarks didn't work, refresh dropped
  // back to default tab, and copy-paste-share-with-teammate sent them to the
  // wrong tab. The drawer state already had two-way URL sync (line ~280); this
  // mirrors that pattern for the three primary view-state slices so every URL
  // is faithful + bookmarkable + back-button-friendly.
  //
  // Implementation: wrap the raw setState in setTab/setRunsSub/setCodeSub that
  // also write the new tuple to window.location via history.replaceState. We
  // use replaceState (not pushState) because tab toggles aren't navigation
  // events that deserve a back-button hop — but the URL still updates so
  // refresh / share / bookmark all work.
  const writeViewToUrl = (next: {tab: TabKey; runsSub: RunsSubTab; codeSub: CodeSubTab}) => {
    const url = new URL(window.location.href);
    url.searchParams.set('tab', next.tab);
    if (next.tab === 'runs') {
      url.searchParams.set('sub', next.runsSub);
      url.searchParams.delete('codeSub');
    } else if (next.tab === 'code') {
      url.searchParams.set('codeSub', next.codeSub);
      url.searchParams.delete('sub');
    } else {
      url.searchParams.delete('sub');
      url.searchParams.delete('codeSub');
    }
    window.history.replaceState(null, '', url.toString());
  };
  const setTab = (t: TabKey) => {
    setTabRaw(t);
    writeViewToUrl({tab: t, runsSub, codeSub});
  };
  const setRunsSub = (s: RunsSubTab) => {
    setRunsSubRaw(s);
    writeViewToUrl({tab, runsSub: s, codeSub});
  };
  const setCodeSub = (s: CodeSubTab) => {
    setCodeSubRaw(s);
    writeViewToUrl({tab, runsSub, codeSub: s});
  };
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [projectLookup, setProjectLookup] = useState<'pending' | 'found' | 'missing'>('pending');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [runCache, setRunCache] = useState<Record<string, RunDetail | 'loading' | 'error'>>({});

  const {dashboard, repos, loading, error, fetchDashboard, fetchScoreTrend, fetchRepos, reset} =
    useProjectStore();

  useEffect(() => {
    if (!projectId) return;
    setProjectLookup('pending');
    api
      .getProject(projectId)
      .then((p) => {
        setProject(p);
        setProjectLookup('found');
        // P-08 — feed the project name into the UI store so Header's
        // breadcrumb can render "Projects / <name>" instead of the legacy
        // literal "Projects / Dashboard".
        if (p?.name) useUiStore.getState().setCurrentProjectName(p.name);
      })
      .catch(() => {
        setProject(null);
        setProjectLookup('missing');
      });
    fetchDashboard(projectId);
    fetchScoreTrend(projectId);
    fetchRepos(projectId);
    return () => {
      reset();
      setProject(null);
      setProjectLookup('pending');
      setSelectedRunId(null);
      setRunCache({});
      // Clear the breadcrumb name on unmount so Header doesn't keep
      // showing the previous project's name when the user navigates away.
      useUiStore.getState().setCurrentProjectName(null);
    };
  }, [projectId, fetchDashboard, fetchScoreTrend, fetchRepos, reset]);

  // If a deep link asked for a drawer panel, open it after mount.
  useEffect(() => {
    if (initial.drawer) {
      drawer.setPanel(initial.drawer);
      drawer.setOpen(true);
      const url = new URL(window.location.href);
      url.searchParams.set('drawer', initial.drawer);
      window.history.replaceState(null, '', url.toString());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // P-23 (UX audit, 2026-05-03) — popstate listener. When the user hits the
  // browser back/forward button, restore tab/sub/codeSub from the URL. Without
  // this, history nav would visit URLs whose state we'd ignore on re-render —
  // breaking the back-button contract that "URL → screen" must hold both ways.
  useEffect(() => {
    const onPopState = () => {
      const sp = new URLSearchParams(window.location.search);
      const legacy = sp.get('tab');
      const mapped = legacyTabToNew(legacy);
      // Read the canonical sub/codeSub keys we now also write — fall back to
      // legacy mapping for old links that only carried `tab=`.
      const subParam = sp.get('sub') as RunsSubTab | null;
      const codeSubParam = sp.get('codeSub') as CodeSubTab | null;
      setTabRaw(mapped.tab);
      setRunsSubRaw(subParam ?? mapped.sub ?? 'launches');
      setCodeSubRaw(codeSubParam ?? mapped.codeSub ?? 'architecture');
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  // 2026-05-09 — same-route `<Link>` propagation. React Router updates
  // location.search when a Link navigates within the current route, but
  // popstate doesn't fire (it's not a history pop) and the mount-time
  // useMemo only runs once. Without this, cross-component links like
  // RepoContextPanel's "Open Architecture →" (Link to ?tab=code) update
  // the URL but the tab state stays where it was → "click does nothing".
  // Effect re-derives tab/sub/codeSub from URL whenever the search string
  // changes; the writeViewToUrl path is idempotent (replaceState with the
  // same string is a no-op) so this doesn't loop.
  useEffect(() => {
    const sp = new URLSearchParams(location.search);
    const legacy = sp.get('tab');
    const mapped = legacyTabToNew(legacy);
    const subParam = sp.get('sub') as RunsSubTab | null;
    const codeSubParam = sp.get('codeSub') as CodeSubTab | null;
    const nextTab = mapped.tab;
    const nextRunsSub = subParam ?? mapped.sub ?? 'launches';
    const nextCodeSub = codeSubParam ?? mapped.codeSub ?? 'architecture';
    // Only setState when something actually changed — keeps render churn low.
    setTabRaw((curr) => (curr === nextTab ? curr : nextTab));
    setRunsSubRaw((curr) => (curr === nextRunsSub ? curr : nextRunsSub));
    setCodeSubRaw((curr) => (curr === nextCodeSub ? curr : nextCodeSub));
  }, [location.search]);

  // R-09 lint cleanup — wrap `tasks` in useMemo so the auto-select useEffect
  // below has a stable dependency. Without the memo the `?? []` literal
  // produces a new array reference on every render which would re-fire the
  // effect even when the actual dashboard state didn't change.
  const tasks: TaskSummary[] = useMemo(
    () => dashboard?.recentTasks ?? [],
    [dashboard?.recentTasks],
  );
  const recentPrs = dashboard?.recentPrs ?? [];

  // Auto-select first run so the right pane is never empty when runs exist
  useEffect(() => {
    if (!selectedRunId && tasks.length > 0) {
      setSelectedRunId(tasks[0].runId);
    }
  }, [tasks, selectedRunId]);

  const loadRun = async (runId: string) => {
    setRunCache((c) => ({...c, [runId]: 'loading'}));
    try {
      const run = await api.getRun(runId);
      setRunCache((c) => ({...c, [runId]: run}));
    } catch {
      setRunCache((c) => ({...c, [runId]: 'error'}));
    }
  };

  useEffect(() => {
    if (selectedRunId && !runCache[selectedRunId]) void loadRun(selectedRunId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRunId]);

  const selectedDetail = selectedRunId ? runCache[selectedRunId] : undefined;

  const onOpenRun = (runId: string) => {
    setSelectedRunId(runId);
    setTab('runs');
    setRunsSub('detail');
  };

  const onSwitchTab = (t: TabKey, opts?: {codeSub?: CodeSubTab}) => {
    setTab(t);
    // P-05 (UX audit, 2026-05-03) — when the caller specifies the code
    // sub-pane (e.g. PulseTab's HealthCard "Drill into Code" → Architecture),
    // apply it in the same call so the URL and tab strip line up on first
    // render. Without this, switching to Code would land on whatever sub-tab
    // was last selected — surprising for the user who clicked "Architecture".
    if (t === 'code' && opts?.codeSub) {
      setCodeSub(opts.codeSub);
    }
  };

  /**
   * v2.7.2 — single entry point for "open the AddRepoModal". Used by:
   *   - EmptyProjectLanding's "Link a repo" button (#1)
   *   - the project header "Repos" chip (#2a)
   *   - the Pulse-tab "Add repository" banner when repoCount===0 (#2b)
   *
   * After v2.7.2 promoted Repos to a top-level tab, this just switches
   * tab and dispatches the global event ProjectRepoManager listens for.
   * The 50ms defer waits for ProjectRepoManager's mount + window-listener
   * registration; without it the event fires into the void.
   */
  const openAddRepoFlow = () => {
    setTab('repos');
    setTimeout(() => {
      window.dispatchEvent(new Event('open-add-repo-modal'));
    }, 50);
  };

  const openDrawer = (panel: DrawerPanel) => {
    drawer.setPanel(panel);
    drawer.setOpen(true);
    const url = new URL(window.location.href);
    url.searchParams.set('drawer', panel);
    window.history.replaceState(null, '', url.toString());
  };

  const closeDrawer = () => {
    drawer.setOpen(false);
    const url = new URL(window.location.href);
    url.searchParams.delete('drawer');
    window.history.replaceState(null, '', url.toString());
  };

  if (!projectId) {
    return (
      <div className='h-full overflow-y-auto'>
        <div className='mx-auto max-w-5xl p-4 text-sm text-muted'>No project selected.</div>
      </div>
    );
  }
  if (projectLookup === 'missing') {
    return (
      <div className='h-full overflow-y-auto'>
        <div className='mx-auto max-w-5xl p-4 space-y-3'>
          <h1 className='text-[14px] font-medium text-primary'>Project not found</h1>
          <p className='text-xs text-muted'>
            <span className='font-mono'>{projectId}</span> is not a valid project.
          </p>
          <Link to='/projects' className='text-xs text-accent-blue hover:underline'>
            ← Back to projects
          </Link>
        </div>
      </div>
    );
  }

  const showLeftRunsList = tab === 'runs' && runsSub === 'detail';
  const noRuns = tasks.length === 0;
  const repoCount = repos?.length ?? 0;

  // 2026-05-07 — wide + fill modifiers on the v3 main wrapper are
  // driven by `Shell.classifyRoute` for /projects/:id/* paths. Previous
  // per-page `useEffect` removed in favour of the centralised policy.

  return (
    <div className='h-full flex flex-col'>
      {/* Top chrome: back link + header + compact summary bar + tab strip */}
      <div className='border-b border-[var(--border-default)] px-4 pt-3 pb-2 space-y-2 flex-shrink-0'>
        <Link
          to='/projects'
          className='inline-flex items-center gap-1 text-xs text-muted hover:text-accent-blue transition-colors'
        >
          <ArrowLeft className='h-3 w-3' />
          Projects
        </Link>

        {projectId && <PostMergeBar projectId={projectId} />}

        {/* X-10 (UX audit, 2026-05-03) — header now wraps on narrow screens
            so the action cluster (Repos chip / New / More) drops below the
            project name + icon instead of overflowing or truncating. The
            stacked layout on <sm: keeps every affordance reachable without
            horizontal scroll. */}
        <header className='flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3'>
          <div className='min-w-0 flex items-start gap-3'>
            <span className='inline-flex h-9 w-9 items-center justify-center rounded-md border border-[color-mix(in_srgb,var(--accent-blue)_30%,transparent)] bg-[color-mix(in_srgb,var(--accent-blue)_12%,transparent)] text-accent-blue flex-shrink-0'>
              <FolderGit2 className='h-5 w-5' />
            </span>
            <div className='min-w-0'>
              <div className='font-mono text-[10.5px] font-medium uppercase tracking-[0.08em] text-accent-blue mb-0.5'>
                Project
              </div>
              <h1 className='text-lg sm:text-xl font-semibold text-primary tracking-[-0.015em] leading-tight truncate'>
                {project?.name ?? 'Project'}
              </h1>
            </div>
          </div>
          <div className='flex items-center gap-2 flex-wrap'>
            {/* v2.7.2 — first-class Repos chip. Always visible; amber-tinted
                when zero repos so users see the linkage gap before they
                discover it via a failed Launch. Click opens AddRepoModal
                directly via the shared openAddRepoFlow.
                P-05 (UX audit, 2026-05-03): pre-fix the chip ALWAYS opened
                the Add modal — including for users who already had repos
                and just wanted to inspect them, which broke their muscle
                memory ("show me what's there" vs. "add a new one"). Now
                the chip splits behaviour: zero repos still opens the modal
                (nothing to manage); ≥1 repo switches to Code → Repos tab,
                with a separate small "+" button immediately to its right
                for the add-another flow. */}
            {repoCount === 0 ? (
              <button
                onClick={openAddRepoFlow}
                aria-label='Link your first repository'
                title='No repos linked — click to add one'
                className='inline-flex items-center gap-1 text-xs font-medium px-2 py-1 rounded-sm border transition-colors border-status-waiting/40 bg-status-waiting/10 text-status-waiting hover:bg-status-waiting/20'
              >
                <Plus className='h-3.5 w-3.5' />
                <span>Repos · 0</span>
              </button>
            ) : (
              <div className='inline-flex items-center rounded-sm border border-[var(--border-default)] overflow-hidden'>
                <button
                  onClick={() => onSwitchTab('repos')}
                  aria-label={`View ${repoCount} linked repositor${repoCount === 1 ? 'y' : 'ies'}`}
                  title={`View ${repoCount} linked repo${repoCount === 1 ? '' : 's'}`}
                  className='inline-flex items-center gap-1 text-xs font-medium px-2 py-1 text-secondary hover:text-primary hover:bg-[var(--gray-200)] transition-colors'
                >
                  <FolderGit2 className='h-3.5 w-3.5' />
                  <span>Repos · {repoCount}</span>
                </button>
                <span className='w-px h-4 bg-[var(--border-default)]' aria-hidden='true' />
                <button
                  onClick={openAddRepoFlow}
                  aria-label='Add another repository'
                  title='Add another repository'
                  className='inline-flex items-center justify-center px-1.5 py-1 text-secondary hover:text-primary hover:bg-[var(--gray-200)] transition-colors'
                >
                  <Plus className='h-3.5 w-3.5' />
                </button>
              </div>
            )}
            <NewExecutionButton projectId={projectId} repoCount={repoCount} />
            {/* P-01 (UX audit, 2026-05-04) — reverse cross-link to the
                ProjectHomePage composer surface ("What do you want to
                build?" with one-shot spec textbox + iteration thread).
                Pre-fix users on the dashboard had no path back to the
                composer surface short of typing /projects/{id} into the
                URL bar. This button closes that loop; ProjectHomePage's
                "Dashboard →" button is the matching forward link. The
                two surfaces stay distinct (per the Option C decision)
                but each one knows about the other. */}
            <Link
              to={`/projects/${projectId}`}
              aria-label='Open the quick-spec composer view'
              title='Quick-spec composer — write a one-line goal, ship a draft PR.'
              className='inline-flex items-center gap-1 text-xs font-medium px-2 py-1 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary hover:border-accent-blue/40 transition-colors'
            >
              <Sparkles className='h-3.5 w-3.5' />
              <span className='hidden sm:inline'>Quick spec</span>
            </Link>
            <button
              onClick={() => openDrawer('settings')}
              aria-label='Open more'
              title='Settings, docs, memory, activity, team'
              className='inline-flex items-center gap-1 text-xs font-medium px-2 py-1 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary hover:border-accent-blue/40 transition-colors'
            >
              <MoreHorizontal className='h-3.5 w-3.5' />
              More
            </button>
          </div>
        </header>

        {/* v2.6.4 — compact one-row summary bar replaces the 6 KPI tiles.
            Moves "no data yet" sea of nothing into a single low-noise row. */}
        <SummaryBar
          provider={project?.aiProvider}
          status={project?.status}
          githubOwner={project?.githubOwner}
          repoCount={repoCount}
          completedRuns={dashboard?.completedRuns}
          activeRuns={dashboard?.activeRuns}
          failedRuns={dashboard?.failedRuns}
          totalCostUsd={dashboard?.totalCostUsd}
          avgScore={dashboard?.avgScore}
        />

        {loading && <div className='text-xs text-muted'>Loading…</div>}
        {error && <div className='text-xs text-accent-red'>{error}</div>}

        {/* Top-level 4-tab strip */}
        <nav
          className='flex gap-0.5 border-b border-[var(--border-default)] overflow-x-auto -mx-4 px-4'
          role='tablist'
          aria-label='Project sections'
        >
          {TAB_LABELS.map((t) => (
            <button
              key={t.key}
              role='tab'
              aria-selected={tab === t.key}
              onClick={() => setTab(t.key)}
              className={cn(
                'px-3 py-1.5 text-xs font-semibold whitespace-nowrap transition-colors border-b-2 -mb-px',
                tab === t.key
                  ? 'border-accent-blue text-primary'
                  : 'border-transparent text-muted hover:text-secondary',
              )}
            >
              {t.label}
            </button>
          ))}
        </nav>

        {/* Runs sub-tab strip — only shown when in Runs tab */}
        {tab === 'runs' && (
          <nav
            className='flex gap-0.5 overflow-x-auto -mx-4 px-4 pt-1'
            role='tablist'
            aria-label='Runs sections'
          >
            {(['launches', 'prs', 'detail'] as RunsSubTab[]).map((s) => (
              <button
                key={s}
                role='tab'
                aria-selected={runsSub === s}
                onClick={() => setRunsSub(s)}
                className={cn(
                  'px-2.5 py-1 text-[11px] font-medium whitespace-nowrap rounded-sm transition-colors',
                  runsSub === s
                    ? 'bg-accent-blue/10 text-accent-blue border border-accent-blue/30'
                    : 'border border-transparent text-muted hover:text-secondary hover:border-[var(--border-default)]',
                )}
              >
                {s === 'launches' ? 'Launches' : s === 'prs' ? 'Pull requests' : 'Run detail'}
              </button>
            ))}
          </nav>
        )}
      </div>

      {/* Body */}
      <div className='flex-1 min-h-0 flex flex-col lg:flex-row overflow-hidden'>
        {showLeftRunsList && (
          <aside className='w-full lg:w-[300px] flex-shrink-0 border-b lg:border-b-0 lg:border-r border-[var(--border-default)] overflow-y-auto max-h-40 lg:max-h-none'>
            <RunsSidebar
              tasks={tasks}
              selectedRunId={selectedRunId}
              onSelect={(id) => {
                setSelectedRunId(id);
                setRunsSub('detail');
              }}
            />
          </aside>
        )}
        <main
          className={cn(
            'flex-1 min-w-0 min-h-0',
            // Single-surface retirement (2026-05-06): Compose + Chat
            // both want flex-column layout (composer/input pinned to
            // the bottom of the pane). Other tabs use the standard
            // scrolling content layout.
            tab === 'chat' || tab === 'compose' ? 'flex flex-col' : 'overflow-y-auto p-3 sm:p-4',
          )}
        >
          {/* Single-surface retirement (2026-05-06) — Compose tab.
              Hosts the chat-thread + composer that previously lived
              on the now-deleted ProjectHomePage. Default tab. */}
          {tab === 'compose' && (
            <div
              style={{
                padding: '12px 14px',
                display: 'flex',
                flexDirection: 'column',
                flex: 1,
                minHeight: 0,
              }}
            >
              <ComposeTab projectId={projectId!} defaultProvider={project?.aiProvider} />
            </div>
          )}
          {tab === 'pulse' &&
            (noRuns && repoCount === 0 ? (
              <EmptyProjectLanding
                projectId={projectId}
                repoCount={repoCount}
                onLinkRepo={openAddRepoFlow}
              />
            ) : (
              <PulseTab
                projectId={projectId}
                recentTasks={tasks}
                recentPrs={recentPrs}
                onOpenRun={onOpenRun}
                onSwitchTab={onSwitchTab}
                onLinkRepo={repoCount === 0 ? openAddRepoFlow : undefined}
              />
            ))}
          {tab === 'repos' && <ProjectRepoManager projectId={projectId} />}
          {tab === 'runs' && runsSub === 'launches' && (
            <ProjectLaunchesList projectId={projectId} />
          )}
          {tab === 'runs' && runsSub === 'prs' && (
            <ProjectPrRollup
              projectId={projectId}
              onAddressReview={(config) => {
                navigate(`/projects/${projectId}/executions/new`, {state: {continueOnPr: config}});
              }}
            />
          )}
          {tab === 'runs' &&
            runsSub === 'detail' &&
            (selectedDetail === 'loading' ? (
              <div className='text-xs text-muted p-3'>Loading run…</div>
            ) : selectedDetail === 'error' ? (
              <div className='text-xs text-accent-red p-3'>Failed to load run.</div>
            ) : selectedDetail ? (
              <InlineRunDetail
                run={selectedDetail}
                onRefresh={() => (selectedRunId ? loadRun(selectedRunId) : Promise.resolve())}
              />
            ) : (
              /* R-191 (post-cutover) — Mission rename reverted; v3 vocabulary
                 is "run" everywhere. */
              <div className='text-xs text-muted p-3'>Select a run on the left.</div>
            ))}
          {tab === 'code' && (
            <CodeTab
              projectId={projectId}
              orgId={project?.orgId}
              repos={repos ?? []}
              sub={codeSub}
              onSubChange={setCodeSub}
            />
          )}
          {tab === 'chat' && (
            <ProjectChatPanel projectId={projectId} aiProvider={project?.aiProvider} />
          )}
        </main>
      </div>

      <MoreDrawer
        open={drawer.open}
        panel={drawer.panel}
        onClose={closeDrawer}
        onPanelChange={drawer.setPanel}
        projectId={projectId}
        project={project}
        onProjectUpdate={setProject}
      />
    </div>
  );
}

function SummaryBar({
  provider,
  status,
  githubOwner,
  repoCount,
  completedRuns,
  activeRuns,
  failedRuns,
  totalCostUsd,
  avgScore,
}: {
  provider?: string | null;
  status?: string | null;
  githubOwner?: string | null;
  repoCount: number;
  completedRuns?: number;
  activeRuns?: number;
  failedRuns?: number;
  totalCostUsd?: number;
  avgScore?: number;
}) {
  // Render a single scrollable row of facts. Only show segments that
  // actually have data so empty projects don't have 8 "—" placeholders.
  const segments: {label: string; value: string; muted?: boolean}[] = [];
  if (status) segments.push({label: '', value: status, muted: true});
  if (provider) segments.push({label: '', value: provider});
  if (githubOwner) segments.push({label: 'owner', value: githubOwner});
  segments.push({label: 'repos', value: String(repoCount)});
  if (completedRuns != null) segments.push({label: 'done', value: String(completedRuns)});
  if (activeRuns != null && activeRuns > 0)
    segments.push({label: 'active', value: String(activeRuns)});
  if (failedRuns != null && failedRuns > 0)
    segments.push({label: 'failed', value: String(failedRuns)});
  if (avgScore != null && avgScore > 0) {
    segments.push({label: 'score', value: `${Math.round(avgScore)}`});
  }
  if (totalCostUsd != null && totalCostUsd > 0) {
    segments.push({label: 'spend', value: `$${totalCostUsd.toFixed(2)}`});
  }

  if (segments.length === 0) {
    return <div className='text-[11px] text-muted'>No project metadata yet.</div>;
  }

  return (
    // X-10 (UX audit, 2026-05-03) — on <sm screens this collapses to a
    // horizontally-scrollable row so it stays one line even with 9 segments
    // (was wrapping to 5 rows on phones). Desktop keeps the wrap behaviour
    // so power users can still see everything at once.
    <div className='flex sm:flex-wrap items-center gap-x-3 gap-y-1 text-[11px] overflow-x-auto sm:overflow-visible whitespace-nowrap sm:whitespace-normal -mx-3 px-3 sm:mx-0 sm:px-0'>
      {segments.map((s, i) => (
        <span key={i} className='inline-flex items-center gap-1 flex-shrink-0'>
          {s.label && (
            <span className='text-muted font-mono uppercase tracking-wider'>{s.label}</span>
          )}
          <span className={cn('font-mono', s.muted ? 'text-muted' : 'text-secondary')}>
            {s.value}
          </span>
          {i < segments.length - 1 && <span className='text-muted'>·</span>}
        </span>
      ))}
    </div>
  );
}

function RunsSidebar({
  tasks,
  selectedRunId,
  onSelect,
}: {
  tasks: TaskSummary[];
  selectedRunId: string | null;
  onSelect: (runId: string) => void;
}) {
  if (tasks.length === 0) {
    return <div className='text-xs text-muted p-3'>No runs yet.</div>;
  }
  return (
    <div>
      <div className='px-3 py-2 text-[11px] uppercase tracking-wide text-muted border-b border-[var(--border-default)]'>
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
              <div className='flex items-center justify-between gap-2'>
                <StatusBadge status={t.status} />
                <span className={cn('text-[11px] font-mono tabular-nums', scoreColor(t.score))}>
                  {t.score != null ? t.score : '—'}
                </span>
              </div>
              <div
                className={cn(
                  'text-sm truncate mt-0.5',
                  selected ? 'text-primary' : 'text-secondary',
                )}
              >
                {t.name || t.runId.slice(0, 8)}
              </div>
              <div className='text-[11px] text-muted font-mono mt-0.5'>
                {t.createdAt ? new Date(t.createdAt).toLocaleString() : '—'}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function scoreColor(score: number | null | undefined): string {
  if (score == null) return 'text-muted';
  if (score >= 90) return 'text-status-success';
  if (score >= 75) return 'text-status-waiting';
  return 'text-status-error';
}

function NewExecutionButton({projectId, repoCount}: {projectId: string; repoCount: number}) {
  const navigate = useNavigate();
  const isAdmin = useIsProjectAdmin();
  // Two independent disable reasons. Order matters for the title — show the
  // permission gate first because non-admins can't fix the repo gap themselves.
  const disabledReason: string | null = !isAdmin
    ? 'Only org admins can start a Launch. Ask an admin to run this for you.'
    : repoCount === 0
      ? 'Link at least one repository to launch.'
      : null;
  const disabled = disabledReason !== null;
  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
  const title = disabled ? disabledReason! : 'Start a cross-repo Launch';
  return (
    <div className='flex flex-col items-end gap-1'>
      <button
        onClick={() => !disabled && navigate(`/projects/${projectId}/executions/new`)}
        disabled={disabled}
        aria-label={title}
        className={cn(
          'flex items-center gap-1.5 text-xs font-medium rounded-sm border px-2.5 py-1 transition-colors',
          disabled
            ? 'border-[var(--border-default)] bg-[var(--gray-100)] text-muted cursor-not-allowed opacity-60'
            : 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20',
        )}
        title={title}
      >
        <Rocket className='h-3 w-3' />
        Launch
      </button>
      {disabled && (
        <span className='text-[10px] text-muted italic whitespace-nowrap'>
          {!isAdmin ? 'Admin only' : 'Link a repo to enable'}
        </span>
      )}
    </div>
  );
}
