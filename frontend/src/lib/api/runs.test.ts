import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import type {AuthUser} from './core';
import {createRun, forkRun, iterateRun, requiresCostCap} from './runs';

beforeEach(() => {
  vi.stubGlobal('localStorage', {
    getItem: () => null,
    setItem: () => undefined,
    removeItem: () => undefined,
  } satisfies Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Minimal AuthUser factory — requiresCostCap only reads `tier` + key flags. */
function user(partial: Partial<AuthUser>): AuthUser {
  const base: AuthUser = {
    id: 'u1',
    email: 'u@example.com',
    firstName: 'U',
    lastName: 'One',
    globalRole: 'USER',
    tier: 'FREE',
    status: 'ACTIVE',
    mustChangePassword: false,
    emailVerified: true,
    orgId: null,
    orgRole: null,
  };
  return {...base, ...partial};
}

describe('requiresCostCap tier stratification (RR-167 / RR-1338)', () => {
  it('exempts every own-keys tier from the platform cost cap', () => {
    // RR-1338 — BYOK was previously falling through to the `return true` default.
    expect(requiresCostCap(user({tier: 'BYOK'}))).toBe(false);
    expect(requiresCostCap(user({tier: 'TEAM'}))).toBe(false);
    expect(requiresCostCap(user({tier: 'ENTERPRISE'}))).toBe(false);
  });

  it('caps FREE and PRO/PRO_PLUS unless the user attached their own keys', () => {
    expect(requiresCostCap(user({tier: 'FREE'}))).toBe(true);
    expect(requiresCostCap(user({tier: 'PRO'}))).toBe(true);
    expect(requiresCostCap(user({tier: 'PRO_PLUS'}))).toBe(true);

    // A PRO / PRO_PLUS user with an own key is exempt.
    expect(requiresCostCap(user({tier: 'PRO', hasAnthropicKey: true}))).toBe(false);
    expect(requiresCostCap(user({tier: 'PRO_PLUS', hasFireworksKey: true}))).toBe(false);
  });

  it('treats an anonymous user as FREE-equivalent', () => {
    expect(requiresCostCap(null)).toBe(true);
    expect(requiresCostCap(undefined)).toBe(true);
  });
});

describe('run-creating mutation idempotency', () => {
  it.each([
    ['create', () => createRun({userRequest: 'build'}, 'create-key')],
    ['fork', () => forkRun('run-1', {aiProvider: 'OPENAI'}, 'fork-key')],
    ['iterate', () => iterateRun('run-1', 'continue', 'iterate-key')],
  ])('sends the caller-owned Idempotency-Key for %s', async (_name, request) => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({runId: 'run-2'}), {
        status: 202,
        headers: {'Content-Type': 'application/json'},
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await request();

    const headers = fetchMock.mock.calls[0][1]?.headers as Record<string, string>;
    expect(headers['Idempotency-Key']).toMatch(/^(create|fork|iterate)-key$/);
  });
});
