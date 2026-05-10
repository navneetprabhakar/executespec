import {useEffect, useState} from 'react';
import {Link, NavLink} from 'react-router-dom';
import {Menu, X} from 'lucide-react';
import AvatarMenu from './AvatarMenu';
import ProjectSwitcher from './ProjectSwitcher';
import {UsageBadge} from '@/components/billing/UsageBadge';
// Single-surface retirement (2026-05-06) — kept after v2 Header
// deletion. NotificationBell + NotificationPanel are framework-
// agnostic (they poll the existing /api/notifications endpoint and
// render a styled flyout) so they fit the v3 chrome unchanged.
import NotificationBell from '@/components/layout/NotificationBell';
import {useAuthStore} from '@/stores/useAuthStore';

/**
 * R-101 — top-bar shell.
 *
 * R-104/R-124 fill in the avatar menu + project switcher.
 *
 * R-330/R-340 — Library + Migrations product links. The catalogs are
 * first-class surfaces, so they live in the top bar (not behind a
 * project) — discoverable for FREE users browsing the locked solutions
 * before they have a project to instantiate against.
 *
 * 2026-04-29 (UX research review): Visible "Search ⌘K" button removed.
 * The keyboard hotkey is wired globally on {@link ../components/CmdK}.
 *
 * [M-01/02/03] (audit Sprint 3, 2026-05-01) — mobile drawer-collapse.
 * The original inline-links layout broke at every viewport ≤768px:
 * Migrations link, UsageBadge, and AvatarMenu all clipped off the right
 * edge. FREE/PRO mobile users physically could not log out and could
 * not see their quota. Confirmed across 3 viewports (360/390/428) per
 * MOBILE_AUDIT.md.
 *
 * Fix: at <=768px the nav links collapse into a hamburger that opens
 * a slide-from-right drawer containing the same nav + Avatar.
 * UsageBadge stays visible on mobile (FREE/PRO need quota visibility
 * even when the drawer is closed) but moves to a tighter slot.
 *
 * Implementation kept minimal — inline styles + a single useEffect
 * for window-resize + Escape-to-close. No new component file,
 * no Tailwind class additions, no v3.css edits. Same UX-preservation
 * contract as v2.6.4.
 */
const navLinkStyle = ({isActive}: {isActive: boolean}): React.CSSProperties => ({
  display: 'inline-flex',
  alignItems: 'center',
  padding: '4px 10px',
  borderRadius: 6,
  fontSize: 13,
  fontWeight: 500,
  textDecoration: 'none',
  color: isActive ? 'var(--v3-text)' : 'var(--v3-text-muted)',
  background: isActive ? 'var(--v3-surface)' : 'transparent',
  border: `1px solid ${isActive ? 'var(--v3-border)' : 'transparent'}`,
});

const drawerLinkStyle = ({isActive}: {isActive: boolean}): React.CSSProperties => ({
  display: 'block',
  padding: '12px 16px',
  fontSize: 16,
  fontWeight: 500,
  textDecoration: 'none',
  color: isActive ? 'var(--v3-text)' : 'var(--v3-text-muted)',
  background: isActive ? 'var(--v3-surface)' : 'transparent',
  borderRadius: 8,
  minHeight: 44, // tap-target floor per WCAG / Apple HIG
});

const MOBILE_BREAKPOINT_PX = 768;

export default function TopBar() {
  // [M-01/02/03] track viewport width to pick desktop-inline vs
  // mobile-hamburger layout. ResizeObserver isn't necessary at this
  // resolution; matchMedia + change listener is plenty.
  const [isMobile, setIsMobile] = useState<boolean>(
    () =>
      typeof window !== 'undefined' &&
      window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT_PX}px)`).matches,
  );
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT_PX}px)`);
    const handler = (e: MediaQueryListEvent) => {
      setIsMobile(e.matches);
      if (!e.matches) setDrawerOpen(false); // close drawer on resize → desktop
    };
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  // Escape closes the drawer (a11y)
  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setDrawerOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  // Body scroll lock while drawer is open
  useEffect(() => {
    if (drawerOpen) {
      const prev = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      return () => {
        document.body.style.overflow = prev;
      };
    }
  }, [drawerOpen]);

  return (
    <header className='v3-topbar' role='banner'>
      <Link to='/home' className='v3-topbar__brand' aria-label='ExecuteSpec home'>
        <span className='v3-topbar__brand-mark' aria-hidden='true'>
          ES
        </span>
        ExecuteSpec
      </Link>

      {/* Desktop: inline center nav. Hidden on mobile. */}
      {!isMobile && (
        <div className='v3-topbar__center' style={{display: 'flex', alignItems: 'center', gap: 8}}>
          <ProjectSwitcher />
          <NavLink to='/runs' style={navLinkStyle} title='All runs across projects'>
            Runs
          </NavLink>
          <NavLink to='/library' style={navLinkStyle} title='Readymade Solutions library'>
            Library
          </NavLink>
          <NavLink to='/migrations' style={navLinkStyle} title='Migration recipes'>
            Migrations
          </NavLink>
        </div>
      )}

      {/* Mobile: only ProjectSwitcher stays inline (it's the most
          frequently-used center surface). Other links go to drawer. */}
      {isMobile && (
        <div
          className='v3-topbar__center'
          style={{display: 'flex', alignItems: 'center', gap: 4, flex: 1, minWidth: 0}}
        >
          <ProjectSwitcher />
        </div>
      )}

      <div className='v3-topbar__right' style={{display: 'flex', alignItems: 'center', gap: 12}}>
        {/* I1-04 (audit-2026-05-09 iter1) — explicit tier indicator. The
            UsageBadge alone implies tier via the run quota, but unlimited
            tiers (TEAM/ENTERPRISE) hide the badge entirely, leaving paid
            users with no visible signal of which plan they're on. The
            chip self-hides for SUPER_ADMIN (who isn't tier-bound) and
            for users without a resolved tier. */}
        <TierChip />
        {/* [IA-Q4] quota chip — visible on both desktop and mobile.
            UsageBadge self-hides for TEAM/ENTERPRISE/SUPER_ADMIN. */}
        <UsageBadge />
        {/* Single-surface retirement (2026-05-06) — NotificationBell
            moved here from the deleted v2 Header. The bell self-hides
            when the user is anonymous (it polls `/api/notifications`
            which 401s without auth) so it's safe on every page. */}
        {!isMobile && <NotificationBell />}
        {!isMobile && <AvatarMenu />}
        {isMobile && (
          <button
            type='button'
            onClick={() => setDrawerOpen(true)}
            aria-label='Open navigation menu'
            aria-expanded={drawerOpen}
            aria-controls='v3-mobile-nav-drawer'
            style={{
              minWidth: 44,
              minHeight: 44,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'transparent',
              border: '1px solid var(--v3-border)',
              borderRadius: 8,
              color: 'var(--v3-text)',
              cursor: 'pointer',
            }}
          >
            <Menu size={20} aria-hidden='true' />
          </button>
        )}
      </div>

      {/* Mobile drawer — slide-from-right. */}
      {isMobile && drawerOpen && (
        <>
          {/* Scrim */}
          <div
            onClick={() => setDrawerOpen(false)}
            style={{
              position: 'fixed',
              inset: 0,
              background: 'rgba(0,0,0,0.5)',
              zIndex: 50,
            }}
            aria-hidden='true'
          />
          {/* Drawer panel */}
          <nav
            id='v3-mobile-nav-drawer'
            aria-label='Main navigation'
            style={{
              position: 'fixed',
              top: 0,
              right: 0,
              bottom: 0,
              // 2026-05-10 (mobile audit) — cap drawer width so on phones < 360px
              // (where 85vw would be < 306px) we leave a 32px margin from the
              // left edge for the user's thumb. min(...) picks the smallest of:
              // 85vw (large phones), 320px (medium phones), calc(100vw - 32px)
              // (sub-360 phones). overflow-x: hidden guards any inner card with
              // accidental fixed-width content.
              width: 'min(85vw, 320px, calc(100vw - 32px))',
              background: 'var(--v3-bg)',
              borderLeft: '1px solid var(--v3-border)',
              zIndex: 51,
              padding: 16,
              paddingTop: 'max(16px, env(safe-area-inset-top))',
              paddingBottom: 'max(16px, env(safe-area-inset-bottom))',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              overflowX: 'hidden',
              overflowY: 'auto',
            }}
          >
            <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 8}}>
              <button
                type='button'
                onClick={() => setDrawerOpen(false)}
                aria-label='Close navigation menu'
                style={{
                  minWidth: 44,
                  minHeight: 44,
                  display: 'inline-flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  background: 'transparent',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 8,
                  color: 'var(--v3-text)',
                  cursor: 'pointer',
                }}
              >
                <X size={20} aria-hidden='true' />
              </button>
            </div>
            <NavLink to='/runs' style={drawerLinkStyle} onClick={() => setDrawerOpen(false)}>
              Runs
            </NavLink>
            <NavLink to='/library' style={drawerLinkStyle} onClick={() => setDrawerOpen(false)}>
              Library
            </NavLink>
            <NavLink to='/migrations' style={drawerLinkStyle} onClick={() => setDrawerOpen(false)}>
              Migrations
            </NavLink>
            <div
              style={{marginTop: 'auto', paddingTop: 16, borderTop: '1px solid var(--v3-border)'}}
            >
              <AvatarMenu />
            </div>
          </nav>
        </>
      )}
    </header>
  );
}

/**
 * I1-04 (audit-2026-05-09 iter1) — small, glance-able tier label.
 * Pulls tier from the auth store (set at login + refresh). Hides when
 * SUPER_ADMIN (operator role, not tier-bound) or when the tier is
 * missing entirely.
 *
 * Visual: capsule pill matching the v3 chip palette. Tier-tinted bg so
 * a visible tier swap is obvious if a user upgrades mid-session.
 */
function TierChip() {
  const user = useAuthStore((s) => s.user);
  const tier = (user?.tier || '').toUpperCase();
  if (!tier) return null;
  if (user?.globalRole === 'SUPER_ADMIN') return null;

  // Tier-tinted backgrounds — defensive fallbacks so this works even
  // before the v3 token cascade if mounted in a v2 context.
  const tone =
    tier === 'ENTERPRISE'
      ? {bg: 'rgba(122,209,200,0.12)', fg: 'var(--v3-brand-2, #7AD1C8)'}
      : tier === 'TEAM' || tier === 'MAX'
        ? {bg: 'rgba(91,141,239,0.14)', fg: 'var(--v3-brand, #5B8DEF)'}
        : tier === 'PRO'
          ? {bg: 'rgba(245,158,11,0.14)', fg: 'var(--v3-warning, #F59E0B)'}
          : {bg: 'var(--v3-surface)', fg: 'var(--v3-text-muted)'}; // FREE

  const label =
    tier === 'PRO'
      ? 'Solo'
      : tier === 'TEAM'
        ? 'Team'
        : tier === 'MAX'
          ? 'Max'
          : tier === 'ENTERPRISE'
            ? 'Enterprise'
            : 'Free';

  return (
    <span
      title={`Plan: ${label}`}
      aria-label={`Current plan: ${label}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        padding: '2px 8px',
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: '0.04em',
        textTransform: 'uppercase',
        borderRadius: 999,
        background: tone.bg,
        color: tone.fg,
        border: `1px solid color-mix(in srgb, ${tone.fg} 30%, transparent)`,
        whiteSpace: 'nowrap',
      }}
    >
      {label}
    </span>
  );
}
