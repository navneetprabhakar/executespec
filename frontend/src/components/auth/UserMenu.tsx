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
        className="flex items-center gap-2 rounded-full bg-slate-700 px-3 py-1.5 text-sm text-white hover:bg-slate-600"
      >
        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-purple-600 text-xs font-bold">
          {initials}
        </span>
        <span className="hidden sm:inline">{user.firstName}</span>
        <TierBadge tier={user.tier} small />
      </button>

      {open && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-20 mt-2 w-56 rounded-xl border border-slate-700 bg-[#1a1f2e] shadow-xl">
            <div className="border-b border-slate-700 p-4">
              <p className="font-semibold text-white">{user.firstName} {user.lastName}</p>
              <p className="text-xs text-slate-400">{user.email}</p>
              {user.orgId && (
                <p className="mt-1 text-xs text-purple-400">
                  {user.orgRole === 'ORG_ADMIN' ? '👑 Org Admin' :
                   user.orgRole === 'BILLING_ADMIN' ? '💳 Billing Admin' : '💻 Developer'}
                </p>
              )}
            </div>
            <div className="p-2">
              <button
                onClick={() => { setOpen(false); navigate('/settings'); }}
                className="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-300 hover:bg-slate-700"
              >
                Settings
              </button>
              {/* Show Org Settings for admins with an org */}
              {user.orgId && (user.orgRole === 'ORG_ADMIN' || user.globalRole === 'SUPER_ADMIN') && (
                <button
                  onClick={() => { setOpen(false); navigate('/org/settings'); }}
                  className="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-300 hover:bg-slate-700"
                >
                  Org Settings
                </button>
              )}
              {/* Show Create Organization for PRO/ENTERPRISE users without an org */}
              {!user.orgId && (user.tier === 'PRO' || user.tier === 'ENTERPRISE') && (
                <button
                  onClick={() => { setOpen(false); navigate('/org/settings'); }}
                  className="w-full rounded-lg px-3 py-2 text-left text-sm text-purple-400 hover:bg-slate-700"
                >
                  Create Organization
                </button>
              )}
              {user.globalRole === 'SUPER_ADMIN' && (
                <button
                  onClick={() => { setOpen(false); navigate('/admin'); }}
                  className="w-full rounded-lg px-3 py-2 text-left text-sm text-amber-400 hover:bg-slate-700"
                >
                  Admin Panel
                </button>
              )}
              <hr className="my-1 border-slate-700" />
              <button
                onClick={async () => { setOpen(false); await logout(); navigate('/login'); }}
                className="w-full rounded-lg px-3 py-2 text-left text-sm text-red-400 hover:bg-slate-700"
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
