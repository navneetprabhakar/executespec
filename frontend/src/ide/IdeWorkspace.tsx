import {useCallback, useEffect, useState} from 'react';
import {NavLink, Outlet, useLocation, useNavigate} from 'react-router-dom';
import './ide.css';
import '@/signal/signal.css';
import * as api from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';
import {
  IdeCommandPalette,
  usePaletteHotkey,
  type PaletteCmd,
  statusKind,
  ResizeHandle,
} from '@/ide/IdeKit';
import {PlanGateIndicator} from '@/ide/PlanGateIndicator';
import {
  WorkspaceIcon,
  NewSpecIcon,
  RunsIcon,
  ProjectsIcon,
  AdminIcon,
  AccountIcon,
  BellIcon,
  SidebarIcon,
  PlusIcon,
  MenuIcon,
  CommandIcon,
  SunIcon,
  MoonIcon,
  MonitorIcon,
  BranchIcon,
  StatusDot,
  type IconProps,
} from '@/ide/IdeIcons';

type Act = {path: string; Icon: (p: IconProps) => React.ReactElement; label: string};
const ACTIVITY: Act[] = [
  {path: '/home', Icon: WorkspaceIcon, label: 'Workspace'},
  {path: '/compose', Icon: NewSpecIcon, label: 'New spec'},
  {path: '/runs', Icon: RunsIcon, label: 'All runs'},
  {path: '/projects', Icon: ProjectsIcon, label: 'Projects'},
];

function runLabel(r: api.RunSummary): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 60);
}

export default function IdeWorkspace() {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [sideWidth, setSideWidth] = useState(
    () => Number(localStorage.getItem('es:ideSideW')) || 270,
  );
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  // Notification unread count for the activity-bar bell (founder report #2 — the
  // inbox existed at /notifications but had no entry point in the chrome).
  const [unread, setUnread] = useState(0);

  usePaletteHotkey(setPaletteOpen);

  // Recent runs must stay live (RL-2026-05-052, user feedback #10): the list was
  // fetched once on mount and never refreshed, so a just-submitted run or a status
  // change never appeared in the side menu. Poll every 12s (skip when the tab is
  // hidden) + refresh on navigation; projects change rarely → mount + nav only.
  const refreshRuns = useCallback(() => {
    api
      .getRuns({size: 8})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => {});
  }, []);

  // Unread notification count — polled on the same cadence as the run list so the
  // bell badge stays live without a second timer story.
  const refreshUnread = useCallback(() => {
    api
      .getUnreadCount()
      .then((r) => setUnread(r.count ?? 0))
      .catch(() => {});
  }, []);

  useEffect(() => {
    refreshRuns();
    refreshUnread();
    api
      .getProjects()
      .then(setProjects)
      .catch(() => {});
  }, [refreshRuns, refreshUnread]);

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
  }, [location.pathname, refreshRuns, refreshUnread]);

  const path = location.pathname;
  const actActive = (p: string) =>
    p === '/home' ? path === '/' || path.startsWith('/home') : path.startsWith(p);

  const commands: PaletteCmd[] = [
    {id: 'home', label: 'Go: Workspace', hint: 'Home', run: () => navigate('/home')},
    {id: 'compose', label: 'New spec', hint: '＋', run: () => navigate('/compose')},
    {id: 'new-project', label: 'New project', hint: '＋', run: () => navigate('/projects')},
    {id: 'runs', label: 'Go: All runs', run: () => navigate('/runs')},
    {id: 'projects', label: 'Go: Projects', run: () => navigate('/projects')},
    {id: 'theme', label: 'Toggle theme', run: toggleTheme},
    ...runs.map((r) => ({
      id: r.runId,
      label: `Open run: ${runLabel(r)}`,
      run: () => navigate(`/result/${r.runId}`),
    })),
  ];

  const ThemeIcon = theme === 'light' ? SunIcon : theme === 'system' ? MonitorIcon : MoonIcon;

  return (
    <div className='ide-root'>
      <div className='ide-mobilebar'>
        <button onClick={() => setDrawerOpen(true)} title='Open menu' aria-label='Open menu'>
          <MenuIcon size={20} />
        </button>
        <strong style={{fontSize: 13}}>ExecuteSpec</strong>
        <span style={{flex: 1}} />
        <PlanGateIndicator dir='down' />
        <button
          onClick={() => navigate('/notifications')}
          title={unread > 0 ? `Notifications · ${unread} unread` : 'Notifications'}
          aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
          style={{position: 'relative'}}
        >
          <BellIcon size={20} />
          {unread > 0 && (
            <span
              aria-hidden='true'
              style={{
                position: 'absolute',
                top: -2,
                right: -2,
                minWidth: 13,
                height: 13,
                padding: '0 3px',
                borderRadius: 7,
                background: 'var(--ide-red)',
                color: '#fff',
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
        </button>
        <button
          onClick={() => setPaletteOpen(true)}
          title='Command palette (⌘K)'
          aria-label='Command palette'
        >
          <CommandIcon size={20} />
        </button>
      </div>

      <div className='ide-body'>
        {/* activity bar */}
        <div className='ide-activitybar'>
          {ACTIVITY.map((a) => (
            <NavLink
              key={a.path}
              to={a.path}
              className={`ide-act${actActive(a.path) ? ' active' : ''}`}
              title={a.label}
              aria-label={a.label}
            >
              <a.Icon size={22} />
            </NavLink>
          ))}
          <span className='ide-act-spacer' />
          <NavLink
            to='/notifications'
            className={`ide-act${path.startsWith('/notifications') ? ' active' : ''}`}
            title={unread > 0 ? `Notifications · ${unread} unread` : 'Notifications'}
            aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
            style={{position: 'relative'}}
          >
            <BellIcon size={22} />
            {unread > 0 && (
              <span
                aria-hidden='true'
                style={{
                  position: 'absolute',
                  top: 9,
                  right: 9,
                  minWidth: 14,
                  height: 14,
                  padding: '0 3px',
                  borderRadius: 7,
                  background: 'var(--ide-red)',
                  color: '#fff',
                  fontSize: 9,
                  fontWeight: 700,
                  lineHeight: '14px',
                  textAlign: 'center',
                  boxSizing: 'border-box',
                }}
              >
                {unread > 9 ? '9+' : unread}
              </span>
            )}
          </NavLink>
          <button
            className='ide-act'
            onClick={() => setCollapsed((c) => !c)}
            title='Toggle sidebar'
            aria-label='Toggle sidebar'
          >
            <SidebarIcon size={22} />
          </button>
          <NavLink
            to='/account'
            className={`ide-act${path.startsWith('/account') ? ' active' : ''}`}
            title='Account'
            aria-label='Account'
          >
            <AccountIcon size={22} />
          </NavLink>
        </div>

        {drawerOpen && <div className='ide-backdrop' onClick={() => setDrawerOpen(false)} />}

        {/* explorer */}
        <aside
          className={`ide-side${collapsed ? ' collapsed' : ''}${drawerOpen ? ' drawer-open' : ''}`}
          style={{width: sideWidth}}
        >
          <div className='ide-side-head'>
            Explorer
            <button
              className='ide-status btn'
              style={{color: 'var(--ide-fg-muted)', padding: 0, display: 'inline-flex'}}
              onClick={() => setPaletteOpen(true)}
              title='Command palette (⌘K)'
              aria-label='Command palette'
            >
              <CommandIcon size={15} />
            </button>
          </div>
          <div className='ide-side-body'>
            <div className='ide-tree-group-title'>Open</div>
            <NavLink className='ide-tree-item' to='/compose'>
              <span className='ide-tree-ico'>
                <NewSpecIcon size={15} />
              </span>
              New spec
            </NavLink>
            <NavLink className='ide-tree-item' to='/runs'>
              <span className='ide-tree-ico'>
                <RunsIcon size={15} />
              </span>
              All runs
            </NavLink>

            <div className='ide-tree-group-title'>Recent runs</div>
            {runs.length === 0 ? (
              <div className='ide-tree-item' style={{color: 'var(--ide-fg-muted)'}}>
                No runs yet
              </div>
            ) : (
              runs.map((r) => (
                <NavLink
                  key={r.runId}
                  className={({isActive}) => `ide-tree-item${isActive ? ' active' : ''}`}
                  to={`/result/${r.runId}`}
                  title={runLabel(r)}
                >
                  <span className='ide-tree-ico'>
                    <StatusDot kind={statusKind(r.status)} size={8} />
                  </span>
                  {runLabel(r)}
                </NavLink>
              ))
            )}

            {/* PROJECTS group — header carries a discoverable create-project (+)
                affordance that routes to /projects (the working create flow). */}
            <div className='ide-tree-group-title'>
              Projects
              <span style={{flex: 1}} />
              <button
                className='ide-status btn'
                style={{color: 'var(--ide-fg-muted)', padding: 0, display: 'inline-flex'}}
                onClick={() => navigate('/projects')}
                title='New project'
                aria-label='New project'
              >
                <PlusIcon size={15} />
              </button>
            </div>
            {projects.length === 0 ? (
              <button className='ide-tree-item' onClick={() => navigate('/projects')}>
                <span className='ide-tree-ico'>
                  <PlusIcon size={14} />
                </span>
                New project
              </button>
            ) : (
              projects.map((p) => (
                <NavLink
                  key={p.id}
                  className={({isActive}) => `ide-tree-item${isActive ? ' active' : ''}`}
                  to={`/projects/${p.id}`}
                  title={p.name}
                >
                  <span className='ide-tree-ico'>
                    <ProjectsIcon size={15} />
                  </span>
                  {p.name}
                </NavLink>
              ))
            )}

            {isSuperAdmin && (
              <>
                <div className='ide-tree-group-title'>System</div>
                <NavLink className='ide-tree-item' to='/admin'>
                  <span className='ide-tree-ico'>
                    <AdminIcon size={15} />
                  </span>
                  Admin
                </NavLink>
                <NavLink className='ide-tree-item' to='/benchmark'>
                  <span className='ide-tree-ico'>
                    <MonitorIcon size={15} />
                  </span>
                  Benchmarks
                </NavLink>
              </>
            )}
          </div>
        </aside>
        {!collapsed && (
          <ResizeHandle
            onDelta={(dx) =>
              setSideWidth((w) => {
                const next = Math.max(180, Math.min(520, w + dx));
                localStorage.setItem('es:ideSideW', String(next));
                return next;
              })
            }
          />
        )}

        {/* editor area — the active surface renders here */}
        <div className='ide-editor'>
          <div className='ide-editor-body'>
            <Outlet />
          </div>
        </div>
      </div>

      {/* status bar — branch is intentionally omitted at the chrome level: no
          repo is in scope here, so hardcoding "main" would be a lie. The
          per-run surfaces surface the real branch where one is known. */}
      <div className='ide-statusbar'>
        <span className='ide-status' title='ExecuteSpec workspace'>
          <BranchIcon size={13} style={{verticalAlign: -2, marginRight: 4}} />
          ExecuteSpec
        </span>
        {/* Plain white-on-translucent chip — IdeBadge's green-on-blue here was
            borderline unreadable (review/11 defect #4). */}
        <span className='ide-status' title={`Plan: ${tier}`}>
          <span className='ide-status-chip'>{tier}</span>
        </span>
        <PlanGateIndicator dir='up' />
        <span className='ide-status-spacer' />
        <button
          className='ide-status btn'
          onClick={() => setPaletteOpen(true)}
          title='Command palette (⌘K)'
          aria-label='Command palette'
        >
          <CommandIcon size={13} style={{verticalAlign: -2, marginRight: 4}} />
          Commands
        </button>
        <button
          className='ide-status btn'
          onClick={toggleTheme}
          title={`Theme: ${theme} — click to switch`}
          aria-label={`Theme: ${theme}. Click to switch theme.`}
        >
          <ThemeIcon size={13} style={{verticalAlign: -2, marginRight: 4}} />
          {theme}
        </button>
        <button
          className='ide-status btn'
          onClick={() => {
            localStorage.setItem('es:console', '1');
            window.location.reload();
          }}
          title='Try the new Console layout (beta)'
        >
          Try Console ⤴
        </button>
      </div>

      {/* mobile bottom tabs */}
      <nav className='ide-tabbar'>
        {ACTIVITY.map((a) => (
          <NavLink
            key={a.path}
            to={a.path}
            title={a.label}
            aria-label={a.label}
            className={({isActive}) => (isActive ? 'active' : '')}
          >
            <span className='ico'>
              <a.Icon size={20} />
            </span>
            {a.label}
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
