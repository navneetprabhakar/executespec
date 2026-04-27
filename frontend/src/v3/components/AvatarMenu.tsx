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
 * to /v3/home with a query hint so the user gets a "coming soon"
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
    return (
      <div style={{ display: 'flex', gap: 6 }}>
        <Link
          to="/v3/pricing"
          style={{
            color: 'var(--v3-text-muted)',
            fontSize: 12,
            padding: '6px 10px',
            borderRadius: 6,
            textDecoration: 'none',
          }}
        >
          Pricing
        </Link>
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
          }}
        >
          Sign in
        </Link>
      </div>
    );
  }

  // Logged-in: avatar dropdown
  const items: { label: string; onClick: () => void; subtle?: boolean }[] = [
    { label: 'Account', onClick: () => navigate('/v3/settings') },
    { label: 'Billing & plan', onClick: () => navigate('/v3/billing') },
  ];
  if (user.tier && user.tier !== 'FREE') {
    items.push({ label: 'Team', onClick: () => navigate('/v3/team') });
  }
  items.push({ label: 'Integrations', onClick: () => navigate('/v3/integrations') });
  if (isOrgAdmin || isSuperAdmin) {
    items.push({ label: 'Insights', onClick: () => navigate('/v3/insights') });
  }
  items.push({ label: 'Help', onClick: () => navigate('/v3/home?dest=help') });
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
          color: '#0B0D10',
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
