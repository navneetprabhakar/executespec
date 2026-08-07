import {useCallback, useEffect, useRef, useState} from 'react';
import {NavLink, Outlet, useLocation, useNavigate} from 'react-router-dom';
import '@/ide/ide.css';
import './console.css';
import '@/signal/signal.css';
import * as api from '@/lib/api';
import {routeTitle} from '@/hooks/useDocumentTitle';
import {useUiStore} from '@/stores/useUiStore';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';
import {IdeCommandPalette, usePaletteHotkey, type PaletteCmd, statusKind} from '@/ide/IdeKit';
import {PlanGateIndicator} from '@/ide/PlanGateIndicator';
import {ScoreBadge} from '@/ide/ScoreBadge';
import {AnnouncementBanner} from './AnnouncementBanner';
import {requestNewAgentSession} from '@/ide/agentSessionActions';
import {
  WorkspaceIcon,
  RunsIcon,
  ProjectsIcon,
  AdminIcon,
  AccountIcon,
  BellIcon,
  MenuIcon,
  CommandIcon,
  SunIcon,
  MoonIcon,
  MonitorIcon,
  SettingsIcon,
  StatusDot,
  PlusIcon,
  type IconProps,
} from '@/ide/IdeIcons';

/**
 * ConsoleShell is the product chrome. It replaces the VS Code-style global
 * workspace metaphor with a control plane: top bar, primary product nav, live
 * recent runs, and a protected route outlet for the actual work surfaces.
 */
type NavItem = {path: string; Icon: (p: IconProps) => React.ReactElement; label: string};
const MERGE_RAIL_MARK = '/assets/brand/merge-rail/executespec-merge-rail-mark-light.svg';
const NAV: NavItem[] = [
  {path: '/home', Icon: WorkspaceIcon, label: 'Home'},
  {path: '/compose', Icon: PlusIcon, label: 'Start'},
  {path: '/agent', Icon: CommandIcon, label: 'Agent'},
  {path: '/runs', Icon: RunsIcon, label: 'Work'},
  {path: '/projects', Icon: ProjectsIcon, label: 'Projects'},
  {path: '/org-members', Icon: AccountIcon, label: 'Team'},
  // Setup used to reuse WorkspaceIcon, so Home and Setup — two different
  // destinations — were the same glyph in the nav, the drawer and the tab bar.
  {path: '/setup', Icon: SettingsIcon, label: 'Setup'},
];

function runLabel(r: api.RunSummary): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 48);
}

function projectLabel(p: api.ProjectDto): string {
  return (p.name || p.id).slice(0, 48);
}

/** Label for a sandbox session's server-reported latest linked run. */
function sandboxLabel(s: api.AgentSessionResponse): string {
  return (s.latestLinkedRunTitle || s.title || s.latestLinkedRunId || s.id).slice(0, 48);
}

/**
 * The newest agent session that actually linked a run. Session order from the
 * API is newest-first; a session with no linked run is not a sandbox the
 * operator can open.
 */
function latestSandboxSession(
  sessions: api.AgentSessionResponse[],
): api.AgentSessionResponse | null {
  return sessions.find((s) => !!s.latestLinkedRunId) ?? null;
}

export default function ConsoleShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);
  const drawerRef = useRef<HTMLElement | null>(null);
  const drawerCloseRef = useRef<HTMLButtonElement | null>(null);
  const shellRef = useRef<HTMLDivElement | null>(null);
  // RR-1655 — the three shell loaders used to share one `apiError` boolean and leave their
  // state at `[]`/`0` on failure, so a projects-only read failure rendered the affirmative
  // "you have none" copy ("No work yet", the empty-projects "New project" affordance) for a
  // user who may well have both, while any single failure raised the workspace-wide banner
  // over two rails that had rendered real data. Each source now tracks its own outcome.
  const [runsFailed, setRunsFailed] = useState(false);
  const [projectsFailed, setProjectsFailed] = useState(false);
  const [unreadFailed, setUnreadFailed] = useState(false);
  const apiError = runsFailed || projectsFailed || unreadFailed;
  const [navCollapsed, setNavCollapsed] = useState(
    () => localStorage.getItem('es:nav:collapsed') === '1',
  );
  const toggleNav = () =>
    setNavCollapsed((v) => {
      localStorage.setItem('es:nav:collapsed', v ? '0' : '1');
      return !v;
    });
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [agentSessions, setAgentSessions] = useState<api.AgentSessionResponse[]>([]);
  const [remoteSandboxEnabled, setRemoteSandboxEnabled] = useState(false);
  const [unread, setUnread] = useState(0);
  const latestSandbox = latestSandboxSession(agentSessions);

  usePaletteHotkey(setPaletteOpen);

  // Recent runs stay live (poll 12s, skip when hidden) — same discipline as the
  // side-menu fix (#10). Refresh on navigation too so a just-submitted run shows.
  const refreshRuns = useCallback(() => {
    api
      .getRuns({size: 6})
      .then((r) => {
        setRuns(r.content ?? []);
        setRunsFailed(false);
      })
      .catch(() => setRunsFailed(true));
  }, []);
  // Unread notification count for the top-bar bell (founder report #2) — same cadence.
  const refreshUnread = useCallback(() => {
    api
      .getUnreadCount()
      .then((r) => {
        setUnread(r.count ?? 0);
        setUnreadFailed(false);
      })
      .catch(() => setUnreadFailed(true));
  }, []);
  const refreshProjects = useCallback(() => {
    api
      .getProjects()
      .then((items) => {
        setProjects(items.filter((p) => !p.archivedAt));
        setProjectsFailed(false);
      })
      .catch(() => setProjectsFailed(true));
  }, []);
  // Sandbox sessions back the "latest sandbox" command. This is a secondary
  // palette affordance, so a failure must stay silent rather than raise the
  // shell-wide API error banner.
  const refreshAgentSessions = useCallback(() => {
    api
      .listAgentSessions(20)
      .then(setAgentSessions)
      .catch(() => setAgentSessions([]));
  }, []);
  useEffect(() => {
    refreshRuns();
    refreshUnread();
    refreshProjects();
    refreshAgentSessions();
    api
      .getRuntimeClientCapabilities()
      .then((capabilities) => setRemoteSandboxEnabled(capabilities.remoteSandbox?.enabled === true))
      .catch(() => setRemoteSandboxEnabled(false));
  }, [refreshRuns, refreshUnread, refreshProjects, refreshAgentSessions]);
  useEffect(() => {
    const id = window.setInterval(() => {
      if (!document.hidden) {
        refreshRuns();
        refreshUnread();
      }
    }, 12000);
    return () => window.clearInterval(id);
  }, [refreshRuns, refreshUnread]);
  useEffect(() => {
    setDrawerOpen(false);
    refreshRuns();
    refreshUnread();
    refreshProjects();
    refreshAgentSessions();
  }, [location.pathname, refreshRuns, refreshUnread, refreshProjects, refreshAgentSessions]);

  // C2 — route-derived tab titles; C4 — focus the content region on navigation.
  useEffect(() => {
    document.title = `${routeTitle(location.pathname)} · ExecuteSpec`;
  }, [location.pathname]);
  const mainRef = useRef<HTMLElement | null>(null);
  useEffect(() => {
    mainRef.current?.focus({preventScroll: true});
  }, [location.pathname]);
  // Account dropdown (C1.3) — closes on navigation.
  const [accountOpen, setAccountOpen] = useState(false);
  useEffect(() => setAccountOpen(false), [location.pathname]);

  // The mobile drawer is fixed below both the announcement and top bar. Keep
  // the offset measured instead of guessing a banner height (the message can
  // wrap differently on narrow screens). Focus is returned to the trigger and
  // keyboard users cannot tab behind the open drawer.
  useEffect(() => {
    const shell = shellRef.current;
    if (!shell) return;
    const updateBannerHeight = () => {
      const noticeHeight = Array.from(
        shell.querySelectorAll<HTMLElement>('.con-banner, .con-api-error'),
      ).reduce((height, notice) => height + notice.offsetHeight, 0);
      shell.style.setProperty('--con-banner-height', `${noticeHeight}px`);
    };
    updateBannerHeight();
    const resizeObserver =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(updateBannerHeight);
    shell.querySelectorAll<HTMLElement>('.con-banner, .con-api-error').forEach((notice) => {
      resizeObserver?.observe(notice);
    });
    // Dismissal removes the banner node, which does not produce a resize on
    // the removed element. Observe the shell tree so the offset resets to zero
    // immediately after dismissal as well.
    const mutationObserver =
      typeof MutationObserver === 'undefined' ? null : new MutationObserver(updateBannerHeight);
    mutationObserver?.observe(shell, {childList: true, subtree: true});
    return () => {
      resizeObserver?.disconnect();
      mutationObserver?.disconnect();
    };
  }, [location.pathname]);

  useEffect(() => {
    if (!drawerOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setDrawerOpen(false);
        menuButtonRef.current?.focus();
        return;
      }
      if (event.key !== 'Tab' || !drawerRef.current) return;
      const focusable = Array.from(
        drawerRef.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), summary, [tabindex]:not([tabindex="-1"])',
        ),
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    const focusFrame = requestAnimationFrame(() => drawerCloseRef.current?.focus());
    return () => {
      cancelAnimationFrame(focusFrame);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [drawerOpen]);

  const path = location.pathname;
  const workFocusedPath =
    path.startsWith('/agent') ||
    path.startsWith('/plan') ||
    path.startsWith('/live') ||
    path.startsWith('/result');
  // On /agent the primary CTA must actually create a sandbox session. It used
  // to navigate to /agent while already on /agent, which is a no-op that reads
  // as a broken button. Navigation is transport, not creation.
  const startSandbox = useCallback(() => {
    requestNewAgentSession({});
    if (!location.pathname.startsWith('/agent')) navigate('/agent');
  }, [location.pathname, navigate]);
  const primaryCta = path.startsWith('/agent')
    ? {
        label: remoteSandboxEnabled ? 'Sandbox' : 'Workspace',
        title: remoteSandboxEnabled ? 'Start a remote sandbox' : 'Start a remote workspace',
        run: startSandbox,
      }
    : {label: 'Start', title: 'Start work', run: () => navigate('/compose')};
  const shellClasses = [
    'ide-root',
    'con-root',
    drawerOpen ? 'drawer-active' : '',
    navCollapsed ? 'nav-collapsed' : '',
    path.startsWith('/compose') ? 'compose-active' : '',
    path.startsWith('/agent') ? 'agent-active' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const navActive = (p: string) => {
    if (p === '/home') return path === '/' || path.startsWith('/home');
    if (p === '/compose') return path.startsWith('/compose');
    if (p === '/runs')
      return (
        path.startsWith('/runs') ||
        path.startsWith('/decisions') ||
        path.startsWith('/plan') ||
        path.startsWith('/live') ||
        path.startsWith('/result')
      );
    if (p === '/agent') return path.startsWith('/agent');
    if (p === '/org-members') return path.startsWith('/org-members');
    if (p === '/setup')
      return (
        path.startsWith('/setup') ||
        path.startsWith('/more') ||
        path.startsWith('/account') ||
        path.startsWith('/billing') ||
        path.startsWith('/plans') ||
        path.startsWith('/integrations') ||
        path.startsWith('/api-keys') ||
        path.startsWith('/provider-keys') ||
        path.startsWith('/org/settings') ||
        path.startsWith('/insights') ||
        path.startsWith('/audit') ||
        path.startsWith('/deployments') ||
        path.startsWith('/admin') ||
        path.startsWith('/benchmark')
      );
    return path.startsWith(p);
  };

  const commands: PaletteCmd[] = [
    // One entry per destination. `start` and `compose` both went to /compose,
    // and `decisions` duplicated `agent-approvals` — the palette listed the same
    // two destinations twice under different names.
    {id: 'compose', label: 'Start work', hint: 'new run', run: () => navigate('/compose')},
    {id: 'agent', label: 'Go: Agent', hint: 'chat', run: () => navigate('/agent')},
    {
      id: 'agent-new-sandbox',
      label: `Agent: New remote ${remoteSandboxEnabled ? 'sandbox' : 'workspace'}`,
      hint: remoteSandboxEnabled ? 'sandbox' : 'workspace',
      run: startSandbox,
    },
    {
      id: 'agent-approvals',
      label: 'Go: Awaiting approvals',
      hint: 'gate',
      run: () => navigate('/decisions'),
    },
    {
      id: 'agent-latest-sandbox',
      label: `Agent: Open latest ${remoteSandboxEnabled ? 'sandbox' : 'workspace'}`,
      // Server-owned truth. `runs[0]` is the newest run of ANY origin, so this
      // command used to open a run the sandbox never touched. `latestLinkedRun*`
      // on the agent-session read model is the run actually linked to a sandbox.
      hint: latestSandbox ? sandboxLabel(latestSandbox) : 'none',
      run: () =>
        navigate(
          latestSandbox?.latestLinkedRunId
            ? `/result/${latestSandbox.latestLinkedRunId}`
            : '/agent',
        ),
    },
    {id: 'new-project', label: 'New project', hint: 'new', run: () => navigate('/projects')},
    {id: 'runs', label: 'Go: Work', hint: 'g r', run: () => navigate('/runs')},
    {id: 'projects', label: 'Go: Projects', run: () => navigate('/projects')},
    {id: 'team', label: 'Go: Team / invites', run: () => navigate('/org-members')},
    {id: 'setup', label: 'Go: Setup', run: () => navigate('/setup')},
    {id: 'decisions', label: 'Work: Awaiting approval', run: () => navigate('/decisions')},
    {id: 'search', label: 'Go: Search', run: () => navigate('/search')},
    {id: 'notifications', label: 'Go: Notifications', run: () => navigate('/notifications')},
    {id: 'account', label: 'Go: Account', run: () => navigate('/account')},
    ...(isSuperAdmin
      ? [
          {id: 'admin', label: 'Go: Admin', run: () => navigate('/admin')},
          {id: 'benchmark', label: 'Go: Benchmarks', run: () => navigate('/benchmark')},
        ]
      : []),
    {id: 'theme', label: 'Toggle theme', run: toggleTheme},
    ...runs.map((r) => ({
      id: r.runId,
      label: `Open run: ${runLabel(r)}`,
      run: () => navigate(`/result/${r.runId}`),
    })),
    ...projects.map((p) => ({
      id: p.id,
      label: `Open project: ${projectLabel(p)}`,
      run: () => navigate(`/projects/${p.id}`),
    })),
  ];

  const ThemeIcon = theme === 'light' ? SunIcon : theme === 'system' ? MonitorIcon : MoonIcon;

  return (
    <div
      ref={shellRef}
      className={shellClasses}
      data-surface={path.startsWith('/agent') ? 'transcript' : 'console'}
    >
      <a
        href='#main-content'
        className='con-skip-link'
        onClick={(e) => {
          e.preventDefault();
          mainRef.current?.focus({preventScroll: false});
        }}
      >
        Skip to main content
      </a>
      {!workFocusedPath && <AnnouncementBanner />}
      <header className='con-topbar'>
        <button
          type='button'
          className='con-icon con-only-mobile'
          ref={menuButtonRef}
          onClick={() => setDrawerOpen(true)}
          title='Open navigation menu'
          aria-label='Open navigation menu'
          aria-expanded={drawerOpen}
        >
          <MenuIcon size={20} />
        </button>
        <NavLink to='/home' className='con-brand'>
          <img className='con-brand-mark' src={MERGE_RAIL_MARK} alt='' aria-hidden='true' />
          <span>ExecuteSpec</span>
        </NavLink>
        <span className='con-topbar-spacer' />
        <PlanGateIndicator dir='down' />
        <button
          type='button'
          className='con-newrun'
          onClick={primaryCta.run}
          title={primaryCta.title}
        >
          <PlusIcon size={15} />
          <span className='con-newrun-label'>{primaryCta.label}</span>
        </button>
        <button
          type='button'
          className='con-icon'
          onClick={() => setPaletteOpen(true)}
          title='Command palette (⌘K)'
          aria-label='Command palette'
        >
          <CommandIcon size={18} />
        </button>
        <button
          type='button'
          className='con-icon'
          onClick={toggleTheme}
          title={`Theme: ${theme} — click to switch`}
          aria-label={`Theme: ${theme}. Click to switch.`}
        >
          <ThemeIcon size={18} />
        </button>
        <NavLink
          to='/notifications'
          className='con-icon'
          title={unread > 0 ? `Notifications · ${unread} unread` : 'Notifications'}
          aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
          style={{position: 'relative'}}
        >
          <BellIcon size={18} />
          {unread > 0 && (
            <span
              aria-hidden='true'
              style={{
                position: 'absolute',
                top: 2,
                right: 2,
                minWidth: 13,
                height: 13,
                padding: '0 3px',
                borderRadius: 'var(--ide-r-control)',
                background: 'var(--ide-red)',
                color: 'var(--ide-accent-fg)',
                fontSize: 12,
                fontWeight: 700,
                lineHeight: '13px',
                textAlign: 'center',
                boxSizing: 'border-box',
              }}
            >
              {unread > 9 ? '9+' : unread}
            </span>
          )}
        </NavLink>
        {/* Profile/account entry point. Deeper billing/org routes stay inside Account. */}
        <div className='con-account-menu-anchor' style={{position: 'relative'}}>
          <button
            type='button'
            className='con-icon'
            title='Account & settings'
            aria-label='Account and settings'
            aria-haspopup='menu'
            aria-expanded={accountOpen}
            onClick={() => setAccountOpen((o) => !o)}
          >
            <AccountIcon size={18} />
          </button>
          {accountOpen && (
            <div role='menu' className='con-menu'>
              <NavLink to='/account' className='con-menu-item' role='menuitem'>
                <AccountIcon size={15} />
                <span>Account</span>
              </NavLink>
            </div>
          )}
        </div>
      </header>

      {apiError && (
        <div className='con-api-error' role='alert'>
          <span>Workspace data is temporarily unavailable.</span>
          <button
            type='button'
            className='con-api-error-retry'
            onClick={() => {
              // Retry only the sources that actually failed; a successful reload clears
              // its own flag, and the banner disappears once none remain set.
              if (runsFailed) refreshRuns();
              if (unreadFailed) refreshUnread();
              if (projectsFailed) refreshProjects();
            }}
          >
            Retry
          </button>
        </div>
      )}

      <div className='con-body'>
        {drawerOpen && (
          <div
            className='con-backdrop'
            onClick={() => {
              setDrawerOpen(false);
              menuButtonRef.current?.focus();
            }}
            aria-hidden='true'
          />
        )}
        <nav
          ref={drawerRef}
          className={`con-side${drawerOpen ? ' drawer-open' : ''}`}
          aria-label='Primary navigation'
        >
          <button
            type='button'
            ref={drawerCloseRef}
            className='con-drawer-close con-only-mobile'
            onClick={() => {
              setDrawerOpen(false);
              menuButtonRef.current?.focus();
            }}
          >
            Close navigation
          </button>
          <div className='con-side-brand'>
            <img src={MERGE_RAIL_MARK} alt='' aria-hidden='true' />
            <span>ExecuteSpec</span>
          </div>
          <button
            type='button'
            className='con-side-collapse con-only-desktop'
            onClick={toggleNav}
            title={navCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-label={navCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            <svg
              width='16'
              height='16'
              viewBox='0 0 24 24'
              fill='none'
              stroke='currentColor'
              strokeWidth='2'
              strokeLinecap='round'
              strokeLinejoin='round'
              aria-hidden='true'
            >
              {navCollapsed ? (
                <polyline points='9 18 15 12 9 6' />
              ) : (
                <polyline points='15 18 9 12 15 6' />
              )}
            </svg>
          </button>
          {NAV.map((n) => (
            <NavLink
              key={n.path}
              to={n.path}
              className={`con-navitem${navActive(n.path) ? ' active' : ''}`}
              title={n.label}
            >
              <n.Icon size={18} />
              <span>{n.label}</span>
            </NavLink>
          ))}
          <details className='con-side-details'>
            <summary className='con-side-section'>
              <RunsIcon size={12} />
              Recent work
              <span style={{flex: 1}} />
              <span className='con-side-count'>{runsFailed ? '—' : runs.length}</span>
            </summary>
            {runsFailed && runs.length === 0 ? (
              // RR-1655 — never claim the account has no work when the read failed.
              <button type='button' className='con-side-empty' onClick={refreshRuns}>
                Couldn&apos;t load recent work — retry
              </button>
            ) : runs.length === 0 ? (
              <div className='con-side-empty'>No work yet</div>
            ) : (
              runs.slice(0, 5).map((r) => (
                <NavLink
                  key={r.runId}
                  to={`/result/${r.runId}`}
                  className={({isActive}) => `con-runitem${isActive ? ' active' : ''}`}
                  title={runLabel(r)}
                >
                  <StatusDot kind={statusKind(r.status)} size={8} />
                  <span className='con-runitem-label'>{runLabel(r)}</span>
                  {r.verifiedScore != null && <ScoreBadge score={r.verifiedScore} compact />}
                </NavLink>
              ))
            )}
          </details>

          <details className='con-side-details'>
            <summary className='con-side-section'>
              <ProjectsIcon size={12} />
              Projects
              <span style={{flex: 1}} />
              <span className='con-side-count'>{projectsFailed ? '—' : projects.length}</span>
            </summary>
            {projectsFailed && projects.length === 0 ? (
              // RR-1655 — offering "New project" as the only affordance told a user with
              // projects that they had none. Retry the read instead.
              <button type='button' className='con-side-empty' onClick={refreshProjects}>
                Couldn&apos;t load projects — retry
              </button>
            ) : projects.length === 0 ? (
              <button type='button' className='con-runitem' onClick={() => navigate('/projects')}>
                <PlusIcon size={13} />
                <span className='con-runitem-label'>New project</span>
              </button>
            ) : (
              projects.slice(0, 8).map((p) => (
                <NavLink
                  key={p.id}
                  to={`/projects/${p.id}`}
                  className={({isActive}) => `con-runitem${isActive ? ' active' : ''}`}
                  title={projectLabel(p)}
                >
                  <ProjectsIcon size={14} />
                  <span className='con-runitem-label'>{projectLabel(p)}</span>
                </NavLink>
              ))
            )}
            <button
              type='button'
              className='con-runitem con-runitem-action'
              onClick={() => navigate('/projects')}
            >
              <PlusIcon size={13} />
              <span className='con-runitem-label'>All projects</span>
            </button>
          </details>

          {isSuperAdmin && (
            <>
              <div className='con-side-section'>
                <AdminIcon size={12} />
                Operator
              </div>
              <NavLink
                to='/admin'
                className={`con-runitem${path.startsWith('/admin') ? ' active' : ''}`}
              >
                <AdminIcon size={14} />
                <span className='con-runitem-label'>Admin</span>
              </NavLink>
              <NavLink
                to='/benchmark'
                className={`con-runitem${path.startsWith('/benchmark') ? ' active' : ''}`}
              >
                <MonitorIcon size={14} />
                <span className='con-runitem-label'>Benchmarks</span>
              </NavLink>
            </>
          )}

          {/* Below 480px the top bar drops the bell and the account menu for
              space (see console.css). Without these rows they would be
              unreachable on a phone, so the drawer carries them. */}
          <div className='con-side-section con-only-mobile'>
            <AccountIcon size={12} />
            You
          </div>
          <NavLink to='/notifications' className='con-runitem con-only-mobile'>
            <BellIcon size={14} />
            <span className='con-runitem-label'>Notifications</span>
            {unread > 0 && <span className='con-side-count'>{unread > 9 ? '9+' : unread}</span>}
          </NavLink>
          <NavLink to='/account' className='con-runitem con-only-mobile'>
            <AccountIcon size={14} />
            <span className='con-runitem-label'>Account</span>
          </NavLink>

          <span className='con-side-grow' />
          <div className='con-side-tier' title={`Plan: ${tier}`}>
            {tier}
          </div>
        </nav>

        <main
          id='main-content'
          className='con-content'
          ref={mainRef}
          tabIndex={-1}
          role='main'
          style={{outline: 'none'}}
        >
          <Outlet />
        </main>
      </div>

      <nav className='con-tabbar'>
        {NAV.map((n) => (
          <NavLink
            key={n.path}
            to={n.path}
            className={() => (navActive(n.path) ? 'active' : '')}
            title={n.label}
          >
            <span className='ico'>
              <n.Icon size={20} />
            </span>
            {n.label}
          </NavLink>
        ))}
      </nav>

      <IdeCommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        commands={commands}
      />
    </div>
  );
}
