import {create} from 'zustand';
import * as api from '@/lib/api';
import type {TierPermissions} from '@/hooks/useTierPermissions';

/**
 * RR-198 — permissions are a three-state signal, not a nullable value. `null`
 * means "unknown" but the store must also tell consumers WHY it is unknown so
 * the UI can render a retry/error rather than silently downgrading a paid/admin
 * account to a disabled-controls state when the permission fetch merely failed.
 */
export type PermissionsStatus = 'idle' | 'loading' | 'ready' | 'error';

interface AuthStore {
  user: api.AuthUser | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  /** Transient session-hydration failure. The access session is retained so the user can retry. */
  loadMeError: string | null;
  permissions: TierPermissions | null;
  /** RR-198 — lifecycle of the permissions fetch. `error` ≠ empty permissions. */
  permissionsStatus: PermissionsStatus;
  /**
   * RR-196 — set when a user-initiated logout could NOT be confirmed by the server. The HttpOnly
   * refresh cookie cannot be cleared from JavaScript, so an unconfirmed logout means the seven-day
   * session may still be live. The UI must not report a clean sign-out in that case.
   */
  logoutError: string | null;

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
  /**
   * RR-1335 — adopt a login-shaped session returned outside the normal login form (e.g. the
   * email-verification endpoint, which sets the refresh cookie and returns an access token + user).
   * Mirrors `login`'s post-steps so a just-verified user lands authenticated instead of being sent
   * back to re-enter credentials while a valid refresh cookie is discarded.
   */
  adoptSession: (accessToken: string, user: api.AuthUser) => void;
  loadMe: () => Promise<void>;
  /** RR-198 — (re)fetch tier permissions, tracking loading/ready/error state. */
  loadPermissions: () => Promise<void>;
  setUser: (user: api.AuthUser | null) => void;
  clearAuth: () => void;
}

// All protected-route mounts await the same hydration attempt. The generation fence prevents a
// late /me response from reviving a logged-out session or overwriting a newer login/adopted session.
let loadMeInFlight: Promise<void> | null = null;
let authStateGeneration = 0;

export const useAuthStore = create<AuthStore>((set, get) => ({
  user: null,
  accessToken: localStorage.getItem('access_token'),
  isAuthenticated: !!localStorage.getItem('access_token'),
  isLoading: false,
  loadMeError: null,
  permissions: null,
  permissionsStatus: 'idle',
  logoutError: null,

  login: async (email, password) => {
    const resp = await api.authLogin({email, password});
    authStateGeneration++;
    localStorage.setItem('access_token', resp.accessToken);
    // RR-196 — an explicit successful login clears any prior unconfirmed-logout marker so
    // cookie-based auto-refresh is allowed again for this deliberately re-authenticated session.
    localStorage.removeItem('es:logout_unconfirmed');
    // R-190 — normalize legacy 'vibe' viewMode at the parse boundary.
    set({
      accessToken: resp.accessToken,
      user: api.normalizeAuthUser(resp.user),
      isAuthenticated: true,
      loadMeError: null,
    });
    // RR-198 — load permissions with tracked loading/ready/error state.
    void get().loadPermissions();
  },

  logout: async () => {
    // RR-196 — logout is a server-side revocation, not a local token delete. Attempt it (with one
    // retry for a transient blip) and record whether the server actually confirmed. Local access
    // state is always cleared, but if the revocation was NOT confirmed we (a) surface `logoutError`
    // so the UI never claims a clean sign-out and (b) set a durable marker that blocks cookie-based
    // auto-refresh in `loadMe` — otherwise the next visitor on a shared machine could be silently
    // reauthenticated from the still-live refresh cookie.
    let confirmed = false;
    for (let attempt = 0; attempt < 2 && !confirmed; attempt++) {
      try {
        await api.authLogout();
        confirmed = true;
      } catch {
        /* retry once, then give up */
      }
    }
    localStorage.removeItem('access_token');
    authStateGeneration++;
    if (confirmed) {
      localStorage.removeItem('es:logout_unconfirmed');
    } else {
      localStorage.setItem('es:logout_unconfirmed', '1');
    }
    set({
      accessToken: null,
      user: null,
      isAuthenticated: false,
      permissions: null,
      permissionsStatus: 'idle',
      isLoading: false,
      loadMeError: null,
      logoutError: confirmed
        ? null
        : 'Sign-out could not be confirmed with the server — your session may still be active. Retry or close this browser.',
    });
  },

  register: async (email, firstName, lastName, password, attribution) => {
    await api.authRegister({email, firstName, lastName, password, ...attribution});
    // Don't auto-login — user must verify email first
  },

  refreshAccessToken: async () => {
    try {
      const resp = await api.authRefresh();
      authStateGeneration++;
      localStorage.setItem('access_token', resp.accessToken);
      // R-190 — normalize legacy 'vibe' viewMode at the parse boundary.
      set({
        accessToken: resp.accessToken,
        user: api.normalizeAuthUser(resp.user),
        isAuthenticated: true,
        loadMeError: null,
      });
      return true;
    } catch {
      authStateGeneration++;
      localStorage.removeItem('access_token');
      set({
        accessToken: null,
        user: null,
        isAuthenticated: false,
        permissions: null,
        permissionsStatus: 'idle',
        loadMeError: null,
      });
      return false;
    }
  },

  adoptSession: (accessToken, user) => {
    authStateGeneration++;
    localStorage.setItem('access_token', accessToken);
    // RR-196 — a freshly adopted session is a deliberate authentication; clear any stale marker.
    localStorage.removeItem('es:logout_unconfirmed');
    set({
      accessToken,
      user: api.normalizeAuthUser(user),
      isAuthenticated: true,
      loadMeError: null,
      logoutError: null,
    });
    void get().loadPermissions();
  },

  loadMe: () => {
    if (loadMeInFlight) return loadMeInFlight;

    const hydrate = async () => {
      set({isLoading: true, loadMeError: null});
      let attemptGeneration = authStateGeneration;
      try {
        const token = localStorage.getItem('access_token');
        if (!token) {
          // RR-196 — do NOT resurrect a session the user explicitly (if unconfirmably) logged out
          // of. The marker is cleared only by a deliberate login, so a shared machine cannot
          // silently reauthenticate the next visitor from the still-live refresh cookie.
          if (localStorage.getItem('es:logout_unconfirmed')) return;
          // Try refresh via cookie. A successful refresh intentionally advances the generation;
          // adopt that new generation before requesting /me.
          const ok = await get().refreshAccessToken();
          if (!ok) return;
          attemptGeneration = authStateGeneration;
        }
        const rawUser = await api.authMe();
        if (attemptGeneration !== authStateGeneration) return;
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
        set({user, isAuthenticated: true, loadMeError: null});
        // RR-198 — load tier permissions with tracked loading/ready/error state.
        void get().loadPermissions();
      } catch (e) {
        // A newer login/logout/adopted session owns the store now; this stale attempt has no
        // authority to clear it or publish an error against it.
        if (attemptGeneration !== authStateGeneration) return;
        // RR-197 — distinguish a definitively dead session from a transient fault.
        // A 401/403 means the token is invalid: clear ALL access state including
        // the persisted token, atomically, so a reload does not reconstruct
        // isAuthenticated=true from the same dead token and loop the spinner /
        // unauthorized-request path. A transient failure (network / 5xx / unknown)
        // must NOT mutate authentication truth — leave the session intact so a
        // retry can recover instead of forcing a spurious logout.
        const status = e instanceof api.ApiError ? e.status : undefined;
        const definitiveAuthFailure = status === 401 || status === 403;
        if (definitiveAuthFailure) {
          get().clearAuth();
        } else {
          set({
            loadMeError:
              'ExecuteSpec could not verify your session. Your session was kept; retry when the service is available.',
          });
        }
      } finally {
        set({isLoading: false});
      }
    };

    loadMeInFlight = hydrate().finally(() => {
      loadMeInFlight = null;
    });
    return loadMeInFlight;
  },

  loadPermissions: async () => {
    // RR-198 — model permissions as loading | ready | error so consumers can
    // tell "the permission service failed" apart from "not entitled". On failure
    // we retain the last-known permissions snapshot (do not null it) and flag
    // `error` so the UI can offer retry instead of rendering a downgraded account.
    set({permissionsStatus: 'loading'});
    try {
      const p = await api.getMyPermissions();
      set({permissions: p, permissionsStatus: 'ready'});
    } catch {
      set({permissionsStatus: 'error'});
    }
  },

  setUser: (user) => {
    authStateGeneration++;
    set({user});
  },
  clearAuth: () => {
    authStateGeneration++;
    localStorage.removeItem('access_token');
    set({
      accessToken: null,
      user: null,
      isAuthenticated: false,
      isLoading: false,
      loadMeError: null,
      permissions: null,
      permissionsStatus: 'idle',
    });
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
