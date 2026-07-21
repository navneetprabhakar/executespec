import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import ConsoleShell from './ConsoleShell';
import * as api from '@/lib/api';

vi.mock('@/lib/api', () => ({
  getRuns: vi.fn().mockResolvedValue({content: []}),
  getUnreadCount: vi.fn().mockResolvedValue({count: 0}),
  getProjects: vi.fn().mockResolvedValue([]),
  // Backs the "latest sandbox" palette command, which now reads the
  // server-owned agent-session read model instead of the global run list.
  listAgentSessions: vi.fn().mockResolvedValue([]),
  createAgentSession: vi.fn().mockResolvedValue({id: 's1'}),
}));

vi.mock('@/stores/useUiStore', () => ({
  useUiStore: (selector: (state: {theme: string; toggleTheme: () => void}) => unknown) =>
    selector({theme: 'dark', toggleTheme: vi.fn()}),
}));

vi.mock('@/stores/useAuthStore', () => {
  const state = {user: {globalRole: 'USER'}, tier: 'PRO'};
  return {
    selectIsSuperAdmin: (s: typeof state) => s.user.globalRole === 'SUPER_ADMIN',
    selectNormalizedTier: (s: typeof state) => s.tier,
    useAuthStore: (selector: (s: typeof state) => unknown) => selector(state),
  };
});

vi.mock('@/ide/PlanGateIndicator', () => ({
  PlanGateIndicator: () => <span data-testid='plan-gate-indicator' />,
}));

vi.mock('./AnnouncementBanner', () => ({
  AnnouncementBanner: () => <div role='status'>Announcement</div>,
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
  ensureLocalStorage();
  localStorage.clear();
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('ConsoleShell focus affordances', () => {
  it('keeps the skip link first and focuses main content on activation', async () => {
    await renderShell('/home');

    const skipLink = host.querySelector<HTMLAnchorElement>('a.con-skip-link');
    const main = host.querySelector<HTMLElement>('main#main-content');
    expect(skipLink?.textContent).toContain('Skip to main content');
    expect(main?.getAttribute('role')).toBe('main');
    expect(firstFocusable()).toBe(skipLink);

    host.querySelector<HTMLButtonElement>('.con-newrun')?.focus();
    expect(document.activeElement).not.toBe(main);

    await act(async () => {
      skipLink?.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true}));
    });

    expect(document.activeElement).toBe(main);
  });

  it('moves route-change focus to the main content region', async () => {
    await renderShell('/home');

    const main = host.querySelector<HTMLElement>('main#main-content');

    expect(document.activeElement).toBe(main);
  });

  it('puts the agent route in transcript focus mode instead of dashboard chrome', async () => {
    await renderShell('/agent');

    const shell = host.querySelector<HTMLElement>('.con-root');
    expect(shell?.classList.contains('agent-active')).toBe(true);
    expect(shell?.dataset.surface).toBe('transcript');
  });

  it('closes the mobile drawer with Escape and returns focus to its trigger', async () => {
    await renderShell('/home');
    const trigger = host.querySelector<HTMLButtonElement>('.con-icon.con-only-mobile');
    expect(trigger).not.toBeNull();
    await act(async () => trigger?.click());
    expect(host.querySelector('.con-side.drawer-open')).not.toBeNull();
    const close = host.querySelector<HTMLButtonElement>('.con-drawer-close');
    expect(close).not.toBeNull();
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
    });
    expect(host.querySelector('.con-side.drawer-open')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('shows a retryable alert when navigation data fails', async () => {
    vi.mocked(api.getRuns).mockRejectedValueOnce(new Error('offline'));
    await renderShell('/home');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('temporarily unavailable');
    expect(host.querySelector('.con-api-error-retry')).not.toBeNull();
  });
});

// RR-1655 — the three loaders shared one `apiError` boolean and left their state at
// `[]`/`0` on failure, so a failed read rendered as an affirmative "you have none".
describe('ConsoleShell per-source load state', () => {
  it('does not claim the account has no projects when the projects read fails', async () => {
    vi.mocked(api.getProjects).mockRejectedValue(new Error('offline'));
    await renderShell('/home');

    const sidebar = host.textContent ?? '';
    expect(sidebar).toContain("Couldn't load projects");
    expect(sidebar).not.toContain('New project');
  });

  it('keeps a successful runs rail intact when only the projects read fails', async () => {
    vi.mocked(api.getProjects).mockRejectedValue(new Error('offline'));
    vi.mocked(api.getRuns).mockResolvedValue({
      content: [{runId: 'run-1', status: 'COMPLETE', userRequest: 'Add a login page'}],
    } as Awaited<ReturnType<typeof api.getRuns>>);
    await renderShell('/home');

    const sidebar = host.textContent ?? '';
    expect(sidebar).not.toContain('No work yet');
    expect(sidebar).not.toContain("Couldn't load recent work");
    expect(sidebar).toContain('Add a login page');
  });

  it('does not claim there is no work when only the runs read fails', async () => {
    vi.mocked(api.getRuns).mockRejectedValue(new Error('offline'));
    await renderShell('/home');

    const sidebar = host.textContent ?? '';
    expect(sidebar).toContain("Couldn't load recent work");
    expect(sidebar).not.toContain('No work yet');
  });
});

async function renderShell(path: string) {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<ConsoleShell />}>
            <Route path='/home' element={<button type='button'>Child action</button>} />
            <Route path='/agent' element={<button type='button'>Agent composer</button>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
  });
  await act(async () => {
    await Promise.resolve();
  });
}

function firstFocusable(): Element | null {
  return host.querySelector(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  );
}

function ensureLocalStorage() {
  if (globalThis.localStorage) return;
  const values = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      removeItem: (key: string) => values.delete(key),
      setItem: (key: string, value: string) => values.set(key, value),
    },
  });
}
