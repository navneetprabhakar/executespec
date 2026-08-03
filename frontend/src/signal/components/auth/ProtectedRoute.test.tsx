import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const auth = vi.hoisted(() => ({
  isAuthenticated: true,
  isLoading: false,
  loadMeError: null as string | null,
  user: null as {globalRole: string} | null,
  loadMe: vi.fn(),
}));

vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector?: (state: typeof auth) => unknown) => (selector ? selector(auth) : auth),
}));

import ProtectedRoute from './ProtectedRoute';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  auth.isAuthenticated = true;
  auth.isLoading = false;
  auth.loadMeError = null;
  auth.user = null;
  auth.loadMe.mockReset();
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('ProtectedRoute', () => {
  it('holds role-gated children while a persisted session hydrates /me', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/admin']}>
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route path='/admin' element={<div>Admin shell</div>} />
            </Route>
            <Route path='/home' element={<div>Home</div>} />
          </Routes>
        </MemoryRouter>,
      );
    });

    expect(host.textContent).not.toContain('Admin shell');
    expect(host.textContent).not.toContain('Home');
    expect(auth.loadMe).toHaveBeenCalledTimes(1);
  });

  it('shows a retryable error instead of an infinite spinner after transient hydration failure', async () => {
    auth.loadMeError = 'safe display error';

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/agent']}>
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route path='/agent' element={<div>Agent shell</div>} />
            </Route>
          </Routes>
        </MemoryRouter>,
      );
    });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      'Could not verify your session',
    );
    expect(host.textContent).not.toContain('Agent shell');

    const retry = host.querySelector('button');
    expect(retry?.textContent).toBe('Retry');
    await act(async () => retry?.click());
    expect(auth.loadMe).toHaveBeenCalledTimes(2);
  });
});
