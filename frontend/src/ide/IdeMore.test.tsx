import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const apiMocks = vi.hoisted(() => ({
  getNotificationPreferences: vi.fn(),
  getRuns: vi.fn(),
  updateNotificationPreference: vi.fn(),
}));

vi.mock('@/lib/api', () => apiMocks);

const authState = {
  user: {
    id: 'u1',
    email: 'demo@example.com',
    firstName: 'Demo',
    lastName: 'User',
    tier: 'PRO',
    globalRole: 'USER',
  },
  logout: vi.fn(),
  permissions: {
    monthlyRunLimit: 100,
    currentRunCount: 1,
    maxOrgMembers: 1,
    currentOrgMembers: 1,
    maxApplySuggestions: 5,
    maxPreviewStarts: 5,
    maxDeployments: 1,
    canDeploy: true,
  },
};

vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: typeof authState) => unknown) => selector(authState),
  selectNormalizedTier: () => 'PRO',
  selectIsSuperAdmin: () => false,
}));

const uiState = {theme: 'dark', setTheme: vi.fn()};
vi.mock('@/stores/useUiStore', () => ({
  useUiStore: (selector: (state: typeof uiState) => unknown) => selector(uiState),
}));

import {IdeAccount, IdeRuns} from './IdeMore';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

describe('IdeAccount notification deep link', () => {
  let host: HTMLDivElement;
  let root: Root;
  const scrollIntoView = vi.fn();

  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    apiMocks.getNotificationPreferences.mockReset();
    apiMocks.getRuns.mockReset();
    apiMocks.updateNotificationPreference.mockReset();
    apiMocks.updateNotificationPreference.mockResolvedValue(undefined);
    apiMocks.getNotificationPreferences.mockResolvedValue([
      {
        eventType: 'RUN_FAILED',
        tier: 'HIGH_SIGNAL',
        channel: 'EMAIL',
        enabled: true,
        defaultEnabled: true,
        override: false,
      },
    ]);
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value: scrollIntoView,
    });
    scrollIntoView.mockReset();
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.unstubAllGlobals();
  });

  it('opens, focuses, and scrolls the notification controls from the canonical settings URL', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/settings?section=notifications']}>
          <IdeAccount />
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    const section = host.querySelector<HTMLButtonElement>('#notification-preferences');
    expect(section).not.toBeNull();
    expect(section?.textContent).toContain('Notifications');
    expect(section?.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(section);
    expect(scrollIntoView).toHaveBeenCalled();
    expect(host.textContent).toContain('run failed');

    const toggle = host.querySelector<HTMLButtonElement>(
      'button[aria-label="run failed · email channel"]',
    );
    expect(toggle?.style.width).toBe('44px');
    expect(toggle?.style.height).toBe('44px');

    toggle?.focus();
    await act(async () => {
      toggle?.click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(apiMocks.updateNotificationPreference).toHaveBeenCalledWith(
      'RUN_FAILED',
      'EMAIL',
      false,
    );
    expect(document.activeElement).toBe(toggle);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('presents a user-stopped run neutrally while retaining failure copy for actual failures', async () => {
    apiMocks.getRuns.mockResolvedValue({
      content: [
        {
          runId: 'cancelled-run',
          status: 'CANCELLED',
          runTitle: 'Cancelled work',
          startedAt: '2026-07-29T10:00:00Z',
          terminalOutcome: {
            category: 'USER_STOPPED',
          },
        },
        {
          runId: 'failed-run',
          status: 'FAILED',
          runTitle: 'Failed work',
          startedAt: '2026-07-29T10:00:00Z',
          terminalOutcome: {
            category: 'PLATFORM_FAILURE',
          },
        },
      ],
      hasNext: false,
    });

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/runs']}>
          <IdeRuns />
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    const rows = host.querySelectorAll<HTMLElement>('.ide-work-row');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Stopped by you — open to review the saved run evidence');
    expect(rows[0].textContent).not.toContain('Needs attention');
    expect(rows[0].querySelector('.ide-badge')?.className).toContain('warn');
    expect(rows[1].textContent).toContain(
      'Needs attention — open to review the failure and retry options',
    );
    expect(rows[1].querySelector('.ide-badge')?.className).toContain('bad');
  });
});
