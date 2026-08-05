import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

// The auth store reads localStorage at module-eval time. This Node/jsdom env does
// not always expose one, so install an in-memory shim BEFORE the store imports.
vi.hoisted(() => {
  if (typeof globalThis.localStorage === 'undefined') {
    const mem = new Map<string, string>();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (globalThis as any).localStorage = {
      getItem: (k: string) => (mem.has(k) ? (mem.get(k) as string) : null),
      setItem: (k: string, v: string) => void mem.set(k, String(v)),
      removeItem: (k: string) => void mem.delete(k),
      clear: () => mem.clear(),
      key: (i: number) => [...mem.keys()][i] ?? null,
      get length() {
        return mem.size;
      },
    };
  }
});

// Mock the API barrel. A local ApiError class is provided so `e instanceof
// api.ApiError` in the store resolves against the SAME class we throw here.
vi.mock('@/lib/api', () => {
  class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
    }
  }
  return {
    ApiError,
    authMe: vi.fn(),
    getMyPermissions: vi.fn(),
    normalizeAuthUser: (u: unknown) => u,
    updateViewMode: vi.fn(() => Promise.resolve()),
    authRefresh: vi.fn(),
    authLogin: vi.fn(),
    authLogout: vi.fn(),
    authRegister: vi.fn(),
  };
});

import * as api from '@/lib/api';
import {useAuthStore} from './useAuthStore';

const authMeMock = api.authMe as unknown as ReturnType<typeof vi.fn>;
const getMyPermissionsMock = api.getMyPermissions as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  localStorage.clear();
  authMeMock.mockReset();
  getMyPermissionsMock.mockReset();
  getMyPermissionsMock.mockResolvedValue({});
  useAuthStore.setState({
    user: null,
    accessToken: null,
    isAuthenticated: false,
    isLoading: false,
    loadMeError: null,
    permissions: null,
    permissionsStatus: 'idle',
  });
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('useAuthStore — RR-197 stale token on hydration failure', () => {
  it('clears the persisted token on a definitive 401', async () => {
    localStorage.setItem('access_token', 'dead-token');
    useAuthStore.setState({accessToken: 'dead-token', isAuthenticated: true});
    authMeMock.mockRejectedValue(new api.ApiError(401, 'expired'));

    await useAuthStore.getState().loadMe();

    expect(localStorage.getItem('access_token')).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('clears the persisted token on a definitive 403', async () => {
    localStorage.setItem('access_token', 'dead-token');
    useAuthStore.setState({accessToken: 'dead-token', isAuthenticated: true});
    authMeMock.mockRejectedValue(new api.ApiError(403, 'forbidden'));

    await useAuthStore.getState().loadMe();

    expect(localStorage.getItem('access_token')).toBeNull();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('preserves the token and auth truth on a transient (network) failure', async () => {
    localStorage.setItem('access_token', 'good-token');
    useAuthStore.setState({accessToken: 'good-token', isAuthenticated: true});
    authMeMock.mockRejectedValue(new api.ApiError(0, 'network down'));

    await useAuthStore.getState().loadMe();

    // A transient fault must NOT mutate authentication truth or clear the token.
    expect(localStorage.getItem('access_token')).toBe('good-token');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expect(useAuthStore.getState().isLoading).toBe(false);
    expect(useAuthStore.getState().loadMeError).toContain('could not verify your session');
  });

  it('surfaces a transient 503 without clearing the authenticated session', async () => {
    localStorage.setItem('access_token', 'good-token');
    useAuthStore.setState({accessToken: 'good-token', isAuthenticated: true});
    authMeMock.mockRejectedValue(new api.ApiError(503, 'temporarily unavailable'));

    await useAuthStore.getState().loadMe();

    expect(localStorage.getItem('access_token')).toBe('good-token');
    expect(useAuthStore.getState()).toMatchObject({
      isAuthenticated: true,
      isLoading: false,
      user: null,
    });
    expect(useAuthStore.getState().loadMeError).toContain('could not verify your session');
  });

  it('clears the hydration error after a successful retry', async () => {
    localStorage.setItem('access_token', 'good-token');
    useAuthStore.setState({
      accessToken: 'good-token',
      isAuthenticated: true,
      loadMeError: 'prior transient failure',
    });
    authMeMock.mockResolvedValue({id: 'user-1'});

    await useAuthStore.getState().loadMe();

    expect(useAuthStore.getState()).toMatchObject({
      isAuthenticated: true,
      isLoading: false,
      loadMeError: null,
      user: {id: 'user-1'},
    });
  });

  it('deduplicates concurrent hydration attempts', async () => {
    localStorage.setItem('access_token', 'good-token');
    useAuthStore.setState({accessToken: 'good-token', isAuthenticated: true});
    let resolveMe: ((value: {id: string}) => void) | undefined;
    authMeMock.mockImplementation(
      () =>
        new Promise<{id: string}>((resolve) => {
          resolveMe = resolve;
        }),
    );

    const first = useAuthStore.getState().loadMe();
    const second = useAuthStore.getState().loadMe();
    expect(authMeMock).toHaveBeenCalledTimes(1);
    let secondSettled = false;
    void second.then(() => {
      secondSettled = true;
    });
    await Promise.resolve();
    expect(secondSettled).toBe(false);

    resolveMe?.({id: 'user-1'});
    await Promise.all([first, second]);

    expect(secondSettled).toBe(true);
    expect(useAuthStore.getState().user).toEqual({id: 'user-1'});
  });

  it('does not resurrect a session cleared while hydration is pending', async () => {
    localStorage.setItem('access_token', 'good-token');
    useAuthStore.setState({accessToken: 'good-token', isAuthenticated: true});
    let resolveMe: ((value: {id: string}) => void) | undefined;
    authMeMock.mockImplementation(
      () =>
        new Promise<{id: string}>((resolve) => {
          resolveMe = resolve;
        }),
    );

    const hydration = useAuthStore.getState().loadMe();
    useAuthStore.getState().clearAuth();
    resolveMe?.({id: 'stale-user'});
    await hydration;

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: null,
      isAuthenticated: false,
      isLoading: false,
      user: null,
    });
  });

  it('does not overwrite a newer adopted session with a stale hydration response', async () => {
    localStorage.setItem('access_token', 'old-token');
    useAuthStore.setState({accessToken: 'old-token', isAuthenticated: true});
    let resolveMe: ((value: {id: string}) => void) | undefined;
    authMeMock.mockImplementation(
      () =>
        new Promise<{id: string}>((resolve) => {
          resolveMe = resolve;
        }),
    );

    const hydration = useAuthStore.getState().loadMe();
    useAuthStore.getState().adoptSession('new-token', {id: 'new-user'} as api.AuthUser);
    resolveMe?.({id: 'stale-user'});
    await hydration;

    expect(localStorage.getItem('access_token')).toBe('new-token');
    expect(useAuthStore.getState().user).toEqual({id: 'new-user'});
  });
});

describe('useAuthStore — RR-198 permissions status', () => {
  it('marks permissions error (not empty) when the fetch fails, keeping last-known', async () => {
    useAuthStore.setState({
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      permissions: {canDeploy: true} as any,
      permissionsStatus: 'ready',
    });
    getMyPermissionsMock.mockRejectedValue(new Error('service down'));

    await useAuthStore.getState().loadPermissions();

    const state = useAuthStore.getState();
    expect(state.permissionsStatus).toBe('error');
    // Last-known snapshot retained so entitlements aren't silently downgraded.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((state.permissions as any).canDeploy).toBe(true);
  });

  it('marks permissions ready on success', async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    getMyPermissionsMock.mockResolvedValue({canDeploy: false} as any);

    await useAuthStore.getState().loadPermissions();

    const state = useAuthStore.getState();
    expect(state.permissionsStatus).toBe('ready');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((state.permissions as any).canDeploy).toBe(false);
  });
});
