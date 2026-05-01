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

// ── Selectors ─────────────────────────────────────────────────────────────────

export const selectIsSuperAdmin = (s: AuthStore) => s.user?.globalRole === 'SUPER_ADMIN';

export const selectIsOrgAdmin = (s: AuthStore) =>
  s.user?.globalRole === 'SUPER_ADMIN' || s.user?.orgRole === 'ORG_ADMIN';

export const selectIsOrgMember = (s: AuthStore) =>
  s.user?.orgId !== null && s.user?.orgId !== undefined;
