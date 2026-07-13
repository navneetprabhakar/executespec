import {act, type ReactNode} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import ConsoleHome from './ConsoleHome';
import * as api from '@/lib/api';

vi.mock('@/lib/api', () => ({
  getRuns: vi.fn().mockResolvedValue({
    content: [],
    page: 0,
    size: 25,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false,
  }),
  getProjects: vi.fn().mockResolvedValue([]),
  getMyOrg: vi.fn().mockResolvedValue({}),
  getOrgMembers: vi.fn().mockResolvedValue([]),
  getOrgInvitations: vi.fn().mockResolvedValue([]),
  getPrCommentPendingCount: vi.fn().mockResolvedValue({count: 0}),
}));

vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: () => false,
  useAuthStore: (selector: (state: {user: null}) => unknown) => selector({user: null}),
}));

vi.mock('@/ide/IdeIcons', () => ({
  CommandIcon: () => null,
  PlusIcon: () => null,
  ProjectsIcon: () => null,
  RunsIcon: () => null,
  StatusDot: () => null,
}));

vi.mock('@/ide/ScoreBadge', () => ({ScoreBadge: () => null}));
vi.mock('@/ide/IdeKit', () => ({
  IdeBadge: ({children}: {children: ReactNode}) => <span>{children}</span>,
  statusKind: () => 'warn',
}));

(
  globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}
).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.mocked(api.getRuns).mockReset().mockResolvedValue({
    content: [],
    page: 0,
    size: 25,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false,
  });
  vi.mocked(api.getProjects).mockReset().mockResolvedValue([]);
  vi.mocked(api.getMyOrg).mockReset().mockResolvedValue({} as api.OrgDto);
  vi.mocked(api.getOrgMembers).mockReset().mockResolvedValue([]);
  vi.mocked(api.getOrgInvitations).mockReset().mockResolvedValue([]);
  vi.mocked(api.getPrCommentPendingCount).mockReset().mockResolvedValue({count: 0});
});

describe('ConsoleHome data states', () => {
  it('exposes a retryable alert when the workspace API is unavailable', async () => {
    vi.mocked(api.getRuns).mockRejectedValue(new Error('offline'));
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/home']}>
          <ConsoleHome />
        </MemoryRouter>,
      );
      await Promise.resolve();
    });
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      'Some workspace data could not be loaded',
    );
    expect(host.querySelector('button.con-api-error-retry')).not.toBeNull();
  });
});
