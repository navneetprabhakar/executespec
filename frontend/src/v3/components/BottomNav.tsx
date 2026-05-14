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
 *
 * 2026-05-14 UX overhaul — adds the active-pill indicator (animated
 * underline) + filled-icon variant when active + press-feedback scale +
 * proper iconography (Apple HIG: tab-bar glyphs should be visually
 * "tap-pressed" on selection, not just colour-shifted).
 */
import {NavLink, useLocation} from 'react-router-dom';
import {useLayoutEffect, useRef, useState} from 'react';
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
  const navRef = useRef<HTMLElement | null>(null);
  const slotRefs = useRef<Map<string, HTMLAnchorElement | null>>(new Map());
  const [pillRect, setPillRect] = useState<{left: number; width: number} | null>(null);

  // Decide which slot is active for the pill indicator. /home matches end;
  // every other slot matches prefix so sub-pages still highlight the slot.
  const activeKey = (() => {
    if (location.pathname === '/home') return '/home';
    for (const slot of SLOTS) {
      if (slot.to === '/home') continue;
      if (location.pathname === slot.to || location.pathname.startsWith(slot.to + '/')) {
        return slot.to;
      }
    }
    // If on a project page, surface Home as active (HomeRedirectPage owns
    // /home → /projects/:id redirect, so /projects/* still feels "home").
    if (location.pathname.startsWith('/projects/')) return '/home';
    return null;
  })();

  // Measure active slot's bounding rect to position the indicator pill.
  // Re-measure on route change + resize.
  useLayoutEffect(() => {
    if (!activeKey) {
      setPillRect(null);
      return;
    }
    const nav = navRef.current;
    const slot = slotRefs.current.get(activeKey);
    if (!nav || !slot) return;
    const measure = () => {
      const navRect = nav.getBoundingClientRect();
      const slotRect = slot.getBoundingClientRect();
      // Pill is 32px wide, centered on the icon column.
      const iconCenter = slotRect.left - navRect.left + slotRect.width / 2;
      setPillRect({left: iconCenter - 16, width: 32});
    };
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [activeKey, location.pathname]);

  if (!isPhone) return null;
  if (!user) return null;
  if (HIDDEN_PREFIXES.some((p) => location.pathname === p || location.pathname.startsWith(p))) {
    return null;
  }

  return (
    <nav
      ref={navRef}
      aria-label='Primary'
      style={{
        position: 'fixed',
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 40,
        display: 'flex',
        alignItems: 'stretch',
        background: 'color-mix(in srgb, var(--v3-bg) 86%, transparent)',
        backdropFilter: 'blur(14px) saturate(160%)',
        WebkitBackdropFilter: 'blur(14px) saturate(160%)',
        borderTop: '1px solid var(--hairline)',
        paddingBottom: 'env(safe-area-inset-bottom, 0px)',
      }}
    >
      {/* Active-pill indicator — slides between slots via the named motion
          track. The pill sits 4px above the bottom edge, 4px tall, 32px
          wide. Apple HIG iOS UITabBar uses a similar glyph-only indicator
          (filled icon + colour shift) rather than a pill; the pill here
          works on top of the colour-shift to give two visual channels
          (colour + position) for the active state — important since
          colour alone can drop below the accessibility threshold for
          colour-blind users. */}
      {pillRect && (
        <span
          aria-hidden='true'
          style={{
            position: 'absolute',
            top: 4,
            left: pillRect.left,
            width: pillRect.width,
            height: 3,
            borderRadius: 9999,
            background: 'var(--accent-blue)',
            transition:
              'left 220ms cubic-bezier(0.2, 0, 0, 1), width 220ms cubic-bezier(0.2, 0, 0, 1)',
            pointerEvents: 'none',
          }}
        />
      )}
      {SLOTS.map(({to, label, Icon}) => (
        <NavLink
          key={to}
          ref={(el) => {
            if (el) slotRefs.current.set(to, el);
            else slotRefs.current.delete(to);
          }}
          to={to}
          end={to === '/home'}
          className='press-effect'
          aria-current={activeKey === to ? 'page' : undefined}
          style={({isActive}) => ({
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 3,
            padding: '10px 4px 6px',
            minHeight: 56,
            textDecoration: 'none',
            color: isActive || activeKey === to ? 'var(--accent-blue)' : 'var(--v3-text-muted)',
            fontSize: 10.5,
            fontWeight: isActive || activeKey === to ? 600 : 500,
            letterSpacing: '0.02em',
            fontFamily: 'inherit',
            // No transition on color (instant), the pill carries motion.
          })}
        >
          {({isActive}) => {
            const visuallyActive = isActive || activeKey === to;
            return (
              <>
                <Icon
                  size={22}
                  aria-hidden='true'
                  // Filled-style look when active: lucide doesn't ship a
                  // separate fill glyph for these, so we use stroke-width
                  // amplification (2.5 → reads as bolder/filled) and
                  // brand-colour fill on the inner svg via currentColor.
                  strokeWidth={visuallyActive ? 2.4 : 1.9}
                  fill={visuallyActive ? 'currentColor' : 'none'}
                  fillOpacity={visuallyActive ? 0.15 : 0}
                />
                <span>{label}</span>
              </>
            );
          }}
        </NavLink>
      ))}
    </nav>
  );
}
