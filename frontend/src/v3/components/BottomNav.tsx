/**
 * 2026-05-14 (mobile-first redesign, Track A.3) — phone bottom nav.
 *
 * Before: every primary destination was reachable only via the hamburger
 * drawer in the topbar. That's two taps to reach Runs, two taps to reach
 * Insights, two taps to reach Settings. Best-in-class mobile apps (Linear
 * iOS, GitHub Mobile, Stripe Dashboard, Cursor) put 4-5 primary
 * destinations in a persistent bottom nav so the most-common actions are
 * always one tap away. The hamburger keeps secondary/admin items.
 *
 * Slots (in user-flow order, left-to-right):
 *   1. Home    → /home (HomeRedirectPage dispatches to current project)
 *   2. Runs    → /runs (flat list across projects)
 *   3. Insights → /insights (dashboard)
 *   4. You     → /settings (account + billing + integrations live in the drawer
 *                or via Settings sub-tabs)
 *
 * Layout: 60px tall (the platform standard; matches iOS UITabBar),
 * full-width, fixed to viewport bottom, safe-area-inset-bottom for the
 * iPhone home indicator, glass-blur surface to match the topbar.
 *
 * Hides automatically:
 *   - On screens ≥ md (768px) — desktop has the topbar's inline links
 *     plus the hamburger.
 *   - When the user isn't logged in (no auth = nothing to navigate to).
 *   - On the public auth surfaces (/login, /register, /forgot-password,
 *     /reset-password, /verify-email, /accept-invite, /auth/callback)
 *     even when authenticated state hasn't settled yet, so the nav doesn't
 *     flash on the cold load.
 */
import {NavLink, useLocation} from 'react-router-dom';
import {Home, Sparkles, Activity, User, type LucideIcon} from 'lucide-react';
import {useIsPhone} from '@/hooks/useIsMobile';
import {useAuthStore} from '@/stores/useAuthStore';

const HIDDEN_PREFIXES = [
  '/login',
  '/register',
  '/forgot-password',
  '/reset-password',
  '/verify-email',
  '/accept-invite',
  '/auth/',
  '/reports/shared/',
  '/projects/snapshot/',
  '/terms',
  '/privacy',
  '/dpa',
  '/exec-demo',
];

const SLOTS: {to: string; label: string; Icon: LucideIcon}[] = [
  {to: '/home', label: 'Home', Icon: Home},
  {to: '/runs', label: 'Runs', Icon: Sparkles},
  {to: '/insights', label: 'Insights', Icon: Activity},
  {to: '/settings', label: 'You', Icon: User},
];

export default function BottomNav() {
  const isPhone = useIsPhone();
  const user = useAuthStore((s) => s.user);
  const location = useLocation();

  if (!isPhone) return null;
  if (!user) return null;
  if (HIDDEN_PREFIXES.some((p) => location.pathname === p || location.pathname.startsWith(p))) {
    return null;
  }

  return (
    <nav
      aria-label='Primary'
      style={{
        position: 'fixed',
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 40,
        display: 'flex',
        alignItems: 'stretch',
        background: 'color-mix(in srgb, var(--v3-bg) 88%, transparent)',
        backdropFilter: 'blur(12px) saturate(140%)',
        WebkitBackdropFilter: 'blur(12px) saturate(140%)',
        borderTop: '1px solid var(--v3-border)',
        paddingBottom: 'env(safe-area-inset-bottom, 0px)',
      }}
    >
      {SLOTS.map(({to, label, Icon}) => (
        <NavLink
          key={to}
          to={to}
          end={to === '/home'}
          style={({isActive}) => ({
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 2,
            padding: '8px 4px',
            minHeight: 56,
            textDecoration: 'none',
            color: isActive ? 'var(--v3-brand)' : 'var(--v3-text-muted)',
            fontSize: 10.5,
            fontWeight: 600,
            letterSpacing: '0.02em',
            fontFamily: 'inherit',
          })}
        >
          {({isActive}) => (
            <>
              <Icon size={22} aria-hidden='true' />
              <span style={{opacity: isActive ? 1 : 0.85}}>{label}</span>
            </>
          )}
        </NavLink>
      ))}
    </nav>
  );
}
