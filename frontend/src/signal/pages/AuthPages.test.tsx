import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes, useLocation} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const login = vi.hoisted(() => vi.fn());

vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: {login: typeof login}) => unknown) => selector({login}),
}));

vi.mock('@/lib/authProviders', () => ({
  useAuthProviders: () => ({oauthProviders: ['google'], passwordEnabled: true}),
}));

import {LoginPage} from './AuthPages';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

function LocationProbe() {
  const location = useLocation();
  return <div data-location={`${location.pathname}${location.search}${location.hash}`} />;
}

function change(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, value);
  input.dispatchEvent(new Event('input', {bubbles: true}));
}

describe('LoginPage protected return target', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    login.mockReset();
    login.mockResolvedValue(undefined);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  it('preserves the notification section through OAuth and password sign-in', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/login',
              state: {
                from: {
                  pathname: '/settings',
                  search: '?section=notifications',
                  hash: '#notification-preferences',
                },
              },
            },
          ]}
        >
          <Routes>
            <Route path='/login' element={<LoginPage />} />
            <Route path='/settings' element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>,
      );
    });

    const google = [...host.querySelectorAll<HTMLAnchorElement>('a')].find((a) =>
      a.textContent?.includes('Continue with Google'),
    );
    expect(google?.getAttribute('href')).toBe(
      '/api/auth/oauth2/authorize/google?redirect=%2Fsettings%3Fsection%3Dnotifications%23notification-preferences',
    );

    const email = host.querySelector<HTMLInputElement>('#login-email');
    const password = host.querySelector<HTMLInputElement>('#login-password');
    const form = host.querySelector('form');
    expect(email && password && form).toBeTruthy();

    await act(async () => {
      change(email!, 'demo@example.com');
      change(password!, 'secret');
    });
    await act(async () => {
      form!.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
      await Promise.resolve();
    });

    expect(login).toHaveBeenCalledWith('demo@example.com', 'secret');
    expect(host.querySelector('[data-location]')?.getAttribute('data-location')).toBe(
      '/settings?section=notifications#notification-preferences',
    );
  });
});
