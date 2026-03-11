import { create } from 'zustand';
import * as api from '@/lib/api';
import type { TierPermissions } from '@/hooks/useTierPermissions';

interface AuthStore {
  user: api.AuthUser | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  permissions: TierPermissions | null;

  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  register: (email: string, firstName: string, lastName: string, password: string) => Promise<void>;
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
    const resp = await api.authLogin({ email, password });
    localStorage.setItem('access_token', resp.accessToken);
    set({ accessToken: resp.accessToken, user: resp.user, isAuthenticated: true });
    // Load permissions after login
    api.getMyPermissions().then((p) => set({ permissions: p })).catch(() => {});
  },

  logout: async () => {
    try { await api.authLogout(); } catch { /* ignore */ }
    localStorage.removeItem('access_token');
    set({ accessToken: null, user: null, isAuthenticated: false, permissions: null });
  },

  register: async (email, firstName, lastName, password) => {
    await api.authRegister({ email, firstName, lastName, password });
    // Don't auto-login — user must verify email first
  },

  refreshAccessToken: async () => {
    try {
      const resp = await api.authRefresh();
      localStorage.setItem('access_token', resp.accessToken);
      set({ accessToken: resp.accessToken, user: resp.user, isAuthenticated: true });
      return true;
    } catch {
      localStorage.removeItem('access_token');
      set({ accessToken: null, user: null, isAuthenticated: false, permissions: null });
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
      const user = await api.authMe();
      set({ user, isAuthenticated: true });
      // Load tier permissions
      api.getMyPermissions().then((p) => set({ permissions: p })).catch(() => {});
    } catch {
      set({ user: null, isAuthenticated: false, permissions: null });
    }
  },

  setUser: (user) => set({ user }),
  clearAuth: () => {
    localStorage.removeItem('access_token');
    set({ accessToken: null, user: null, isAuthenticated: false, permissions: null });
  },
}));

// ── Selectors ─────────────────────────────────────────────────────────────────

export const selectIsSuperAdmin = (s: AuthStore) =>
  s.user?.globalRole === 'SUPER_ADMIN';

export const selectIsOrgAdmin = (s: AuthStore) =>
  s.user?.globalRole === 'SUPER_ADMIN' || s.user?.orgRole === 'ORG_ADMIN';

export const selectIsOrgMember = (s: AuthStore) =>
  s.user?.orgId !== null && s.user?.orgId !== undefined;
