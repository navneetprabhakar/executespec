import {useCallback, useEffect, useState} from 'react';
import {NavLink, Outlet, useLocation, useNavigate} from 'react-router-dom';
import '@/ide/ide.css';
import './console.css';
import '@/signal/signal.css';
import * as api from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';
import {IdeCommandPalette, usePaletteHotkey, type PaletteCmd, statusKind} from '@/ide/IdeKit';
import {PlanGateIndicator} from '@/ide/PlanGateIndicator';
import {
  WorkspaceIcon,
  RunsIcon,
  ProjectsIcon,
  AdminIcon,
  AccountIcon,
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
 * ConsoleShell — the control-plane chrome (RL-2026-05-052, web reframe). Replaces
 * the VS Code-style IdeWorkspace (activity bar + "Explorer" + "editor") with a
 * product console: a top bar (brand · plan-gate indicator · New-run CTA · ⌘K ·
 * theme · account) + a slim left nav (Home / Runs / Projects) + live Recent runs.
 * Flag-gated in App.tsx (localStorage `es:console`) so it has ZERO impact on the
 * default chrome until flipped. Every existing protected surface renders unchanged
 * in <Outlet/>.
 */
type NavItem = {path: string; Icon: (p: IconProps) => React.ReactElement; label: string};
const NAV: NavItem[] = [
  {path: '/home', Icon: WorkspaceIcon, label: 'Home'},
  {path: '/runs', Icon: RunsIcon, label: 'Runs'},
  {path: '/projects', Icon: ProjectsIcon, label: 'Projects'},
];

function runLabel(r: api.RunSummary): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 48);
}

export default function ConsoleShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);

  usePaletteHotkey(setPaletteOpen);

  // Recent runs stay live (poll 12s, skip when hidden) — same discipline as the
  // side-menu fix (#10). Refresh on navigation too so a just-submitted run shows.
  const refreshRuns = useCallback(() => {
    api
      .getRuns({size: 6})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => {});
  }, []);
  useEffect(() => {
    refreshRuns();
  }, [refreshRuns]);
  useEffect(() => {
    const id = window.setInterval(() => {
      if (!document.hidden) refreshRuns();
    }, 12000);
    return () => window.clearInterval(id);
  }, [refreshRuns]);
  useEffect(() => {
    setDrawerOpen(false);
    refreshRuns();
  }, [location.pathname, refreshRuns]);

  const path = location.pathname;
  const navActive = (p: string) =>
    p === '/home' ? path === '/' || path.startsWith('/home') : path.startsWith(p);

  const commands: PaletteCmd[] = [
    {id: 'home', label: 'Go: Home', run: () => navigate('/home')},
    {id: 'compose', label: 'New run', hint: '＋', run: () => navigate('/compose')},
    {id: 'runs', label: 'Go: Runs', run: () => navigate('/runs')},
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
    <div className='ide-root con-root'>
      <header className='con-topbar'>
        <button
          className='con-icon con-only-mobile'
          onClick={() => setDrawerOpen(true)}
          title='Menu'
          aria-label='Open menu'
        >
          <MenuIcon size={20} />
        </button>
        <NavLink to='/home' className='con-brand'>
          ExecuteSpec
        </NavLink>
        <span className='con-topbar-spacer' />
        <PlanGateIndicator dir='down' />
        <button className='con-newrun' onClick={() => navigate('/compose')} title='Start a new run'>
          <PlusIcon size={15} />
          New run
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
        <NavLink to='/account' className='con-icon' title='Account' aria-label='Account'>
          <AccountIcon size={18} />
        </NavLink>
      </header>

      <div className='con-body'>
        {drawerOpen && <div className='con-backdrop' onClick={() => setDrawerOpen(false)} />}
        <nav className={`con-side${drawerOpen ? ' drawer-open' : ''}`}>
          {NAV.map((n) => (
            <NavLink
              key={n.path}
              to={n.path}
              className={`con-navitem${navActive(n.path) ? ' active' : ''}`}
            >
              <n.Icon size={18} />
              <span>{n.label}</span>
            </NavLink>
          ))}
          {isSuperAdmin && (
            <NavLink
              to='/admin'
              className={`con-navitem${path.startsWith('/admin') ? ' active' : ''}`}
            >
              <AdminIcon size={18} />
              <span>Admin</span>
            </NavLink>
          )}

          <div className='con-side-section'>Recent runs</div>
          {runs.length === 0 ? (
            <div className='con-side-empty'>No runs yet</div>
          ) : (
            runs.map((r) => (
              <NavLink
                key={r.runId}
                to={`/result/${r.runId}`}
                className={({isActive}) => `con-runitem${isActive ? ' active' : ''}`}
                title={runLabel(r)}
              >
                <StatusDot kind={statusKind(r.status)} size={8} />
                <span className='con-runitem-label'>{runLabel(r)}</span>
              </NavLink>
            ))
          )}

          <span className='con-side-grow' />
          <div className='con-side-tier' title={`Plan: ${tier}`}>
            {tier}
          </div>
        </nav>

        <main className='con-content'>
          <Outlet />
        </main>
      </div>

      <nav className='con-tabbar'>
        {NAV.map((n) => (
          <NavLink
            key={n.path}
            to={n.path}
            className={({isActive}) => (isActive ? 'active' : '')}
            title={n.label}
          >
            <span className='ico'>
              <n.Icon size={20} />
            </span>
            {n.label}
          </NavLink>
        ))}
        <NavLink
          to='/account'
          className={({isActive}) => (isActive ? 'active' : '')}
          title='Account'
        >
          <span className='ico'>
            <AccountIcon size={20} />
          </span>
          Account
        </NavLink>
      </nav>

      <IdeCommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        commands={commands}
      />
    </div>
  );
}
