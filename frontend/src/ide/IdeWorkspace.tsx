import {useEffect, useState} from 'react';
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
  IdeBadge,
} from '@/ide/IdeKit';

type Act = {path: string; icon: string; label: string};
const ACTIVITY: Act[] = [
  {path: '/home', icon: '⊞', label: 'Workspace'},
  {path: '/compose', icon: '＋', label: 'New spec'},
  {path: '/runs', icon: '◎', label: 'All runs'},
  {path: '/projects', icon: '▤', label: 'Projects'},
];

function runLabel(r: api.RunSummary): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 60);
}

export default function IdeWorkspace() {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const toggleUiMode = useUiStore((s) => s.toggleUiMode);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);

  usePaletteHotkey(setPaletteOpen);

  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    api
      .getRuns({size: 8})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => {});
    api
      .getProjects()
      .then(setProjects)
      .catch(() => {});
  }, []);

  const path = location.pathname;
  const actActive = (p: string) =>
    p === '/home' ? path === '/' || path.startsWith('/home') : path.startsWith(p);

  const commands: PaletteCmd[] = [
    {id: 'home', label: 'Go: Workspace', hint: 'Home', run: () => navigate('/home')},
    {id: 'compose', label: 'New spec', hint: '＋', run: () => navigate('/compose')},
    {id: 'runs', label: 'Go: All runs', run: () => navigate('/runs')},
    {id: 'projects', label: 'Go: Projects', run: () => navigate('/projects')},
    {id: 'theme', label: 'Toggle theme', run: toggleTheme},
    {id: 'signal', label: 'Switch to classic Signal UI', run: toggleUiMode},
    ...runs.map((r) => ({
      id: r.runId,
      label: `Open run: ${runLabel(r)}`,
      run: () => navigate(`/result/${r.runId}`),
    })),
  ];

  const themeIcon = theme === 'light' ? '☀' : theme === 'system' ? '⊟' : '☾';

  return (
    <div className='ide-root'>
      <div className='ide-mobilebar'>
        <button onClick={() => setDrawerOpen(true)} aria-label='Open menu'>
          ☰
        </button>
        <strong style={{fontSize: 13}}>ExecuteSpec</strong>
        <span style={{flex: 1}} />
        <button onClick={() => setPaletteOpen(true)} aria-label='Command palette'>
          ⌘K
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
              {a.icon}
            </NavLink>
          ))}
          <span className='ide-act-spacer' />
          <button
            className='ide-act'
            onClick={() => setCollapsed((c) => !c)}
            title='Toggle sidebar'
          >
            ▥
          </button>
          <NavLink
            to='/account'
            className={`ide-act${path.startsWith('/account') ? ' active' : ''}`}
            title='Account'
          >
            ◍
          </NavLink>
        </div>

        {drawerOpen && <div className='ide-backdrop' onClick={() => setDrawerOpen(false)} />}

        {/* explorer */}
        <aside
          className={`ide-side${collapsed ? ' collapsed' : ''}${drawerOpen ? ' drawer-open' : ''}`}
        >
          <div className='ide-side-head'>
            Explorer
            <button
              className='ide-status btn'
              style={{color: 'var(--ide-fg-muted)', padding: 0}}
              onClick={() => setPaletteOpen(true)}
              title='Command palette (⌘K)'
            >
              ⌘K
            </button>
          </div>
          <div className='ide-side-body'>
            <div className='ide-tree-group-title'>Open</div>
            <NavLink className='ide-tree-item' to='/compose'>
              <span className='ide-tree-ico'>＋</span>New spec
            </NavLink>
            <NavLink className='ide-tree-item' to='/runs'>
              <span className='ide-tree-ico'>◎</span>All runs
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
                  <span className='ide-tree-ico'>{statusKind(r.status) === 'ok' ? '✓' : '•'}</span>
                  {runLabel(r)}
                </NavLink>
              ))
            )}

            {projects.length > 0 && (
              <>
                <div className='ide-tree-group-title'>Projects</div>
                {projects.map((p) => (
                  <NavLink
                    key={p.id}
                    className={({isActive}) => `ide-tree-item${isActive ? ' active' : ''}`}
                    to={`/projects/${p.id}`}
                    title={p.name}
                  >
                    <span className='ide-tree-ico'>▤</span>
                    {p.name}
                  </NavLink>
                ))}
              </>
            )}

            {isSuperAdmin && (
              <>
                <div className='ide-tree-group-title'>System</div>
                <NavLink className='ide-tree-item' to='/admin'>
                  <span className='ide-tree-ico'>✦</span>Admin
                </NavLink>
              </>
            )}
          </div>
        </aside>

        {/* editor area — the active surface renders here */}
        <div className='ide-editor'>
          <div className='ide-editor-body'>
            <Outlet />
          </div>
        </div>
      </div>

      {/* status bar */}
      <div className='ide-statusbar'>
        <span className='ide-status'>⎇ main</span>
        <span className='ide-status'>
          <IdeBadge kind='ok'>{tier}</IdeBadge>
        </span>
        <span className='ide-status-spacer' />
        <button className='ide-status btn' onClick={() => setPaletteOpen(true)}>
          ⌘K
        </button>
        <button className='ide-status btn' onClick={toggleTheme}>
          {themeIcon} {theme}
        </button>
        <button
          className='ide-status btn'
          onClick={toggleUiMode}
          title='Switch to classic Signal UI'
        >
          ⇄ Signal UI
        </button>
        <span className='ide-status'>IDE · Beta</span>
      </div>

      {/* mobile bottom tabs */}
      <nav className='ide-tabbar'>
        {ACTIVITY.map((a) => (
          <NavLink key={a.path} to={a.path} className={({isActive}) => (isActive ? 'active' : '')}>
            <span className='ico'>{a.icon}</span>
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
