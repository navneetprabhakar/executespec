import {useEffect, useState} from 'react';
import {NavLink, Outlet, useLocation} from 'react-router-dom';
import './ide.css';
import '@/signal/signal.css';
import {useUiStore} from '@/stores/useUiStore';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';

type NavItem = {path: string; label: string; icon: string};
type NavGroup = {title: string; items: NavItem[]};

// Primary destinations on the activity bar (far-left rail). Mirrors the Signal
// sidebar's "Daily + Workspace + Account" surfaces so the A/B switch is at parity.
const ACTIVITY: NavItem[] = [
  {path: '/home', label: 'Today', icon: '⊕'},
  {path: '/runs', label: 'All runs', icon: '◎'},
  {path: '/compose', label: 'New spec', icon: '＋'},
  {path: '/projects', label: 'Projects', icon: '▤'},
  {path: '/account', label: 'Account', icon: '◍'},
];

function navGroups(isSuperAdmin: boolean): NavGroup[] {
  const groups: NavGroup[] = [
    {
      title: 'Daily',
      items: [
        {path: '/home', label: 'Today', icon: '⊕'},
        {path: '/runs', label: 'All runs', icon: '◎'},
        {path: '/compose', label: 'New spec', icon: '＋'},
      ],
    },
    {title: 'Workspace', items: [{path: '/projects', label: 'Projects', icon: '▤'}]},
    {
      title: 'Account',
      items: [
        {path: '/billing', label: 'Billing', icon: '$'},
        {path: '/integrations', label: 'Integrations', icon: '◇'},
        {path: '/account', label: 'Account', icon: '◍'},
      ],
    },
  ];
  if (isSuperAdmin)
    groups.push({title: 'System', items: [{path: '/admin', label: 'Admin', icon: '✦'}]});
  return groups;
}

const MOBILE_TABS: NavItem[] = [
  {path: '/home', label: 'Today', icon: '⊕'},
  {path: '/runs', label: 'Runs', icon: '◎'},
  {path: '/compose', label: 'New', icon: '＋'},
  {path: '/projects', label: 'Projects', icon: '▤'},
  {path: '/account', label: 'More', icon: '◍'},
];

// Human label for the current route, shown as the active editor "tab".
function labelForPath(pathname: string): string {
  const map: Array<[RegExp, string]> = [
    [/^\/home/, 'Today'],
    [/^\/runs$/, 'All runs'],
    [/^\/compose/, 'New spec'],
    [/^\/plan/, 'Plan'],
    [/^\/live/, 'Live run'],
    [/^\/result|^\/runs\//, 'Run result'],
    [/^\/projects\/[^/]+\/code/, 'Code'],
    [/^\/projects\/[^/]+\/chat/, 'Chat'],
    [/^\/projects\/[^/]+/, 'Project'],
    [/^\/projects/, 'Projects'],
    [/^\/billing/, 'Billing'],
    [/^\/integrations/, 'Integrations'],
    [/^\/account|^\/settings/, 'Account'],
    [/^\/org\/settings/, 'Org settings'],
    [/^\/admin/, 'Admin'],
    [/^\/insights/, 'Insights'],
  ];
  for (const [re, label] of map) if (re.test(pathname)) return label;
  return 'ExecuteSpec';
}

export default function IdeShell() {
  const location = useLocation();
  const theme = useUiStore((s) => s.theme);
  const toggleTheme = useUiStore((s) => s.toggleTheme);
  const toggleUiMode = useUiStore((s) => s.toggleUiMode);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const tier = useAuthStore(selectNormalizedTier);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // Close the mobile drawer on navigation.
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  const groups = navGroups(isSuperAdmin);
  const activePath = location.pathname;
  const isActive = (p: string) =>
    p === '/home' ? activePath === '/' || activePath.startsWith('/home') : activePath.startsWith(p);
  const themeIcon = theme === 'light' ? '☀' : theme === 'system' ? '⊟' : '☾';

  return (
    <div className='ide-shell'>
      {/* Mobile top bar */}
      <div className='ide-mobile-bar'>
        <button
          className='ide-mobile-menu'
          onClick={() => setDrawerOpen(true)}
          aria-label='Open menu'
        >
          ☰
        </button>
        <span className='ide-mobile-brand'>ExecuteSpec</span>
      </div>

      <div className='ide-body'>
        {/* Activity bar */}
        <div className='ide-activitybar'>
          {ACTIVITY.map((a) => (
            <NavLink
              key={a.path}
              to={a.path}
              className={`ide-act-btn${isActive(a.path) ? ' active' : ''}`}
              title={a.label}
              aria-label={a.label}
            >
              {a.icon}
            </NavLink>
          ))}
          <span className='ide-act-spacer' />
          <button
            className='ide-act-btn'
            onClick={() => setSidebarCollapsed((v) => !v)}
            title='Toggle sidebar'
            aria-label='Toggle sidebar'
          >
            ▥
          </button>
        </div>

        {/* Mobile drawer backdrop */}
        {drawerOpen && <div className='ide-backdrop' onClick={() => setDrawerOpen(false)} />}

        {/* Side bar */}
        <aside
          className={`ide-sidebar${sidebarCollapsed ? ' collapsed' : ''}${drawerOpen ? ' drawer-open' : ''}`}
          aria-label='Navigation'
        >
          <div className='ide-sidebar-title'>Explorer</div>
          <nav className='ide-sidebar-nav'>
            {groups.map((g) => (
              <div className='ide-nav-section' key={g.title}>
                <div className='ide-nav-section-title'>{g.title}</div>
                {g.items.map((it) => (
                  <NavLink
                    key={it.path}
                    to={it.path}
                    end={it.path === '/home'}
                    className={({isActive: a}) => `ide-nav-link${a ? ' active' : ''}`}
                  >
                    <span className='ide-nav-icon' aria-hidden='true'>
                      {it.icon}
                    </span>
                    {it.label}
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
        </aside>

        {/* Editor column */}
        <div className='ide-editor'>
          <div className='ide-tabs'>
            <div className='ide-tab active'>
              <span className='ide-tab-dot' />
              {labelForPath(activePath)}
            </div>
          </div>
          <div className='ide-editor-body'>
            <Outlet />
          </div>
        </div>
      </div>

      {/* Status bar */}
      <div className='ide-statusbar'>
        <span className='ide-status-item'>⎇ main</span>
        <span className='ide-status-item'>{tier}</span>
        <span className='ide-status-spacer' />
        <button className='ide-status-item button' onClick={toggleTheme} title='Toggle theme'>
          {themeIcon} {theme}
        </button>
        <button
          className='ide-status-item button'
          onClick={toggleUiMode}
          title='Switch back to the classic Signal UI'
        >
          ⇄ Signal UI
        </button>
        <span className='ide-status-item'>IDE · Beta</span>
      </div>

      {/* Mobile bottom tabs */}
      <nav className='ide-tabbar' aria-label='Quick navigation'>
        {MOBILE_TABS.map((t) => (
          <NavLink
            key={t.path}
            to={t.path}
            end={t.path === '/home'}
            className={({isActive: a}) => (a ? 'active' : '')}
          >
            <span className='ide-tab-ico' aria-hidden='true'>
              {t.icon}
            </span>
            {t.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
