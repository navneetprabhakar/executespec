import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import type {ReactNode} from 'react';
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
  IdeBadge: ({children}: {children: ReactNode}) => <span>{children}</span>,
  statusKind: () => 'warn',
}));

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

const emptyPage = {
  content: [],
  page: 0,
  size: 30,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
  hasPrevious: false,
};

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

describe('ConsoleDecisions skip', () => {
  const gateRun = {
    runId: 'run-1',
    status: 'AWAITING_APPROVAL',
    userRequest: 'Build a URL shortener',
    projectName: 'Demo',
    startedAt: new Date().toISOString(),
  };

  function mockGate() {
    vi.mocked(api.getRuns).mockImplementation(
      (params?: {status?: string}) =>
        Promise.resolve(
          params?.status === 'AWAITING_APPROVAL'
            ? {...emptyPage, content: [gateRun as never], totalElements: 1}
            : emptyPage,
        ) as ReturnType<typeof api.getRuns>,
    );
  }

  async function renderDecisions() {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/decisions']}>
          <ConsoleDecisions />
        </MemoryRouter>,
      );
      await Promise.resolve();
    });
  }

  // This jsdom environment exposes no localStorage, so stub the minimum surface the skip ledger
  // uses. The module degrades to "nothing skipped" without one, which would silently pass.
  const store = new Map<string, string>();
  beforeEach(() => {
    store.clear();
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        getItem: (k: string) => store.get(k) ?? null,
        setItem: (k: string, v: string) => void store.set(k, v),
        removeItem: (k: string) => void store.delete(k),
        clear: () => store.clear(),
      },
    });
  });
  afterEach(() => store.clear());

  it('hides a skipped decision from the queue and says the run is unchanged', async () => {
    mockGate();
    await renderDecisions();
    expect(host.textContent).toContain('Build a URL shortener');

    await act(async () => {
      host.querySelector<HTMLButtonElement>('.con-decision-skip')?.click();
      await Promise.resolve();
    });

    expect(host.textContent).not.toContain('Build a URL shortener');
    // The distinction that matters: this is a queue-visibility gesture, not a run action.
    expect(host.textContent).toContain('hidden from your queue');
    expect(host.textContent).toContain('unchanged');
  });

  it('restores a skipped decision and never calls a run API to skip', async () => {
    mockGate();
    await renderDecisions();

    await act(async () => {
      host.querySelector<HTMLButtonElement>('.con-decision-skip')?.click();
      await Promise.resolve();
    });
    const callsAfterSkip = vi.mocked(api.getRuns).mock.calls.length;

    await act(async () => {
      // "Show hidden" is the last ghost button in the notice row.
      const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('button'));
      buttons.find((b) => b.textContent === 'Show hidden')?.click();
      await Promise.resolve();
    });
    expect(host.textContent).toContain('Build a URL shortener');

    await act(async () => {
      const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('button'));
      buttons.find((b) => b.textContent === 'Restore')?.click();
      await Promise.resolve();
    });

    // Skipping is local-only: no additional run fetch, and nothing was cancelled or approved.
    expect(vi.mocked(api.getRuns).mock.calls.length).toBe(callsAfterSkip);
  });

  it('survives a reload because the skip is persisted', async () => {
    mockGate();
    await renderDecisions();
    await act(async () => {
      host.querySelector<HTMLButtonElement>('.con-decision-skip')?.click();
      await Promise.resolve();
    });

    act(() => root.unmount());
    root = createRoot(host);
    await renderDecisions();

    expect(host.textContent).not.toContain('Build a URL shortener');
  });
});
