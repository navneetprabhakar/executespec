import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ForgotPasswordPage, ResetPasswordPage} from './AuthRecoveryPages';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  authForgotPassword: vi.fn(),
  authResetPassword: vi.fn(),
}));

vi.mock('@/lib/api', () => api);

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.authForgotPassword.mockResolvedValue(undefined);
  api.authResetPassword.mockResolvedValue(undefined);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('AuthRecoveryPages', () => {
  it('sends a password reset request and clears the email field', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter
          initialEntries={['/forgot-password']}
          future={{v7_relativeSplatPath: true, v7_startTransition: true}}
        >
          <ForgotPasswordPage />
        </MemoryRouter>,
      );
    });

    setInputValue(host.querySelector('input[type="email"]') as HTMLInputElement, 'user@test.dev');
    await submitForm();

    expect(api.authForgotPassword).toHaveBeenCalledWith('user@test.dev');
    expect(host.textContent).toContain('Reset link sent. Check your inbox (and spam folder).');
    expect((host.querySelector('input[type="email"]') as HTMLInputElement).value).toBe('');
  });

  it('validates reset-password input before calling the API', async () => {
    await renderReset('/reset-password?token=t1');

    const inputs = host.querySelectorAll('input[type="password"]');
    setInputValue(inputs[0] as HTMLInputElement, 'short');
    setInputValue(inputs[1] as HTMLInputElement, 'short');
    await submitForm();

    expect(host.textContent).toContain('Password must be at least 12 characters.');
    expect(api.authResetPassword).not.toHaveBeenCalled();
  });

  it('submits a valid reset token and navigates to sign in', async () => {
    await renderReset('/reset-password?token=t1');

    const inputs = host.querySelectorAll('input[type="password"]');
    setInputValue(inputs[0] as HTMLInputElement, 'long-password-1');
    setInputValue(inputs[1] as HTMLInputElement, 'long-password-1');
    await submitForm();

    expect(api.authResetPassword).toHaveBeenCalledWith('t1', 'long-password-1');
    expect(host.textContent).toContain('Login after reset');
  });
});

async function renderReset(path: string) {
  await act(async () => {
    root.render(
      <MemoryRouter
        initialEntries={[path]}
        future={{v7_relativeSplatPath: true, v7_startTransition: true}}
      >
        <Routes>
          <Route path='/reset-password' element={<ResetPasswordPage />} />
          <Route path='/login' element={<div>Login after reset</div>} />
        </Routes>
      </MemoryRouter>,
    );
  });
}

function setInputValue(input: HTMLInputElement, value: string) {
  act(() => {
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    valueSetter?.call(input, value);
    input.dispatchEvent(new Event('input', {bubbles: true}));
  });
}

async function submitForm() {
  await act(async () => {
    host
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}));
    await Promise.resolve();
    await Promise.resolve();
  });
}
