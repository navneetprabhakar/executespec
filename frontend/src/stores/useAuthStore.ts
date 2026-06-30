import {create} from 'zustand';
import * as api from '@/lib/api';
import type {TierPermissions} from '@/hooks/useTierPermissions';
import {useUiStore} from './useUiStore';

interface AuthStore {
  user: api.AuthUser | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  permissions: TierPermissions | null;

  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  register: (
    email: string,
    firstName: string,
    lastName: string,
    password: string,
    attribution?: {signupSource?: string; utmSource?: string; utmCampaign?: string},
  ) => Promise<void>;
  refreshAccessToken: () => Promise<boolean>;
  loadMe: () => Promise<void>;
  setUser: (user: api.AuthUser | null) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthStore>((set, get) => ({
  user: null,
  accessToken: localStorage.getItem('access_token'),
  isAuthenticated: !!localStorage.getItem('access_token'),
  isLoading: false,
  permissions: null,

  login: async (email, password) => {
    const resp = await api.authLogin({email, password});
    localStorage.setItem('access_token', resp.accessToken);
    // R-190 — normalize legacy 'vibe' viewMode at the parse boundary.
    set({
      accessToken: resp.accessToken,
      user: api.normalizeAuthUser(resp.user),
      isAuthenticated: true,
    });
    // Load permissions after login
    api
      .getMyPermissions()
      .then((p) => set({permissions: p}))
      .catch(() => {});
  },

  logout: async () => {
    try {
      await api.authLogout();
    } catch {
      /* ignore */
    }
    localStorage.removeItem('access_token');
    set({accessToken: null, user: null, isAuthenticated: false, permissions: null});
  },

  register: async (email, firstName, lastName, password, attribution) => {
    await api.authRegister({email, firstName, lastName, password, ...attribution});
    // Don't auto-login — user must verify email first
  },

  refreshAccessToken: async () => {
    try {
      const resp = await api.authRefresh();
      localStorage.setItem('access_token', resp.accessToken);
      // R-190 — normalize legacy 'vibe' viewMode at the parse boundary.
      set({
        accessToken: resp.accessToken,
        user: api.normalizeAuthUser(resp.user),
        isAuthenticated: true,
      });
      return true;
    } catch {
      localStorage.removeItem('access_token');
      set({accessToken: null, user: null, isAuthenticated: false, permissions: null});
      return false;
    }
  },

  loadMe: async () => {
    const token = localStorage.getItem('access_token');
    if (!token) {
      // Try refresh via cookie
      const ok = await get().refreshAccessToken();
      if (!ok) return;
    }
    try {
      const rawUser = await api.authMe();
      // R-190 (post-cutover) — coerce legacy `view_mode='vibe'` rows to 'pro'
      // at the API parse boundary so the rest of the frontend never sees
      // 'vibe'. This is the only place we need to normalize: every other
      // user-shaped read flows through useAuthStore.user.
      const user = api.normalizeAuthUser(rawUser);
      // 'project' is session-local — never a login default. If the server
      // has it persisted from an older session, reset to 'pro' immediately
      // so the toggle + view don't disagree.
      if (user.viewMode === 'project') {
        user.viewMode = 'pro';
        api.updateViewMode('pro').catch(() => {});
      }
      set({user, isAuthenticated: true});
      if (user.viewMode === 'pro' || user.viewMode === 'project') {
        useUiStore.getState().setViewMode(user.viewMode);
      }
      // Load tier permissions
      api
        .getMyPermissions()
        .then((p) => set({permissions: p}))
        .catch(() => {});
    } catch {
      set({user: null, isAuthenticated: false, permissions: null});
    }
  },

  setUser: (user) => set({user}),
  clearAuth: () => {
    localStorage.removeItem('access_token');
    set({accessToken: null, user: null, isAuthenticated: false, permissions: null});
  },
}));

export const selectIsSuperAdmin = (s: AuthStore) => s.user?.globalRole === 'SUPER_ADMIN';

export const selectIsOrgAdmin = (s: AuthStore) =>
  s.user?.globalRole === 'SUPER_ADMIN' || s.user?.orgRole === 'ORG_ADMIN';

export const selectIsOrgMember = (s: AuthStore) =>
  s.user?.orgId !== null && s.user?.orgId !== undefined;

/**
 * X-06 (UX audit, 2026-05-03) — single source of truth for "what tier should
 * the UI treat the user as". Centralises the legacy MAX → TEAM rename
 * coercion that was duplicated across Sidebar.tsx and other tier-aware
 * surfaces.
 *
 * The V141 backend migration renamed the MAX tier to TEAM; some grandfathered
 * users still have 'MAX' on their token from before the rename. Sidebar was
 * doing `tier.replace(/^MAX$/, 'TEAM')` inline — fragile and would need a
 * matching `replace` call every time a future tier renames. Putting the
 * normalisation here means new tier renames touch ONE place.
 *
 * Falls back to FREE when nothing is set so consumers don't have to handle
 * undefined.
 */
export const selectNormalizedTier = (
  s: AuthStore,
): 'FREE' | 'PRO' | 'PRO_PLUS' | 'BYOK' | 'TEAM' | 'ENTERPRISE' => {
  const raw = (s.user?.tier ?? '').toString().toUpperCase();
  // Legacy MAX rows still in flight from grandfathered users — coerce.
  if (raw === 'MAX') return 'TEAM';
  if (raw === 'PRO') return 'PRO';
  if (raw === 'PRO_PLUS') return 'PRO_PLUS';
  if (raw === 'BYOK') return 'BYOK';
  if (raw === 'TEAM') return 'TEAM';
  if (raw === 'ENTERPRISE') return 'ENTERPRISE';
  // Anything else (blank, FREE, unknown new tier) → FREE.
  return 'FREE';
};
