import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeAdmin} from '@/ide/IdeSettings';
import {AdminPage} from './MiscPages';

const user = {
  id: 'u1',
  email: 'buyer@example.com',
  firstName: 'Buyer',
  lastName: 'User',
  globalRole: 'USER',
  tier: 'PRO',
  status: 'ACTIVE',
  mustChangePassword: false,
  emailVerified: true,
  orgId: 'org-1',
  orgRole: 'ORG_ADMIN',
} as const;

const org = {
  id: 'org-1',
  name: 'Acme',
  domainPatterns: 'acme.test',
  tier: 'PRO',
  status: 'ACTIVE',
  monthlyRunCount: 3,
  isPaymentVerified: true,
  monthlyRunLimitOverride: null,
  apiKeysProvided: false,
  planExpiresAt: null,
  createdAt: '2026-07-01T00:00:00Z',
} as const;

const api = vi.hoisted(() => ({
  adminListUsers: vi.fn(),
  adminUpdateUserRole: vi.fn(),
  adminUpdateUserTier: vi.fn(),
  adminUpdateUserStatus: vi.fn(),
  adminListOrgs: vi.fn(),
  adminUpdateOrgStatus: vi.fn(),
  adminUpdateOrgTier: vi.fn(),
  adminListFeatureFlags: vi.fn(),
  fetchTiersLifecycle: vi.fn(),
  getActivationFunnel: vi.fn(),
}));

vi.mock('@/lib/api', () => api);

vi.mock('@/stores/useAuthStore', () => {
  const state = {user: {globalRole: 'SUPER_ADMIN', orgId: 'org-1'}};
  return {
    selectIsSuperAdmin: (s: typeof state) => s.user.globalRole === 'SUPER_ADMIN',
    useAuthStore: (selector: (s: typeof state) => unknown) => selector(state),
  };
});

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
  api.adminListUsers.mockResolvedValue([user]);
  api.adminUpdateUserRole.mockResolvedValue({...user, globalRole: 'SUPER_ADMIN'});
  api.adminUpdateUserTier.mockResolvedValue({...user, tier: 'TEAM'});
  api.adminUpdateUserStatus.mockResolvedValue({...user, status: 'SUSPENDED'});
  api.adminListOrgs.mockResolvedValue([org]);
  api.adminUpdateOrgStatus.mockResolvedValue({...org, status: 'SUSPENDED'});
  api.adminUpdateOrgTier.mockResolvedValue({...org, tier: 'TEAM'});
  api.adminListFeatureFlags.mockResolvedValue([]);
  api.fetchTiersLifecycle.mockResolvedValue([]);
  api.getActivationFunnel.mockResolvedValue({windowDays: 30, windowStart: '', stages: {}});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('admin write confirmation UI', () => {
  it('gates standalone admin user-role writes behind the confirmation modal', async () => {
    await renderSignalAdmin();
    await clickButton('Users');
    await flush();

    const roleSelect = selectWithValue('USER');
    await changeSelect(roleSelect, 'SUPER_ADMIN');

    expect(api.adminUpdateUserRole).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Change user role');
    expect(host.textContent).toContain('buyer@example.com');
    expect(host.textContent).toContain('SUPER_ADMIN can operate platform-wide admin surfaces');

    await clickButton('Change role');
    await flush();

    expect(api.adminUpdateUserRole).toHaveBeenCalledWith('u1', 'SUPER_ADMIN');
  });

  it('gates IDE-native admin org suspension behind the confirmation modal', async () => {
    await renderIdeAdmin();
    await clickButton('Orgs');
    await flush();

    const statusSelect = selectWithValue('ACTIVE');
    await changeSelect(statusSelect, 'SUSPENDED');

    expect(api.adminUpdateOrgStatus).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Suspend org');
    expect(host.textContent).toContain('All members lose access until reactivated');

    await clickButton('Suspend');
    await flush();

    expect(api.adminUpdateOrgStatus).toHaveBeenCalledWith('org-1', 'SUSPENDED');
  });
});

async function renderSignalAdmin() {
  await act(async () => {
    root.render(
      <MemoryRouter>
        <AdminPage />
      </MemoryRouter>,
    );
  });
  await flush();
}

async function renderIdeAdmin() {
  await act(async () => {
    root.render(<IdeAdmin />);
  });
  await flush();
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function clickButton(text: string) {
  const button = [...host.querySelectorAll('button')].find(
    (candidate) => candidate.textContent?.trim() === text,
  );
  if (!button) throw new Error(`Button not found: ${text}`);
  await act(async () => {
    button.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
  });
}

function selectWithValue(value: string): HTMLSelectElement {
  const select = [...host.querySelectorAll('select')].find(
    (candidate) => candidate.value === value,
  );
  if (!select) throw new Error(`Select not found for value: ${value}`);
  return select;
}

async function changeSelect(select: HTMLSelectElement, value: string) {
  await act(async () => {
    select.value = value;
    select.dispatchEvent(new Event('change', {bubbles: true, cancelable: true}));
  });
}
