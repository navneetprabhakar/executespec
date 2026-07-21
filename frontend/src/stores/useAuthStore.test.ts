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
