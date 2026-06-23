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
import {AnnouncementBanner} from './AnnouncementBanner';
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
  {path: '/compose', Icon: PlusIcon, label: 'Start'},
  {path: '/agent', Icon: CommandIcon, label: 'Agent'},
  {path: '/runs', Icon: RunsIcon, label: 'Work'},
  {path: '/projects', Icon: ProjectsIcon, label: 'Projects'},
  {path: '/org-members', Icon: AccountIcon, label: 'Team'},
  {path: '/setup', Icon: WorkspaceIcon, label: 'Setup'},
];

function runLabel(r: api.RunSummary): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 48);
}

function projectLabel(p: api.ProjectDto): string {
  return (p.name || p.id).slice(0, 48);
}

export default function ConsoleShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [drawerOpen, setDrawerOpen] = useState(false);
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
  const [unread, setUnread] = useState(0);

  usePaletteHotkey(setPaletteOpen);

  // Recent runs stay live (poll 12s, skip when hidden) — same discipline as the
  // side-menu fix (#10). Refresh on navigation too so a just-submitted run shows.
  const refreshRuns = useCallback(() => {
    api
      .getRuns({size: 6})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => {});
  }, []);
  // Unread notification count for the top-bar bell (founder report #2) — same cadence.
  const refreshUnread = useCallback(() => {
    api
      .getUnreadCount()
      .then((r) => setUnread(r.count ?? 0))
      .catch(() => {});
  }, []);
  const refreshProjects = useCallback(() => {
    api
      .getProjects()
      .then((items) => setProjects(items.filter((p) => !p.archivedAt)))
      .catch(() => {});
  }, []);
  useEffect(() => {
    refreshRuns();
    refreshUnread();
    refreshProjects();
  }, [refreshRuns, refreshUnread, refreshProjects]);
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
  }, [location.pathname, refreshRuns, refreshUnread, refreshProjects]);

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

  const path = location.pathname;
  const shellClasses = [
    'ide-root',
    'con-root',
    drawerOpen ? 'drawer-active' : '',
    navCollapsed ? 'nav-collapsed' : '',
    path.startsWith('/compose') ? 'compose-active' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const navActive = (p: string) => {
    if (p === '/compose')
      return path === '/' || path.startsWith('/home') || path.startsWith('/compose');
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
    {id: 'start', label: 'Go: Start', hint: 'Start', run: () => navigate('/compose')},
    {id: 'compose', label: 'Start work', hint: '＋', run: () => navigate('/compose')},
    {id: 'agent', label: 'Open agent', hint: 'chat', run: () => navigate('/agent')},
    {id: 'new-project', label: 'New project', hint: '＋', run: () => navigate('/projects')},
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
    <div className={shellClasses}>
      <AnnouncementBanner />
      <header className='con-topbar'>
        <button
          className='con-icon con-only-mobile'
          onClick={() => setDrawerOpen(true)}
          title='Menu'
          aria-label='Open menu'
        >
          <MenuIcon size={20} />
        </button>
        <NavLink to='/compose' className='con-brand'>
          <img className='con-brand-mark' src={MERGE_RAIL_MARK} alt='' aria-hidden='true' />
          <span>ExecuteSpec</span>
        </NavLink>
        <span className='con-topbar-spacer' />
        <PlanGateIndicator dir='down' />
        <button className='con-newrun' onClick={() => navigate('/compose')} title='Start work'>
          <PlusIcon size={15} />
          <span className='con-newrun-label'>Start</span>
        </button>
        <button
          className='con-icon'
          onClick={() => setPaletteOpen(true)}
          title='Command palette (⌘K)'
          aria-label='Command palette'
        >
          <CommandIcon size={18} />
        </button>
        <button
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
                borderRadius: 7,
                background: 'var(--ide-red)',
                color: 'var(--ide-accent-fg)',
                fontSize: 9,
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

      <div className='con-body'>
        {drawerOpen && <div className='con-backdrop' onClick={() => setDrawerOpen(false)} />}
        <nav className={`con-side${drawerOpen ? ' drawer-open' : ''}`}>
          <div className='con-side-brand'>
            <img src={MERGE_RAIL_MARK} alt='' aria-hidden='true' />
            <span>ExecuteSpec</span>
          </div>
          <button
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
              <span className='con-side-count'>{runs.length}</span>
            </summary>
            {runs.length === 0 ? (
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
                  {r.verifiedScore != null && (
                    <span style={{fontSize: 10, color: 'var(--ide-fg-muted)'}}>
                      {r.verifiedScore}
                    </span>
                  )}
                </NavLink>
              ))
            )}
          </details>

          <details className='con-side-details'>
            <summary className='con-side-section'>
              <ProjectsIcon size={12} />
              Projects
              <span style={{flex: 1}} />
              <span className='con-side-count'>{projects.length}</span>
            </summary>
            {projects.length === 0 ? (
              <button className='con-runitem' onClick={() => navigate('/projects')}>
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

          <span className='con-side-grow' />
          <div className='con-side-tier' title={`Plan: ${tier}`}>
            {tier}
          </div>
        </nav>

        <main
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
