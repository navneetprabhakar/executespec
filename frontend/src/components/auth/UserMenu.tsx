import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/useAuthStore';
import TierBadge from './TierBadge';

export default function UserMenu() {
  const { user, logout } = useAuthStore();
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();

  if (!user) return null;

  const initials = `${user.firstName[0]}${user.lastName[0]}`.toUpperCase();

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(o => !o)}
        className="flex items-center gap-2 rounded-sm bg-[var(--gray-400)] px-3 py-1.5 text-sm text-primary hover:bg-slate-600"
      >
        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-accent-blue text-xs font-bold">
          {initials}
        </span>
        <span className="hidden sm:inline">{user.firstName}</span>
        <TierBadge tier={user.tier} small />
      </button>

      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-20 mt-2 w-56 rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] ">
            <div className="border-b border-[var(--border-default)] p-4">
              <p className="font-semibold text-primary">{user.firstName} {user.lastName}</p>
              <p className="text-xs text-muted">{user.email}</p>
              {user.orgId && (
                <p className="mt-1 text-xs text-[var(--text-secondary)]">
                  {user.orgRole === 'ORG_ADMIN' ? '👑 Org Admin' :
                   user.orgRole === 'BILLING_ADMIN' ? '💳 Billing Admin' : '💻 Developer'}
                </p>
              )}
            </div>
            <div className="p-2">
              <button
                onClick={() => { setOpen(false); navigate('/settings'); }}
                className="w-full rounded-sm px-3 py-2 text-left text-sm text-secondary hover:bg-[var(--gray-400)]"
              >
                Settings
              </button>
              {/* Show Org Settings for admins with an org */}
              {user.orgId && (user.orgRole === 'ORG_ADMIN' || user.globalRole === 'SUPER_ADMIN') && (
                <button
                  onClick={() => { setOpen(false); navigate('/org/settings'); }}
                  className="w-full rounded-sm px-3 py-2 text-left text-sm text-secondary hover:bg-[var(--gray-400)]"
                >
                  Org Settings
                </button>
              )}
              {/* Show Create Organization for PRO/ENTERPRISE users without an org */}
              {!user.orgId && (user.tier === 'PRO' || user.tier === 'ENTERPRISE') && (
                <button
                  onClick={() => { setOpen(false); navigate('/org/settings'); }}
                  className="w-full rounded-sm px-3 py-2 text-left text-sm text-[var(--text-secondary)] hover:bg-[var(--gray-400)]"
                >
                  Create Organization
                </button>
              )}
              {user.globalRole === 'SUPER_ADMIN' && (
                <button
                  onClick={() => { setOpen(false); navigate('/admin'); }}
                  className="w-full rounded-sm px-3 py-2 text-left text-sm text-accent-amber hover:bg-[var(--gray-400)]"
                >
                  Admin Panel
                </button>
              )}
              <hr className="my-1 border-[var(--border-default)]" />
              <button
                onClick={async () => { setOpen(false); await logout(); navigate('/login'); }}
                className="w-full rounded-sm px-3 py-2 text-left text-sm text-accent-red hover:bg-[var(--gray-400)]"
              >
                Log out
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
