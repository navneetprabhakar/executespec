import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore, selectIsOrgAdmin, selectIsSuperAdmin } from '@/stores/useAuthStore';

/**
 * R-104 — avatar dropdown.
 *
 * Top-right of every v3 page. Items per greenfield §2.4:
 *   Account / Billing & plan / Team (Pro+) / Integrations /
 *   Insights (ORG_ADMIN+) / Help / Sign out.
 *
 * Role-gating via existing v2 selectors (selectIsOrgAdmin /
 * selectIsSuperAdmin) so v3 inherits the v2 RBAC truth without
 * duplicating logic.
 *
 * Logged-out users see only "Sign in" + "Pricing".
 *
 * Each menu item is a real Link or a function call. Destinations
 * that haven't shipped yet (R-141 billing, R-143 team, etc.) route
 * to /home with a query hint so the user gets a "coming soon"
 * affordance rather than a 404.
 */
export default function AvatarMenu() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);

  // Click-outside to close
  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (open && ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    function onKey(e: KeyboardEvent) {
      if (open && e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const initials = user
    ? `${user.firstName?.[0] ?? ''}${user.lastName?.[0] ?? ''}`.toUpperCase() || (user.email?.[0] ?? '?').toUpperCase()
    : '?';

  // Logged-out: show Sign in + Pricing
  if (!user) {
    // Selling-point fix #4: per-persona top-nav routing. Procurement (Devon)
    // and eng-managers (Priya) need a self-route to /team + /trust
    // before they're willing to sign up. Linear / Vercel / Stripe all do
    // this — separate "For teams" / "For enterprise" entry points.
    const navLinkStyle: React.CSSProperties = {
      color: 'var(--v3-text-muted)',
      fontSize: 12,
      padding: '6px 10px',
      borderRadius: 6,
      textDecoration: 'none',
      whiteSpace: 'nowrap',
    };
    return (
      <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
        {/* R-330 / R-340 — public catalog surfaces.  Casey (FREE
            tier explorer) is the persona that converts on browsing
            the catalog *before* signing up; the lock icons + tier
            copy on the catalog rows do the upgrade selling.  Keeping
            these in the marketing rail closes the
            "I can't find the products" gap. */}
        <Link to="/library" style={navLinkStyle}>Library</Link>
        <Link to="/migrations" style={navLinkStyle}>Migrations</Link>
        <Link to="/team" style={navLinkStyle}>For teams</Link>
        <Link to="/trust" style={navLinkStyle}>For enterprise</Link>
        <Link to="/pricing" style={navLinkStyle}>Pricing</Link>
        <Link
          to="/login"
          style={{
            color: 'var(--v3-text)',
            fontSize: 12,
            fontWeight: 600,
            padding: '6px 12px',
            borderRadius: 6,
            background: 'var(--v3-brand)',
            textDecoration: 'none',
            marginLeft: 4,
          }}
        >
          Sign in
        </Link>
      </div>
    );
  }

  // Logged-in: avatar dropdown
  const items: { label: string; onClick: () => void; subtle?: boolean }[] = [
    { label: 'Account', onClick: () => navigate('/settings') },
    { label: 'Billing & plan', onClick: () => navigate('/billing') },
  ];
  if (user.tier && user.tier !== 'FREE') {
    items.push({ label: 'Team', onClick: () => navigate('/team') });
  }
  items.push({ label: 'Integrations', onClick: () => navigate('/integrations') });
  if (isOrgAdmin || isSuperAdmin) {
    items.push({ label: 'Insights', onClick: () => navigate('/insights') });
  }
  items.push({ label: 'Help', onClick: () => navigate('/home?dest=help') });
  items.push({
    label: 'Sign out',
    subtle: true,
    onClick: async () => {
      await logout();
      navigate('/login');
    },
  });

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label="Account menu"
        aria-expanded={open}
        aria-haspopup="menu"
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 30,
          height: 30,
          borderRadius: '50%',
          background: 'linear-gradient(135deg, var(--v3-brand), var(--v3-brand-2))',
          color: 'var(--v3-text-on-brand)',
          border: 'none',
          cursor: 'pointer',
          fontSize: 11,
          fontWeight: 700,
          letterSpacing: 0.4,
        }}
      >
        {initials}
      </button>

      {open && (
        <ul
          role="menu"
          aria-label="Account"
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            right: 0,
            minWidth: 220,
            margin: 0,
            padding: 6,
            listStyle: 'none',
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border-strong)',
            borderRadius: 8,
            boxShadow: '0 16px 32px rgba(0,0,0,0.5)',
            zIndex: 80,
          }}
        >
          <li
            style={{
              padding: '8px 10px',
              borderBottom: '1px solid var(--v3-border)',
              marginBottom: 4,
            }}
          >
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--v3-text)' }}>
              {user.firstName ?? user.email}
            </div>
            <div style={{ fontSize: 10.5, color: 'var(--v3-text-subtle)' }}>
              {user.email} · {user.tier ?? 'FREE'}
            </div>
            {/* Conversion-fix #A3 — Pro is HIDDEN from new buyers (V122
                lifecycle state) but existing Pro subscribers stay subscribed.
                Without an explicit "you're grandfathered" signal, the silence
                reads as "they pulled the rug" and triggers panic-cancel.
                This pill makes Pro feel like a status, not an accident. */}
            {user.tier === 'PRO' && (
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 5,
                  marginTop: 6,
                  padding: '3px 8px',
                  background: 'rgba(91,141,239,0.10)',
                  border: '1px solid rgba(91,141,239,0.32)',
                  borderRadius: 4,
                  fontSize: 10,
                  fontWeight: 500,
                  color: 'var(--v3-brand)',
                }}
                title="Pro tier is closed to new buyers. Your subscription is locked at this price for as long as you stay subscribed."
              >
                <span style={{ fontSize: 10 }}>🔒</span>
                Pro · grandfathered · locked rate
              </div>
            )}
          </li>
          {items.map((item, idx) => (
            <li key={idx} role="none">
              <button
                role="menuitem"
                type="button"
                onClick={() => {
                  item.onClick();
                  setOpen(false);
                }}
                style={{
                  display: 'block',
                  width: '100%',
                  textAlign: 'left',
                  padding: '7px 10px',
                  background: 'transparent',
                  color: item.subtle ? 'var(--v3-text-muted)' : 'var(--v3-text)',
                  border: 'none',
                  borderRadius: 4,
                  cursor: 'pointer',
                  fontSize: 12.5,
                  fontFamily: 'inherit',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = 'var(--v3-surface-elevated)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'transparent';
                }}
              >
                {item.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
