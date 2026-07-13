import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import ConsoleDecisions from './ConsoleDecisions';
import * as api from '@/lib/api';

vi.mock('@/lib/api', () => ({
  getRuns: vi.fn(),
  getPrCommentPendingCount: vi.fn(),
}));

vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: () => false,
  useAuthStore: (selector: (state: {user: null}) => unknown) => selector({user: null}),
}));

vi.mock('@/ide/IdeIcons', () => ({StatusDot: () => null}));
vi.mock('@/ide/ScoreBadge', () => ({ScoreBadge: () => null}));
vi.mock('@/ide/IdeKit', () => ({
  IdeBadge: ({children}: {children: unknown}) => <span>{children}</span>,
  statusKind: () => 'warn',
}));

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

const emptyPage = {content: [], page: 0, size: 30, totalElements: 0, totalPages: 0};

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  vi.mocked(api.getRuns).mockResolvedValue(emptyPage);
  vi.mocked(api.getPrCommentPendingCount).mockResolvedValue({count: 0});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('ConsoleDecisions data states', () => {
  it('shows a retryable error instead of treating an API outage as an empty queue', async () => {
    vi.mocked(api.getRuns).mockRejectedValue(new Error('offline'));

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/decisions']}>
          <ConsoleDecisions />
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      'Decision data could not be loaded.',
    );
    expect(host.querySelector('.con-api-error-retry')).not.toBeNull();
    expect(host.textContent).not.toContain('Nothing needs attention right now.');
  });

  it('clears the error after a successful retry', async () => {
    vi.mocked(api.getRuns).mockRejectedValueOnce(new Error('offline'));

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/decisions']}>
          <ConsoleDecisions />
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    vi.mocked(api.getRuns).mockResolvedValue(emptyPage);
    await act(async () => {
      host.querySelector<HTMLButtonElement>('.con-api-error-retry')?.click();
      await Promise.resolve();
    });

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('Nothing needs attention right now.');
  });
});
