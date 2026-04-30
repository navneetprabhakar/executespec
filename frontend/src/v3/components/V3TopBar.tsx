import { Link, NavLink } from 'react-router-dom';
import AvatarMenu from './AvatarMenu';
import ProjectSwitcher from './ProjectSwitcher';
import { UsageBadge } from '@/components/billing/UsageBadge';

/**
 * R-101 — top-bar shell.
 *
 * R-104/R-124 fill in the avatar menu + project switcher; the
 * placeholder chips are still here for those.
 *
 * R-330/R-340 — Library + Migrations product links. The catalogs
 * are first-class surfaces, so they live in the top bar (not behind
 * a project) — discoverable for FREE users browsing the locked
 * solutions before they have a project to instantiate against.
 *
 * 2026-04-29 (UX research review):
 * - Visible "Search ⌘K" button removed.  The keyboard hotkey is wired
 *   globally on {@link ../components/CmdK} (document-level keydown
 *   listener on ⌘K / Ctrl+K), so power users still summon the palette;
 *   mouse users have no use for it because the top-bar pills +
 *   ProjectSwitcher cover the main destinations.  Net: one fewer slot
 *   in the top bar without functional regression.
 */
const navLinkStyle = ({ isActive }: { isActive: boolean }): React.CSSProperties => ({
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

export default function V3TopBar() {
  return (
    <header className="v3-topbar" role="banner">
      <Link to="/home" className="v3-topbar__brand" aria-label="ExecuteSpec home">
        <span className="v3-topbar__brand-mark" aria-hidden="true">
          ES
        </span>
        ExecuteSpec
      </Link>
      <div className="v3-topbar__center" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <ProjectSwitcher />
        <NavLink to="/runs" style={navLinkStyle} title="All runs across projects">
          Runs
        </NavLink>
        <NavLink to="/library" style={navLinkStyle} title="Readymade Solutions library">
          Library
        </NavLink>
        <NavLink to="/migrations" style={navLinkStyle} title="Migration recipes">
          Migrations
        </NavLink>
      </div>
      <div className="v3-topbar__right" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        {/* [IA-Q4] (audit Sprint 1 PR-1) — quota chip in top-nav for
            FREE/PRO. UsageBadge self-hides for TEAM/ENTERPRISE
            (unlimited) and SUPER_ADMIN, so this is safe to render
            unconditionally. Was: only visible in v2 Sidebar footer
            (AppLayout-only routes), so v3-shell FREE/PRO users had no
            quota visibility. */}
        <UsageBadge />
        <AvatarMenu />
      </div>
    </header>
  );
}
