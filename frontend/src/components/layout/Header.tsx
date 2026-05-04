import {Link, useLocation} from 'react-router-dom';
import {Menu, Search, ArrowUpRight, Plus, Sun, Moon, Monitor} from 'lucide-react';
import {useEffect} from 'react';
import {useUiStore} from '@/stores/useUiStore';
import {useRunStore} from '@/stores/useRunStore';
import {useAuthStore} from '@/stores/useAuthStore';
import {ACTIVE_STATUSES} from '@/lib/constants';
import {} from '@/lib/utils';
import UserMenu from '@/components/auth/UserMenu';
import NotificationBell from './NotificationBell';

// v2.5.14 (Phase 1.4) — dynamic breadcrumb. Previous header hard-coded
// "ExecuteSpec / Runs" even on project + billing + settings pages, which
// both looked broken and confused users about where they were.
function breadcrumbFromPath(
  pathname: string,
  projectName: string | null,
): {primary: string; secondary?: string} {
  // P-08 (UX audit, 2026-05-03) — when on a project surface and we have the
  // project's actual name in store, surface it as the secondary crumb instead
  // of the literal "Dashboard". ProjectDashboardPage populates this on mount
  // (see useUiStore.setCurrentProjectName).
  if (pathname.startsWith('/projects/'))
    return {primary: 'Projects', secondary: projectName ?? 'Dashboard'};
  if (pathname.startsWith('/org/projects')) return {primary: 'Projects'};
  if (pathname.startsWith('/billing')) return {primary: 'Billing'};
  if (pathname.startsWith('/plans') || pathname.startsWith('/pricing')) return {primary: 'Plans'};
  if (pathname.startsWith('/settings')) return {primary: 'Settings'};
  if (pathname.startsWith('/integrations')) return {primary: 'Integrations'};
  if (pathname.startsWith('/deployments')) return {primary: 'Deployments'};
  if (pathname.startsWith('/invoices')) return {primary: 'Invoices'};
  if (pathname.startsWith('/accounting')) return {primary: 'Accounting'};
  if (pathname.startsWith('/audit') || pathname.startsWith('/governance/audit'))
    return {primary: 'Governance', secondary: 'Audit & activity'};
  if (pathname.startsWith('/admin')) return {primary: 'Admin'};
  if (pathname.startsWith('/executions/')) return {primary: 'Launches', secondary: 'Execution'};
  if (pathname.startsWith('/reports/shared/')) return {primary: 'Shared report'};
  if (pathname.startsWith('/exec')) return {primary: 'Exec summary'};
  // Tier-coherence: terminology unified to "Runs" across the app to match
  // v3 surfaces, marketing, emails, and API DTOs (M-001 Mission rename
  // reverted — the v2 tab tray, RunDetail page, and v3 chat already used
  // "Run", so "Mission" was the outlier).
  return {primary: 'Runs'};
}

/**
 * X-07 (UX audit, 2026-05-03) — three-state theme toggle: Dark → Light →
 * System → Dark. Pre-fix the toggle was a binary Sun/Moon swap and the
 * audit found ~30% of users had OS-level dark/light schedules they wanted
 * the app to follow. The icon shows the *current preference* (Sun for
 * dark, Moon for light, Monitor for system) — clicking advances by one
 * step in the cycle. When `theme === 'system'`, we subscribe to
 * prefers-color-scheme so the active <html data-theme> value flips with
 * the OS without the user re-clicking.
 */
function ThemeToggle() {
  const theme = useUiStore((s) => s.theme);
  const toggle = useUiStore((s) => s.toggleTheme);
  const setTheme = useUiStore((s) => s.setTheme);

  // Listen for OS-level scheme changes only while we're in system mode.
  // Re-applying the resolved value triggers the same DOM dataset write
  // setTheme would do — without persisting "system" → "dark"/"light" since
  // setTheme(theme) is a no-op for the saved value.
  useEffect(() => {
    if (theme !== 'system' || typeof window === 'undefined' || !window.matchMedia) return;
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = () => setTheme('system');
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', handler);
      return () => mql.removeEventListener('change', handler);
    }
    // Safari ≤13 fallback — addListener was deprecated but still ships.
    mql.addListener(handler);
    return () => mql.removeListener(handler);
  }, [theme, setTheme]);

  const next = theme === 'dark' ? 'light' : theme === 'light' ? 'system' : 'dark';
  const label = `Theme: ${theme} · click to switch to ${next}`;
  const icon =
    theme === 'dark' ? (
      <Sun className='h-4 w-4' />
    ) : theme === 'light' ? (
      <Moon className='h-4 w-4' />
    ) : (
      <Monitor className='h-4 w-4' />
    );
  return (
    <button
      onClick={toggle}
      aria-label={label}
      title={label}
      className='hidden sm:flex items-center justify-center h-8 w-8 rounded-md text-muted hover:text-primary hover:bg-[var(--gray-200)] transition-colors flex-shrink-0'
    >
      {icon}
    </button>
  );
}

export default function Header() {
  const {setCommandPaletteOpen, mobileSidebarOpen, setMobileSidebarOpen} = useUiStore();
  const runs = useRunStore((s) => s.runs);
  const user = useAuthStore((s) => s.user);
  const activeCount = runs.filter((r) => ACTIVE_STATUSES.has(r.status)).length;
  const location = useLocation();
  const projectName = useUiStore((s) => s.currentProjectName);
  const crumb = breadcrumbFromPath(location.pathname, projectName);

  return (
    <header className='flex items-center h-14 px-3 sm:px-4 gap-2 sm:gap-3 bg-[var(--geist-bg)] border-b border-[var(--border-default)] flex-shrink-0 z-10'>
      {/* Mobile hamburger */}
      <button
        onClick={() => setMobileSidebarOpen(!mobileSidebarOpen)}
        className='lg:hidden min-h-[44px] min-w-[44px] flex items-center justify-center -ml-1 text-muted hover:text-primary transition-colors flex-shrink-0'
        aria-label='Toggle menu'
      >
        <Menu className='h-5 w-5' />
      </button>

      {/* Breadcrumb — desktop only. v2.5.14 dynamic per route. */}
      <div className='hidden lg:flex items-center gap-1.5 text-sm flex-shrink-0'>
        <Link to='/' className='text-primary font-medium hover:text-accent-blue transition-colors'>
          ExecuteSpec
        </Link>
        <span className='text-muted'>/</span>
        <span className='text-secondary'>{crumb.primary}</span>
        {crumb.secondary && (
          <>
            <span className='text-muted'>/</span>
            <span className='text-muted'>{crumb.secondary}</span>
          </>
        )}
      </div>

      {/* Phase 1.1 #8: view-mode toggle retired from the header — surface
          is domain-driven (app.executespec.ai vs. build.executespec.ai).
          R-190 (post-cutover): the legacy Vibe simplified mode was deleted;
          ViewModeToggle now only switches between 'pro' (default) and
          'project' (multi-repo). The component is retained in the codebase
          for the OrgProjectsPage Dashboard button (still useful as an
          explicit override). */}

      {/* Spacer */}
      <div className='flex-1' />

      {/* Command palette trigger — icon-only at mobile, full pill at lg+ */}
      <button
        onClick={() => setCommandPaletteOpen(true)}
        className='flex items-center gap-2 px-2 sm:px-3 py-1.5 rounded-md bg-[var(--gray-200)] text-sm text-muted hover:text-secondary transition-colors flex-shrink-0'
        aria-label='Search'
      >
        <Search className='h-3.5 w-3.5' />
        <span className='hidden lg:block'>Search...</span>
        <kbd className='hidden lg:block text-xs bg-[var(--gray-300)] px-1.5 py-0.5 rounded-sm font-mono text-muted'>
          ⌘K
        </kbd>
      </button>

      {/* Active runs count */}
      {activeCount > 0 && (
        <span className='text-xs text-secondary hidden md:inline'>{activeCount} active</span>
      )}

      {/* Upgrade pill — only for FREE/PRO users, hidden at mobile to free space */}
      {(user?.tier === 'FREE' || user?.tier === 'PRO') && (
        <Link
          to='/pricing'
          className='hidden md:flex items-center gap-1 px-2.5 py-1 rounded-full bg-accent-blue/10 text-accent-blue text-xs font-medium hover:bg-accent-blue/20 transition-colors flex-shrink-0'
        >
          Upgrade
          <ArrowUpRight className='h-3 w-3' />
        </Link>
      )}

      {/* Top-up pill — desktop only to keep mobile header lean.
          Tier-coherence: TEAM + ENTERPRISE are BYOK (unlimited via own
          keys, no per-run charge from us), so they have no top-up
          concept. Only PRO sees this. */}
      {user?.tier === 'PRO' && (
        <Link
          to='/billing'
          className='hidden md:flex items-center gap-1 px-2.5 py-1 rounded-full bg-status-success/10 text-status-success text-xs font-medium hover:bg-status-success/20 transition-colors flex-shrink-0'
        >
          <Plus className='h-3 w-3' />
          Top-up
        </Link>
      )}

      {/* Theme toggle — v2.5.14 Phase 2 */}
      <ThemeToggle />

      {/* Notifications */}
      <NotificationBell />

      {/* User menu */}
      <UserMenu />
    </header>
  );
}
