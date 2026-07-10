import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import ConsoleShell from './ConsoleShell';

vi.mock('@/lib/api', () => ({
  getRuns: vi.fn().mockResolvedValue({content: []}),
  getUnreadCount: vi.fn().mockResolvedValue({count: 0}),
  getProjects: vi.fn().mockResolvedValue([]),
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
