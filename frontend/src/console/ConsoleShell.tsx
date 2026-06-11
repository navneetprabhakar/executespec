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
  useEffect(() => {
    refreshRuns();
    refreshUnread();
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
  const navActive = (p: string) =>
    p === '/home' ? path === '/' || path.startsWith('/home') : path.startsWith(p);

  // C1.4 — every route is reachable from the palette.
  const commands: PaletteCmd[] = [
    {id: 'home', label: 'Go: Home', run: () => navigate('/home')},
    {id: 'compose', label: 'New run', hint: '＋', run: () => navigate('/compose')},
    {id: 'runs', label: 'Go: Runs', run: () => navigate('/runs')},
    {id: 'projects', label: 'Go: Projects', run: () => navigate('/projects')},
    {id: 'search', label: 'Go: Search', run: () => navigate('/search')},
    {id: 'notifications', label: 'Go: Notifications', run: () => navigate('/notifications')},
    {id: 'account', label: 'Go: Account', run: () => navigate('/account')},
    {id: 'billing', label: 'Go: Billing', run: () => navigate('/billing')},
    {id: 'plans', label: 'Go: Plans', run: () => navigate('/plans')},
    {id: 'api-keys', label: 'Go: API keys (BYOK)', run: () => navigate('/api-keys')},
    {id: 'org-members', label: 'Go: Org members', run: () => navigate('/org-members')},
    {id: 'org-settings', label: 'Go: Org settings', run: () => navigate('/org/settings')},
    {id: 'integrations', label: 'Go: Integrations', run: () => navigate('/integrations')},
    {id: 'deployments', label: 'Go: Deployments', run: () => navigate('/deployments')},
    {id: 'insights', label: 'Go: Insights', run: () => navigate('/insights')},
    {id: 'library', label: 'Go: Library', run: () => navigate('/library')},
    {id: 'migrations', label: 'Go: Migrations', run: () => navigate('/migrations')},
    {id: 'templates', label: 'Go: Spec templates', run: () => navigate('/templates')},
    {id: 'audit', label: 'Go: Audit log', run: () => navigate('/audit')},
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
          <span className='con-newrun-label'>New run</span>
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
        {/* C1.3 — Account is a dropdown: billing / keys / org / integrations were
            unreachable from the chrome (deep-link only). */}
        <div style={{position: 'relative'}}>
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
              {[
                {to: '/account', label: 'Account'},
                {to: '/billing', label: 'Billing'},
                {to: '/plans', label: 'Plans'},
                {to: '/api-keys', label: 'API keys (BYOK)'},
                {to: '/org-members', label: 'Org members'},
                {to: '/org/settings', label: 'Org settings'},
                {to: '/integrations', label: 'Integrations'},
                {to: '/deployments', label: 'Deployments'},
              ].map((m) => (
                <NavLink key={m.to} to={m.to} className='con-menu-item' role='menuitem'>
                  {m.label}
                </NavLink>
              ))}
            </div>
          )}
        </div>
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

          {/* C1.3 — Discover routes were orphaned (no nav entry anywhere). */}
          <div className='con-side-section'>Discover</div>
          {[
            {to: '/insights', label: 'Insights'},
            {to: '/library', label: 'Library'},
            {to: '/migrations', label: 'Migrations'},
            {to: '/templates', label: 'Templates'},
          ].map((d) => (
            <NavLink
              key={d.to}
              to={d.to}
              className={`con-runitem${path.startsWith(d.to) ? ' active' : ''}`}
            >
              <span className='con-runitem-label'>{d.label}</span>
            </NavLink>
          ))}

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
                {r.verifiedScore != null && (
                  <span style={{fontSize: 10, color: 'var(--ide-fg-muted)'}}>
                    {r.verifiedScore}
                  </span>
                )}
              </NavLink>
            ))
          )}

          <span className='con-side-grow' />
          <button
            className='con-switch'
            onClick={() => {
              localStorage.removeItem('es:console');
              window.location.reload();
            }}
            title='Switch back to the classic IDE chrome'
          >
            ← Classic IDE
          </button>
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
