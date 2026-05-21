import {useEffect, useRef, useState} from 'react';
import {NavLink, Outlet, useLocation} from 'react-router-dom';
import '@/signal/signal.css';
import {useSignalTheme} from '@/signal/hooks/useSignalTheme';
import {Moon, Sun} from '@/signal/icons/icons';
import {useAuthStore, selectIsSuperAdmin, selectNormalizedTier} from '@/stores/useAuthStore';

type SidebarItem = {path: string; label: string; icon?: string; badge?: 'BETA'};
type SidebarGroup = {title: string; items: SidebarItem[]};

// Build the sidebar groups based on the current user's tier + role. This is a
// pure function so we can re-evaluate on auth changes without React state.
function buildSidebar(opts: {
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';
  isSuperAdmin: boolean;
  canDeploy: boolean;
}): SidebarGroup[] {
  // 2026-05-19 honesty pass — collapse the sidebar to the seven surfaces a
  // customer actually uses in their first 30 days. Library / Migrations /
  // Templates / Deployments / Insights / API keys / Team / Audit log /
  // Notifications / Search remain reachable via /<path> URLs (and contextually
  // — e.g. Templates is the picker on the Compose page) but no longer eat
  // sidebar real estate or carry BETA chips that signal "unfinished."
  const groups: SidebarGroup[] = [
    {
      title: 'Daily',
      items: [
        {path: '/home', label: 'Today', icon: '⊕'},
        {path: '/runs', label: 'All runs', icon: '◎'},
        {path: '/compose', label: 'New spec', icon: '＋'},
      ],
    },
    {
      title: 'Workspace',
      items: [{path: '/projects', label: 'Projects', icon: '▤'}],
    },
    {
      title: 'Account',
      items: [
        {path: '/billing', label: 'Billing', icon: '$'},
        {path: '/integrations', label: 'Integrations', icon: '◇'},
        {path: '/account', label: 'Account', icon: '◍'},
      ],
    },
  ];
  if (opts.isSuperAdmin) {
    groups.push({title: 'System', items: [{path: '/admin', label: 'Admin', icon: '✦'}]});
  }
  // Suppress unused-arg warnings without removing the typed knobs — they
  // come back when we resurface tier-gated items contextually.
  void opts.tier;
  void opts.canDeploy;
  return groups;
}

// Bottom tab bar (mobile only)
const MOBILE_TABS: Array<{path: string; label: string}> = [
  {path: '/home', label: 'Today'},
  {path: '/runs', label: 'Runs'},
  {path: '/compose', label: 'New'},
  {path: '/projects', label: 'Projects'},
  {path: '/account', label: 'More'},
];

export default function SignalShell() {
  const rootRef = useRef<HTMLDivElement>(null);
  const {theme, toggle} = useSignalTheme(rootRef);
  const location = useLocation();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const tier = useAuthStore(selectNormalizedTier);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const canDeploy = useAuthStore((s) => s.permissions?.canDeploy ?? tier !== 'FREE');
  const sidebarGroups = buildSidebar({tier, isSuperAdmin, canDeploy});

  // Close the drawer whenever route changes (tap-on-nav-item dismisses)
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  // Lock body scroll while the drawer is open
  useEffect(() => {
    document.body.style.overflow = drawerOpen ? 'hidden' : '';
    return () => {
      document.body.style.overflow = '';
    };
  }, [drawerOpen]);

  return (
    <div className='signal-app signal-shell' ref={rootRef} data-signal-theme={theme}>
      {/* Sidebar (persistent on desktop, drawer on mobile) */}
      <aside
        className={`signal-sidebar${drawerOpen ? ' is-open' : ''}`}
        aria-label='Primary navigation'
      >
        <div className='sidebar-head'>
          <div className='sidebar-brand'>
            <span className='logo-chip'>E</span>
            <span className='brand-name'>ExecuteSpec</span>
          </div>
          <button
            className='sidebar-close'
            onClick={() => setDrawerOpen(false)}
            aria-label='Close menu'
          >
            ✕
          </button>
        </div>

        <nav className='sidebar-nav'>
          {sidebarGroups.map((group) => (
            <div className='nav-group' key={group.title}>
              <div className='nav-group-title'>{group.title}</div>
              {group.items.map((item) => (
                <NavLink
                  key={item.path}
                  to={item.path}
                  end={item.path === '/home'}
                  className={({isActive}) => `nav-link${isActive ? ' active' : ''}`}
                >
                  <span className='nav-icon' aria-hidden='true'>
                    {item.icon}
                  </span>
                  <span className='nav-label'>{item.label}</span>
                  {item.badge && <span className='nav-badge'>{item.badge}</span>}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className='sidebar-foot'>
          <button className='sidebar-theme' onClick={toggle} aria-label='Toggle theme'>
            <span className='theme-moon'>
              <Moon size={14} />
            </span>
            <span className='theme-sun'>
              <Sun size={14} />
            </span>
            <span className='theme-lbl-dark'>Dark mode</span>
            <span className='theme-lbl-light'>Light mode</span>
          </button>
        </div>
      </aside>

      {/* Mobile drawer backdrop */}
      {drawerOpen && <div className='sidebar-backdrop' onClick={() => setDrawerOpen(false)} />}

      {/* Main column (TopBar lives inside the page; we just provide the canvas) */}
      <main className='signal-main'>
        {/* Mobile-only header bar with hamburger */}
        <div className='mobile-bar'>
          <button
            className='mobile-menu-btn'
            onClick={() => setDrawerOpen(true)}
            aria-label='Open menu'
          >
            <span />
            <span />
            <span />
          </button>
          <div className='mobile-brand'>
            <span className='logo-chip-sm'>E</span>
            ExecuteSpec
          </div>
          <button className='mobile-theme' onClick={toggle} aria-label='Toggle theme'>
            <span className='theme-moon'>
              <Moon size={14} />
            </span>
            <span className='theme-sun'>
              <Sun size={14} />
            </span>
          </button>
        </div>

        <div className='signal-content'>
          <Outlet />
        </div>

        {/* Mobile bottom tab bar (hidden on desktop where sidebar takes over) */}
        <nav className='signal-tabbar mobile-tabs' aria-label='Quick navigation'>
          {MOBILE_TABS.map((t) => (
            <NavLink
              key={t.path}
              to={t.path}
              end={t.path === '/home'}
              className={({isActive}) =>
                `tab${isActive ? ' active' : ''}${t.label === 'New' ? ' fab' : ''}`
              }
            >
              <span className='tab-label'>{t.label}</span>
            </NavLink>
          ))}
        </nav>
      </main>
    </div>
  );
}
