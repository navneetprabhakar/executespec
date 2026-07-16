import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const auth = vi.hoisted(() => ({
  isAuthenticated: true,
  isLoading: false,
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
});
