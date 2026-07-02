import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import SuperAdminOnly from '@/console/SuperAdminOnly';

const auth = vi.hoisted(() => ({
  globalRole: 'USER',
}));

vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: (state: {user: {globalRole: string} | null}) =>
    state.user?.globalRole === 'SUPER_ADMIN',
  useAuthStore: (selector: (state: {user: {globalRole: string} | null}) => boolean) =>
    selector({user: {globalRole: auth.globalRole}}),
}));

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  auth.globalRole = 'USER';
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('SuperAdminOnly', () => {
  it('redirects non-superadmin direct admin URLs before rendering operator UI', async () => {
    await renderGuard('/admin');

    expect(host.textContent).toContain('Home');
    expect(host.textContent).not.toContain('Admin shell');
  });

  it('renders operator UI for superadmin users', async () => {
    auth.globalRole = 'SUPER_ADMIN';

    await renderGuard('/admin');

    expect(host.textContent).toContain('Admin shell');
    expect(host.textContent).not.toContain('Home');
  });
});

async function renderGuard(path: string) {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path='/admin'
            element={
              <SuperAdminOnly>
                <div>Admin shell</div>
              </SuperAdminOnly>
            }
          />
          <Route path='/home' element={<div>Home</div>} />
        </Routes>
      </MemoryRouter>,
    );
  });
}
