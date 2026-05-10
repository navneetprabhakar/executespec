import {useEffect, useState} from 'react';
import {createPortal} from 'react-dom';
import {Link, NavLink, useLocation, useNavigate} from 'react-router-dom';
import {Menu, X} from 'lucide-react';
import AvatarMenu from './AvatarMenu';
import ProjectSwitcher from './ProjectSwitcher';
import {UsageBadge} from '@/components/billing/UsageBadge';
// Single-surface retirement (2026-05-06) — kept after v2 Header
// deletion. NotificationBell + NotificationPanel are framework-
// agnostic (they poll the existing /api/notifications endpoint and
// render a styled flyout) so they fit the v3 chrome unchanged.
import NotificationBell from '@/components/layout/NotificationBell';
import {selectIsOrgAdmin, selectIsSuperAdmin, useAuthStore} from '@/stores/useAuthStore';

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
  display: 'flex',
  alignItems: 'center',
  padding: '11px 14px',
  fontSize: 14.5,
  fontWeight: 500,
  textDecoration: 'none',
  color: isActive ? 'var(--v3-text)' : 'var(--v3-text-muted)',
  background: isActive ? 'var(--v3-surface-elevated)' : 'transparent',
  borderRadius: 8,
  minHeight: 44, // tap-target floor per WCAG / Apple HIG
});

// 2026-05-10 mobile pass iter-5 — drawer is now the single mobile-nav surface.
// Account / Org / SUPER_ADMIN items moved out of `<AvatarMenu />`'s nested
// popup (which rendered awkwardly INSIDE the drawer with absolute-position
// dropdown going off-canvas) into a flat list of styled buttons. Same shape
// as the AvatarMenu items but no nesting; whole drawer scrolls together.
const drawerActionStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  width: '100%',
  textAlign: 'left',
  padding: '11px 14px',
  fontSize: 14.5,
  fontWeight: 500,
  color: 'var(--v3-text-muted)',
  background: 'transparent',
  border: 'none',
  borderRadius: 8,
  minHeight: 44,
  cursor: 'pointer',
  fontFamily: 'inherit',
};

const drawerSectionLabel: React.CSSProperties = {
  fontSize: 10.5,
  fontWeight: 600,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'var(--v3-text-subtle)',
  padding: '10px 14px 6px',
};

/** Project tabs surfaced in the drawer when the URL matches `/projects/:id`. */
const PROJECT_TABS: {key: string; label: string}[] = [
  {key: 'pulse', label: 'Pulse'},
  {key: 'compose', label: 'Compose'},
  {key: 'repos', label: 'Repos'},
  {key: 'runs', label: 'Runs'},
  {key: 'code', label: 'Code'},
  {key: 'chat', label: 'Chat'},
];

/** Match `/projects/{uuid}` and capture the slug. Excludes deeper paths. */
function parseProjectIdFromPathname(pathname: string): string | null {
  const m = pathname.match(/^\/projects\/([0-9a-fA-F-]{8,})(?:\/|$)/);
  return m ? m[1] : null;
}

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

  // 2026-05-10 mobile pass iter-5 — auth + role state read at the top so the
  // unified mobile drawer can render the same role-gated items the AvatarMenu
  // surfaces on desktop, without the nested-popup wart.
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const navigate = useNavigate();
  const location = useLocation();
  const projectIdFromUrl = parseProjectIdFromPathname(location.pathname);
  const currentTabFromUrl = (() => {
    if (typeof window === 'undefined') return null;
    const sp = new URLSearchParams(location.search);
    return sp.get('tab');
  })();

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

  // 2026-05-10 mobile pass iter-3 — drawer is portaled to document.body so
  // its position:fixed escapes the topbar's containing block. The topbar has
  // `backdrop-filter: blur(8px)` which CSS spec says creates a containing
  // block for fixed-positioned descendants, scoping the drawer's right:0/top:0
  // to the 52px-tall header instead of the viewport. Result before fix: drawer
  // panel rendered off-canvas inside the header, scrim invisible, hamburger
  // appeared to do nothing. Portal sidesteps it cleanly.
  // 2026-05-10 mobile pass iter-5 — drawer rebuilt as a flat unified nav.
  // Pre-fix it had three nav links + the desktop AvatarMenu component
  // embedded as the footer; the AvatarMenu's nested-popup opened with
  // `position: absolute; top: calc(100% + 6px)` so on mobile inside the
  // drawer the dropdown rendered ABOVE the SA chip, partially clipped, and
  // the user couldn't tell it was a menu vs the rest of the drawer.
  //
  // New shape (top → bottom, scroll if overflow):
  //   1. user identity card (name + email + tier pill) — no nesting
  //   2. PROJECT section (only when on `/projects/:id`) — Pulse / Compose /
  //      Repos / Runs / Code / Chat as `Link`s with active highlight
  //   3. CATALOG — Runs (cross-project) / Library / Migrations
  //   4. ACCOUNT — Account / Billing & plan / Pricing / Team (paid) /
  //      Integrations / Trust & compliance
  //   5. ORG ADMIN (gated) — Insights / Exec / Audit / Invoices / Accounting
  //   6. SUPER ADMIN (gated) — Admin / Feature flags / Tier lifecycle
  //   7. Help / Sign out (footer)
  const closeDrawer = () => setDrawerOpen(false);
  const handleSignOut = async () => {
    try {
      await logout();
    } finally {
      closeDrawer();
      navigate('/login');
    }
  };
  const tierLabel =
    user?.tier === 'PRO'
      ? 'Solo'
      : user?.tier === 'TEAM'
        ? 'Team'
        : user?.tier === 'ENTERPRISE'
          ? 'Enterprise'
          : 'Free';

  const drawer =
    isMobile && drawerOpen
      ? createPortal(
          <>
            <div
              onClick={closeDrawer}
              style={{
                position: 'fixed',
                inset: 0,
                background: 'rgba(0,0,0,0.7)',
                zIndex: 60,
                backdropFilter: 'blur(2px)',
                WebkitBackdropFilter: 'blur(2px)',
              }}
              aria-hidden='true'
            />
            <nav
              id='v3-mobile-nav-drawer'
              aria-label='Main navigation'
              style={{
                position: 'fixed',
                top: 0,
                right: 0,
                bottom: 0,
                width: 'min(88vw, 340px, calc(100vw - 24px))',
                background: 'var(--v3-surface)',
                borderLeft: '1px solid var(--v3-border)',
                boxShadow: '-8px 0 24px rgba(0,0,0,0.6)',
                zIndex: 61,
                paddingTop: 'max(8px, env(safe-area-inset-top))',
                paddingBottom: 'max(8px, env(safe-area-inset-bottom))',
                display: 'flex',
                flexDirection: 'column',
                overflowX: 'hidden',
                overflowY: 'auto',
              }}
            >
              {/* Header row: brand mark + close button */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '8px 14px 6px',
                }}
              >
                <span
                  className='v3-topbar__brand-mark'
                  aria-hidden='true'
                  style={{width: 28, height: 28}}
                >
                  ES
                </span>
                <button
                  type='button'
                  onClick={closeDrawer}
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

              {/* User identity card */}
              {user && (
                <div
                  style={{
                    margin: '4px 8px 12px',
                    padding: '10px 12px',
                    background: 'var(--v3-surface-elevated)',
                    border: '1px solid var(--v3-border)',
                    borderRadius: 10,
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 10,
                    }}
                  >
                    <span
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        width: 32,
                        height: 32,
                        borderRadius: '50%',
                        background: 'linear-gradient(135deg, var(--v3-brand), var(--v3-brand-2))',
                        color: 'var(--v3-text-on-brand)',
                        fontSize: 12,
                        fontWeight: 700,
                        flexShrink: 0,
                      }}
                      aria-hidden='true'
                    >
                      {(user.firstName?.[0] ?? user.email?.[0] ?? '?').toUpperCase()}
                      {(user.lastName?.[0] ?? '').toUpperCase()}
                    </span>
                    <div style={{minWidth: 0}}>
                      <div
                        style={{
                          fontSize: 13,
                          fontWeight: 600,
                          color: 'var(--v3-text)',
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                        }}
                      >
                        {user.firstName ?? user.email}
                      </div>
                      <div
                        style={{
                          fontSize: 11,
                          color: 'var(--v3-text-subtle)',
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                        }}
                      >
                        {user.email}
                      </div>
                    </div>
                  </div>
                  <div style={{marginTop: 6}}>
                    <span
                      style={{
                        display: 'inline-block',
                        fontSize: 10,
                        fontWeight: 600,
                        letterSpacing: '0.06em',
                        textTransform: 'uppercase',
                        padding: '2px 8px',
                        borderRadius: 999,
                        background: 'rgba(91,141,239,0.14)',
                        color: 'var(--v3-brand)',
                      }}
                    >
                      {tierLabel}
                    </span>
                  </div>
                </div>
              )}

              {/* Project section — only shown when on a project page */}
              {projectIdFromUrl && (
                <>
                  <div style={drawerSectionLabel}>Project</div>
                  {PROJECT_TABS.map((t) => {
                    const isActive = currentTabFromUrl === t.key;
                    return (
                      <Link
                        key={t.key}
                        to={`/projects/${projectIdFromUrl}?tab=${t.key}`}
                        onClick={closeDrawer}
                        style={drawerLinkStyle({isActive})}
                      >
                        {t.label}
                      </Link>
                    );
                  })}
                </>
              )}

              {/* Catalog (cross-project) */}
              <div style={drawerSectionLabel}>Catalog</div>
              <NavLink to='/runs' style={drawerLinkStyle} onClick={closeDrawer} end>
                Runs
              </NavLink>
              <NavLink to='/library' style={drawerLinkStyle} onClick={closeDrawer}>
                Library
              </NavLink>
              <NavLink to='/migrations' style={drawerLinkStyle} onClick={closeDrawer}>
                Migrations
              </NavLink>

              {/* Account — flat list (replaces nested AvatarMenu popup) */}
              {user && (
                <>
                  <div style={drawerSectionLabel}>Account</div>
                  <NavLink to='/settings' style={drawerLinkStyle} onClick={closeDrawer}>
                    Account settings
                  </NavLink>
                  <NavLink to='/billing' style={drawerLinkStyle} onClick={closeDrawer}>
                    Billing & plan
                  </NavLink>
                  <NavLink to='/pricing' style={drawerLinkStyle} onClick={closeDrawer}>
                    Pricing
                  </NavLink>
                  {user.tier && user.tier !== 'FREE' && (
                    <NavLink to='/team' style={drawerLinkStyle} onClick={closeDrawer}>
                      Team
                    </NavLink>
                  )}
                  <NavLink to='/integrations' style={drawerLinkStyle} onClick={closeDrawer}>
                    Integrations
                  </NavLink>
                  <NavLink to='/trust' style={drawerLinkStyle} onClick={closeDrawer}>
                    Trust & compliance
                  </NavLink>
                </>
              )}

              {/* Org admin (gated) */}
              {(isOrgAdmin || isSuperAdmin) && (
                <>
                  <div style={drawerSectionLabel}>Org admin</div>
                  <NavLink to='/insights' style={drawerLinkStyle} onClick={closeDrawer}>
                    Insights
                  </NavLink>
                  <NavLink to='/exec' style={drawerLinkStyle} onClick={closeDrawer}>
                    Executive summary
                  </NavLink>
                  <NavLink to='/audit' style={drawerLinkStyle} onClick={closeDrawer}>
                    Audit log
                  </NavLink>
                  <NavLink to='/invoices' style={drawerLinkStyle} onClick={closeDrawer}>
                    Invoices
                  </NavLink>
                  <NavLink to='/accounting' style={drawerLinkStyle} onClick={closeDrawer}>
                    Accounting
                  </NavLink>
                </>
              )}

              {/* Super admin (gated) */}
              {isSuperAdmin && (
                <>
                  <div style={drawerSectionLabel}>Super admin</div>
                  <NavLink to='/admin' style={drawerLinkStyle} onClick={closeDrawer} end>
                    Admin home
                  </NavLink>
                  <NavLink to='/admin/feature-flags' style={drawerLinkStyle} onClick={closeDrawer}>
                    Feature flags
                  </NavLink>
                  <NavLink to='/admin/tiers' style={drawerLinkStyle} onClick={closeDrawer}>
                    Tier lifecycle
                  </NavLink>
                </>
              )}

              {/* Footer — Help + Sign out, pushed to the bottom */}
              <div
                style={{
                  marginTop: 'auto',
                  paddingTop: 12,
                  borderTop: '1px solid var(--v3-border)',
                }}
              >
                {user ? (
                  <>
                    <NavLink to='/home?dest=help' style={drawerLinkStyle} onClick={closeDrawer}>
                      Help
                    </NavLink>
                    <button type='button' onClick={handleSignOut} style={drawerActionStyle}>
                      Sign out
                    </button>
                  </>
                ) : (
                  <NavLink to='/login' style={drawerLinkStyle} onClick={closeDrawer}>
                    Sign in
                  </NavLink>
                )}
              </div>
            </nav>
          </>,
          document.body,
        )
      : null;

  return (
    <>
      <header className='v3-topbar' role='banner'>
        <Link to='/home' className='v3-topbar__brand' aria-label='ExecuteSpec home'>
          <span className='v3-topbar__brand-mark' aria-hidden='true'>
            ES
          </span>
          {/* 2026-05-10 mobile pass — hide the wordmark below 480px so the
            ProjectSwitcher trigger has room to breathe without ellipsis-ing
            on every label. Logo mark + ARIA label keep the brand readable
            and accessible. */}
          <span className='v3-topbar__brand-text'>ExecuteSpec</span>
        </Link>

        {/* Desktop: inline center nav. Hidden on mobile. */}
        {!isMobile && (
          <div
            className='v3-topbar__center'
            style={{display: 'flex', alignItems: 'center', gap: 8}}
          >
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
      </header>
      {drawer}
    </>
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
