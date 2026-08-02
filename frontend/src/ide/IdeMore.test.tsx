import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const apiMocks = vi.hoisted(() => ({
  getNotificationPreferences: vi.fn(),
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

import {IdeAccount} from './IdeMore';

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
});
